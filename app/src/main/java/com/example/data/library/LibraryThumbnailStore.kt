package com.example.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.pdf.PdfDocumentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object LibraryThumbnailStore {
    private const val TAG = "LibraryThumbnailStore"
    private const val LIB_THUMBS_DIR = "lib_thumbs"
    private const val RECENTS_THUMBS_DIR = "thumbs"
    private const val THUMB_WIDTH_PX = 200
    private const val THUMB_MAX_HEIGHT_PX = 300
    private const val JPEG_QUALITY = 80
    private const val RENDER_TIMEOUT_MS = 8_000L
    private const val MAX_MEM_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_DISK_BYTES = 40L * 1024L * 1024L

    private val renderSemaphore = Semaphore(1)
    private val failedDocKeys = ConcurrentHashMap.newKeySet<String>()
    private val diskLock = Any()

    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_MEM_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4).coerceAtLeast(1)
        }
    }

    fun getLibThumbsDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, LIB_THUMBS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getRecentThumbFile(context: Context, docKey: String): File {
        return File(File(context.applicationContext.filesDir, RECENTS_THUMBS_DIR), "thumb_${docKey}.png")
    }

    private fun getLibThumbFile(context: Context, docKey: String): File {
        return File(getLibThumbsDir(context), "${docKey}.jpg")
    }

    fun getCachedSync(docKey: String): ImageBitmap? {
        return memoryCache.get(docKey)
    }

    suspend fun loadOrGenerateThumbnail(
        context: Context,
        uri: Uri,
        docKey: String,
        recentThumbnailPath: String? = null
    ): ImageBitmap? {
        memoryCache.get(docKey)?.let { return it }

        val appContext = context.applicationContext

        // 1. Check existing recents thumbnail first
        val existingBitmap = withContext(Dispatchers.IO) {
            val recentByPath = recentThumbnailPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 0L }
            val recentByKey = getRecentThumbFile(appContext, docKey)
                .takeIf { it.exists() && it.length() > 0L }
            val libThumb = getLibThumbFile(appContext, docKey)
                .takeIf { it.exists() && it.length() > 0L }

            val candidate = recentByPath ?: recentByKey ?: libThumb
            if (candidate != null) {
                try {
                    BitmapFactory.decodeFile(candidate.absolutePath)?.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }

        if (existingBitmap != null) {
            memoryCache.put(docKey, existingBitmap)
            return existingBitmap
        }

        if (docKey in failedDocKeys) {
            return null
        }

        // 2. Lazily render with single worker Semaphore(1), cancellable when leaving screen
        return renderSemaphore.withPermit {
            memoryCache.get(docKey)?.let { return@withPermit it }
            if (docKey in failedDocKeys) return@withPermit null

            withContext(Dispatchers.IO) {
                // Re-check disk inside permit in case another job just finished it
                val diskFile = getLibThumbFile(appContext, docKey)
                if (diskFile.exists() && diskFile.length() > 0L) {
                    val decoded = try {
                        BitmapFactory.decodeFile(diskFile.absolutePath)?.asImageBitmap()
                    } catch (_: Throwable) {
                        null
                    }
                    if (decoded != null) {
                        memoryCache.put(docKey, decoded)
                        return@withContext decoded
                    }
                }

                val rendered = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    renderAndSaveJpeg(appContext, uri, diskFile)
                }

                if (rendered == null) {
                    failedDocKeys.add(docKey)
                    null
                } else {
                    memoryCache.put(docKey, rendered)
                    trimDiskCacheIfNeeded(appContext)
                    rendered
                }
            }
        }
    }

    private suspend fun renderAndSaveJpeg(
        context: Context,
        uri: Uri,
        destFile: File
    ): ImageBitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        var renderBitmap: Bitmap? = null
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")

        try {
            val (openedPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
            pfd = openedPfd ?: return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            page = renderer.openPage(0)

            val pageW = page.width.coerceAtLeast(1)
            val pageH = page.height.coerceAtLeast(1)
            val targetHeight = ((THUMB_WIDTH_PX.toFloat() * pageH.toFloat()) / pageW.toFloat())
                .roundToInt()
                .coerceIn(1, THUMB_MAX_HEIGHT_PX)

            renderBitmap = Bitmap.createBitmap(THUMB_WIDTH_PX, targetHeight, Bitmap.Config.ARGB_8888)
            renderBitmap.eraseColor(Color.WHITE)
            page.render(renderBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            synchronized(diskLock) {
                destFile.parentFile?.mkdirs()
                FileOutputStream(tmpFile).use { out ->
                    renderBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.flush()
                }
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (ce: CancellationException) {
            try { tmpFile.delete() } catch (_: Exception) {}
            throw ce
        } catch (t: Throwable) {
            try { tmpFile.delete() } catch (_: Exception) {}
            Log.w(TAG, "Failed generating thumbnail for $uri: ${t.message}")
            return null
        } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            try { renderBitmap?.recycle() } catch (_: Throwable) {}
        }

        return try {
            BitmapFactory.decodeFile(destFile.absolutePath)?.asImageBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed decoding saved thumbnail ${destFile.name}: ${t.message}")
            null
        }
    }

    private fun trimDiskCacheIfNeeded(context: Context) {
        synchronized(diskLock) {
            try {
                val dir = getLibThumbsDir(context)
                val files = dir.listFiles()?.filter { it.isFile } ?: return
                var total = 0L
                for (f in files) {
                    total += f.length()
                }
                if (total <= MAX_DISK_BYTES) return

                val sortedOldest = files.sortedBy { it.lastModified() }
                for (f in sortedOldest) {
                    if (total <= MAX_DISK_BYTES) break
                    val len = f.length()
                    if (f.delete()) {
                        total -= len
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "trimDiskCacheIfNeeded error: ${e.message}")
            }
        }
    }

    fun deleteThumbnail(context: Context, docKey: String) {
        memoryCache.remove(docKey)
        failedDocKeys.remove(docKey)
        try {
            val libFile = getLibThumbFile(context, docKey)
            if (libFile.exists()) libFile.delete()
        } catch (_: Exception) {}
    }

    suspend fun clearLibThumbCache(context: Context) = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        failedDocKeys.clear()
        synchronized(diskLock) {
            try {
                val dir = getLibThumbsDir(context)
                dir.listFiles()?.forEach { file ->
                    try { file.delete() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "clearLibThumbCache error: ${e.message}")
            }
        }
    }
}
