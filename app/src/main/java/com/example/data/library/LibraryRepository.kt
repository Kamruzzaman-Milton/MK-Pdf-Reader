package com.example.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.data.pdf.FilePathResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

class LibraryRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val scanMutex = Mutex()
    private val syncLoadLock = Any()

    private val _roots = MutableStateFlow<List<LibraryRoot>>(emptyList())
    val roots: StateFlow<List<LibraryRoot>> = _roots.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _tree = MutableStateFlow(LibraryTree.EMPTY)
    val tree: StateFlow<LibraryTree> = _tree.asStateFlow()

    private val indicesByRoot = HashMap<String, List<LibraryPdf>>()

    @Volatile
    private var nameSizeMap: HashMap<NameSizeKey, LibraryPdf> = HashMap()

    @Volatile
    private var isLoaded: Boolean = false

    private data class NameSizeKey(
        val nameLower: String,
        val size: Long
    )

    companion object {
        private const val TAG = "LibraryRepository"

        @Volatile
        private var INSTANCE: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    init {
        scope.launch {
            ensureLoaded()
        }
    }

    private suspend fun ensureLoaded() {
        if (isLoaded) return
        loadMutex.withLock {
            if (isLoaded) return
            val loadedRoots = withContext(Dispatchers.IO) {
                LibraryStorage.loadRoots(appContext)
            }
            val loadedIndices = withContext(Dispatchers.IO) {
                val map = HashMap<String, List<LibraryPdf>>(loadedRoots.size)
                for (root in loadedRoots) {
                    map[root.id] = LibraryStorage.loadIndex(appContext, root.id)
                }
                map
            }
            val (builtTree, builtMap) = buildTreeAndLookup(loadedIndices)
            synchronized(syncLoadLock) {
                if (!isLoaded) {
                    indicesByRoot.clear()
                    indicesByRoot.putAll(loadedIndices)
                    _roots.value = loadedRoots
                    nameSizeMap = builtMap
                    _tree.value = builtTree
                    isLoaded = true
                }
            }
        }
    }

    private fun ensureLoadedSync() {
        if (isLoaded) return
        synchronized(syncLoadLock) {
            if (isLoaded) return
            val loadedRoots = LibraryStorage.loadRoots(appContext)
            val loadedIndices = HashMap<String, List<LibraryPdf>>(loadedRoots.size)
            for (root in loadedRoots) {
                loadedIndices[root.id] = LibraryStorage.loadIndex(appContext, root.id)
            }
            val (builtTree, builtMap) = buildTreeAndLookupSync(loadedIndices)
            indicesByRoot.clear()
            indicesByRoot.putAll(loadedIndices)
            _roots.value = loadedRoots
            nameSizeMap = builtMap
            _tree.value = builtTree
            isLoaded = true
        }
    }

    suspend fun addRoot(treeUri: Uri): LibraryRoot? {
        ensureLoaded()
        takeTreePersistablePermission(treeUri)

        val treeUriStr = treeUri.toString()
        val absPath = FilePathResolver.resolveTreeUriToPath(treeUri)?.let { normalizePath(it) }
        val displayName = resolveRootName(treeUri, absPath)
        val rootId = LibraryRoot.computeId(absPath, treeUriStr)
        val now = System.currentTimeMillis()

        val targetToScan: LibraryRoot = scanMutex.withLock {
            val currentRoots = _roots.value.toMutableList()
            val toRemove = ArrayList<LibraryRoot>()

            for (existing in currentRoots) {
                val existingPath = existing.absPath?.let { normalizePath(it) }
                if (absPath != null && existingPath != null) {
                    if (absPath == existingPath) {
                        if (existing.status == LibraryRootStatus.NEEDS_ACCESS || existing.treeUri != treeUriStr) {
                            val updated = existing.copy(treeUri = treeUriStr, name = displayName)
                            val idx = currentRoots.indexOf(existing)
                            currentRoots[idx] = updated
                            LibraryStorage.saveRoots(appContext, currentRoots)
                            _roots.value = currentRoots
                            return@withLock updated
                        }
                        return null
                    }
                    if (isInside(child = absPath, parent = existingPath)) {
                        // Reject a folder inside an existing root
                        return null
                    }
                    if (isInside(child = existingPath, parent = absPath)) {
                        // New root contains existing root -> replace it
                        toRemove.add(existing)
                    }
                } else if (existing.treeUri == treeUriStr) {
                    if (existing.status == LibraryRootStatus.NEEDS_ACCESS) {
                        return@withLock existing
                    }
                    return null
                }
            }

            if (toRemove.isNotEmpty()) {
                for (old in toRemove) {
                    currentRoots.remove(old)
                    indicesByRoot.remove(old.id)
                    withContext(Dispatchers.IO) {
                        LibraryStorage.deleteIndex(appContext, old.id)
                    }
                }
            }

            val newRoot = LibraryRoot(
                id = rootId,
                name = displayName,
                absPath = absPath,
                treeUri = treeUriStr,
                addedAt = now,
                lastScanAt = 0L,
                pdfCount = 0,
                status = LibraryRootStatus.OK
            )
            currentRoots.add(newRoot)
            withContext(Dispatchers.IO) {
                LibraryStorage.saveRoots(appContext, currentRoots)
            }
            _roots.value = currentRoots
            if (toRemove.isNotEmpty()) {
                val (builtTree, builtMap) = buildTreeAndLookup(indicesByRoot)
                nameSizeMap = builtMap
                _tree.value = builtTree
            }
            newRoot
        }

        scanMutex.withLock {
            try {
                scanSingleRootLocked(targetToScan)
            } finally {
                _scanState.value = ScanState.Idle
            }
        }
        return _roots.value.find { it.id == targetToScan.id } ?: targetToScan
    }

    suspend fun removeRoot(id: String) {
        ensureLoaded()
        scanMutex.withLock {
            val currentRoots = _roots.value.toMutableList()
            val removed = currentRoots.removeAll { it.id == id }
            if (!removed) return
            indicesByRoot.remove(id)
            withContext(Dispatchers.IO) {
                LibraryStorage.saveRoots(appContext, currentRoots)
                LibraryStorage.deleteIndex(appContext, id)
            }
            _roots.value = currentRoots
            val (builtTree, builtMap) = buildTreeAndLookup(indicesByRoot)
            nameSizeMap = builtMap
            _tree.value = builtTree
        }
    }

    suspend fun rescan(id: String? = null) {
        ensureLoaded()
        scanMutex.withLock {
            try {
                val targets = if (id != null) {
                    _roots.value.filter { it.id == id }
                } else {
                    _roots.value.toList()
                }
                for (root in targets) {
                    coroutineContext.ensureActive()
                    scanSingleRootLocked(root)
                }
            } finally {
                _scanState.value = ScanState.Idle
            }
        }
    }

    suspend fun rescanAllIfStale(maxAgeMs: Long) {
        ensureLoaded()
        scanMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val staleRoots = _roots.value.filter { root ->
                    root.status == LibraryRootStatus.NEEDS_ACCESS ||
                        root.lastScanAt <= 0L ||
                        (now - root.lastScanAt) >= maxAgeMs
                }
                for (root in staleRoots) {
                    coroutineContext.ensureActive()
                    scanSingleRootLocked(root)
                }
            } finally {
                _scanState.value = ScanState.Idle
            }
        }
    }

    fun findByNameAndSize(name: String, size: Long): LibraryPdf? {
        ensureLoadedSync()
        if (name.isBlank()) return null
        return nameSizeMap[NameSizeKey(name.lowercase(Locale.ROOT), size)]
    }

    private suspend fun scanSingleRootLocked(root: LibraryRoot) {
        _scanState.value = ScanState.Scanning(root.name, 0)
        try {
            val result = LibraryScanner.scan(appContext, root) { found ->
                _scanState.value = ScanState.Scanning(root.name, found)
            }
            when (result) {
                is ScanResult.Success -> {
                    val now = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        LibraryStorage.saveIndex(appContext, root.id, result.pdfs)
                    }
                    indicesByRoot[root.id] = result.pdfs
                    val updatedRoot = root.copy(
                        lastScanAt = now,
                        pdfCount = result.pdfs.size,
                        status = LibraryRootStatus.OK
                    )
                    val updatedRoots = _roots.value.map { if (it.id == root.id) updatedRoot else it }
                    withContext(Dispatchers.IO) {
                        LibraryStorage.saveRoots(appContext, updatedRoots)
                    }
                    _roots.value = updatedRoots

                    val (builtTree, builtMap) = buildTreeAndLookup(indicesByRoot)
                    nameSizeMap = builtMap
                    _tree.value = builtTree
                }
                ScanResult.NeedsAccess -> {
                    val updatedRoot = root.copy(status = LibraryRootStatus.NEEDS_ACCESS)
                    val updatedRoots = _roots.value.map { if (it.id == root.id) updatedRoot else it }
                    withContext(Dispatchers.IO) {
                        LibraryStorage.saveRoots(appContext, updatedRoots)
                    }
                    _roots.value = updatedRoots
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Scan failed for root ${root.name}, keeping previous index: ${t.message}")
        }
    }

    private suspend fun buildTreeAndLookup(
        indices: Map<String, List<LibraryPdf>>
    ): Pair<LibraryTree, HashMap<NameSizeKey, LibraryPdf>> = withContext(Dispatchers.Default) {
        val snapshot = HashMap<String, List<LibraryPdf>>(indices.size)
        for ((k, v) in indices) {
            snapshot[k] = v
        }
        buildTreeAndLookupSync(snapshot)
    }

    private fun buildTreeAndLookupSync(
        indices: Map<String, List<LibraryPdf>>
    ): Pair<LibraryTree, HashMap<NameSizeKey, LibraryPdf>> {
        val nodes = HashMap<String, DirNode>()
        val lookup = HashMap<NameSizeKey, LibraryPdf>()

        for ((rootId, pdfs) in indices) {
            if (pdfs.isEmpty()) continue

            val filesByDir = HashMap<String, ArrayList<LibraryPdf>>()
            val childDirsByParent = HashMap<String, LinkedHashSet<String>>()
            val recursiveCounts = HashMap<String, Int>()
            val allDirs = LinkedHashSet<String>()

            for (pdf in pdfs) {
                val key = NameSizeKey(pdf.name.lowercase(Locale.ROOT), pdf.size)
                if (!lookup.containsKey(key)) {
                    lookup[key] = pdf
                }

                val rel = pdf.relDir.trim('/')
                filesByDir.getOrPut(rel) { ArrayList() }.add(pdf)

                var curr = rel
                while (true) {
                    allDirs.add(curr)
                    if (curr.isEmpty()) break
                    recursiveCounts[curr] = (recursiveCounts[curr] ?: 0) + 1
                    val slashIdx = curr.lastIndexOf('/')
                    val parent = if (slashIdx >= 0) curr.substring(0, slashIdx) else ""
                    childDirsByParent.getOrPut(parent) { LinkedHashSet() }.add(curr)
                    curr = parent
                }
            }

            for (dir in allDirs) {
                val childRelDirs = childDirsByParent[dir]
                val folders = if (childRelDirs != null) {
                    childRelDirs.map { childRel ->
                        val folderName = childRel.substringAfterLast('/')
                        FolderEntry(
                            name = folderName,
                            relDir = childRel,
                            pdfCountRecursive = recursiveCounts[childRel] ?: 0
                        )
                    }
                } else {
                    emptyList()
                }
                val files = filesByDir[dir] ?: emptyList()
                nodes["$rootId|$dir"] = DirNode(folders = folders, files = files)
            }
        }

        return Pair(LibraryTree(nodes), lookup)
    }

    private fun takeTreePersistablePermission(treeUri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "takeTreePersistablePermission failed for $treeUri: ${e.message}")
            }
        }
    }

    private fun resolveRootName(treeUri: Uri, absPath: String?): String {
        if (absPath == "/storage/emulated/0") return "Internal Storage"
        if (!absPath.isNullOrBlank()) {
            val name = File(absPath).name
            if (name.isNotBlank()) return name
        }
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            appContext.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx != -1) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
            val tail = docId.substringAfterLast(':').substringAfterLast('/').trim()
            if (tail.isNotBlank()) return tail
        } catch (_: Exception) {
        }
        return "PDF Folder"
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    private fun isInside(child: String, parent: String): Boolean {
        if (parent == "/") return child != "/"
        return child.startsWith("$parent/")
    }
}
