package com.example.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.example.data.settings.PageColorTone
import com.example.data.settings.RenderSharpness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfRendererEngine(
    private val context: Context,
    private val uri: Uri
) {
    private val tag = "PdfRendererEngine"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var tempCachedFile: File? = null

    val pageCount: Int
        get() = pdfRenderer?.pageCount ?: 0

    // Concurrency lock because PdfRenderer requires single-threaded access per instance
    private val renderMutex = Mutex()

    // Dynamic memory cache sized according to device heap (1/5th of max heap, min 48MB, max 256MB)
    private val maxMemoryCacheBytes: Int = run {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        val cacheSize = maxMemory / 5
        cacheSize.coerceIn(48 * 1024 * 1024, 256 * 1024 * 1024)
    }

    private val memoryCache = object : LruCache<String, Bitmap>(maxMemoryCacheBytes) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    // Prefetch job tracking
    private var prefetchJob: Job? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            renderMutex.withLock {
                closeInternal()

                val (pfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
                if (pfd == null) {
                    Log.e(tag, "Could not open ParcelFileDescriptor for URI: $uri")
                    return@withContext false
                }

                fileDescriptor = pfd
                pdfRenderer = PdfRenderer(pfd)
                Log.d(tag, "PdfRenderer initialized successfully with ${pdfRenderer?.pageCount} pages")
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize PDF renderer", e)
            false
        }
    }

    fun getPageAspectRatio(pageIndex: Int): Float {
        if (pageIndex < 0 || pageIndex >= pageCount) return 1.414f
        return try {
            val renderer = pdfRenderer ?: return 1.414f
            var ratio = 1.414f
            synchronized(this) {
                val page = renderer.openPage(pageIndex)
                val width = page.width.toFloat()
                val height = page.height.toFloat()
                page.close()
                if (width > 0 && height > 0) {
                    ratio = height / width
                }
            }
            ratio
        } catch (e: Exception) {
            1.414f
        }
    }

    private fun buildCacheKey(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        sharpness: RenderSharpness,
        colorTone: PageColorTone
    ): String {
        return "${pageIndex}_${targetWidth}_${targetHeight}_${sharpness.name}_${colorTone.name}"
    }

    /**
     * Fast synchronous cache lookup for instant 60fps rendering without loading flicker.
     */
    fun getCachedPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        sharpness: RenderSharpness,
        colorTone: PageColorTone
    ): Bitmap? {
        val key = buildCacheKey(pageIndex, targetWidth, targetHeight, sharpness, colorTone)
        val bmp = memoryCache.get(key)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        sharpness: RenderSharpness = RenderSharpness.HIGH_CLARITY,
        colorTone: PageColorTone = PageColorTone.ORIGINAL,
        highQualityPrintMode: Boolean = true
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null

        val cacheKey = buildCacheKey(pageIndex, targetWidth, targetHeight, sharpness, colorTone)
        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            memoryCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }

            try {
                val renderer = pdfRenderer ?: return@withLock null
                val page = renderer.openPage(pageIndex)

                val originalWidth = page.width
                val originalHeight = page.height

                // Calculate razor-sharp dimensions using supersampling
                val baseScale = if (targetWidth > 0 && targetHeight > 0) {
                    min(targetWidth.toFloat() / originalWidth, targetHeight.toFloat() / originalHeight)
                } else if (targetWidth > 0) {
                    targetWidth.toFloat() / originalWidth
                } else {
                    2.0f
                }

                val maxScale = when (sharpness) {
                    RenderSharpness.BALANCED -> 3.2f
                    RenderSharpness.HIGH_CLARITY -> 4.5f
                    RenderSharpness.ULTRA_HD -> 6.0f
                }
                val effectiveScale = (baseScale * sharpness.scaleMultiplier).coerceIn(1.8f, maxScale)
                var finalWidth = (originalWidth * effectiveScale).roundToInt().coerceAtLeast(300)
                var finalHeight = (originalHeight * effectiveScale).roundToInt().coerceAtLeast(300)

                // Bound max dimension to avoid memory overflow (up to 4600px for Ultra HD)
                val maxDimension = when (sharpness) {
                    RenderSharpness.ULTRA_HD -> 4600
                    RenderSharpness.HIGH_CLARITY -> 3600
                    RenderSharpness.BALANCED -> 2600
                }
                if (finalWidth > maxDimension || finalHeight > maxDimension) {
                    val downscale = maxDimension.toFloat() / max(finalWidth, finalHeight)
                    finalWidth = (finalWidth * downscale).roundToInt()
                    finalHeight = (finalHeight * downscale).roundToInt()
                }

                val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                val matrix = Matrix()
                matrix.postScale(finalWidth.toFloat() / originalWidth, finalHeight.toFloat() / originalHeight)

                val renderMode = if (highQualityPrintMode) {
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                } else {
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                }

                page.render(bitmap, null, matrix, renderMode)
                page.close()

                // Apply Color Tone processing if not original
                val processedBitmap = when (colorTone) {
                    PageColorTone.ORIGINAL -> bitmap
                    PageColorTone.SEPIA -> applySepiaTone(bitmap)
                    PageColorTone.INVERTED_DARK -> applyInvertedDarkTone(bitmap)
                    PageColorTone.HIGH_CONTRAST -> applyHighContrastTone(bitmap)
                }

                memoryCache.put(cacheKey, processedBitmap)
                processedBitmap
            } catch (e: OutOfMemoryError) {
                Log.e(tag, "OutOfMemoryError rendering page $pageIndex, evicting cache", e)
                memoryCache.evictAll()
                System.gc()
                null
            } catch (e: Exception) {
                Log.e(tag, "Exception during PDF render for page $pageIndex", e)
                null
            }
        }
    }

    private fun applySepiaTone(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Warm parchment sepia matrix
        val sepiaMatrix = ColorMatrix().apply {
            set(
                floatArrayOf(
                    0.393f * 0.95f, 0.769f * 0.95f, 0.189f * 0.95f, 0f, 25f,
                    0.349f * 0.92f, 0.686f * 0.92f, 0.168f * 0.92f, 0f, 15f,
                    0.272f * 0.85f, 0.534f * 0.85f, 0.131f * 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(sepiaMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    private fun applyInvertedDarkTone(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Invert colors matrix (Black background, clear light gray/white text)
        val invertMatrix = ColorMatrix().apply {
            set(
                floatArrayOf(
                    -0.88f, 0f, 0f, 0f, 235f,
                    0f, -0.88f, 0f, 0f, 235f,
                    0f, 0f, -0.88f, 0f, 235f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(invertMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    private fun applyHighContrastTone(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val contrastMatrix = ColorMatrix().apply {
            val contrast = 1.35f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            set(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Asynchronously pre-renders adjacent pages in background coroutines for lag-free scrolling.
     */
    fun prefetchAdjacentPages(
        scope: CoroutineScope,
        currentPage: Int,
        radius: Int,
        targetWidth: Int,
        targetHeight: Int,
        sharpness: RenderSharpness,
        colorTone: PageColorTone,
        highQualityPrintMode: Boolean
    ) {
        if (radius <= 0 || pageCount <= 1) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            val pagesToPrefetch = mutableListOf<Int>()
            for (offset in 1..radius) {
                val next = currentPage + offset
                val prev = currentPage - offset
                if (next < pageCount) pagesToPrefetch.add(next)
                if (prev >= 0) pagesToPrefetch.add(prev)
            }

            for (pageIdx in pagesToPrefetch) {
                try {
                    val key = buildCacheKey(pageIdx, targetWidth, targetHeight, sharpness, colorTone)
                    if (memoryCache.get(key) == null) {
                        renderPage(
                            pageIndex = pageIdx,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            sharpness = sharpness,
                            colorTone = colorTone,
                            highQualityPrintMode = highQualityPrintMode
                        )
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    // Ignore prefetch failures
                }
            }
        }
    }

    suspend fun renderThumbnail(pageIndex: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        renderPage(
            pageIndex = pageIndex,
            targetWidth = 260,
            targetHeight = 360,
            sharpness = RenderSharpness.BALANCED,
            colorTone = PageColorTone.ORIGINAL,
            highQualityPrintMode = false
        )
    }

    fun getCacheUsageBytes(): Int = memoryCache.size()
    fun getMaxCacheBytes(): Int = maxMemoryCacheBytes

    fun clearCache() {
        memoryCache.evictAll()
    }

    private fun closeInternal() {
        prefetchJob?.cancel()
        prefetchJob = null

        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing PdfRenderer: ${e.message}")
        } finally {
            pdfRenderer = null
        }

        try {
            fileDescriptor?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing ParcelFileDescriptor: ${e.message}")
        } finally {
            fileDescriptor = null
        }

        try {
            tempCachedFile?.delete()
        } catch (e: Exception) {
            // Ignore
        } finally {
            tempCachedFile = null
        }

        memoryCache.evictAll()
    }

    fun close() {
        closeInternal()
    }
}
