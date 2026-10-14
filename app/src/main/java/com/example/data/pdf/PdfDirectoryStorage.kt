package com.example.data.pdf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import com.example.data.db.PdfRecentItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Dedicated Persistent PDF Directory Storage Manager.
 *
 * Saves every PDF's file URL directory / metadata entry in a dedicated separate folder:
 * `context.filesDir/pdf_directories/`
 *
 * Key Architectural Guarantees:
 * 1. This folder and its contents are NEVER deleted by cache cleaners, app restarts, or background purges.
 * 2. It contains only tiny descriptor JSON files (~1 KB each), so storage bloat is ZERO
 *    (preventing the 2 GB accumulation that occurred when full PDF files were copied).
 * 3. Recent documents are ALWAYS loaded directly from this dedicated folder.
 * 4. Never removes or deletes an entry automatically on transient read failures; entries are only
 *    removed when the user explicitly deletes them from the UI.
 */
object PdfDirectoryStorage {
    private const val TAG = "PdfDirectoryStorage"
    private const val DIRECTORY_FOLDER_NAME = "pdf_directories"
    private const val INDEX_FILE_NAME = "directory_index.json"
    private val lock = Any()

    data class PdfDirectoryEntry(
        val docKey: String,
        val name: String,
        val uri: String,
        val path: String? = null,
        val directoryPath: String? = null,
        val time: Long = System.currentTimeMillis(),
        val size: Long = 0L,
        val pageCount: Int = 0,
        val lastReadPage: Int = 0,
        val scroll: Float = 0f,
        val mode: String = "VERTICAL",
        val thumb: String? = null,
        val mediaUri: String? = null
    )

