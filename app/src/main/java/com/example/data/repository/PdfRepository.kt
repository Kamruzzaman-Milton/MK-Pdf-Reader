package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.db.PdfBookmarkDao
import com.example.data.db.PdfBookmarkEntity
import com.example.data.db.PdfRecentDao
import com.example.data.db.PdfRecentItem
import com.example.data.pdf.FilePathResolver
import com.example.data.pdf.PdfDirectoryStorage
import com.example.data.pdf.PdfDocumentInfo
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.pdf.PdfOutlineParser
import com.example.data.pdf.ReadingMode
import com.example.data.pdf.RecentPdfJsonStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Repository orchestrating the persistent PDF directory system:
 * 1. Dedicated `context.filesDir/pdf_directories/` storage layer (Authoritative source-of-truth)
 * 2. SQLite Database layer (Room ORM)
 * 3. Synchronous persistent JSON layer (recent_books.json)
 *
 * Ensures recent PDFs are ALWAYS loaded directly from the separate folder,
 * never lost across app restarts or crashes, with zero storage bloat.
 */
class PdfRepository(
    private val context: Context,
    private val pdfRecentDao: PdfRecentDao,
    private val pdfBookmarkDao: PdfBookmarkDao
) {
    private val tag = "PdfRepository"

    private val _recentPdfsFlow = MutableStateFlow<List<PdfRecentItem>>(emptyList())
    val recentPdfs: Flow<List<PdfRecentItem>> = _recentPdfsFlow.asStateFlow()

    fun reloadRecentsFromDirectory() {
        val items = PdfDirectoryStorage.loadAllDirectoriesAsRecentItems(context)
        _recentPdfsFlow.value = items
        Log.d(tag, "reloadRecentsFromDirectory: Emitted ${items.size} recent PDFs from dedicated pdf_directories")
    }

    init {
        // Always load recent PDFs immediately from the dedicated folder inside app data
        reloadRecentsFromDirectory()
        syncRecentsOnStartup()
    }

    /**
     * Requirement 4: Reads from JSON file, validates each file still exists,
     * returns only valid PDFs sorted by timestamp (newest first).
     */
    /**
     * Requirement: Loads recents directly from the dedicated `pdf_directories/` folder.
     * Guaranteed never to lose files on app restart.
     */
    fun getRecentPDFs(): List<PdfRecentItem> {
        val dirList = PdfDirectoryStorage.loadAllDirectoriesSync(context)
        if (dirList.isNotEmpty()) {
            Log.d(tag, "getRecentPDFs: Found ${dirList.size} entries in dedicated pdf_directories folder")
            return dirList.map { entry ->
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

        // Fallback to JSON file if directory folder was just initialized
        val validJson = RecentPdfJsonStorage.getRecentPDFsSync(context)
        Log.d(tag, "getRecentPDFs: Found ${validJson.size} valid entries in legacy JSON file")

        return validJson.map { entry ->
            PdfRecentItem(
                pdf_path = entry.path,
                is_recent = true,
                recent_timestamp = entry.time,
                uriString = entry.uri ?: entry.path,
                fileName = entry.name,
                fileSize = entry.size,
                pageCount = entry.pageCount,
                lastReadPage = entry.page,
                scrollOffsetFraction = entry.scroll,
                readingMode = entry.mode,
                thumbnailPath = entry.thumb,
                localFilePath = if (entry.path.startsWith("/")) entry.path else null
            )
        }
    }

    /**
     * Startup reconciliation: verifies recent list and cross-synchronizes
     * dedicated directory folder, JSON file, and SQLite database.
     */
    fun syncRecentsOnStartup() {
        try {
            val dirList = PdfDirectoryStorage.loadAllDirectoriesSync(context)
            if (dirList.isNotEmpty()) {
                for (entry in dirList) {
                    val item = PdfRecentItem(
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
                    pdfRecentDao.insertOrUpdate(item)
                }
                Log.d(tag, "syncRecentsOnStartup: Synchronized ${dirList.size} recents from dedicated pdf_directories into SQLite")
            } else {
                // If directory folder is empty, seed from JSON file or existing SQLite
                val jsonList = RecentPdfJsonStorage.getRecentPDFsSync(context)
                if (jsonList.isNotEmpty()) {
                    for (entry in jsonList) {
                        val docKey = PdfDocumentStorage.getDocumentKey(Uri.parse(entry.uri ?: entry.path))
                        PdfDirectoryStorage.saveDirectoryEntrySync(
                            context,
                            PdfDirectoryStorage.PdfDirectoryEntry(
                                docKey = docKey,
                                name = entry.name,
                                uri = entry.uri ?: entry.path,
                                path = if (entry.path.startsWith("/")) entry.path else null,
                                time = entry.time,
                                size = entry.size,
                                pageCount = entry.pageCount,
                                lastReadPage = entry.page,
                                scroll = entry.scroll,
                                mode = entry.mode,
                                thumb = entry.thumb
                            )
                        )
                        val item = PdfRecentItem(
                            pdf_path = entry.path,
                            is_recent = true,
                            recent_timestamp = entry.time,
                            uriString = entry.uri ?: entry.path,
                            fileName = entry.name,
                            fileSize = entry.size,
                            pageCount = entry.pageCount,
                            lastReadPage = entry.page,
                            scrollOffsetFraction = entry.scroll,
                            readingMode = entry.mode,
                            thumbnailPath = entry.thumb,
                            localFilePath = if (entry.path.startsWith("/")) entry.path else null
                        )
                        pdfRecentDao.insertOrUpdate(item)
                    }
                    Log.d(tag, "syncRecentsOnStartup: Migrated ${jsonList.size} JSON recents to dedicated pdf_directories")
                } else {
                    val dbList = pdfRecentDao.getAllRecentsSync()
                    for (dbItem in dbList) {
                        val docKey = PdfDocumentStorage.getDocumentKey(Uri.parse(dbItem.uriString))
                        PdfDirectoryStorage.saveDirectoryEntrySync(
                            context,
                            PdfDirectoryStorage.PdfDirectoryEntry(
                                docKey = docKey,
                                name = dbItem.fileName,
                                uri = dbItem.uriString,
                                path = dbItem.localFilePath ?: dbItem.pdf_path.takeIf { it.startsWith("/") },
                                time = dbItem.recent_timestamp,
                                size = dbItem.fileSize,
                                pageCount = dbItem.pageCount,
                                lastReadPage = dbItem.lastReadPage,
                                scroll = dbItem.scrollOffsetFraction,
                                mode = dbItem.readingMode,
                                thumb = dbItem.thumbnailPath
                            )
                        )
                    }
                    Log.d(tag, "syncRecentsOnStartup: Seeded dedicated pdf_directories from ${dbList.size} SQLite recents")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "syncRecentsOnStartup error: ${e.message}")
        }
    }

    /**
     * Requirement 3: Add recent PDF synchronously to:
     * 1. Dedicated `pdf_directories/` folder
     * 2. JSON backup
     * 3. SQLite database
     * Updates timestamp to System.currentTimeMillis().
     */
    fun addRecent(
        pdfPath: String,
        uriString: String = pdfPath,
        fileName: String = "",
        fileSize: Long = 0L,
        pageCount: Int = 0,
        lastReadPage: Int = 0,
        scrollOffset: Float = 0f,
        readingMode: ReadingMode = ReadingMode.VERTICAL,
        thumbnailPath: String? = null
    ) {
        try {
            val now = System.currentTimeMillis()
            val displayName = if (fileName.isNotBlank() && fileName != "Document.pdf") {
                fileName
            } else {
                val fromPath = File(pdfPath).name
                if (fromPath.isNotBlank()) fromPath else "Document.pdf"
            }

            Log.d(tag, "addRecent called: pdfPath=$pdfPath, name=$displayName, time=$now")

            // 1. Write to dedicated `pdf_directories/` folder (never purged, zero storage bloat)
            val docKey = try {
                PdfDocumentStorage.getDocumentKey(Uri.parse(uriString))
            } catch (e: Exception) {
                "doc_${Math.abs(pdfPath.hashCode())}"
            }
            PdfDirectoryStorage.saveDirectoryEntrySync(
                context,
                PdfDirectoryStorage.PdfDirectoryEntry(
                    docKey = docKey,
                    name = displayName,
                    uri = uriString,
                    path = if (pdfPath.startsWith("/")) pdfPath else null,
                    time = now,
                    size = fileSize,
                    pageCount = pageCount,
                    lastReadPage = lastReadPage,
                    scroll = scrollOffset,
                    mode = readingMode.name,
                    thumb = thumbnailPath
                )
            )

            // 2. Write to JSON file synchronously
            RecentPdfJsonStorage.addRecentSync(
                context,
                RecentPdfJsonStorage.RecentPdfJsonEntry(
                    name = displayName,
                    path = pdfPath,
                    time = now,
                    uri = uriString,
                    page = lastReadPage,
                    pageCount = pageCount,
                    scroll = scrollOffset,
                    size = fileSize,
                    mode = readingMode.name,
                    thumb = thumbnailPath
                )
            )

            // 3. Write to SQLite atomically
            val item = PdfRecentItem(
                pdf_path = pdfPath,
                is_recent = true,
                recent_timestamp = now,
                uriString = uriString,
                fileName = displayName,
                fileSize = fileSize,
                pageCount = pageCount,
                lastReadPage = lastReadPage,
                scrollOffsetFraction = scrollOffset,
                readingMode = readingMode.name,
                thumbnailPath = thumbnailPath,
                localFilePath = if (pdfPath.startsWith("/")) pdfPath else null
            )
            pdfRecentDao.insertOrUpdate(item)

            // Immediately emit updated directory list to Flow for instant UI updates
            reloadRecentsFromDirectory()

            Log.d(tag, "addRecent finished: Persisted to dedicated pdf_directories, SQLite and JSON for $pdfPath")
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist recent PDF $pdfPath", e)
        }
    }

    suspend fun getRecentByUri(uriString: String): PdfRecentItem? = withContext(Dispatchers.IO) {
        pdfRecentDao.getRecentByUri(uriString) ?: pdfRecentDao.getRecentByPath(uriString)
    }

    suspend fun resolveDocumentInfo(uri: Uri, pageCount: Int = 0, isSample: Boolean = false): PdfDocumentInfo =
        withContext(Dispatchers.IO) {
            var fileName = "Document.pdf"
            var fileSize = 0L

            // 1. Check existing recent record first
            val existingRecent = pdfRecentDao.getRecentByUri(uri.toString())
                ?: pdfRecentDao.getRecentByPath(uri.toString())
            if (existingRecent != null && existingRecent.fileName.isNotBlank()) {
                fileName = existingRecent.fileName
                fileSize = existingRecent.fileSize
            }

            // 2. Check FilePathResolver
            val resolvedPath = FilePathResolver.resolvePath(context, uri)
            if (resolvedPath != null) {
                val f = File(resolvedPath)
                if (f.exists()) {
                    fileName = f.name
                    fileSize = f.length()
                }
            }

            // 3. Try taking persistable permission and querying ContentResolver
            try {
                PdfDocumentStorage.takePersistablePermissionSafely(context, uri)
                if (uri.scheme == "content" && fileSize <= 0L) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) {
                                val name = cursor.getString(nameIndex)
                                if (!name.isNullOrBlank()) fileName = name
                            }
                            if (sizeIndex != -1) {
                                val size = cursor.getLong(sizeIndex)
                                if (size > 0L) fileSize = size
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Error querying ContentResolver: ${e.message}")
            }

            // 4. Fallback to ContentResolver openFileDescriptor statSize
            if (fileSize <= 0L) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        fileSize = pfd.statSize
                    }
                } catch (ignored: Exception) {}
            }

            if (fileName.isBlank() || fileName == "Document.pdf") {
                val lastSegment = uri.lastPathSegment
                if (!lastSegment.isNullOrBlank()) {
                    fileName = lastSegment
                }
            }

            PdfDocumentInfo(
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                pageCount = pageCount,
                isSample = isSample
            )
        }

    /**
     * Requirement 5: Called when user opens a PDF.
     */
    suspend fun recordDocumentOpened(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        pageCount: Int,
        lastReadPage: Int,
        scrollOffset: Float,
        readingMode: ReadingMode,
        thumbnailBitmap: Bitmap? = null
    ) = withContext(Dispatchers.IO) {
        val resolvedPath = FilePathResolver.resolvePath(context, uri)
        val canonicalKey = resolvedPath ?: uri.toString()

        var thumbnailPath: String? = null
        if (thumbnailBitmap != null) {
            try {
                val thumbDir = File(context.filesDir, "thumbs")
                if (!thumbDir.exists()) thumbDir.mkdirs()
                val thumbFile = File(thumbDir, "thumb_${PdfDocumentStorage.getDocumentKey(uri)}.png")
                FileOutputStream(thumbFile).use { out ->
                    thumbnailBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
                thumbnailPath = thumbFile.absolutePath
            } catch (e: Exception) {
                Log.w(tag, "Error saving thumbnail: ${e.message}")
            }
        }

        // Call addRecent synchronously
        addRecent(
            pdfPath = canonicalKey,
            uriString = uri.toString(),
            fileName = fileName,
            fileSize = fileSize,
            pageCount = pageCount,
            lastReadPage = lastReadPage,
            scrollOffset = scrollOffset,
            readingMode = readingMode,
            thumbnailPath = thumbnailPath
        )
    }

    /**
     * Requirement 5: Called when user resumes a PDF.
     */
    fun recordDocumentResumed(uri: Uri, currentPage: Int, scrollOffset: Float, readingMode: ReadingMode) {
        try {
            val resolvedPath = FilePathResolver.resolvePath(context, uri) ?: uri.toString()
            val existing = pdfRecentDao.getRecentByPath(resolvedPath)
                ?: pdfRecentDao.getRecentByUri(uri.toString())

            addRecent(
                pdfPath = resolvedPath,
                uriString = uri.toString(),
                fileName = existing?.fileName ?: File(resolvedPath).name,
                fileSize = existing?.fileSize ?: 0L,
                pageCount = existing?.pageCount ?: 0,
                lastReadPage = currentPage,
                scrollOffset = scrollOffset,
                readingMode = readingMode,
                thumbnailPath = existing?.thumbnailPath
            )
            Log.d(tag, "recordDocumentResumed: Updated recent entry on resume for $resolvedPath")
        } catch (e: Exception) {
            Log.e(tag, "Failed to record resume for $uri", e)
        }
    }

    /**
     * Requirement 5: Called when user closes a PDF or updates reading position.
     */
    suspend fun updateReadingProgress(
        uri: Uri,
        lastReadPage: Int,
        scrollOffset: Float,
        readingMode: ReadingMode
    ) = withContext(Dispatchers.IO) {
        val resolvedPath = FilePathResolver.resolvePath(context, uri) ?: uri.toString()
        val existing = pdfRecentDao.getRecentByPath(resolvedPath)
            ?: pdfRecentDao.getRecentByUri(uri.toString())

        addRecent(
            pdfPath = resolvedPath,
            uriString = uri.toString(),
            fileName = existing?.fileName ?: File(resolvedPath).name,
            fileSize = existing?.fileSize ?: 0L,
            pageCount = existing?.pageCount ?: 0,
            lastReadPage = lastReadPage,
            scrollOffset = scrollOffset,
            readingMode = readingMode,
            thumbnailPath = existing?.thumbnailPath
        )
        Log.d(tag, "updateReadingProgress: Updated position page=$lastReadPage for $resolvedPath")
    }

    suspend fun deleteRecent(pathOrUri: String) = withContext(Dispatchers.IO) {
        pdfRecentDao.deleteRecent(pathOrUri)
        PdfDirectoryStorage.deleteDirectoryEntrySync(context, pathOrUri)
        RecentPdfJsonStorage.removeRecentSync(context, pathOrUri)
        reloadRecentsFromDirectory()
        try {
            val uri = Uri.parse(pathOrUri)
            PdfDocumentStorage.deletePersistentFile(context, uri)
            PdfOutlineParser.deleteOutlineCache(context, uri)
        } catch (e: Exception) {
            Log.w(tag, "Error deleting auxiliary caches for $pathOrUri", e)
        }
        Log.d(tag, "deleteRecent: Removed $pathOrUri from dedicated directory, SQLite, and JSON")
    }

    suspend fun clearAllRecents() = withContext(Dispatchers.IO) {
        pdfRecentDao.clearAll()
        PdfDirectoryStorage.clearAllDirectoriesSync(context)
        RecentPdfJsonStorage.clearAllSync(context)
        PdfDocumentStorage.clearAllStoredFiles(context)
        reloadRecentsFromDirectory()
        Log.d(tag, "clearAllRecents: Cleared all recents in dedicated directories, SQLite and JSON")
    }

    fun getBookmarksForUri(uriString: String): Flow<List<PdfBookmarkEntity>> {
        return pdfBookmarkDao.getBookmarksForUri(uriString)
    }

    fun getBookmarkedPages(uriString: String): Flow<List<Int>> {
        return pdfBookmarkDao.getBookmarkedPageIndices(uriString)
    }

    suspend fun isPageBookmarked(uriString: String, pageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        pdfBookmarkDao.getBookmarkForPage(uriString, pageIndex) != null
    }

    suspend fun toggleBookmark(uriString: String, pageIndex: Int, title: String? = null): Boolean = withContext(Dispatchers.IO) {
        val existing = pdfBookmarkDao.getBookmarkForPage(uriString, pageIndex)
        if (existing != null) {
            pdfBookmarkDao.deleteBookmark(existing)
            false
        } else {
            val entity = PdfBookmarkEntity(
                uriString = uriString,
                pageIndex = pageIndex,
                pageNumber = pageIndex + 1,
                title = title ?: "Page ${pageIndex + 1}"
            )
            pdfBookmarkDao.insertBookmark(entity)
            true
        }
    }

    suspend fun addBookmark(uriString: String, pageIndex: Int, title: String? = null) = withContext(Dispatchers.IO) {
        val entity = PdfBookmarkEntity(
            uriString = uriString,
            pageIndex = pageIndex,
            pageNumber = pageIndex + 1,
            title = title ?: "Page ${pageIndex + 1}"
        )
        pdfBookmarkDao.insertBookmark(entity)
    }

    suspend fun deleteBookmark(bookmark: PdfBookmarkEntity) = withContext(Dispatchers.IO) {
        pdfBookmarkDao.deleteBookmark(bookmark)
    }

    suspend fun deleteBookmarkByPage(uriString: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        pdfBookmarkDao.deleteBookmarkByPage(uriString, pageIndex)
    }
}
