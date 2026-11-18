package com.example.data.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, "toc_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getCacheFile(context: Context, uri: Uri): File {
        val key = PdfDocumentStorage.getDocumentKey(uri)
        return File(getCacheDir(context), "toc_$key.json")
    }

    /**
     * Purges any empty outline cache files saved during previous failed parsing attempts.
     */
    fun purgeEmptyOutlineCaches(context: Context) {
        try {
            val dir = getCacheDir(context)
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("toc_") && file.length() < 10L) {
                    file.delete()
                } else if (file.name.startsWith("toc_")) {
                    val text = file.readText().trim()
                    if (text == "[]" || text.isEmpty()) {
                        file.delete()
                    }
                }
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Instantly returns the cached outline from memory or persistent disk cache.
     * Takes < 1 millisecond.
     */
    fun loadCachedOutline(context: Context, uri: Uri): List<PdfOutlineItem>? {
        val key = uri.toString()
        if (key.contains("MK_PDF_Reader_Guide")) {
            return SAMPLE_GUIDE_OUTLINE
        }
        // 1. Check L1 in-memory cache
        val mem = inMemoryCache[key]
        if (mem != null && mem.isNotEmpty()) {
            return mem
        }

        // 2. Check L2 persistent on-disk cache
        try {
            val cacheFile = getCacheFile(context, uri)
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val jsonStr = cacheFile.readText(Charsets.UTF_8)
                val items = deserializeOutline(jsonStr)
                if (items.isNotEmpty()) {
                    inMemoryCache[key] = items
                    Log.d(TAG, "Loaded ${items.size} outline items instantly from persistent disk cache for $uri")
                    return items
                } else {
                    cacheFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading outline cache: ${e.message}")
        }

        return null
    }

    /**
     * Saves the parsed outline to both in-memory and persistent disk cache.
     */
    fun saveOutlineCache(context: Context, uri: Uri, items: List<PdfOutlineItem>) {
        if (items.isEmpty()) return
        val key = uri.toString()
        inMemoryCache[key] = items

        try {
            val cacheFile = getCacheFile(context, uri)
            val jsonStr = serializeOutline(items)
            cacheFile.writeText(jsonStr, Charsets.UTF_8)
            Log.d(TAG, "Saved ${items.size} outline items to persistent cache (${cacheFile.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Error writing outline cache: ${e.message}")
        }
    }

    /**
     * Deletes the persistent outline cache when a document is removed.
     */
    fun deleteOutlineCache(context: Context, uri: Uri) {
        val key = uri.toString()
        inMemoryCache.remove(key)
        try {
            val file = getCacheFile(context, uri)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting outline cache: ${e.message}")
        }
    }

    /**
     * Extracts full Table of Contents / Outline hierarchy from a PDF document.
     * Uses L1 memory cache, L2 disk cache, and Native C/C++ Pdfium Engine.
     */
    suspend fun parseOutline(context: Context, uri: Uri, totalPages: Int): List<PdfOutlineItem> = withContext(Dispatchers.IO) {
        if (uri.toString().contains("MK_PDF_Reader_Guide")) {
            return@withContext SAMPLE_GUIDE_OUTLINE
        }

        try {
            // Fast path 1: Check in-memory & disk cache (< 1ms)
            val cached = loadCachedOutline(context, uri)
            if (cached != null && cached.isNotEmpty()) {
                return@withContext cached
            }

            val startTime = System.currentTimeMillis()

            // Native C/C++ Pdfium Engine
            val nativeOutline = parseWithNativePdfium(context, uri, totalPages)
            if (!nativeOutline.isNullOrEmpty()) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Native C/C++ Pdfium Engine parsed ${nativeOutline.size} root chapters in ${elapsed}ms")
                saveOutlineCache(context, uri, nativeOutline)
                return@withContext nativeOutline
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Outline parsing failed: ${e.message}")
        }

        return@withContext emptyList()
    }

    /**
     * Primary Native C/C++ Engine Parser using PdfiumCore.
     * Operates with direct C++ pointer lookups for instant (< 5ms) bookmark tree retrieval.
     */
    private suspend fun parseWithNativePdfium(context: Context, uri: Uri, totalPages: Int): List<PdfOutlineItem>? {
        var pfd: ParcelFileDescriptor? = null
        var pdfiumCore: PdfiumCore? = null
        var pdfDocument: PdfDocument? = null

        try {
            // Open seekable ParcelFileDescriptor reliably via PdfDocumentStorage (works even if external folder was deleted)
            val (openedPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
            pfd = openedPfd

            if (pfd == null) {
                return null
            }

            pdfiumCore = PdfiumCore(context.applicationContext)

            // Try standard load first. If it fails (e.g. encrypted document with owner password), try empty password
            pdfDocument = try {
                pdfiumCore.newDocument(pfd)
            } catch (e: Exception) {
                try {
                    pfd.close()
                    val (retryPfd, _) = PdfDocumentStorage.openFileDescriptor(context, uri)
                    pfd = retryPfd
                    if (pfd != null) {
                        pdfiumCore.newDocument(pfd, "")
                    } else {
                        null
                    }
                } catch (e2: Exception) {
                    null
                }
            }

            if (pdfDocument == null) {
                return null
            }

            val docPageCount = try {
                pdfiumCore.getPageCount(pdfDocument)
            } catch (e: Exception) {
                totalPages
            }
            val maxPages = if (totalPages > 0) totalPages else docPageCount.coerceAtLeast(1)

            // Primary: Iterative JNI Native Extraction (immune to StackOverflowError on 4,000+ chapter books)
            val iterativeResults = extractBookmarksIterativeNative(pdfiumCore, pdfDocument, maxPages)
            if (!iterativeResults.isNullOrEmpty()) {
                Log.d(TAG, "Iterative native extraction successfully parsed ${iterativeResults.size} root chapters")
                return iterativeResults
            }

            // Fallback: standard library getTableOfContents
            val rawBookmarks = try {
                pdfiumCore.getTableOfContents(pdfDocument)
            } catch (e: Throwable) {
                Log.w(TAG, "Standard getTableOfContents failed (likely StackOverflow): ${e.message}")
                null
            }
            if (rawBookmarks.isNullOrEmpty()) {
                return null
            }

            fun convertNativeBookmark(
                bm: PdfDocument.Bookmark,
                level: Int,
                parentPath: String,
                index: Int
            ): PdfOutlineItem {
                val rawTitle = bm.title ?: "Untitled"
                val cleanedTitle = rawTitle
                    .replace("\uFEFF", "")
                    .replace("\uFFFE", "")
                    .replace("\r\n", " ")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace("\t", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "Untitled" }

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

            val results = rawBookmarks.mapIndexed { index, bm ->
                convertNativeBookmark(bm, 0, "native_toc", index)
            }

            return results.ifEmpty { null }

        } catch (e: Throwable) {
            Log.w(TAG, "Native C++ Pdfium extraction error: ${e.message}")
            return null
        } finally {
            try {
                if (pdfDocument != null && pdfiumCore != null) {
                    pdfiumCore.closeDocument(pdfDocument)
                }
            } catch (ignored: Exception) {}
            try {
                pfd?.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * High-speed iterative JNI reader that uses a `while` loop for sibling bookmarks instead of recursion.
     * Prevents StackOverflowError on huge medical/scientific textbooks with thousands of bookmarks.
     */
    private fun extractBookmarksIterativeNative(
        pdfiumCore: PdfiumCore,
        pdfDocument: PdfDocument,
        totalPages: Int
    ): List<PdfOutlineItem>? {
        return try {
            val docPtrField = PdfDocument::class.java.getDeclaredField("mNativeDocPtr").apply {
                isAccessible = true
            }
            val docPtr = docPtrField.getLong(pdfDocument)
            if (docPtr == 0L) return null

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
                if (level > 25) return emptyList()
                val list = mutableListOf<PdfOutlineItem>()
                var currentPtr = getFirstChild.invoke(pdfiumCore, docPtr, parentPtr) as? Long
                var index = 0
                val visitedInLevel = HashSet<Long>()

                // Iterative while-loop for all siblings at this level!
                while (currentPtr != null && currentPtr != 0L) {
                    if (!visitedInLevel.add(currentPtr)) {
                        break // Prevent infinite cycles
                    }

                    val rawTitle = (getTitle.invoke(pdfiumCore, currentPtr) as? String) ?: "Untitled"
                    val cleanedTitle = rawTitle
                        .replace("\uFEFF", "")
                        .replace("\uFFFE", "")
                        .replace("\r\n", " ")
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace("\t", " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { "Untitled" }

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

                    // Only recurse down the tree for nested sub-chapters
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

                    // Get next sibling
                    currentPtr = getSibling.invoke(pdfiumCore, docPtr, currentPtr) as? Long
                    index++
                }

                return list
            }

            val items = traverseChildren(null, 0, "native_toc")
            items.ifEmpty { null }
        } catch (e: Throwable) {
            Log.w(TAG, "Iterative native PDFium extraction failed: ${e.message}")
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
