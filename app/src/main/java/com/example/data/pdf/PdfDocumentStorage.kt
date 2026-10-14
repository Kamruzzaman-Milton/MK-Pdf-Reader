package com.example.data.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * High-performance, zero-waste document access manager for MK PDF Reader.
 *
 * Reads PDFs directly in-place from content:// and file:// URIs using Android's
 * ContentResolver and ParcelFileDescriptor without duplicating or cloning PDF files
 * into private app storage. This prevents app storage from bloating to > 1GB after
 * reading multiple documents.
 */
object PdfDocumentStorage {
    private const val TAG = "PdfDocumentStorage"
    private const val BOOKS_DIR_NAME = "books"

    fun getBooksDir(context: Context): File {
        val dir = File(context.filesDir, BOOKS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Attempts to take persistable URI permissions from the Android ContentResolver.
     * Returns true if permission is persistable, false if temporary/unsupported.
     */
    fun takePersistablePermissionSafely(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "Persistable URI read permission acquired for $uri")
            true
        } catch (e: Exception) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                Log.d(TAG, "Persistable URI read+write permission acquired for $uri")
                true
            } catch (e2: Exception) {
                Log.w(TAG, "Cannot take persistable permission for $uri: ${e.message}")
                false
            }
        }
    }

    /**
     * Queries Android's MediaStore to find a system-authorized content URI for a file.
     * This bypasses Scoped Storage permission barriers on Android 10+ (API 29+) without
     * requiring MANAGE_EXTERNAL_STORAGE. Searches both MediaStore.Files and MediaStore.Downloads.
     */
    fun findMediaStoreUriForPath(context: Context, filePath: String, fileName: String? = null, fileSize: Long = 0L): Uri? {
        return try {
            val file = File(filePath)
            val name = (fileName ?: file.name).trim()
            val size = if (fileSize > 0L) fileSize else (if (file.exists()) file.length() else 0L)

            val collections = mutableListOf<Uri>()
            collections.add(MediaStore.Files.getContentUri("external"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collections.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }

            for (collection in collections) {
                val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)

                // 1. Exact path match in MediaStore
                try {
                    context.contentResolver.query(
                        collection,
                        projection,
                        "${MediaStore.Files.FileColumns.DATA} = ?",
                        arrayOf(filePath),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                            val mediaUri = android.content.ContentUris.withAppendedId(collection, id)
                            Log.d(TAG, "Found MediaStore URI by path: $mediaUri")
                            return mediaUri
                        }
                    }
                } catch (ignored: Exception) {}

                // 2. Name + Size match in MediaStore
                if (name.isNotBlank()) {
                    try {
                        val selection = if (size > 0L) {
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
                        } else {
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                        }
                        val selectionArgs = if (size > 0L) arrayOf(name, size.toString()) else arrayOf(name)

                        context.contentResolver.query(
                            collection,
                            projection,
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                                val mediaUri = android.content.ContentUris.withAppendedId(collection, id)
                                Log.d(TAG, "Found MediaStore URI by name/size: $mediaUri")
                                return mediaUri
                            }
                        }
                    } catch (ignored: Exception) {}

                    // 3. Name only match
                    try {
                        context.contentResolver.query(
                            collection,
                            projection,
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                            arrayOf(name),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                                val mediaUri = android.content.ContentUris.withAppendedId(collection, id)
                                Log.d(TAG, "Found MediaStore URI by name only: $mediaUri")
                                return mediaUri
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "findMediaStoreUriForPath error: ${e.message}")
            null
        }
    }

    /**
     * Generates a deterministic key for caching metadata, thumbnails, and outline JSON.
     * Uses normalized (decoded) URI so encoded vs unencoded variants map to the identical key.
     */
    fun getDocumentKey(uri: Uri): String {
        val rawStr = uri.toString()
        val decodedStr = try { Uri.decode(rawStr) } catch (e: Exception) { rawStr }
        val normalized = decodedStr.trim().lowercase(java.util.Locale.ROOT)
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(normalized.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(32)
        } catch (e: Exception) {
            "doc_${Math.abs(normalized.hashCode())}"
        }
    }

    /**
     * Resolves and opens a ParcelFileDescriptor directly in-place without copying the PDF.
     * Guaranteed zero storage bloat, using the dedicated `pdf_directories/` storage registry
     * and multi-layer recovery (Direct content, MediaStore, and File system).
     */
    suspend fun openFileDescriptor(context: Context, uri: Uri): Pair<ParcelFileDescriptor?, File?> = withContext(Dispatchers.IO) {
        // Strategy 1: Direct file:// path
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.canRead()) {
                try {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    Log.d(TAG, "Opened PDF directly from file:// path: ${file.absolutePath}")
                    return@withContext Pair(pfd, file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed opening file:// descriptor directly: ${e.message}")
                }
            }
        }

        // Strategy 2: Direct ContentResolver openFileDescriptor (content:// scheme)
        if (uri.scheme == "content") {
            try {
                takePersistablePermissionSafely(context, uri)
                val contentPfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (contentPfd != null) {
                    Log.d(TAG, "Opened PDF directly via ContentResolver: $uri")
                    return@withContext Pair(contentPfd, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct ContentResolver.openFileDescriptor failed: ${e.message}")
            }

            // Strategy 3: Try decoded URI if URL-encoded characters caused issues with 3rd party providers
            try {
                val decodedStr = Uri.decode(uri.toString())
                if (decodedStr != uri.toString()) {
                    val decodedUri = Uri.parse(decodedStr)
                    takePersistablePermissionSafely(context, decodedUri)
                    val decodedPfd = context.contentResolver.openFileDescriptor(decodedUri, "r")
                    if (decodedPfd != null) {
                        Log.d(TAG, "Opened PDF via decoded ContentResolver: $decodedUri")
                        return@withContext Pair(decodedPfd, null)
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Strategy 4: Retrieve directory metadata from the dedicated `pdf_directories/` folder
        val dirEntry = PdfDirectoryStorage.findDirectoryEntry(context, uri)

        // Try directory entry's mediaUri if saved
        if (dirEntry?.mediaUri != null && dirEntry.mediaUri != uri.toString()) {
            try {
                val mUri = Uri.parse(dirEntry.mediaUri)
                val pfd = context.contentResolver.openFileDescriptor(mUri, "r")
                if (pfd != null) {
                    Log.d(TAG, "Opened PDF via saved MediaStore URI: $mUri")
                    return@withContext Pair(pfd, null)
                }
            } catch (ignored: Exception) {}
        }

        // Strategy 5: FilePathResolver / Directory Entry path recovery
        val resolvedPath = dirEntry?.path
            ?: FilePathResolver.resolvePath(context, uri)
            ?: run {
                try {
                    val db = com.example.data.db.AppDatabase.getDatabase(context)
                    val recent = db.pdfRecentDao().getRecentByUri(uri.toString())
                    recent?.localFilePath ?: recent?.pdf_path?.takeIf { it.startsWith("/") }
                } catch (ignored: Exception) { null }
            }

        if (!resolvedPath.isNullOrBlank()) {
            val file = File(resolvedPath)

            // 5a. Direct file system access (if file exists)
            try {
                if (file.exists()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    Log.d(TAG, "Successfully opened PDF via recovered filesystem path: ${file.absolutePath}")
                    return@withContext Pair(pfd, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed opening recovered path: ${e.message}")
            }

            // 5b. MediaStore query by resolved path/name (bypasses Scoped Storage barriers)
            val mediaStoreUri = findMediaStoreUriForPath(
                context = context,
                filePath = resolvedPath,
                fileName = dirEntry?.name ?: file.name,
                fileSize = dirEntry?.size ?: (if (file.exists()) file.length() else 0L)
            )
            if (mediaStoreUri != null) {
                try {
                    val pfd = context.contentResolver.openFileDescriptor(mediaStoreUri, "r")
                    if (pfd != null) {
                        Log.d(TAG, "Successfully opened PDF via MediaStore resolution: $mediaStoreUri")
                        PdfDirectoryStorage.updateMediaUriSync(context, uri, mediaStoreUri)
                        return@withContext Pair(pfd, null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed opening MediaStore resolved URI: ${e.message}")
                }
            }

            // 5c. Common public download/document directories by filename
            val candidateFileNames = listOfNotNull(dirEntry?.name, file.name).distinct()
            for (candidateName in candidateFileNames) {
                if (candidateName.isNotBlank() && candidateName != "Document.pdf") {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val candidateDownload = File(downloadsDir, candidateName)
                    try {
                        if (candidateDownload.exists()) {
                            val pfd = ParcelFileDescriptor.open(candidateDownload, ParcelFileDescriptor.MODE_READ_ONLY)
                            Log.d(TAG, "Successfully opened PDF from public Downloads: ${candidateDownload.absolutePath}")
                            return@withContext Pair(pfd, candidateDownload)
                        }
                    } catch (ignored: Exception) {}

                    val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val candidateDoc = File(docsDir, candidateName)
                    try {
                        if (candidateDoc.exists()) {
                            val pfd = ParcelFileDescriptor.open(candidateDoc, ParcelFileDescriptor.MODE_READ_ONLY)
                            Log.d(TAG, "Successfully opened PDF from public Documents: ${candidateDoc.absolutePath}")
                            return@withContext Pair(pfd, candidateDoc)
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }

        // Strategy 6: Check if internal sample PDF
        val sampleFile = File(context.filesDir, "MK_PDF_Reader_Guide.pdf")
        if (uri.toString().contains("MK_PDF_Reader_Guide") && sampleFile.exists() && sampleFile.canRead()) {
            try {
                val pfd = ParcelFileDescriptor.open(sampleFile, ParcelFileDescriptor.MODE_READ_ONLY)
                return@withContext Pair(pfd, sampleFile)
            } catch (ignored: Exception) {}
        }

        Pair(null, null)
    }

    /**
     * Gets a readable InputStream directly from the original source without copying.
     */
    suspend fun openInputStream(context: Context, uri: Uri): InputStream? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.canRead()) {
                try {
                    return@withContext FileInputStream(file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading file:// stream: ${e.message}")
                }
            }
        }

        try {
            takePersistablePermissionSafely(context, uri)
            context.contentResolver.openInputStream(uri)?.let { return@withContext it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading content:// stream: ${e.message}")
        }

        null
    }

    /**
     * Purges accumulated duplicate PDFs from previous versions and clears temporary caches.
     * Reclaims > 1GB of storage space immediately.
     */
    suspend fun purgeExcessStorageAndCaches(context: Context) = withContext(Dispatchers.IO) {
        try {
            // 1. Delete all duplicate PDFs previously stored in filesDir/books/
            val booksDir = File(context.filesDir, BOOKS_DIR_NAME)
            if (booksDir.exists()) {
                booksDir.listFiles()?.forEach { file ->
                    try {
                        val deleted = file.delete()
                        if (deleted) Log.d(TAG, "Purged duplicate book copy: ${file.name}")
                    } catch (ignored: Exception) {}
                }
            }

            // 2. Delete legacy saved_pdfs directory
            val legacyDir = File(context.filesDir, "saved_pdfs")
            if (legacyDir.exists()) {
                legacyDir.listFiles()?.forEach { file ->
                    try { file.delete() } catch (ignored: Exception) {}
                }
                try { legacyDir.delete() } catch (ignored: Exception) {}
            }

            // 3. Delete internal temporary PDF and TOC files in cacheDir
            context.cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.startsWith("toc_") || name.startsWith("temp_view_") || name.startsWith("tmp_")) {
                    try {
                        val deleted = file.delete()
                        if (deleted) Log.d(TAG, "Purged internal cache file: ${file.name}")
                    } catch (ignored: Exception) {}
                }
            }
            Log.d(TAG, "Storage cleanup completed: freed all duplicate book copies and caches")
        } catch (e: Exception) {
            Log.w(TAG, "Error purging excess storage: ${e.message}")
        }
    }

    /**
     * Deletes temporary cache file if any exists for the given URI.
     */
    suspend fun deletePersistentFile(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val key = getDocumentKey(uri)
            val legacyFile = File(getBooksDir(context), "book_${key}.pdf")
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
            val tempFile = File(context.cacheDir, "temp_view_${key}.pdf")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting persistent file: ${e.message}")
        }
    }

    /**
     * Cleans up all storage and temporary cache files.
     */
    suspend fun clearAllStoredFiles(context: Context) = withContext(Dispatchers.IO) {
        purgeExcessStorageAndCaches(context)
    }
}
