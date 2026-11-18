package com.example.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.data.pdf.FilePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

sealed interface ScanResult {
    data class Success(val pdfs: List<LibraryPdf>) : ScanResult
    data object NeedsAccess : ScanResult
}

object LibraryScanner {
    private const val TAG = "LibraryScanner"
    private const val MAX_DEPTH = 25
    private const val PROGRESS_STEP = 25

    private data class FileFrame(
        val dir: File,
        val relDir: String,
        val depth: Int
    )

    private data class SafFrame(
        val docId: String,
        val relDir: String,
        val depth: Int
    )

    suspend fun scan(
        context: Context,
        root: LibraryRoot,
        onProgress: (found: Int) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // 1. FILE mode
        val rootDir = root.absPath?.let { File(it) }
        val rootChildren = if (rootDir != null && rootDir.exists() && rootDir.isDirectory) {
            rootDir.listFiles()
        } else {
            null
        }
        if (rootDir != null && rootChildren != null) {
            return@withContext scanWithFile(rootDir, rootChildren, onProgress)
        }

        // 2. SAF mode
        val treeUriStr = root.treeUri
        if (!treeUriStr.isNullOrBlank()) {
            val treeUri = Uri.parse(treeUriStr)
            val hasPersistedPermission = appContext.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && (it.uri == treeUri || it.uri.toString() == treeUriStr)
            }
            if (hasPersistedPermission) {
                return@withContext scanWithSaf(appContext, treeUri, onProgress)
            }
        }

        // 3. Neither works
        ScanResult.NeedsAccess
    }

    private suspend fun scanWithFile(
        rootDir: File,
        initialChildren: Array<File>,
        onProgress: (Int) -> Unit
    ): ScanResult {
        val results = ArrayList<LibraryPdf>(256)
        val stack = ArrayDeque<FileFrame>()
        stack.addLast(FileFrame(rootDir, "", 0))
        var isFirst = true

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val frame = stack.removeLast()
            val children = if (isFirst) {
                isFirst = false
                initialChildren
            } else {
                frame.dir.listFiles()
            } ?: continue

            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (shouldSkipDir(name, frame.depth)) continue
                    if (frame.depth < MAX_DEPTH) {
                        val nextRel = if (frame.relDir.isEmpty()) name else "${frame.relDir}/$name"
                        stack.addLast(FileFrame(child, nextRel, frame.depth + 1))
                    }
                } else if (child.isFile) {
                    if (isPdfName(name)) {
                        results.add(
                            LibraryPdf(
                                name = name,
                                relDir = frame.relDir,
                                path = child.absolutePath,
                                uri = Uri.fromFile(child).toString(),
                                size = child.length(),
                                modified = child.lastModified()
                            )
                        )
                        if (results.size % PROGRESS_STEP == 0) {
                            onProgress(results.size)
                        }
                    }
                }
            }
        }
        onProgress(results.size)
        return ScanResult.Success(results)
    }

    private suspend fun scanWithSaf(
        context: Context,
        treeUri: Uri,
        onProgress: (Int) -> Unit
    ): ScanResult {
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get tree document ID for $treeUri: ${e.message}")
            return ScanResult.NeedsAccess
        }

        val isExternalStorage = treeUri.authority.equals("com.android.externalstorage.documents", ignoreCase = true)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val results = ArrayList<LibraryPdf>(256)
        val stack = ArrayDeque<SafFrame>()
        stack.addLast(SafFrame(rootDocId, "", 0))

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val frame = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, frame.docId)
            val querySucceeded = try {
                val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                if (cursor == null) {
                    false
                } else {
                    cursor.use { c ->
                        val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                        while (c.moveToNext()) {
                            val childDocId = if (idIdx != -1) c.getString(idIdx) else null
                            val displayName = if (nameIdx != -1) c.getString(nameIdx) else null
                            if (childDocId.isNullOrBlank() || displayName.isNullOrBlank()) continue
                            val mimeType = if (mimeIdx != -1) c.getString(mimeIdx) else null

                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (shouldSkipDir(displayName, frame.depth)) continue
                                if (frame.depth < MAX_DEPTH) {
                                    val nextRel = if (frame.relDir.isEmpty()) displayName else "${frame.relDir}/$displayName"
                                    stack.addLast(SafFrame(childDocId, nextRel, frame.depth + 1))
                                }
                            } else if (isPdfEntry(displayName, mimeType)) {
                                val size = if (sizeIdx != -1 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                                val modified = if (modIdx != -1 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId).toString()
                                val derivedPath = if (isExternalStorage) {
                                    FilePathResolver.resolveExternalDocIdToPath(childDocId)
                                } else {
                                    null
                                }
                                results.add(
                                    LibraryPdf(
                                        name = displayName,
                                        relDir = frame.relDir,
                                        path = derivedPath,
                                        uri = docUri,
                                        size = size,
                                        modified = modified
                                    )
                                )
                                if (results.size % PROGRESS_STEP == 0) {
                                    onProgress(results.size)
                                }
                            }
                        }
                    }
                    true
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SAF security exception on ${frame.docId}: ${e.message}")
                if (frame.depth == 0) return ScanResult.NeedsAccess
                false
            } catch (e: Exception) {
                Log.w(TAG, "SAF query error on ${frame.docId}: ${e.message}")
                if (frame.depth == 0) return ScanResult.NeedsAccess
                false
            }

            if (!querySucceeded && frame.depth == 0) {
                return ScanResult.NeedsAccess
            }
        }

        onProgress(results.size)
        return ScanResult.Success(results)
    }

    private fun shouldSkipDir(name: String, depth: Int): Boolean {
        if (name.startsWith(".")) return true
        if (depth == 0 && name == "Android") return true
        return false
    }

    private fun isPdfName(name: String): Boolean {
        return name.lowercase(Locale.ROOT).endsWith(".pdf")
    }

    private fun isPdfEntry(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.equals("application/pdf", ignoreCase = true)) return true
        return isPdfName(name)
    }
}
