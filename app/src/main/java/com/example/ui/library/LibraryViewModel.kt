package com.example.ui.library

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.library.DirNode
import com.example.data.library.FolderEntry
import com.example.data.library.LibraryPdf
import com.example.data.library.LibraryRepository
import com.example.data.library.LibraryRoot
import com.example.data.library.LibraryThumbnailStore
import com.example.data.library.LibraryTree
import com.example.data.library.ScanState
import com.example.data.pdf.PdfDirectoryStorage
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.pdf.PdfOutlineParser
import com.example.data.pdf.ReadingMode
import com.example.data.repository.PdfRepository
import com.example.data.settings.AppSettings
import com.example.data.settings.BrowserSort
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface BrowserItem {
    val key: String
    val contentType: String

    data class Folder(val entry: FolderEntry) : BrowserItem {
        override val key: String get() = "d:${entry.relDir}"
        override val contentType: String get() = "folder"
    }

    data class Pdf(val pdf: LibraryPdf) : BrowserItem {
        override val key: String get() = "f:${pdf.path ?: pdf.uri}"
        override val contentType: String get() = "pdf"
    }
}

data class BrowserListing(
    val root: LibraryRoot? = null,
    val relDir: String = "",
    val items: List<BrowserItem> = emptyList(),
    val allRootFiles: List<LibraryPdf> = emptyList()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val libraryRepository = LibraryRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val pdfRepository: PdfRepository = run {
        val db = AppDatabase.getDatabase(application)
        PdfRepository(application, db.pdfRecentDao(), db.pdfBookmarkDao())
    }

    val roots: StateFlow<List<LibraryRoot>> = libraryRepository.roots
    val scanState: StateFlow<ScanState> = libraryRepository.scanState
    val tree: StateFlow<LibraryTree> = libraryRepository.tree
    val appSettings: StateFlow<AppSettings> = settingsRepository.settings

    private val locationFlow = MutableStateFlow<String?>(null)

    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    val listing: StateFlow<BrowserListing> = combine(
        libraryRepository.tree,
        settingsRepository.settings,
        locationFlow
    ) { currentTree, settings, location ->
        buildListing(currentTree, libraryRepository.roots.value, settings, location)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BrowserListing()
        )

    init {
        viewModelScope.launch {
            delay(2_000L)
            if (settingsRepository.settings.value.libraryAutoRescan) {
                libraryRepository.rescanAllIfStale(6L * 60L * 60L * 1000L)
            }
        }
    }

    fun setLocation(location: String?) {
        if (locationFlow.value != location) {
            _selectedKeys.value = emptySet()
            locationFlow.value = location
        }
    }

    fun toggleSelection(pdf: LibraryPdf) {
        val key = selectionKey(pdf)
        val next = HashSet(_selectedKeys.value)
        if (!next.add(key)) {
            next.remove(key)
        }
        _selectedKeys.value = next.toSet()
    }

    fun selectAll(pdfs: List<LibraryPdf>) {
        if (pdfs.isEmpty()) return
        val next = HashSet(_selectedKeys.value)
        for (pdf in pdfs) {
            next.add(selectionKey(pdf))
        }
        _selectedKeys.value = next.toSet()
    }

    fun clearSelection() {
        _selectedKeys.value = emptySet()
    }

    fun addRoot(treeUri: Uri) {
        viewModelScope.launch {
            libraryRepository.addRoot(treeUri)
        }
    }

    fun removeRoot(id: String) {
        viewModelScope.launch {
            libraryRepository.removeRoot(id)
        }
    }

    fun rescan(id: String? = null) {
        viewModelScope.launch {
            libraryRepository.rescan(id)
        }
    }

    fun setBrowserSort(sort: BrowserSort) {
        settingsRepository.updateBrowserSort(sort)
    }

    fun setBrowserFoldersFirst(foldersFirst: Boolean) {
        settingsRepository.updateBrowserFoldersFirst(foldersFirst)
    }

    fun setLibraryAutoRescan(enabled: Boolean) {
        settingsRepository.updateLibraryAutoRescan(enabled)
    }

    fun addToRecents(pdfs: List<LibraryPdf>, onDone: (Int) -> Unit = {}) {
        if (pdfs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (pdf in pdfs) {
                val targetUri = resolveOpenUri(pdf)
                val resolvedUri = pdfRepository.resolveIdentityUri(targetUri)
                val canonicalPath = pdf.path?.takeIf { it.startsWith("/") } ?: resolvedUri.toString()
                val existing = pdfRepository.getRecentByUri(resolvedUri.toString())
                pdfRepository.addRecent(
                    pdfPath = canonicalPath,
                    uriString = resolvedUri.toString(),
                    fileName = pdf.name,
                    fileSize = pdf.size,
                    pageCount = existing?.pageCount ?: 0,
                    lastReadPage = existing?.lastReadPage ?: 0,
                    scrollOffset = existing?.scrollOffsetFraction ?: 0f,
                    readingMode = existing?.let {
                        try { ReadingMode.valueOf(it.readingMode) } catch (_: Exception) { ReadingMode.VERTICAL }
                    } ?: ReadingMode.VERTICAL,
                    thumbnailPath = existing?.thumbnailPath
                )
                count++
            }
            withContext(Dispatchers.Main) {
                onDone(count)
            }
        }
    }

    fun toggleFavorite(pdf: LibraryPdf, matchedRecentUri: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetUri = matchedRecentUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                ?: pdfRepository.resolveIdentityUri(resolveOpenUri(pdf))
            val docKey = PdfDocumentStorage.getDocumentKey(targetUri)
            settingsRepository.toggleFavoriteKey(docKey)
        }
    }

    fun favoriteSelected(
        pdfs: List<LibraryPdf>,
        matchedRecentUrisByKey: Map<String, String> = emptyMap(),
        onDone: (Int) -> Unit = {}
    ) {
        if (pdfs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (pdf in pdfs) {
                val matchedUri = matchedRecentUrisByKey[selectionKey(pdf)]
                val targetUri = matchedUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    ?: pdfRepository.resolveIdentityUri(resolveOpenUri(pdf))
                val docKey = PdfDocumentStorage.getDocumentKey(targetUri)
                settingsRepository.addFavoriteKey(docKey)
            }
            withContext(Dispatchers.Main) {
                onDone(pdfs.size)
            }
        }
    }

    fun deletePdfs(
        pdfs: List<LibraryPdf>,
        onComplete: (deletedCount: Int, failedCount: Int) -> Unit
    ) {
        if (pdfs.isEmpty()) return
        val appContext = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val deletedPdfs = ArrayList<LibraryPdf>()
            var failedCount = 0

            for (pdf in pdfs) {
                val deleted = deletePhysicalFile(appContext, pdf)
                if (deleted) {
                    deletedPdfs.add(pdf)
                } else {
                    failedCount++
                }
            }

            if (deletedPdfs.isNotEmpty()) {
                libraryRepository.removeEntries(deletedPdfs)
                val allDirEntries = PdfDirectoryStorage.loadAllDirectoriesSync(appContext)
                val thumbsDir = File(appContext.filesDir, "thumbs")

                for (pdf in deletedPdfs) {
                    val targetUri = resolveOpenUri(pdf)
                    val resolvedUri = pdfRepository.resolveIdentityUri(targetUri)

                    val relatedUris = LinkedHashSet<Uri>().apply {
                        add(targetUri)
                        add(resolvedUri)
                        if (pdf.uri.isNotBlank()) add(Uri.parse(pdf.uri))
                        for (entry in allDirEntries) {
                            val pathMatch = !pdf.path.isNullOrBlank() &&
                                entry.path?.equals(pdf.path, ignoreCase = true) == true
                            val uriMatch = entry.uri.equals(pdf.uri, ignoreCase = true) ||
                                entry.uri.equals(resolvedUri.toString(), ignoreCase = true)
                            if (pathMatch || uriMatch) {
                                if (entry.uri.isNotBlank()) add(Uri.parse(entry.uri))
                                entry.thumb?.let { thumbPath ->
                                    try { File(thumbPath).delete() } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    pdf.path?.let { p ->
                        pdfRepository.deleteRecent(p)
                    }
                    for (u in relatedUris) {
                        val uStr = u.toString()
                        val key = PdfDocumentStorage.getDocumentKey(u)
                        pdfRepository.deleteRecent(uStr)
                        pdfRepository.deleteAllBookmarksForUri(uStr)
                        PdfOutlineParser.deleteOutlineCache(appContext, u)
                        LibraryThumbnailStore.deleteThumbnail(appContext, key)
                        try {
                            val thumbPng = File(thumbsDir, "thumb_${key}.png")
                            if (thumbPng.exists()) thumbPng.delete()
                        } catch (_: Exception) {}
                        settingsRepository.removeFavoriteKey(key)
                    }
                }

                val removedKeys = deletedPdfs.mapTo(HashSet()) { selectionKey(it) }
                _selectedKeys.value = _selectedKeys.value.filterNot { it in removedKeys }.toSet()
            }

            withContext(Dispatchers.Main) {
                onComplete(deletedPdfs.size, failedCount)
            }
        }
    }

    private fun deletePhysicalFile(context: Application, pdf: LibraryPdf): Boolean {
        val absPath = pdf.path?.takeIf { it.isNotBlank() }
        if (absPath != null) {
            try {
                val file = File(absPath)
                if (file.exists()) {
                    if (file.delete()) return true
                } else if (!pdf.uri.startsWith("content://")) {
                    return false
                }
            } catch (_: Throwable) {}
        }
        if (pdf.uri.startsWith("content://")) {
            try {
                return DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(pdf.uri))
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun resolveOpenUri(pdf: LibraryPdf): Uri {
        val pathFile = pdf.path?.let { File(it) }
        return if (pathFile != null && pathFile.exists() && pathFile.canRead()) {
            Uri.fromFile(pathFile)
        } else {
            Uri.parse(pdf.uri)
        }
    }

    private fun buildListing(
        currentTree: LibraryTree,
        currentRoots: List<LibraryRoot>,
        settings: AppSettings,
        location: String?
    ): BrowserListing {
        if (location.isNullOrBlank()) return BrowserListing()
        val pipeIdx = location.indexOf('|')
        val rootId = if (pipeIdx >= 0) location.substring(0, pipeIdx) else location
        val relDir = if (pipeIdx >= 0) location.substring(pipeIdx + 1).trim('/') else ""
        val root = currentRoots.find { it.id == rootId }
        val node = currentTree.getNode(rootId, relDir) ?: DirNode(emptyList(), emptyList())

        val sort = settings.browserSort
        val foldersFirst = settings.browserFoldersFirst

        val sortedFolders = sortFolders(node.folders, sort)
        val sortedFiles = sortFiles(node.files, sort)

        val combinedItems: List<BrowserItem> = if (foldersFirst) {
            ArrayList<BrowserItem>(sortedFolders.size + sortedFiles.size).apply {
                for (f in sortedFolders) add(BrowserItem.Folder(f))
                for (p in sortedFiles) add(BrowserItem.Pdf(p))
            }
        } else {
            val mixed = ArrayList<BrowserItem>(node.folders.size + node.files.size).apply {
                for (f in node.folders) add(BrowserItem.Folder(f))
                for (p in node.files) add(BrowserItem.Pdf(p))
            }
            mixed.sortedWith(mixedComparator(sort))
        }

        val rootPrefix = "$rootId|"
        val allRootPdfs = ArrayList<LibraryPdf>()
        for ((key, dirNode) in currentTree.nodes) {
            if (key.startsWith(rootPrefix)) {
                allRootPdfs.addAll(dirNode.files)
            }
        }
        val sortedAllRootPdfs = sortFiles(allRootPdfs, sort)

        return BrowserListing(
            root = root,
            relDir = relDir,
            items = combinedItems,
            allRootFiles = sortedAllRootPdfs
        )
    }

    private fun sortFolders(folders: List<FolderEntry>, sort: BrowserSort): List<FolderEntry> {
        if (folders.size <= 1) return folders
        return when (sort) {
            BrowserSort.NAME_DESC -> folders.sortedWith { a, b -> naturalCompare(b.name, a.name) }
            else -> folders.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }
    }

    private fun sortFiles(files: List<LibraryPdf>, sort: BrowserSort): List<LibraryPdf> {
        if (files.size <= 1) return files
        return when (sort) {
            BrowserSort.NAME_ASC -> files.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            BrowserSort.NAME_DESC -> files.sortedWith { a, b -> naturalCompare(b.name, a.name) }
            BrowserSort.DATE_NEW -> files.sortedWith(
                compareByDescending<LibraryPdf> { it.modified }
                    .thenComparator { a, b -> naturalCompare(a.name, b.name) }
            )
            BrowserSort.DATE_OLD -> files.sortedWith(
                compareBy<LibraryPdf> { it.modified }
                    .thenComparator { a, b -> naturalCompare(a.name, b.name) }
            )
            BrowserSort.SIZE_LARGE -> files.sortedWith(
                compareByDescending<LibraryPdf> { it.size }
                    .thenComparator { a, b -> naturalCompare(a.name, b.name) }
            )
            BrowserSort.SIZE_SMALL -> files.sortedWith(
                compareBy<LibraryPdf> { it.size }
                    .thenComparator { a, b -> naturalCompare(a.name, b.name) }
            )
        }
    }

    private fun mixedComparator(sort: BrowserSort): Comparator<BrowserItem> {
        fun itemName(item: BrowserItem): String = when (item) {
            is BrowserItem.Folder -> item.entry.name
            is BrowserItem.Pdf -> item.pdf.name
        }
        return when (sort) {
            BrowserSort.NAME_ASC -> Comparator { a, b -> naturalCompare(itemName(a), itemName(b)) }
            BrowserSort.NAME_DESC -> Comparator { a, b -> naturalCompare(itemName(b), itemName(a)) }
            BrowserSort.DATE_NEW -> Comparator { a, b ->
                val da = (a as? BrowserItem.Pdf)?.pdf?.modified ?: Long.MIN_VALUE
                val db = (b as? BrowserItem.Pdf)?.pdf?.modified ?: Long.MIN_VALUE
                val cmp = db.compareTo(da)
                if (cmp != 0) cmp else naturalCompare(itemName(a), itemName(b))
            }
            BrowserSort.DATE_OLD -> Comparator { a, b ->
                val da = (a as? BrowserItem.Pdf)?.pdf?.modified ?: Long.MAX_VALUE
                val db = (b as? BrowserItem.Pdf)?.pdf?.modified ?: Long.MAX_VALUE
                val cmp = da.compareTo(db)
                if (cmp != 0) cmp else naturalCompare(itemName(a), itemName(b))
            }
            BrowserSort.SIZE_LARGE -> Comparator { a, b ->
                val sa = (a as? BrowserItem.Pdf)?.pdf?.size ?: Long.MIN_VALUE
                val sb = (b as? BrowserItem.Pdf)?.pdf?.size ?: Long.MIN_VALUE
                val cmp = sb.compareTo(sa)
                if (cmp != 0) cmp else naturalCompare(itemName(a), itemName(b))
            }
            BrowserSort.SIZE_SMALL -> Comparator { a, b ->
                val sa = (a as? BrowserItem.Pdf)?.pdf?.size ?: Long.MAX_VALUE
                val sb = (b as? BrowserItem.Pdf)?.pdf?.size ?: Long.MAX_VALUE
                val cmp = sa.compareTo(sb)
                if (cmp != 0) cmp else naturalCompare(itemName(a), itemName(b))
            }
        }
    }

    companion object {
        fun selectionKey(pdf: LibraryPdf): String = pdf.path ?: pdf.uri

        fun naturalCompare(a: String, b: String): Int {
            var ia = 0
            var ib = 0
            val lenA = a.length
            val lenB = b.length

            while (ia < lenA && ib < lenB) {
                val ca = a[ia]
                val cb = b[ib]

                if (ca.isDigit() && cb.isDigit()) {
                    var za = ia
                    while (za < lenA && a[za] == '0') za++
                    var zb = ib
                    while (zb < lenB && b[zb] == '0') zb++

                    var endA = za
                    while (endA < lenA && a[endA].isDigit()) endA++
                    var endB = zb
                    while (endB < lenB && b[endB].isDigit()) endB++

                    val sigLenA = endA - za
                    val sigLenB = endB - zb
                    if (sigLenA != sigLenB) {
                        return sigLenA.compareTo(sigLenB)
                    }

                    for (k in 0 until sigLenA) {
                        val cmp = a[za + k].compareTo(b[zb + k])
                        if (cmp != 0) return cmp
                    }

                    val totalDigitsA = endA - ia
                    val totalDigitsB = endB - ib
                    if (totalDigitsA != totalDigitsB) {
                        return totalDigitsA.compareTo(totalDigitsB)
                    }

                    ia = endA
                    ib = endB
                } else {
                    val la = ca.lowercaseChar()
                    val lb = cb.lowercaseChar()
                    if (la != lb) {
                        return la.compareTo(lb)
                    }
                    ia++
                    ib++
                }
            }
            return lenA.compareTo(lenB)
        }
    }
}
