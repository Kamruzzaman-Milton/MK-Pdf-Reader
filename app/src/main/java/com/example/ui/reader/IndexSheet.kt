package com.example.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.pdf.PdfOutlineItem
import kotlinx.coroutines.flow.distinctUntilChanged

data class FlatOutlineEntry(
    val item: PdfOutlineItem,
    val level: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean
)

data class SearchResultEntry(
    val item: PdfOutlineItem,
    val parentBreadcrumb: String?,
    val level: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexSheet(
    outlineItems: List<PdfOutlineItem>,
    isLoading: Boolean = false,
    currentPageIndex: Int,
    documentTitle: String = "Document Index",
    expandedStates: Map<String, Boolean>,
    initialScrollIndex: Int,
    initialScrollOffset: Int,
    onToggleExpand: (String) -> Unit,
    onSetAllExpanded: (List<String>, Boolean) -> Unit,
    onSaveScrollPosition: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Collect all expandable IDs
    val expandableIds = remember(outlineItems) {
        val list = mutableListOf<String>()
        fun collect(items: List<PdfOutlineItem>) {
            for (item in items) {
                if (item.children.isNotEmpty()) {
                    list.add(item.id)
                    collect(item.children)
                }
            }
        }
        collect(outlineItems)
        list
    }

    // Count total chapters & sub-chapters
    val totalNodesCount = remember(outlineItems) {
        fun countTotal(items: List<PdfOutlineItem>): Int {
            var count = items.size
            for (item in items) {
                count += countTotal(item.children)
            }
            return count
        }
        countTotal(outlineItems)
    }

    // Search results across the entire outline hierarchy
    val searchResults = remember(outlineItems, searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            val query = searchQuery.trim()
            val list = mutableListOf<SearchResultEntry>()
            fun searchIn(items: List<PdfOutlineItem>, parentTitles: List<String>, level: Int) {
                for (item in items) {
                    val currentBreadcrumb = if (parentTitles.isNotEmpty()) parentTitles.joinToString(" › ") else null
                    val matchesTitle = item.title.contains(query, ignoreCase = true)
                    val pageNumStr = (item.pageIndex + 1).toString()
                    val matchesPage = pageNumStr == query || "p. $pageNumStr".contains(query, ignoreCase = true) || "page $pageNumStr".contains(query, ignoreCase = true)

                    if (matchesTitle || matchesPage) {
                        list.add(SearchResultEntry(item, currentBreadcrumb, level))
                    }
                    if (item.children.isNotEmpty()) {
                        searchIn(item.children, parentTitles + item.title, level + 1)
                    }
                }
            }
            searchIn(outlineItems, emptyList(), 0)
            list
        }
    }

    // Flatten visible items based on expansion state for standard tree view
    val flatItems = remember(outlineItems, expandedStates) {
        fun flatten(items: List<PdfOutlineItem>, level: Int = 0): List<FlatOutlineEntry> {
            val list = mutableListOf<FlatOutlineEntry>()
            for (item in items) {
                val hasChildren = item.children.isNotEmpty()
                val isExpanded = expandedStates[item.id] ?: true
                list.add(
                    FlatOutlineEntry(
                        item = item,
                        level = level,
                        hasChildren = hasChildren,
                        isExpanded = isExpanded
                    )
                )
                if (hasChildren && isExpanded) {
                    list.addAll(flatten(item.children, level + 1))
                }
            }
            return list
        }
        flatten(outlineItems)
    }

    // Initialize list state with the remembered scroll position
    val startScrollIndex = if (initialScrollIndex >= 0) initialScrollIndex.coerceIn(0, (flatItems.size - 1).coerceAtLeast(0)) else 0
    val startScrollOffset = if (initialScrollIndex >= 0) initialScrollOffset else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startScrollIndex,
        initialFirstVisibleItemScrollOffset = startScrollOffset
    )

    // On the very first visit (when initialScrollIndex < 0), auto-scroll to the current reading page's chapter
    LaunchedEffect(flatItems) {
        if (initialScrollIndex < 0 && flatItems.isNotEmpty() && searchQuery.isBlank()) {
            val targetIdx = flatItems.indexOfLast { it.item.pageIndex <= currentPageIndex }
            if (targetIdx >= 0) {
                listState.scrollToItem(targetIdx)
            }
        }
    }

    // Track and continuously save scroll position (only when browsing full tree)
    LaunchedEffect(listState, searchQuery) {
        if (searchQuery.isBlank()) {
            snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
                .distinctUntilChanged()
                .collect { (idx, offset) ->
                    onSaveScrollPosition(idx, offset)
                }
        }
    }

    // Fullscreen Dialog matching Xodo and ReadEra experience
    Dialog(
        onDismissRequest = {
            if (searchQuery.isBlank()) {
                onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar with Back Arrow, Document Title, and Close
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = documentTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isLoading -> "Indexing document structure in background..."
                                    searchQuery.isNotBlank() -> "${searchResults.size} of $totalNodesCount chapters found"
                                    outlineItems.isNotEmpty() -> "$totalNodesCount chapters found"
                                    else -> "Table of Contents"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (searchQuery.isBlank()) {
                                    onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                }
                                onDismiss()
                            },
                            modifier = Modifier.testTag("index_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to reader"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (searchQuery.isBlank()) {
                                    onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                }
                                onDismiss()
                            },
                            modifier = Modifier.testTag("index_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Search Bar Section (Available whenever outline items exist or are indexing)
                if (outlineItems.isNotEmpty() || searchQuery.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        text = "Search in table of contents...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search icon",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                focusManager.clearFocus()
                                            },
                                            modifier = Modifier.testTag("clear_index_search_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear search query",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("index_search_input")
                            )
                        }
                    }
                }

                // Sub-header navigation & Quick expand/collapse toolbar (When not in search mode)
                if (searchQuery.isBlank() && outlineItems.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // CONTENTS Tab Indicator
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Toc,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CONTENTS",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                            }

                            // Expand / Collapse All Controls
                            if (expandableIds.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            onSetAllExpanded(expandableIds, true)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("expand_all_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.UnfoldMore,
                                            contentDescription = "Expand All",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Expand All",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            onSetAllExpanded(expandableIds, false)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("collapse_all_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.UnfoldLess,
                                            contentDescription = "Collapse All",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Collapse All",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                when {
                    // 1. Loading State (Indexing in progress and no cached items yet)
                    isLoading && outlineItems.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("index_loading_indicator"),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.5.dp
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = "Indexing Table of Contents...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Extracting document outline and chapter hierarchy in background.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }

                    // 2. Active Search Results
                    searchQuery.isNotBlank() -> {
                        if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(64.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SearchOff,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "No matching chapters found",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No table of contents entries matched \"$searchQuery\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            searchQuery = ""
                                            focusManager.clearFocus()
                                        }
                                    ) {
                                        Text("Clear search")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("index_search_results_list"),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(
                                    items = searchResults,
                                    key = { "search_${it.item.id}" }
                                ) { result ->
                                    SearchResultRow(
                                        result = result,
                                        query = searchQuery,
                                        currentPageIndex = currentPageIndex,
                                        onJumpToPage = { page ->
                                            onJumpToPage(page)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 3. Genuinely Empty State (Parsed, finished loading, but document has no outline)
                    outlineItems.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(72.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Toc,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "No index available",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "This PDF document does not have an embedded table of contents or outline hierarchy.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }

                    // 4. Full Table of Contents Tree
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("index_outline_list"),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = flatItems,
                                key = { it.item.id }
                            ) { flatEntry ->
                                FlatOutlineItemRow(
                                    entry = flatEntry,
                                    currentPageIndex = currentPageIndex,
                                    onToggleExpand = { onToggleExpand(flatEntry.item.id) },
                                    onJumpToPage = { page ->
                                        onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                        onJumpToPage(page)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResultEntry,
    query: String,
    currentPageIndex: Int,
    onJumpToPage: (Int) -> Unit
) {
    val item = result.item
    val isCurrent = item.pageIndex == currentPageIndex

    // Highlight query text in title
    val highlightedTitle = remember(item.title, query) {
        val text = item.title
        val lowerText = text.lowercase()
        val lowerQuery = query.trim().lowercase()

        if (lowerQuery.isEmpty() || !lowerText.contains(lowerQuery)) {
            buildAnnotatedString { append(text) }
        } else {
            buildAnnotatedString {
                var currentIndex = 0
                while (currentIndex < text.length) {
                    val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
                    if (matchIndex == -1) {
                        append(text.substring(currentIndex))
                        break
                    }
                    if (matchIndex > currentIndex) {
                        append(text.substring(currentIndex, matchIndex))
                    }
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1976D2)
                        )
                    ) {
                        append(text.substring(matchIndex, matchIndex + lowerQuery.length))
                    }
                    currentIndex = matchIndex + lowerQuery.length
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                Color.Transparent
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onJumpToPage(item.pageIndex + 1)
                }
                .testTag("search_item_${item.id}")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (result.parentBreadcrumb != null) {
                        Text(
                            text = result.parentBreadcrumb,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        text = highlightedTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Page ${item.pageIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            thickness = 0.5.dp
        )
    }
}

@Composable
private fun FlatOutlineItemRow(
    entry: FlatOutlineEntry,
    currentPageIndex: Int,
    onToggleExpand: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    val item = entry.item
    val isCurrent = item.pageIndex == currentPageIndex
    val indentDp = entry.level * 16

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                Color.Transparent
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onJumpToPage(item.pageIndex + 1)
                }
                .testTag("outline_item_${item.id}")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (14 + indentDp).dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Chapter Title + Indentation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.title,
                        style = when (entry.level) {
                            0 -> MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                            )
                            1 -> MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            )
                            else -> MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Chevron icon for expandable sub-chapters
                if (entry.hasChildren) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("toggle_expand_${item.id}")
                    ) {
                        Icon(
                            imageVector = if (entry.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (entry.isExpanded) "Collapse chapter" else "Expand chapter",
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Clean Page Number (Right-aligned, high contrast)
                Text(
                    text = "${item.pageIndex + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            thickness = 0.5.dp
        )
    }
}
