package com.example.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.PdfRecentItem
import com.example.data.library.FolderEntry
import com.example.data.library.LibraryPdf
import com.example.data.library.LibraryRootStatus
import com.example.data.library.LibraryThumbnailStore
import com.example.data.library.ScanState
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.settings.AppSettings
import com.example.data.settings.BrowserSort
import com.example.data.settings.ReadingStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BreadcrumbSegment(
    val label: String,
    val location: String
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LibraryBrowserScreen(
    listing: BrowserListing,
    scanState: ScanState,
    appSettings: AppSettings,
    recents: List<PdfRecentItem>,
    selectedKeys: Set<String> = emptySet(),
    onNavigateLocation: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenPdf: (Uri) -> Unit,
    onSelectSort: (BrowserSort) -> Unit,
    onToggleFoldersFirst: (Boolean) -> Unit,
    onRescanRoot: (String) -> Unit,
    onOpenLibrarySettings: () -> Unit,
    onToggleSelect: (LibraryPdf) -> Unit = {},
    onSelectAll: (List<LibraryPdf>) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onAddToRecents: (List<LibraryPdf>) -> Unit = {},
    onToggleFavorite: (LibraryPdf, String?) -> Unit = { _, _ -> },
    onFavoriteSelected: (List<LibraryPdf>, Map<String, String>) -> Unit = { _, _ -> },
    onDeletePdfs: (List<LibraryPdf>, (Int, Int) -> Unit) -> Unit = { _, cb -> cb(0, 0) },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val root = listing.root
    val relDir = listing.relDir
    val currentFolderName = remember(root, relDir) {
        if (relDir.isEmpty()) root?.name ?: "Library" else relDir.substringAfterLast('/')
    }

    var isSearchActive by rememberSaveable(root?.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(root?.id) { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var detailsPdf by remember { mutableStateOf<LibraryPdf?>(null) }
    var pendingDeletePdfs by remember { mutableStateOf<List<LibraryPdf>>(emptyList()) }
    val searchFocusRequester = remember { FocusRequester() }

    val isSelectionMode = selectedKeys.isNotEmpty()

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
            snapshotFlow { searchQuery }
                .debounce(150L)
                .collect { debouncedQuery = it.trim() }
        } else {
            debouncedQuery = ""
        }
    }

    // Search BackHandler is registered first; selection BackHandler below overrides it when active.
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        onClearSelection()
    }

    val searchResults by produceState(
        initialValue = emptyList<LibraryPdf>(),
        debouncedQuery,
        listing.allRootFiles,
        isSearchActive
    ) {
        value = if (!isSearchActive || debouncedQuery.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                listing.allRootFiles.filter { it.name.contains(debouncedQuery, ignoreCase = true) }
            }
        }
    }

    val visiblePdfs: List<LibraryPdf> = remember(isSearchActive, searchResults, listing.items) {
        if (isSearchActive) {
            searchResults
        } else {
            listing.items.mapNotNull { (it as? BrowserItem.Pdf)?.pdf }
        }
    }

    val selectedPdfObjects: List<LibraryPdf> = remember(selectedKeys, listing.allRootFiles, listing.items) {
        if (selectedKeys.isEmpty()) {
            emptyList()
        } else {
            val pool = if (listing.allRootFiles.isNotEmpty()) listing.allRootFiles else visiblePdfs
            pool.filter { LibraryViewModel.selectionKey(it) in selectedKeys }
        }
    }

    val recentsByPathOrUri = remember(recents) {
        HashMap<String, PdfRecentItem>(recents.size * 2).apply {
            for (r in recents) {
                r.localFilePath?.takeIf { it.isNotBlank() }?.let { put(it, r) }
                if (r.pdf_path.isNotBlank()) put(r.pdf_path, r)
                if (r.uriString.isNotBlank()) put(r.uriString, r)
            }
        }
    }

    val breadcrumbs = remember(root, relDir) {
        if (root == null) {
            emptyList()
        } else {
            val list = ArrayList<BreadcrumbSegment>()
            list.add(BreadcrumbSegment(label = root.name, location = "${root.id}|"))
            if (relDir.isNotEmpty()) {
                val parts = relDir.split('/').filter { it.isNotBlank() }
                val sb = StringBuilder()
                for (part in parts) {
                    if (sb.isNotEmpty()) sb.append('/')
                    sb.append(part)
                    list.add(BreadcrumbSegment(label = part, location = "${root.id}|$sb"))
                }
            }
            list
        }
    }

    val breadcrumbListState = rememberLazyListState()
    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty()) {
            breadcrumbListState.animateScrollToItem(breadcrumbs.lastIndex)
        }
    }

    val dateFormatter = remember {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }

    val resolveTargetUri: (LibraryPdf) -> Uri = { pdf ->
        val pathFile = pdf.path?.let { File(it) }
        if (pathFile != null && pathFile.exists() && pathFile.canRead()) {
            Uri.fromFile(pathFile)
        } else {
            Uri.parse(pdf.uri)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(
                            onClick = onClearSelection,
                            modifier = Modifier.testTag("library_selection_close_btn")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedKeys.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { onSelectAll(visiblePdfs) },
                            modifier = Modifier.testTag("library_selection_select_all_btn")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(
                            onClick = {
                                val targets = selectedPdfObjects
                                if (targets.isNotEmpty()) {
                                    onAddToRecents(targets)
                                    onClearSelection()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Added ${targets.size} to Recents")
                                    }
                                }
                            },
                            modifier = Modifier.testTag("library_selection_add_recents_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to Recents")
                        }
                        IconButton(
                            onClick = {
                                val targets = selectedPdfObjects
                                if (targets.isNotEmpty()) {
                                    val matchedMap = HashMap<String, String>()
                                    for (pdf in targets) {
                                        val r = (pdf.path?.let { recentsByPathOrUri[it] }) ?: recentsByPathOrUri[pdf.uri]
                                        if (r != null) {
                                            matchedMap[LibraryViewModel.selectionKey(pdf)] = r.uriString
                                        }
                                    }
                                    onFavoriteSelected(targets, matchedMap)
                                    onClearSelection()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Added ${targets.size} to Favorites")
                                    }
                                }
                            },
                            modifier = Modifier.testTag("library_selection_favorite_btn")
                        ) {
                            Icon(Icons.Default.Star, contentDescription = "Favorite")
                        }
                        IconButton(
                            onClick = {
                                if (selectedPdfObjects.isNotEmpty()) {
                                    pendingDeletePdfs = selectedPdfObjects
                                }
                            },
                            modifier = Modifier.testTag("library_selection_delete_btn")
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else if (isSearchActive) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "Search in ${root?.name ?: "library"}...",
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            } else {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Text(
                            text = currentFolderName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search folder")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                BrowserSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sort.displayName) },
                                        trailingIcon = if (appSettings.browserSort == sort) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            showSortMenu = false
                                            onSelectSort(sort)
                                        }
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                DropdownMenuItem(
                                    text = { Text("Folders first") },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = appSettings.browserFoldersFirst,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        showSortMenu = false
                                        onToggleFoldersFirst(!appSettings.browserFoldersFirst)
                                    }
                                )
                            }
                        }
                        if (root != null) {
                            IconButton(
                                onClick = { onRescanRoot(root.id) },
                                enabled = scanState == ScanState.Idle
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan folder")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (scanState is ScanState.Scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Scanning… ${scanState.found} PDFs found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (breadcrumbs.isNotEmpty() && !isSearchActive) {
                LazyRow(
                    state = breadcrumbListState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(breadcrumbs) { index, seg ->
                        val isLast = index == breadcrumbs.lastIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateLocation(seg.location) }
                                .padding(horizontal = 6.dp)
                                .testTag("breadcrumb_$index")
                        ) {
                            Text(
                                text = seg.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }

            val renderPdfRow: @Composable (LibraryPdf, String?) -> Unit = { pdf, subtitlePath ->
                val matchedRecent = (pdf.path?.let { recentsByPathOrUri[it] }) ?: recentsByPathOrUri[pdf.uri]
                val targetUri = remember(pdf.path, pdf.uri) { resolveTargetUri(pdf) }
                val primaryDocKey = remember(targetUri) { PdfDocumentStorage.getDocumentKey(targetUri) }
                val recentDocKey = remember(matchedRecent?.uriString) {
                    matchedRecent?.uriString?.let { PdfDocumentStorage.getDocumentKey(Uri.parse(it)) }
                }
                val isFavorite = (primaryDocKey in appSettings.favoriteKeys) ||
                    (recentDocKey != null && recentDocKey in appSettings.favoriteKeys)
                val selKey = remember(pdf.path, pdf.uri) { LibraryViewModel.selectionKey(pdf) }
                val isSelected = selKey in selectedKeys

                BrowserPdfRow(
                    pdf = pdf,
                    targetUri = targetUri,
                    docKey = recentDocKey ?: primaryDocKey,
                    recentItem = matchedRecent,
                    subtitlePath = subtitlePath,
                    dateFormatter = dateFormatter,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    isFavorite = isFavorite,
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelect(pdf)
                        } else {
                            onOpenPdf(targetUri)
                        }
                    },
                    onLongClick = { onToggleSelect(pdf) },
                    onMenuOpen = { onOpenPdf(targetUri) },
                    onMenuAddToRecents = {
                        onAddToRecents(listOf(pdf))
                        scope.launch {
                            snackbarHostState.showSnackbar("Added \"${pdf.name}\" to Recents")
                        }
                    },
                    onMenuToggleFavorite = {
                        onToggleFavorite(pdf, matchedRecent?.uriString)
                    },
                    onMenuDetails = { detailsPdf = pdf },
                    onMenuCopyPath = {
                        val pathText = pdf.path ?: pdf.uri
                        clipboardManager.setText(AnnotatedString(pathText))
                        scope.launch {
                            snackbarHostState.showSnackbar("Path copied to clipboard")
                        }
                    },
                    onMenuDelete = { pendingDeletePdfs = listOf(pdf) }
                )
            }

            if (root?.status == LibraryRootStatus.NEEDS_ACCESS && listing.items.isEmpty() && !isSearchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Folder access needed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Permission for \"${root.name}\" is no longer active. Re-select or grant storage access in Library settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onOpenLibrarySettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Grant access")
                        }
                    }
                }
            } else if (isSearchActive) {
                if (debouncedQuery.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Type a file name to search inside ${root?.name ?: "this folder"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No PDFs match \"$debouncedQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("library_browser_list"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = searchResults,
                            key = { "f:${it.path ?: it.uri}" },
                            contentType = { "pdf" }
                        ) { pdf ->
                            renderPdfRow(pdf, if (pdf.relDir.isEmpty()) "Root" else pdf.relDir)
                        }
                    }
                }
            } else if (listing.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No PDFs found in this folder",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (root != null && scanState == ScanState.Idle) {
                            Spacer(modifier = Modifier.height(14.dp))
                            FilledTonalButton(
                                onClick = { onRescanRoot(root.id) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rescan")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("library_browser_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (root?.status == LibraryRootStatus.NEEDS_ACCESS) {
                        item(key = "needs_access_banner", contentType = "banner") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Permission expired. Showing cached index.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = onOpenLibrarySettings,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Grant access")
                                    }
                                }
                            }
                        }
                    }

                    items(
                        items = listing.items,
                        key = { it.key },
                        contentType = { it.contentType }
                    ) { item ->
                        when (item) {
                            is BrowserItem.Folder -> {
                                val targetLocation = if (root != null) "${root.id}|${item.entry.relDir}" else ""
                                BrowserFolderRow(
                                    folder = item.entry,
                                    onClick = {
                                        if (!isSelectionMode && targetLocation.isNotEmpty()) {
                                            onNavigateLocation(targetLocation)
                                        }
                                    }
                                )
                            }
                            is BrowserItem.Pdf -> {
                                renderPdfRow(item.pdf, null)
                            }
                        }
                    }
                }
            }
        }
    }

    // Details dialog
    val infoTarget = detailsPdf
    if (infoTarget != null) {
        val matchedRecent = (infoTarget.path?.let { recentsByPathOrUri[it] }) ?: recentsByPathOrUri[infoTarget.uri]
        val targetUri = resolveTargetUri(infoTarget)
        val docKey = matchedRecent?.uriString?.let { PdfDocumentStorage.getDocumentKey(Uri.parse(it)) }
            ?: PdfDocumentStorage.getDocumentKey(targetUri)
        val readingTimeMs = remember(docKey) { ReadingStats.totalFor(context, docKey) }
        val folderDisplay = infoTarget.path?.let { File(it).parent }
            ?: if (infoTarget.relDir.isEmpty()) (root?.name ?: "Root") else "${root?.name ?: ""}/${infoTarget.relDir}"

        AlertDialog(
            onDismissRequest = { detailsPdf = null },
            title = { Text("Document Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Name", infoTarget.name)
                    DetailRow("Folder", folderDisplay)
                    DetailRow("Size", formatBrowserFileSize(infoTarget.size))
                    DetailRow(
                        "Modified",
                        if (infoTarget.modified > 0L) dateFormatter.format(Date(infoTarget.modified)) else "Unknown"
                    )
                    if (matchedRecent != null && matchedRecent.pageCount > 0) {
                        DetailRow("Pages", "${matchedRecent.pageCount}")
                    }
                    DetailRow("Reading time", ReadingStats.formatDuration(readingTimeMs))
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsPdf = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Permanent deletion confirmation dialog
    if (pendingDeletePdfs.isNotEmpty()) {
        val toDelete = pendingDeletePdfs
        val titleText = if (toDelete.size == 1) {
            "Delete \"${toDelete.first().name}\"?"
        } else {
            "Delete ${toDelete.size} PDFs?"
        }
        AlertDialog(
            onDismissRequest = { pendingDeletePdfs = emptyList() },
            title = { Text(titleText) },
            text = {
                Text(
                    "This permanently removes the ${if (toDelete.size == 1) "file" else "files"} from your device and clears recent history, bookmarks, thumbnails, and cached outlines. This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletePdfs = emptyList()
                        onDeletePdfs(toDelete) { deleted, failed ->
                            scope.launch {
                                val msg = when {
                                    failed == 0 -> "Deleted $deleted ${if (deleted == 1) "file" else "files"}"
                                    deleted == 0 -> "Unable to delete ${if (failed == 1) "file" else "$failed files"} (permission denied)"
                                    else -> "Deleted $deleted; failed to delete $failed"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }
                ) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePdfs = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BrowserFolderRow(
    folder: FolderEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("library_folder_${folder.relDir.hashCode()}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.pdfCountRecursive} PDFs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserPdfRow(
    pdf: LibraryPdf,
    targetUri: Uri,
    docKey: String,
    recentItem: PdfRecentItem?,
    subtitlePath: String?,
    dateFormatter: SimpleDateFormat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuAddToRecents: () -> Unit,
    onMenuToggleFavorite: () -> Unit,
    onMenuDetails: () -> Unit,
    onMenuCopyPath: () -> Unit,
    onMenuDelete: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    var thumbBitmap by remember(docKey, recentItem?.thumbnailPath) {
        mutableStateOf<ImageBitmap?>(LibraryThumbnailStore.getCachedSync(docKey))
    }

    LaunchedEffect(docKey, recentItem?.thumbnailPath) {
        if (thumbBitmap == null) {
            thumbBitmap = LibraryThumbnailStore.loadOrGenerateThumbnail(
                context = context,
                uri = targetUri,
                docKey = docKey,
                recentThumbnailPath = recentItem?.thumbnailPath
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("library_pdf_${(pdf.path ?: pdf.uri).hashCode()}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 68.dp, height = 94.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Cover of ${pdf.name}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pdf.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!subtitlePath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitlePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val modifiedText = remember(pdf.modified) {
                    if (pdf.modified > 0L) dateFormatter.format(Date(pdf.modified)) else ""
                }
                val metaText = if (modifiedText.isNotEmpty()) {
                    "${formatBrowserFileSize(pdf.size)} · $modifiedText"
                } else {
                    formatBrowserFileSize(pdf.size)
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (recentItem != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val total = recentItem.pageCount
                    val current = if (total > 0) {
                        (recentItem.lastReadPage + 1).coerceIn(1, total)
                    } else {
                        (recentItem.lastReadPage + 1).coerceAtLeast(1)
                    }
                    val progress = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (total > 0) "p.$current/$total" else "p.$current",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag("library_pdf_menu_${(pdf.path ?: pdf.uri).hashCode()}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "File actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onMenuOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Recents") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onMenuAddToRecents()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                showMenu = false
                                onMenuToggleFavorite()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onMenuDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy path") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onMenuCopyPath()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onMenuDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatBrowserFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", kb)
    }
}
