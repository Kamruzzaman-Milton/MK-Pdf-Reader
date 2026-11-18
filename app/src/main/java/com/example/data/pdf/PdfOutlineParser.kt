package com.example.data.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PdfOutlineItem(
    val id: String,
    val title: String,
    val pageIndex: Int, // 0-indexed page number (0 = page 1)
    val level: Int = 0,
    val children: List<PdfOutlineItem> = emptyList()
)

object PdfOutlineParser {

    private const val TAG = "PdfOutlineParser"
    private const val CACHE_PREFIX = "toc2_"
    private const val PDFIUM_TIMEOUT_MS = 10_000L

    @Volatile
    var activeDocumentUri: String? = null

    private val SAMPLE_GUIDE_OUTLINE = listOf(
        PdfOutlineItem(id = "sample_0", title = "Welcome & Overview", pageIndex = 0, level = 0),
        PdfOutlineItem(id = "sample_1", title = "Chapter 1: Multi-Directional Viewing Modes", pageIndex = 1, level = 0),
        PdfOutlineItem(id = "sample_2", title = "Chapter 2: Intelligent Zoom & Pan Controls", pageIndex = 2, level = 0),
        PdfOutlineItem(id = "sample_3", title = "Chapter 3: Instant Page Jumper & Navigation", pageIndex = 3, level = 0),
        PdfOutlineItem(id = "sample_4", title = "Chapter 4: Document Metadata & Insights", pageIndex = 4, level = 0),
        PdfOutlineItem(id = "sample_5", title = "Chapter 5: Local Database & History Tracking", pageIndex = 5, level = 0),
        PdfOutlineItem(id = "sample_6", title = "Chapter 6: High Performance Caching", pageIndex = 6, level = 0),
        PdfOutlineItem(id = "sample_7", title = "Chapter 7: About MK PDF Reader", pageIndex = 7, level = 0)
    )

    // High-speed in-memory L1 cache for instant retrieval across screen rotations and sessions
    private val inMemoryCache = ConcurrentHashMap<String, List<PdfOutlineItem>>()
    private val diagnostics = ConcurrentHashMap<String, String>()

    fun lastDiagnostic(uri: Uri): String = diagnostics[uri.toString()] ?: ""

    fun hasDiskCache(context: Context, uri: Uri): Boolean {
        return try {
            getCacheFile(context, uri).exists()
        } catch (_: Throwable) {
            false
        }
    }

    internal fun cleanTitle(rawTitle: String?): String {
        return (rawTitle ?: "Untitled")
            .replace("\uFEFF", "")
            .replace("\uFFFE", "")
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Untitled" }
    }

    /**
     * Validates that a parsed outline is plausible:
     * false if empty, or if the flattened list has more than 5 items and over 80% of them have pageIndex 0.
     */
    fun isPlausible(list: List<PdfOutlineItem>): Boolean {
        if (list.isEmpty()) return false
        var total = 0
        var zeroCount = 0
        val stack = ArrayList<PdfOutlineItem>(list.size)
        for (i in list.size - 1 downTo 0) {
            stack.add(list[i])
        }
        while (stack.isNotEmpty()) {
            val item = stack.removeAt(stack.size - 1)
            total++
            if (item.pageIndex == 0) {
                zeroCount++
            }
            for (i in item.children.size - 1 downTo 0) {
                stack.add(item.children[i])
            }
        }
        if (total == 0) return false
        if (total > 5 && zeroCount * 5 > total * 4) {
            return false
        }
        return true
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, "toc_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getCacheFile(context: Context, uri: Uri): File {
        val key = PdfDocumentStorage.getDocumentKey(uri)
        return File(getCacheDir(context), "$CACHE_PREFIX$key.json")
    }

