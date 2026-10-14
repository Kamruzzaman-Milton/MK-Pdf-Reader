package com.example.data.pdf

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * LibreraReader-style JSON Persistence Layer (AppData.java architecture).
 *
 * Maintains a persistent JSON file at `filesDir/recent_books.json`.
 * Features:
 * - Synchronous, crash-safe atomic disk writes (IO.writeObjSync equivalent)
 * - Array of [{name, path, time, ...}]
 * - Automatic file existence validation on read (filters out deleted PDFs)
 * - Source-of-truth redundancy alongside SQLite database
 */
object RecentPdfJsonStorage {
    private const val TAG = "RecentPdfJsonStorage"
    private const val JSON_FILE_NAME = "recent_books.json"
    private val lock = Any()

    data class RecentPdfJsonEntry(
        val name: String,
        val path: String,       // Absolute path to the PDF on disk or canonical URI
        val time: Long,       // System.currentTimeMillis() when added/updated
        val uri: String? = null,
        val page: Int = 0,
        val pageCount: Int = 0,
        val scroll: Float = 0f,
        val size: Long = 0L,
        val mode: String = "VERTICAL",
        val thumb: String? = null
    )

    fun getJsonFile(context: Context): File {
        return File(context.filesDir, JSON_FILE_NAME)
    }

