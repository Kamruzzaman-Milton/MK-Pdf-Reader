package com.example.data.library

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object LibraryStorage {
    private const val TAG = "LibraryStorage"
    private const val LIBRARY_DIR_NAME = "library"
    private const val ROOTS_FILE_NAME = "roots.json"
    private val lock = Any()

    private fun getLibraryDir(context: Context): File {
        val dir = File(context.filesDir, LIBRARY_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getRootsFile(context: Context): File {
        return File(getLibraryDir(context), ROOTS_FILE_NAME)
    }

    private fun getIndexFile(context: Context, rootId: String): File {
        return File(getLibraryDir(context), "index_${rootId}.json")
    }

    fun loadRoots(context: Context): List<LibraryRoot> {
        synchronized(lock) {
            val file = getRootsFile(context)
            if (!file.exists() || file.length() == 0L) return emptyList()
            return try {
                val bytes = AtomicFile(file).readFully()
                val text = String(bytes, StandardCharsets.UTF_8).trim()
                if (text.isEmpty() || !text.startsWith("[")) return emptyList()
                val arr = JSONArray(text)
                val list = ArrayList<LibraryRoot>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = obj.optString("name")
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
                            name = name.ifBlank { "Folder" },
                            absPath = absPath,
                            treeUri = treeUri,
                            addedAt = obj.optLong("addedAt", 0L),
                            lastScanAt = obj.optLong("lastScanAt", 0L),
                            pdfCount = obj.optInt("pdfCount", 0),
                            status = status
                        )
                    )
                }
                list
            } catch (e: Exception) {
                Log.w(TAG, "loadRoots failed, returning empty: ${e.message}")
                emptyList()
            }
        }
    }

    fun saveRoots(context: Context, roots: List<LibraryRoot>) {
        synchronized(lock) {
            val file = getRootsFile(context)
            val arr = JSONArray()
            for (root in roots) {
                val obj = JSONObject().apply {
                    put("id", root.id)
                    put("name", root.name)
                    put("absPath", root.absPath ?: "")
                    put("treeUri", root.treeUri ?: "")
                    put("addedAt", root.addedAt)
                    put("lastScanAt", root.lastScanAt)
                    put("pdfCount", root.pdfCount)
                    put("status", root.status.name)
                }
                arr.put(obj)
            }
            writeAtomicText(file, arr.toString())
        }
    }

    fun loadIndex(context: Context, rootId: String): List<LibraryPdf> {
        synchronized(lock) {
            val file = getIndexFile(context, rootId)
            if (!file.exists() || file.length() == 0L) return emptyList()
            return try {
                val bytes = AtomicFile(file).readFully()
                val text = String(bytes, StandardCharsets.UTF_8).trim()
                if (text.isEmpty() || !text.startsWith("[")) return emptyList()
                val arr = JSONArray(text)
                val list = ArrayList<LibraryPdf>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("n")
                    val uri = obj.optString("u")
                    if (name.isBlank() || uri.isBlank()) continue
                    list.add(
                        LibraryPdf(
                            name = name,
                            relDir = obj.optString("d", ""),
                            path = obj.optString("p").takeIf { it.isNotBlank() },
                            uri = uri,
                            size = obj.optLong("s", 0L),
                            modified = obj.optLong("m", 0L)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                Log.w(TAG, "loadIndex($rootId) failed, returning empty: ${e.message}")
                emptyList()
            }
        }
    }

    fun saveIndex(context: Context, rootId: String, pdfs: List<LibraryPdf>) {
        synchronized(lock) {
            val file = getIndexFile(context, rootId)
            val arr = JSONArray()
            for (pdf in pdfs) {
                val obj = JSONObject().apply {
                    put("n", pdf.name)
                    put("d", pdf.relDir)
                    put("p", pdf.path ?: "")
                    put("u", pdf.uri)
                    put("s", pdf.size)
                    put("m", pdf.modified)
                }
                arr.put(obj)
            }
            writeAtomicText(file, arr.toString())
        }
    }

    fun deleteIndex(context: Context, rootId: String) {
        synchronized(lock) {
            try {
                val file = getIndexFile(context, rootId)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteIndex($rootId) failed: ${e.message}")
            }
        }
    }

    private fun writeAtomicText(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            fos.write(content.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
            Log.e(TAG, "writeAtomicText(${file.name}) failed: ${e.message}", e)
        }
    }
}
