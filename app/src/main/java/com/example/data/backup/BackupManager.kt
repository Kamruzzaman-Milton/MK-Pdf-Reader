package com.example.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.example.data.db.AppDatabase
import com.example.data.db.PdfBookmarkEntity
import com.example.data.library.LibraryRepository
import com.example.data.library.LibraryRoot
import com.example.data.library.LibraryRootStatus
import com.example.data.library.LibraryStorage
import com.example.data.pdf.PdfDirectoryStorage
import com.example.data.repository.PdfRepository
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupExportSummary(
    val recentsCount: Int,
    val bookmarksCount: Int,
    val libraryRootsCount: Int,
    val thumbnailsCount: Int
)

data class BackupImportSummary(
    val recentsRestored: Int,
    val bookmarksRestored: Int,
    val rootsRestored: Int,
    val thumbnailsRestored: Int
)

object BackupManager {
    private const val TAG = "BackupManager"
    private const val BACKUP_FORMAT_VERSION = 1
    private const val PREFS_SETTINGS = "mk_pdf_settings"
    private const val PREFS_STATS = "mk_pdf_stats"
    private const val MAX_JSON_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_THUMB_ENTRY_BYTES = 8 * 1024 * 1024

    private val RECENT_DIR_REGEX = Regex("^recents/dir_([0-9a-f]{32})\\.json$")
    private val THUMB_PNG_REGEX = Regex("^thumbs/thumb_([0-9a-f]{32})\\.png$")

    fun defaultBackupFileName(): String {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "MKPDFReader_backup_$dateStr.zip"
    }