    /**
     * Purges any legacy (non-toc2_), temp (.tmp), or empty outline cache files.
     */
    fun purgeEmptyOutlineCaches(context: Context) {
        try {
            val dir = getCacheDir(context)
            dir.listFiles()?.forEach { file ->
                if (!file.name.startsWith(CACHE_PREFIX) || file.name.endsWith(".tmp")) {
                    file.delete()
                } else if (file.length() < 10L) {
                    file.delete()
                } else {
                    val text = file.readText().trim()
                    if (text == "[]" || text.isEmpty()) {
                        file.delete()
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Reads L2 cache safely; deletes the file on any JSONException or implausible/empty content.
     */
    private fun readDiskCache(context: Context, uri: Uri): List<PdfOutlineItem>? {
        val key = uri.toString()
        val cacheFile = getCacheFile(context, uri)
        if (!cacheFile.exists() || cacheFile.length() == 0L) return null

        return try {
            val jsonStr = cacheFile.readText(Charsets.UTF_8)
            val items = deserializeOutline(jsonStr)
            if (isPlausible(items)) {
                inMemoryCache[key] = items
                Log.d(TAG, "Loaded ${items.size} outline items from L2 cache for $uri")
                items
            } else {
                cacheFile.delete()
                null
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt outline JSON cache, deleting: ${e.message}")
            try { cacheFile.delete() } catch (_: Throwable) {}
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Error loading outline cache: ${e.message}")
            try { cacheFile.delete() } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Instantly returns the cached outline from L1 memory or L2 persistent disk cache,
     * or the sample guide constant. Takes < 1 millisecond.
     */
    fun loadCachedOutline(context: Context, uri: Uri): List<PdfOutlineItem>? {
        val key = uri.toString()

        // 1. Check L1 in-memory cache
        val mem = inMemoryCache[key]
        if (mem != null && isPlausible(mem)) {
            return mem
        }

        // 2. Check L2 persistent on-disk cache
        val disk = readDiskCache(context, uri)
        if (disk != null) {
            return disk
        }

        // 3. Sample-guide constant
        if (key.contains("MK_PDF_Reader_Guide")) {
            return SAMPLE_GUIDE_OUTLINE
        }

        return null
    }

    /**
     * Atomically saves a plausible parsed outline to L1 in-memory and L2 persistent disk cache.
     * Writes to a temporary file and renames it over the target file so crashes never leave half-written JSON.
     */
    fun saveOutlineCache(context: Context, uri: Uri, items: List<PdfOutlineItem>) {
        if (items.isEmpty() || !isPlausible(items)) return
        val key = uri.toString()
        inMemoryCache[key] = items

        var tmpFile: File? = null
        try {
            val cacheFile = getCacheFile(context, uri)
            val parent = cacheFile.parentFile ?: getCacheDir(context)
            tmpFile = File(parent, "${cacheFile.name}.${Thread.currentThread().id}.tmp")
            val jsonStr = serializeOutline(items)
            tmpFile.writeText(jsonStr, Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
            }
            Log.d(TAG, "Saved ${items.size} outline items to L2 cache (${cacheFile.length()} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "Error writing outline cache: ${e.message}")
            try { tmpFile?.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Deletes the persistent outline cache when a document is removed.
     */
    fun deleteOutlineCache(context: Context, uri: Uri) {
        val key = uri.toString()
        inMemoryCache.remove(key)
        diagnostics.remove(key)
        try {
            val file = getCacheFile(context, uri)
            if (file.exists()) file.delete()
            val legacyFile = File(getCacheDir(context), "toc_${PdfDocumentStorage.getDocumentKey(uri)}.json")
            if (legacyFile.exists()) legacyFile.delete()
        } catch (e: Throwable) {
            Log.w(TAG, "Error deleting outline cache: ${e.message}")
        }
    }

    /**
     * Extracts full Table of Contents / Outline hierarchy from a PDF document.
     * Cascade order: L1 -> L2 -> sample-guide -> RawPdfOutlineReader -> Pdfium (iterative, then recursive).
     */
    suspend fun parseOutline(context: Context, uri: Uri, totalPages: Int): List<PdfOutlineItem> = withContext(Dispatchers.IO) {
        val key = uri.toString()

        // 1. L1 in-memory cache
        val mem = inMemoryCache[key]
        if (mem != null && isPlausible(mem)) {
            return@withContext mem
        }

        // 2. L2 persistent disk cache
        val disk = readDiskCache(context, uri)
        if (disk != null) {
            return@withContext disk
        }

        // 3. Sample-guide constant
        if (key.contains("MK_PDF_Reader_Guide")) {
            return@withContext SAMPLE_GUIDE_OUTLINE
        }

        diagnostics.remove(key)
        val diagLines = mutableListOf<String>()
        fun recordDiag(line: String) {
            diagLines.add(line)
            diagnostics[key] = diagLines.joinToString("\n")
        }

        val startTime = System.currentTimeMillis()

        // 4. Pure-Kotlin RawPdfOutlineReader (primary engine, no timeout)
        val rawOutline: List<PdfOutlineItem>? = try {
            var pfd: ParcelFileDescriptor? = null
            try {
                val (openedPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
                pfd = openedPfd
                if (pfd == null) {
                    recordDiag("Raw: open failed")
                    null
                } else {
                    val result = RawPdfOutlineReader.read(pfd, totalPages)
                    val failureReason = RawPdfOutlineReader.consumeLastDiagnostic()
                    when {
                        result == null -> {
                            recordDiag(failureReason ?: "Raw: no outline")
                            null
                        }
                        !isPlausible(result) -> {
                            recordDiag("Raw: implausible (${result.size} roots)")
                            null
                        }
                        else -> result
                    }
                }
            } finally {
                try { pfd?.close() } catch (_: Throwable) {}
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordDiag("Raw: ${t::class.java.simpleName}")
            null
        }

        if (rawOutline != null) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "RawPdfOutlineReader parsed ${rawOutline.size} root chapters in ${elapsed}ms")
            saveOutlineCache(context, uri, rawOutline)
            return@withContext rawOutline
        }

        // 5. Native C/C++ Pdfium Engine fallback (wrapped in 10s timeout)
        var pdfiumCompleted = false
        val nativeOutline = try {
            withTimeoutOrNull(PDFIUM_TIMEOUT_MS) {
                val res = parseWithNativePdfium(context, uri, totalPages, ::recordDiag)
                pdfiumCompleted = true
                res
            }
        } catch (ce: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // TimeoutCancellationException handled by withTimeoutOrNull, but guard just in case
                null
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            pdfiumCompleted = true
            recordDiag("Pdfium: ${t::class.java.simpleName}")
            null
        }

        if (!pdfiumCompleted) {
            recordDiag("Pdfium: timeout")
            return@withContext emptyList()
        }

        if (nativeOutline != null && isPlausible(nativeOutline)) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Pdfium fallback parsed ${nativeOutline.size} root chapters in ${elapsed}ms")
            saveOutlineCache(context, uri, nativeOutline)
            return@withContext nativeOutline
        }

        return@withContext emptyList()
    }

    /**
     * Fallback Native C/C++ Engine Parser using PdfiumCore.
     */
    private suspend fun parseWithNativePdfium(
        context: Context,
        uri: Uri,
        totalPages: Int,
        recordDiag: (String) -> Unit
    ): List<PdfOutlineItem>? {
        var pfd: ParcelFileDescriptor? = null
        var pdfiumCore: PdfiumCore? = null
        var pdfDocument: PdfDocument? = null
        val ctx = currentCoroutineContext()

        try {
            val (openedPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
            pfd = openedPfd
            if (pfd == null) {
                recordDiag("Pdfium: open failed")
                return null
            }

            pdfiumCore = try {
                PdfiumCore(context.applicationContext)
            } catch (t: Throwable) {
                recordDiag("Pdfium: ${t::class.java.simpleName}")
                return null
            }

            pdfDocument = try {
                pdfiumCore.newDocument(pfd)
            } catch (t: Throwable) {
                try {
                    pfd.close()
                    val (retryPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
                    pfd = retryPfd
                    if (pfd != null) {
                        pdfiumCore.newDocument(pfd, "")
                    } else {
                        recordDiag("Pdfium: ${t::class.java.simpleName}")
                        null
                    }
                } catch (t2: Throwable) {
                    recordDiag("Pdfium: ${t2::class.java.simpleName}")
                    null
                }
            }

            if (pdfDocument == null || !ctx.isActive) {
                return null
            }

            val docPageCount = try {
                pdfiumCore.getPageCount(pdfDocument)
            } catch (_: Throwable) {
                totalPages
            }
            val maxPages = if (totalPages > 0) totalPages else docPageCount.coerceAtLeast(1)

            // Sub-path 1: Iterative JNI Native Extraction
            val iterativeResults = try {
                val res = extractBookmarksIterativeNative(pdfiumCore, pdfDocument, maxPages, recordDiag) { ctx.isActive }
                when {
                    !ctx.isActive -> null
                    res == null -> null
                    !isPlausible(res) -> {
                        recordDiag("Pdfium-iter: implausible")
                        null
                    }
                    else -> res
                }
            } catch (t: Throwable) {
                recordDiag("Pdfium-iter: ${t::class.java.simpleName}")
                null
            }

            if (iterativeResults != null || !ctx.isActive) {
                if (iterativeResults != null) {
                    Log.d(TAG, "Iterative native extraction successfully parsed ${iterativeResults.size} root chapters")
                }
                return iterativeResults
            }

            // Sub-path 2: Standard recursive getTableOfContents fallback
            val recursiveResults = try {
                val rawBookmarks = pdfiumCore.getTableOfContents(pdfDocument)
                if (!ctx.isActive) {
                    null
                } else if (rawBookmarks.isNullOrEmpty()) {
                    recordDiag("Pdfium-rec: empty")
                    null
                } else {
                    val converted = convertRecursiveBookmarks(rawBookmarks, maxPages)
                    if (!isPlausible(converted)) {
                        recordDiag("Pdfium-rec: implausible")
                        null
                    } else {
                        converted
                    }
                }
            } catch (t: Throwable) {
                recordDiag("Pdfium-rec: ${t::class.java.simpleName}")
                null
            }

            return recursiveResults
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordDiag("Pdfium: ${t::class.java.simpleName}")
            return null
        } finally {
            try {
                if (pdfDocument != null && pdfiumCore != null) {
                    pdfiumCore.closeDocument(pdfDocument)
                }
            } catch (_: Throwable) {}
            try {
                pfd?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun convertRecursiveBookmarks(
        rawBookmarks: List<PdfDocument.Bookmark>,
        maxPages: Int
    ): List<PdfOutlineItem> {
        fun convertNativeBookmark(
            bm: PdfDocument.Bookmark,
            level: Int,
            parentPath: String,
            index: Int
        ): PdfOutlineItem {
            val cleanedTitle = cleanTitle(bm.title)

            var pageIdx = bm.pageIdx.toInt()
            if (pageIdx < 0) {
                val match = Regex("""(?:page|p\.?|pg\.?)\s*(\d+)|\b(\d+)\s*$""", RegexOption.IGNORE_CASE).find(cleanedTitle)
                val foundPage = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                pageIdx = if (foundPage != null && foundPage in 1..maxPages) {
                    foundPage - 1
                } else {
                    0
                }
            } else {
                pageIdx = pageIdx.coerceIn(0, (maxPages - 1).coerceAtLeast(0))
            }

            val itemId = "${parentPath}_l${level}_i${index}_p${pageIdx}"

            val children = if (bm.hasChildren() && bm.children != null && bm.children.isNotEmpty()) {
                bm.children.mapIndexed { childIndex, childBm ->
                    convertNativeBookmark(childBm, level + 1, itemId, childIndex)
                }
            } else {
                emptyList()
            }

            return PdfOutlineItem(
                id = itemId,
                title = cleanedTitle,
                pageIndex = pageIdx,
                level = level,
                children = children
            )
        }

        return rawBookmarks.mapIndexed { index, bm ->
            convertNativeBookmark(bm, 0, "native_toc", index)
        }
    }

    /**
     * High-speed iterative JNI reader that uses a `while` loop for sibling bookmarks instead of recursion.
     * Prevents StackOverflowError on huge medical/scientific textbooks with thousands of bookmarks.
     */
    private fun extractBookmarksIterativeNative(
        pdfiumCore: PdfiumCore,
        pdfDocument: PdfDocument,
        totalPages: Int,
        recordDiag: (String) -> Unit,
        isActiveCheck: () -> Boolean
    ): List<PdfOutlineItem>? {
        return try {
            val docPtrField = PdfDocument::class.java.getDeclaredField("mNativeDocPtr").apply {
                isAccessible = true
            }
            val docPtr = docPtrField.getLong(pdfDocument)
            if (docPtr == 0L) {
                recordDiag("Pdfium-iter: null doc ptr")
                return null
            }

            val getFirstChild = PdfiumCore::class.java.getDeclaredMethod(
                "nativeGetFirstChildBookmark",
                Long::class.javaPrimitiveType,
                java.lang.Long::class.java
            ).apply { isAccessible = true }

            val getSibling = PdfiumCore::class.java.getDeclaredMethod(
                "nativeGetSiblingBookmark",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val getTitle = PdfiumCore::class.java.getDeclaredMethod(
                "nativeGetBookmarkTitle",
                Long::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val getDestIndex = PdfiumCore::class.java.getDeclaredMethod(
                "nativeGetBookmarkDestIndex",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            ).apply { isAccessible = true }

            fun traverseChildren(parentPtr: Long?, level: Int, parentPath: String): List<PdfOutlineItem> {
                if (level > 25 || !isActiveCheck()) return emptyList()
                val list = mutableListOf<PdfOutlineItem>()
                var currentPtr = getFirstChild.invoke(pdfiumCore, docPtr, parentPtr) as? Long
                var index = 0
                val visitedInLevel = HashSet<Long>()

                // Iterative while-loop for all siblings at this level!
                while (currentPtr != null && currentPtr != 0L && isActiveCheck()) {
                    if (!visitedInLevel.add(currentPtr)) {
                        break
                    }

                    val cleanedTitle = cleanTitle(getTitle.invoke(pdfiumCore, currentPtr) as? String)
                    val rawPageIdx = (getDestIndex.invoke(pdfiumCore, docPtr, currentPtr) as? Long)?.toInt() ?: -1

                    var pageIdx = rawPageIdx
                    if (pageIdx < 0) {
                        val match = Regex("""(?:page|p\.?|pg\.?)\s*(\d+)|\b(\d+)\s*$""", RegexOption.IGNORE_CASE).find(cleanedTitle)
                        val foundPage = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                        pageIdx = if (foundPage != null && foundPage in 1..totalPages) {
                            foundPage - 1
                        } else {
                            0
                        }
                    } else {
                        pageIdx = pageIdx.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    }

                    val itemId = "${parentPath}_l${level}_i${index}_p${pageIdx}"
                    val children = traverseChildren(currentPtr, level + 1, itemId)

                    list.add(
                        PdfOutlineItem(
                            id = itemId,
                            title = cleanedTitle,
                            pageIndex = pageIdx,
                            level = level,
                            children = children
                        )
                    )

                    currentPtr = getSibling.invoke(pdfiumCore, docPtr, currentPtr) as? Long
                    index++
                }

                return list
            }

            val items = traverseChildren(null, 0, "native_toc")
            if (!isActiveCheck()) {
                null
            } else if (items.isEmpty()) {
                recordDiag("Pdfium-iter: empty")
                null
            } else {
                items
            }
        } catch (t: Throwable) {
            recordDiag("Pdfium-iter: ${t::class.java.simpleName}")
            null
        }
    }

    // JSON Serialization Helpers
    private fun serializeOutline(items: List<PdfOutlineItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(itemToJson(item))
        }
        return array.toString()
    }

    private fun itemToJson(item: PdfOutlineItem): JSONObject {
        val obj = JSONObject()
        obj.put("i", item.id)
        obj.put("t", item.title)
        obj.put("p", item.pageIndex)
        obj.put("l", item.level)
        if (item.children.isNotEmpty()) {
            val childrenArray = JSONArray()
            for (child in item.children) {
                childrenArray.put(itemToJson(child))
            }
            obj.put("c", childrenArray)
        }
        return obj
    }

    private fun deserializeOutline(jsonStr: String): List<PdfOutlineItem> {
        val list = mutableListOf<PdfOutlineItem>()
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            list.add(jsonToItem(array.getJSONObject(i)))
        }
        return list
    }

    private fun jsonToItem(obj: JSONObject): PdfOutlineItem {
        val id = obj.optString("i", "")
        val title = obj.optString("t", "Untitled")
        val pageIndex = obj.optInt("p", 0)
        val level = obj.optInt("l", 0)
        val childrenList = mutableListOf<PdfOutlineItem>()
        val childrenArray = obj.optJSONArray("c")
        if (childrenArray != null) {
            for (i in 0 until childrenArray.length()) {
                childrenList.add(jsonToItem(childrenArray.getJSONObject(i)))
            }
        }
        return PdfOutlineItem(id, title, pageIndex, level, childrenList)
    }
}
