package com.example.data.library

import java.security.MessageDigest

enum class LibraryRootStatus {
    OK,
    NEEDS_ACCESS
}

data class LibraryRoot(
    val id: String,
    val name: String,
    val absPath: String?,
    val treeUri: String?,
    val addedAt: Long,
    val lastScanAt: Long,
    val pdfCount: Int,
    val status: LibraryRootStatus
) {
    companion object {
        fun computeId(absPath: String?, treeUri: String?): String {
            val seed = absPath ?: treeUri ?: ""
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val bytes = md.digest(seed.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }.take(12)
            } catch (_: Exception) {
                seed.hashCode().toUInt().toString(16).padStart(12, '0').take(12)
            }
        }
    }
}

data class LibraryPdf(
    val name: String,
    val relDir: String,
    val path: String?,
    val uri: String,
    val size: Long,
    val modified: Long
)

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val rootName: String, val found: Int) : ScanState
}

data class FolderEntry(
    val name: String,
    val relDir: String,
    val pdfCountRecursive: Int
)

data class DirNode(
    val folders: List<FolderEntry>,
    val files: List<LibraryPdf>
)

data class LibraryTree(
    val nodes: Map<String, DirNode> = emptyMap()
) {
    fun getNode(rootId: String, relDir: String = ""): DirNode? {
        return nodes["$rootId|$relDir"]
    }

    companion object {
        val EMPTY = LibraryTree()
    }
}