    suspend fun computeStorageUsedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val filesDir = context.applicationContext.filesDir
        val folders = listOf("thumbs", "lib_thumbs", "toc_cache", "pdf_directories", "library")
        var total = 0L
        for (name in folders) {
            total += dirSize(File(filesDir, name))
        }
        total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    sum += child.length()
                }
            }
        }
        return sum
    }

    suspend fun exportBackup(
        context: Context,
        destUri: Uri,
        includeThumbnails: Boolean
    ): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            val db = AppDatabase.getDatabase(appContext)
            val bookmarks = db.pdfBookmarkDao().getAll()
            val roots = LibraryStorage.loadRoots(appContext)
            val recentsDir = PdfDirectoryStorage.getDirectoryFolder(appContext)
            val recentFiles = recentsDir.listFiles()?.filter { file ->
                file.isFile && (file.name == "directory_index.json" ||
                    (file.name.startsWith("dir_") && file.name.endsWith(".json")))
            } ?: emptyList()

            val thumbsDir = File(appContext.filesDir, "thumbs")
            val thumbFiles = if (includeThumbnails && thumbsDir.exists()) {
                thumbsDir.listFiles()?.filter { file ->
                    file.isFile && file.name.startsWith("thumb_") && file.name.endsWith(".png")
                } ?: emptyList()
            } else {
                emptyList()
            }

            val recentsEntryCount = recentFiles.count { it.name.startsWith("dir_") }

            val rawOut = appContext.contentResolver.openOutputStream(destUri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to open destination file"))

            ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
                // 1. manifest.json
                val manifest = JSONObject().apply {
                    put("format", BACKUP_FORMAT_VERSION)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("createdAt", System.currentTimeMillis())
                    put(
                        "counts",
                        JSONObject().apply {
                            put("recents", recentsEntryCount)
                            put("bookmarks", bookmarks.size)
                            put("libraryRoots", roots.size)
                            put("thumbnails", thumbFiles.size)
                        }
                    )
                }
                writeTextZipEntry(zos, "manifest.json", manifest.toString(2))

                // 2. settings.json
                val settingsPrefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                writeTextZipEntry(zos, "settings.json", serializePrefsToJson(settingsPrefs).toString(2))

                // 3. stats.json
                val statsPrefs = appContext.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
                writeTextZipEntry(zos, "stats.json", serializePrefsToJson(statsPrefs).toString(2))

                // 4. library_roots.json
                val rootsFile = File(File(appContext.filesDir, "library"), "roots.json")
                if (rootsFile.exists() && rootsFile.isFile) {
                    writeFileZipEntry(zos, "library_roots.json", rootsFile)
                } else {
                    writeTextZipEntry(zos, "library_roots.json", "[]")
                }

                // 5. recents/ (all dir_*.json + directory_index.json)
                for (rf in recentFiles) {
                    writeFileZipEntry(zos, "recents/${rf.name}", rf)
                }

                // 6. bookmarks.json
                val bookmarksArray = JSONArray()
                for (bm in bookmarks) {
                    bookmarksArray.put(
                        JSONObject().apply {
                            put("uriString", bm.uriString)
                            put("pageIndex", bm.pageIndex)
                            put("pageNumber", bm.pageNumber)
                            put("title", bm.title ?: "")
                            put("createdAt", bm.createdAt)
                        }
                    )
                }
                writeTextZipEntry(zos, "bookmarks.json", bookmarksArray.toString(2))

                // 7. thumbs/ (recents thumbnails only when included)
                for (tf in thumbFiles) {
                    writeFileZipEntry(zos, "thumbs/${tf.name}", tf)
                }
            }

            Result.success(
                BackupExportSummary(
                    recentsCount = recentsEntryCount,
                    bookmarksCount = bookmarks.size,
                    libraryRootsCount = roots.size,
                    thumbnailsCount = thumbFiles.size
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "exportBackup failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    private data class StagedPrefValue(
        val type: String,
        val value: Any
    )

    private data class ParsedBackup(
        val settings: Map<String, StagedPrefValue>,
        val stats: Map<String, StagedPrefValue>,
        val roots: List<LibraryRoot>,
        val recents: Map<String, PdfDirectoryStorage.PdfDirectoryEntry>,
        val bookmarks: List<PdfBookmarkEntity>,
        val thumbnails: Map<String, ByteArray>
    )

    suspend fun importBackup(
        context: Context,
        srcUri: Uri
    ): Result<BackupImportSummary> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            // Phase 1: Parse and validate entire archive in memory before mutating anything
            val parsed = parseBackupArchive(appContext, srcUri)

            // Phase 2: Apply merge atomically / cleanly
            val thumbsDir = File(appContext.filesDir, "thumbs")
            if (!thumbsDir.exists()) thumbsDir.mkdirs()

            var thumbsWritten = 0
            for ((docKey, bytes) in parsed.thumbnails) {
                val destThumb = File(thumbsDir, "thumb_${docKey}.png")
                val tmp = File(thumbsDir, "thumb_${docKey}.png.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                if (destThumb.exists()) destThumb.delete()
                if (!tmp.renameTo(destThumb)) {
                    tmp.copyTo(destThumb, overwrite = true)
                    tmp.delete()
                }
                thumbsWritten++
            }

            // Merge recents per docKey where newer time wins
            val existingRecents = PdfDirectoryStorage.loadAllDirectoriesSync(appContext)
                .associateBy { it.docKey }
            var recentsRestored = 0
            for ((docKey, imported) in parsed.recents) {
                val existing = existingRecents[docKey]
                val thumbFile = File(thumbsDir, "thumb_${docKey}.png")
                val resolvedThumb = if (thumbFile.exists() && thumbFile.length() > 0L) {
                    thumbFile.absolutePath
                } else {
                    existing?.thumb
                }

                if (existing == null || imported.time > existing.time) {
                    val merged = imported.copy(
                        docKey = docKey,
                        thumb = resolvedThumb,
                        mediaUri = imported.mediaUri ?: existing?.mediaUri
                    )
                    PdfDirectoryStorage.saveDirectoryEntrySync(appContext, merged)
                    recentsRestored++
                } else if (resolvedThumb != null && existing.thumb.isNullOrBlank()) {
                    PdfDirectoryStorage.saveDirectoryEntrySync(appContext, existing.copy(thumb = resolvedThumb))
                }
            }

            // Merge bookmarks (OnConflictStrategy.REPLACE on unique (uriString, pageIndex))
            val db = AppDatabase.getDatabase(appContext)
            val bookmarkDao = db.pdfBookmarkDao()
            for (bm in parsed.bookmarks) {
                bookmarkDao.insertBookmark(bm)
            }

            // Merge settings (overwrite only present keys; union favorite_keys)
            if (parsed.settings.isNotEmpty()) {
                val prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for ((key, item) in parsed.settings) {
                    if (key == "favorite_keys" && item.value is Set<*>) {
                        val existingFavs = prefs.getStringSet("favorite_keys", emptySet()) ?: emptySet()
                        val unionFavs = HashSet<String>(existingFavs)
                        for (entry in item.value) {
                            if (entry is String && entry.isNotBlank()) {
                                unionFavs.add(entry)
                            }
                        }
                        editor.putStringSet("favorite_keys", unionFavs)
                    } else {
                        applyPrefValue(editor, key, item)
                    }
                }
                editor.commit()
            }

            // Merge stats (keep max for numeric reading stats)
            if (parsed.stats.isNotEmpty()) {
                val statsPrefs = appContext.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
                val editor = statsPrefs.edit()
                for ((key, item) in parsed.stats) {
                    if (item.type == "long" && item.value is Long) {
                        val existingVal = statsPrefs.getLong(key, 0L)
                        editor.putLong(key, maxOf(existingVal, item.value))
                    } else {
                        applyPrefValue(editor, key, item)
                    }
                }
                editor.commit()
            }

            // Merge library roots (union by id)
            val libraryRepo = LibraryRepository.getInstance(appContext)
            val rootsAdded = libraryRepo.mergeImportedRoots(parsed.roots)

            // Reload repositories after import
            val pdfRepo = PdfRepository(appContext, db.pdfRecentDao(), bookmarkDao)
            pdfRepo.syncRecentsOnStartup()
            pdfRepo.reloadRecentsFromDirectory()
            SettingsRepository.getInstance(appContext).reloadFromPrefs()

            Result.success(
                BackupImportSummary(
                    recentsRestored = recentsRestored,
                    bookmarksRestored = parsed.bookmarks.size,
                    rootsRestored = rootsAdded,
                    thumbnailsRestored = thumbsWritten
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "importBackup failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    private fun parseBackupArchive(context: Context, srcUri: Uri): ParsedBackup {
        val rawIn = context.contentResolver.openInputStream(srcUri)
            ?: throw IllegalStateException("Could not open backup file")

        var hasManifest = false
        var settingsMap: Map<String, StagedPrefValue> = emptyMap()
        var statsMap: Map<String, StagedPrefValue> = emptyMap()
        var rootsList: List<LibraryRoot> = emptyList()
        var bookmarksList: List<PdfBookmarkEntity> = emptyList()
        val dirEntries = LinkedHashMap<String, PdfDirectoryStorage.PdfDirectoryEntry>()
        val indexFallbackEntries = LinkedHashMap<String, PdfDirectoryStorage.PdfDirectoryEntry>()
        val thumbnails = LinkedHashMap<String, ByteArray>()

        ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val name = entry.name ?: continue
                    if (name.contains("..") || name.startsWith("/")) continue

                    when {
                        name == "manifest.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES)
                            val obj = JSONObject(text)
                            val format = obj.optInt("format", -1)
                            if (format != BACKUP_FORMAT_VERSION) {
                                throw IllegalArgumentException("Unsupported backup format ($format)")
                            }
                            hasManifest = true
                        }
                        name == "settings.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES)
                            settingsMap = deserializePrefsFromJson(JSONObject(text))
                        }
                        name == "stats.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES)
                            statsMap = deserializePrefsFromJson(JSONObject(text))
                        }
                        name == "library_roots.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES).trim()
                            rootsList = parseRootsArray(JSONArray(text))
                        }
                        name == "bookmarks.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES).trim()
                            bookmarksList = parseBookmarksArray(JSONArray(text))
                        }
                        name == "recents/directory_index.json" -> {
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES).trim()
                            val arr = JSONArray(text)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                parseDirEntry(obj, null)?.let { parsedEntry ->
                                    indexFallbackEntries[parsedEntry.docKey] = parsedEntry
                                }
                            }
                        }
                        RECENT_DIR_REGEX.matches(name) -> {
                            val match = RECENT_DIR_REGEX.matchEntire(name) ?: continue
                            val keyFromName = match.groupValues[1]
                            val text = readZipEntryText(zis, MAX_JSON_ENTRY_BYTES)
                            val parsedEntry = parseDirEntry(JSONObject(text), keyFromName)
                            if (parsedEntry != null) {
                                dirEntries[parsedEntry.docKey] = parsedEntry
                            }
                        }
                        THUMB_PNG_REGEX.matches(name) -> {
                            val match = THUMB_PNG_REGEX.matchEntire(name) ?: continue
                            val docKey = match.groupValues[1]
                            thumbnails[docKey] = readZipEntryBytes(zis, MAX_THUMB_ENTRY_BYTES)
                        }
                        else -> {
                            // Zip-slip / strict whitelist guard: ignore unrecognized entries
                        }
                    }
                } finally {
                    zis.closeEntry()
                }
            }
        }

        if (!hasManifest) {
            throw IllegalArgumentException("Invalid backup archive: missing manifest.json")
        }

        val mergedRecents = LinkedHashMap<String, PdfDirectoryStorage.PdfDirectoryEntry>(indexFallbackEntries)
        mergedRecents.putAll(dirEntries)

        return ParsedBackup(
            settings = settingsMap,
            stats = statsMap,
            roots = rootsList,
            recents = mergedRecents,
            bookmarks = bookmarksList,
            thumbnails = thumbnails
        )
    }

    private fun serializePrefsToJson(prefs: SharedPreferences): JSONObject {
        val root = JSONObject()
        for ((key, rawValue) in prefs.all) {
            if (rawValue == null) continue
            val wrapper = JSONObject()
            when (rawValue) {
                is Boolean -> {
                    wrapper.put("type", "boolean")
                    wrapper.put("value", rawValue)
                }
                is Int -> {
                    wrapper.put("type", "int")
                    wrapper.put("value", rawValue)
                }
                is Long -> {
                    wrapper.put("type", "long")
                    wrapper.put("value", rawValue)
                }
                is Float -> {
                    wrapper.put("type", "float")
                    wrapper.put("value", rawValue.toDouble())
                }
                is String -> {
                    wrapper.put("type", "string")
                    wrapper.put("value", rawValue)
                }
                is Set<*> -> {
                    wrapper.put("type", "stringSet")
                    val arr = JSONArray()
                    for (item in rawValue) {
                        if (item is String) arr.put(item)
                    }
                    wrapper.put("value", arr)
                }
                else -> continue
            }
            root.put(key, wrapper)
        }
        return root
    }

    private fun deserializePrefsFromJson(root: JSONObject): Map<String, StagedPrefValue> {
        val map = LinkedHashMap<String, StagedPrefValue>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val wrapper = root.optJSONObject(key)
            if (wrapper != null && wrapper.has("type") && wrapper.has("value")) {
                val type = wrapper.optString("type")
                when (type) {
                    "boolean" -> map[key] = StagedPrefValue(type, wrapper.optBoolean("value"))
                    "int" -> map[key] = StagedPrefValue(type, wrapper.optInt("value"))
                    "long" -> map[key] = StagedPrefValue(type, wrapper.optLong("value"))
                    "float" -> map[key] = StagedPrefValue(type, wrapper.optDouble("value").toFloat())
                    "string" -> map[key] = StagedPrefValue(type, wrapper.optString("value"))
                    "stringSet" -> {
                        val arr = wrapper.optJSONArray("value")
                        val set = HashSet<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val s = arr.optString(i)
                                if (s.isNotBlank()) set.add(s)
                            }
                        }
                        map[key] = StagedPrefValue(type, set)
                    }
                }
            } else if (key == "favoriteKeys" || key == "favorite_keys") {
                val arr = root.optJSONArray(key)
                if (arr != null) {
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) set.add(s)
                    }
                    map["favorite_keys"] = StagedPrefValue("stringSet", set)
                }
            }
        }
        return map
    }

    private fun applyPrefValue(
        editor: SharedPreferences.Editor,
        key: String,
        staged: StagedPrefValue
    ) {
        when (staged.type) {
            "boolean" -> (staged.value as? Boolean)?.let { editor.putBoolean(key, it) }
            "int" -> (staged.value as? Int)?.let { editor.putInt(key, it) }
            "long" -> (staged.value as? Long)?.let { editor.putLong(key, it) }
            "float" -> (staged.value as? Float)?.let { editor.putFloat(key, it) }
            "string" -> (staged.value as? String)?.let { editor.putString(key, it) }
            "stringSet" -> {
                @Suppress("UNCHECKED_CAST")
                val set = staged.value as? Set<String>
                if (set != null) editor.putStringSet(key, HashSet(set))
            }
        }
    }

    private fun parseRootsArray(arr: JSONArray): List<LibraryRoot> {
        val list = ArrayList<LibraryRoot>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val absPath = obj.optString("absPath").takeIf { it.isNotBlank() }
            val treeUri = obj.optString("treeUri").takeIf { it.isNotBlank() }
            if (id.isBlank() || (absPath == null && treeUri == null)) continue
            val statusStr = obj.optString("status", LibraryRootStatus.OK.name)
            val status = try {
                LibraryRootStatus.valueOf(statusStr)
            } catch (_: Exception) {
                LibraryRootStatus.OK
            }
            list.add(
                LibraryRoot(
                    id = id,
                    name = obj.optString("name").ifBlank { "Folder" },
                    absPath = absPath,
                    treeUri = treeUri,
                    addedAt = obj.optLong("addedAt", 0L),
                    lastScanAt = obj.optLong("lastScanAt", 0L),
                    pdfCount = obj.optInt("pdfCount", 0),
                    status = status
                )
            )
        }
        return list
    }

    private fun parseBookmarksArray(arr: JSONArray): List<PdfBookmarkEntity> {
        val list = ArrayList<PdfBookmarkEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uriString = obj.optString("uriString")
            if (uriString.isBlank()) continue
            val pageIndex = obj.optInt("pageIndex", 0).coerceAtLeast(0)
            val pageNumber = obj.optInt("pageNumber", pageIndex + 1).coerceAtLeast(1)
            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            list.add(
                PdfBookmarkEntity(
                    id = 0,
                    uriString = uriString,
                    pageIndex = pageIndex,
                    pageNumber = pageNumber,
                    title = title,
                    createdAt = createdAt
                )
            )
        }
        return list
    }

    private fun parseDirEntry(
        obj: JSONObject,
        docKeyFallback: String?
    ): PdfDirectoryStorage.PdfDirectoryEntry? {
        val name = obj.optString("name")
        val uri = obj.optString("uri")
        if (name.isBlank() && uri.isBlank()) return null
        val docKey = obj.optString("docKey").ifBlank { docKeyFallback ?: "" }
        if (docKey.isBlank()) return null
        val path = obj.optString("path").takeIf { it.isNotBlank() }
        val dirPath = obj.optString("directoryPath").takeIf { it.isNotBlank() }
        return PdfDirectoryStorage.PdfDirectoryEntry(
            docKey = docKey,
            name = name.ifBlank { "Document.pdf" },
            uri = uri,
            path = path,
            directoryPath = dirPath,
            time = obj.optLong("time", 0L),
            size = obj.optLong("size", 0L),
            pageCount = obj.optInt("pageCount", 0),
            lastReadPage = obj.optInt("lastReadPage", 0),
            scroll = obj.optDouble("scroll", 0.0).toFloat(),
            mode = obj.optString("mode", "VERTICAL"),
            thumb = obj.optString("thumb").takeIf { it.isNotBlank() },
            mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
        )
    }

    private fun writeTextZipEntry(zos: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeFileZipEntry(zos: ZipOutputStream, name: String, file: File) {
        val entry = ZipEntry(name)
        zos.putNextEntry(entry)
        val buffer = ByteArray(16 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                zos.write(buffer, 0, read)
            }
        }
        zos.closeEntry()
    }

    private fun readZipEntryText(zis: ZipInputStream, maxBytes: Int): String {
        val bytes = readZipEntryBytes(zis, maxBytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readZipEntryBytes(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = zis.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Backup entry exceeds maximum allowed size")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
