package com.example.data.pdf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Intelligent file path resolver for Android content:// and file:// URIs.
 * Resolves the underlying absolute filesystem path (e.g. /storage/emulated/0/Download/file.pdf)
 * so that recent documents persist and open reliably across app restarts, even if
 * third-party file pickers (like MiXplorer) revoke temporary content permissions on exit.
 */
object FilePathResolver {
    private const val TAG = "FilePathResolver"

    fun resolvePath(context: Context, uri: Uri): String? {
        try {
            // Strategy 1: Direct file:// URI scheme
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        Log.d(TAG, "Resolved path directly from file:// URI: ${file.absolutePath}")
                        return file.absolutePath
                    }
                }
            }

            // Strategy 2: ContentResolver DocumentsContract
            if (uri.scheme.equals("content", ignoreCase = true)) {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val authority = uri.authority ?: ""

                    // ExternalStorageProvider (primary:Download/...)
                    if (authority.equals("com.android.externalstorage.documents", ignoreCase = true)) {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split.getOrNull(0) ?: ""
                        val relativePath = split.getOrNull(1) ?: ""

                        val candidatePath = if (type.equals("primary", ignoreCase = true)) {
                            File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
                        } else if (docId.startsWith("raw:", ignoreCase = true)) {
                            docId.substring(4)
                        } else {
                            "/storage/$type/$relativePath"
                        }

                        Log.d(TAG, "Resolved path via ExternalStorageProvider: $candidatePath")
                        return candidatePath
                    }

                    // DownloadsProvider
                    if (authority.equals("com.android.providers.downloads.documents", ignoreCase = true)) {
                        val id = DocumentsContract.getDocumentId(uri)
                        if (id.startsWith("raw:", ignoreCase = true)) {
                            val path = id.substring(4)
                            Log.d(TAG, "Resolved path via DownloadsProvider raw: $path")
                            return path
                        }
                        try {
                            val contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                id.toLong()
                            )
                            val dataPath = getDataColumn(context, contentUri, null, null)
                            if (!dataPath.isNullOrBlank()) {
                                return dataPath
                            }
                        } catch (ignored: Exception) {}
                    }

                    // MediaProvider
                    if (authority.equals("com.android.providers.media.documents", ignoreCase = true)) {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val id = split.getOrNull(1)
                        if (id != null) {
                            val contentUri = MediaStore.Files.getContentUri("external")
                            val selection = "_id=?"
                            val selectionArgs = arrayOf(id)
                            val dataPath = getDataColumn(context, contentUri, selection, selectionArgs)
                            if (!dataPath.isNullOrBlank()) {
                                return dataPath
                            }
                        }
                    }
                }

                // Strategy 3: Query ContentResolver _data column directly
                val directData = getDataColumn(context, uri, null, null)
                if (!directData.isNullOrBlank()) {
                    Log.d(TAG, "Resolved path from MediaStore _data column: $directData")
                    return directData
                }

                // Strategy 4: MiXplorer & custom FileProvider path pattern detection
                val decodedUriStr = Uri.decode(uri.toString())
                val decodedPath = Uri.decode(uri.path ?: "")

                val candidates = mutableListOf<String>()

                // Check for embedded /storage/emulated/0/... in URI
                val storageIdx = decodedUriStr.indexOf("/storage/emulated/")
                if (storageIdx != -1) {
                    candidates.add(decodedUriStr.substring(storageIdx))
                }

                // MiXplorer ext_storage pattern: /ext_storage/... -> /storage/emulated/0/...
                val extStorageIdx = decodedPath.indexOf("/ext_storage/")
                if (extStorageIdx != -1) {
                    val sub = decodedPath.substring(extStorageIdx + "/ext_storage/".length)
                    candidates.add(File(Environment.getExternalStorageDirectory(), sub).absolutePath)
                    candidates.add("/storage/emulated/0/$sub")
                }

                // MiXplorer root pattern: /root/... -> /...
                if (decodedPath.startsWith("/root/")) {
                    candidates.add(decodedPath.substring(5))
                }

                // Direct sub-path in Downloads or Documents
                val lastPath = uri.lastPathSegment ?: ""
                if (lastPath.isNotBlank() && !lastPath.contains("/")) {
                    val inDownloads = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), lastPath)
                    candidates.add(inDownloads.absolutePath)
                    val inDocuments = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), lastPath)
                    candidates.add(inDocuments.absolutePath)
                }

                for (cand in candidates) {
                    val clean = cand.trim().split("?").first()
                    if (clean.startsWith("/storage/") || clean.startsWith("/sdcard/") || File(clean).exists()) {
                        Log.d(TAG, "Resolved path via provider pattern match: $clean")
                        return clean
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving path for $uri: ${e.message}")
        }

        return null
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = "_data"
        val projection = arrayOf(column)
        return try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