    /**
     * Returns the dedicated `pdf_directories` folder in private app data.
     * Guaranteed to exist and never be purged.
     */
    fun getDirectoryFolder(context: Context): File {
        val folder = File(context.filesDir, DIRECTORY_FOLDER_NAME)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    private fun getIndexFile(context: Context): File {
        return File(getDirectoryFolder(context), INDEX_FILE_NAME)
    }

    private fun getEntryFile(context: Context, docKey: String): File {
        return File(getDirectoryFolder(context), "dir_${docKey}.json")
    }

    /**
     * Synchronously persists a PDF directory entry to:
     * 1. Individual dedicated file: `pdf_directories/dir_<key>.json`
     * 2. Master directory index: `pdf_directories/directory_index.json`
     */
    fun saveDirectoryEntrySync(context: Context, entry: PdfDirectoryEntry) {
        synchronized(lock) {
            try {
                val folder = getDirectoryFolder(context)
                val key = if (entry.docKey.isNotBlank()) entry.docKey else PdfDocumentStorage.getDocumentKey(Uri.parse(entry.uri))
                val dirPath = entry.directoryPath ?: entry.path?.let { p ->
                    try { File(p).parent } catch (ignored: Exception) { null }
                }

                val normalizedEntry = entry.copy(
                    docKey = key,
                    directoryPath = dirPath,
                    time = if (entry.time > 0L) entry.time else System.currentTimeMillis()
                )

                // 1. Write individual descriptor file atomically
                val entryFile = getEntryFile(context, key)
                val entryJson = JSONObject().apply {
                    put("docKey", normalizedEntry.docKey)
                    put("name", normalizedEntry.name)
                    put("uri", normalizedEntry.uri)
                    put("path", normalizedEntry.path ?: "")
                    put("directoryPath", normalizedEntry.directoryPath ?: "")
                    put("time", normalizedEntry.time)
                    put("size", normalizedEntry.size)
                    put("pageCount", normalizedEntry.pageCount)
                    put("lastReadPage", normalizedEntry.lastReadPage)
                    put("scroll", normalizedEntry.scroll.toDouble())
                    put("mode", normalizedEntry.mode)
                    put("thumb", normalizedEntry.thumb ?: "")
                    put("mediaUri", normalizedEntry.mediaUri ?: "")
                }

                val atomicEntryFile = AtomicFile(entryFile)
                var fos: FileOutputStream? = null
                try {
                    fos = atomicEntryFile.startWrite()
                    fos.write(entryJson.toString(2).toByteArray(StandardCharsets.UTF_8))
                    atomicEntryFile.finishWrite(fos)
                } catch (e: Exception) {
                    if (fos != null) atomicEntryFile.failWrite(fos)
                    throw e
                }

                // 2. Update master directory index
                val currentList = loadAllDirectoriesRaw(context).toMutableList()
                currentList.removeAll { 
                    it.docKey == key || 
                    it.uri.equals(normalizedEntry.uri, ignoreCase = true) || 
                    (!normalizedEntry.path.isNullOrBlank() && it.path.equals(normalizedEntry.path, ignoreCase = true)) 
                }
                currentList.add(0, normalizedEntry)
                writeIndexSync(context, currentList)

                Log.d(TAG, "saveDirectoryEntrySync: Saved directory descriptor for '${normalizedEntry.name}' in $folder (key=$key)")
            } catch (e: Exception) {
                Log.e(TAG, "saveDirectoryEntrySync failed: ${e.message}", e)
            }
        }
    }

    /**
     * Loads all persistent PDF directory entries from `filesDir/pdf_directories/`.
     * Does NOT delete entries if files are temporarily inaccessible.
     * Sorted newest first.
     */
    fun loadAllDirectoriesSync(context: Context): List<PdfDirectoryEntry> {
        synchronized(lock) {
            try {
                val list = loadAllDirectoriesRaw(context)
                return list.sortedByDescending { it.time }
            } catch (e: Exception) {
                Log.e(TAG, "loadAllDirectoriesSync error: ${e.message}", e)
                return emptyList()
            }
        }
    }

    /**
     * Loads all directory entries as PdfRecentItem objects ready for the Recent menu.
     * This fulfills the requirement that the recent menu always loads PDFs from the separate folder.
     */
    fun loadAllDirectoriesAsRecentItems(context: Context): List<PdfRecentItem> {
        val entries = loadAllDirectoriesSync(context)
        return entries.map { entry ->
            PdfRecentItem(
                pdf_path = entry.path ?: entry.uri,
                is_recent = true,
                recent_timestamp = entry.time,
                uriString = entry.uri,
                fileName = entry.name,
                fileSize = entry.size,
                pageCount = entry.pageCount,
                lastReadPage = entry.lastReadPage,
                scrollOffsetFraction = entry.scroll,
                readingMode = entry.mode,
                thumbnailPath = entry.thumb,
                localFilePath = entry.path?.takeIf { it.startsWith("/") }
            )
        }
    }

    private fun loadAllDirectoriesRaw(context: Context): List<PdfDirectoryEntry> {
        val indexFile = getIndexFile(context)
        val entriesMap = mutableMapOf<String, PdfDirectoryEntry>()

        // 1. Read master index if available
        if (indexFile.exists() && indexFile.length() > 0L) {
            try {
                val jsonStr = indexFile.readText(StandardCharsets.UTF_8)
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val entry = parseEntryFromJson(obj)
                    if (entry != null) {
                        entriesMap[entry.docKey] = entry
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading directory index: ${e.message}")
            }
        }

        // 2. Scan individual files in pdf_directories/ in case index was missing an entry
        val folder = getDirectoryFolder(context)
        folder.listFiles()?.forEach { file ->
            if (file.name.startsWith("dir_") && file.name.endsWith(".json")) {
                try {
                    val jsonStr = file.readText(StandardCharsets.UTF_8)
                    val obj = JSONObject(jsonStr)
                    val entry = parseEntryFromJson(obj)
                    if (entry != null && !entriesMap.containsKey(entry.docKey)) {
                        entriesMap[entry.docKey] = entry
                    }
                } catch (ignored: Exception) {}
            }
        }

        return entriesMap.values.toList()
    }

    private fun parseEntryFromJson(obj: JSONObject): PdfDirectoryEntry? {
        val name = obj.optString("name")
        val uri = obj.optString("uri")
        if (name.isBlank() && uri.isBlank()) return null

        val docKey = obj.optString("docKey").ifBlank {
            PdfDocumentStorage.getDocumentKey(Uri.parse(uri))
        }

        val path = obj.optString("path").takeIf { it.isNotBlank() }
        val dirPath = obj.optString("directoryPath").takeIf { it.isNotBlank() } ?: path?.let { p ->
            try { File(p).parent } catch (ignored: Exception) { null }
        }

        return PdfDirectoryEntry(
            docKey = docKey,
            name = name.ifBlank { "Document.pdf" },
            uri = uri,
            path = path,
            directoryPath = dirPath,
            time = obj.optLong("time", System.currentTimeMillis()),
            size = obj.optLong("size", 0L),
            pageCount = obj.optInt("pageCount", 0),
            lastReadPage = obj.optInt("lastReadPage", 0),
            scroll = obj.optDouble("scroll", 0.0).toFloat(),
            mode = obj.optString("mode", "VERTICAL"),
            thumb = obj.optString("thumb").takeIf { it.isNotBlank() },
            mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
        )
    }

    private fun writeIndexSync(context: Context, list: List<PdfDirectoryEntry>) {
        val indexFile = getIndexFile(context)
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("docKey", item.docKey)
                put("name", item.name)
                put("uri", item.uri)
                put("path", item.path ?: "")
                put("directoryPath", item.directoryPath ?: "")
                put("time", item.time)
                put("size", item.size)
                put("pageCount", item.pageCount)
                put("lastReadPage", item.lastReadPage)
                put("scroll", item.scroll.toDouble())
                put("mode", item.mode)
                put("thumb", item.thumb ?: "")
                put("mediaUri", item.mediaUri ?: "")
            }
            jsonArray.put(obj)
        }

        val atomicFile = AtomicFile(indexFile)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            fos.write(jsonArray.toString(2).toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) atomicFile.failWrite(fos)
            Log.e(TAG, "Error writing directory index: ${e.message}", e)
        }
    }

    /**
     * Finds a matching directory descriptor for a given URI or filesystem path.
     */
    fun findDirectoryEntry(context: Context, targetUri: Uri): PdfDirectoryEntry? {
        val targetStr = targetUri.toString()
        val targetPath = targetUri.path
        val key = PdfDocumentStorage.getDocumentKey(targetUri)

        val all = loadAllDirectoriesSync(context)
        return all.find { it.docKey == key }
            ?: all.find { it.uri.equals(targetStr, ignoreCase = true) }
            ?: all.find { !it.path.isNullOrBlank() && (it.path.equals(targetStr, ignoreCase = true) || it.path.equals(targetPath, ignoreCase = true)) }
            ?: all.find { !it.mediaUri.isNullOrBlank() && it.mediaUri.equals(targetStr, ignoreCase = true) }
            ?: run {
                // Fuzzy match by filename if last segment matches
                val lastSegment = targetUri.lastPathSegment
                if (!lastSegment.isNullOrBlank()) {
                    all.find { it.name.equals(lastSegment, ignoreCase = true) }
                } else null
            }
    }

    /**
     * Updates an existing directory entry with a newly resolved MediaStore URI or updated location.
     */
    fun updateMediaUriSync(context: Context, targetUri: Uri, newMediaUri: Uri) {
        val entry = findDirectoryEntry(context, targetUri) ?: return
        val updated = entry.copy(mediaUri = newMediaUri.toString())
        saveDirectoryEntrySync(context, updated)
        Log.d(TAG, "updateMediaUriSync: Attached MediaStore URI $newMediaUri to ${entry.name}")
    }

    /**
     * Deletes a directory entry when explicitly requested by user from the UI.
     */
    fun deleteDirectoryEntrySync(context: Context, pathOrUri: String) {
        synchronized(lock) {
            try {
                val currentList = loadAllDirectoriesRaw(context).toMutableList()
                val removed = mutableListOf<PdfDirectoryEntry>()
                currentList.removeAll { item ->
                    val match = item.uri.equals(pathOrUri, ignoreCase = true) ||
                            item.path.equals(pathOrUri, ignoreCase = true) ||
                            item.docKey == pathOrUri
                    if (match) removed.add(item)
                    match
                }

                // Delete individual descriptor files
                for (item in removed) {
                    try {
                        getEntryFile(context, item.docKey).delete()
                    } catch (ignored: Exception) {}
                }

                writeIndexSync(context, currentList)
                Log.d(TAG, "deleteDirectoryEntrySync: Removed $pathOrUri (${removed.size} entries)")
            } catch (e: Exception) {
                Log.e(TAG, "deleteDirectoryEntrySync error: ${e.message}", e)
            }
        }
    }

    /**
     * Clears all directory descriptors only when user explicitly taps "Clear All Recents".
     */
    fun clearAllDirectoriesSync(context: Context) {
        synchronized(lock) {
            try {
                val folder = getDirectoryFolder(context)
                folder.listFiles()?.forEach { file ->
                    try { file.delete() } catch (ignored: Exception) {}
                }
                Log.d(TAG, "clearAllDirectoriesSync: Cleared all PDF directories")
            } catch (e: Exception) {
                Log.e(TAG, "clearAllDirectoriesSync error: ${e.message}", e)
            }
        }
    }
}