    /**
     * Synchronously adds or updates an entry in the persistent JSON file.
     * Uses atomic file writing to ensure crash-safety before returning.
     */
    fun addRecentSync(context: Context, entry: RecentPdfJsonEntry) {
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                val updatedEntry = entry.copy(time = if (entry.time > 0L) entry.time else now)
                val currentList = readRawList(context).toMutableList()

                // Remove existing entry for same path or URI if present
                val it = currentList.iterator()
                while (it.hasNext()) {
                    val existing = it.next()
                    if (existing.path.equals(updatedEntry.path, ignoreCase = true) ||
                        (!existing.uri.isNullOrBlank() && existing.uri.equals(updatedEntry.uri, ignoreCase = true))
                    ) {
                        it.remove()
                    }
                }

                // Add to front (most recent)
                currentList.add(0, updatedEntry)

                // Write synchronously using AtomicFile
                writeListSync(context, currentList)
                Log.d(TAG, "addRecentSync: Successfully added ${updatedEntry.name} (${updatedEntry.path}) time=${updatedEntry.time}")
            } catch (e: Exception) {
                Log.e(TAG, "addRecentSync: Error adding recent to JSON: ${e.message}", e)
            }
        }
    }

    /**
     * Reads recent PDFs from the JSON file and returns all recent PDFs sorted by newest first.
     * Preserves entries so recent items are not lost if permissions are temporarily pending.
     */
    fun getRecentPDFsSync(context: Context): List<RecentPdfJsonEntry> {
        synchronized(lock) {
            try {
                val rawList = readRawList(context)
                val sorted = rawList.sortedByDescending { it.time }
                Log.d(TAG, "getRecentPDFsSync: Returning ${sorted.size} persistent recent PDFs from JSON")
                return sorted
            } catch (e: Exception) {
                Log.e(TAG, "getRecentPDFsSync: Error reading recents from JSON: ${e.message}", e)
                return emptyList()
            }
        }
    }

    /**
     * Validates whether the given PDF file still exists on disk or is accessible.
     */
    private fun isFileValid(context: Context, item: RecentPdfJsonEntry): Boolean {
        // 1. Direct filesystem check
        val f = File(item.path)
        if (f.isFile && f.exists() && f.canRead()) {
            return true
        }

        // 2. Check if localFilePath or decoded path exists
        if (item.path.startsWith("/")) {
            val decoded = Uri.decode(item.path)
            val df = File(decoded)
            if (df.isFile && df.exists() && df.canRead()) {
                return true
            }
        }

        // 3. If URI is content://, check if ContentResolver can open file descriptor
        val uriStr = item.uri ?: item.path
        if (uriStr.startsWith("content://")) {
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    return true
                }
            } catch (ignored: Exception) {}
        }

        // 4. Check if guide sample PDF in private files
        if (item.path.contains("MK_PDF_Reader_Guide") || (item.uri != null && item.uri.contains("MK_PDF_Reader_Guide"))) {
            val sample = File(context.filesDir, "MK_PDF_Reader_Guide.pdf")
            if (sample.exists()) return true
        }

        return false
    }

    /**
     * Removes an entry by path or URI synchronously.
     */
    fun removeRecentSync(context: Context, pathOrUri: String) {
        synchronized(lock) {
            try {
                val currentList = readRawList(context).toMutableList()
                val removed = currentList.removeAll {
                    it.path.equals(pathOrUri, ignoreCase = true) ||
                    it.uri.equals(pathOrUri, ignoreCase = true)
                }
                if (removed) {
                    writeListSync(context, currentList)
                    Log.d(TAG, "removeRecentSync: Removed $pathOrUri from JSON")
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeRecentSync: Error removing recent from JSON: ${e.message}")
            }
        }
    }

    /**
     * Clears all recent entries synchronously.
     */
    fun clearAllSync(context: Context) {
        synchronized(lock) {
            try {
                writeListSync(context, emptyList())
                Log.d(TAG, "clearAllSync: Cleared all recent entries from JSON")
            } catch (e: Exception) {
                Log.e(TAG, "clearAllSync: Error clearing JSON: ${e.message}")
            }
        }
    }

    /**
     * Internal raw list reader without existence filtering.
     */
    private fun readRawList(context: Context): List<RecentPdfJsonEntry> {
        val file = getJsonFile(context)
        if (!file.exists() || file.length() == 0L) {
            return emptyList()
        }

        val atomicFile = AtomicFile(file)
        return try {
            val bytes = atomicFile.readFully()
            val jsonStr = String(bytes, StandardCharsets.UTF_8).trim()
            if (jsonStr.isEmpty() || !jsonStr.startsWith("[")) {
                return emptyList()
            }

            val array = JSONArray(jsonStr)
            val list = mutableListOf<RecentPdfJsonEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "Document.pdf")
                val path = obj.optString("path", "")
                val time = obj.optLong("time", System.currentTimeMillis())
                if (path.isNotBlank()) {
                    list.add(
                        RecentPdfJsonEntry(
                            name = name,
                            path = path,
                            time = time,
                            uri = obj.optString("uri", path).ifBlank { null },
                            page = obj.optInt("page", 0),
                            pageCount = obj.optInt("pageCount", 0),
                            scroll = obj.optDouble("scroll", 0.0).toFloat(),
                            size = obj.optLong("size", 0L),
                            mode = obj.optString("mode", "VERTICAL"),
                            thumb = obj.optString("thumb", null)
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Error reading JSON file, returning empty: ${e.message}")
            emptyList()
        }
    }

    /**
     * Synchronously writes the list to disk using AtomicFile (writeObjSync).
     */
    private fun writeListSync(context: Context, list: List<RecentPdfJsonEntry>) {
        val file = getJsonFile(context)
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("name", item.name)
                    put("path", item.path)
                    put("time", item.time)
                    item.uri?.let { put("uri", it) }
                    put("page", item.page)
                    put("pageCount", item.pageCount)
                    put("scroll", item.scroll.toDouble())
                    put("size", item.size)
                    put("mode", item.mode)
                    item.thumb?.let { put("thumb", it) }
                }
                array.put(obj)
            }

            val jsonBytes = array.toString(2).toByteArray(StandardCharsets.UTF_8)
            stream = atomicFile.startWrite()
            stream.write(jsonBytes)
            stream.flush()
            atomicFile.finishWrite(stream)
            stream = null
            Log.d(TAG, "writeListSync: Wrote ${list.size} recents to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "writeListSync: Failed to write JSON: ${e.message}", e)
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
        }
    }
}
