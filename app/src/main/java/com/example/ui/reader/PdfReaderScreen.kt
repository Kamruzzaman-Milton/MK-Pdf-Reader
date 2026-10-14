package com.example.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.pdf.ReadingMode
import com.example.data.settings.AppSettings
import com.example.data.settings.PageColorTone
import com.example.data.settings.ReaderOrientation
import com.example.ui.components.PdfPageView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    viewModel: PdfReaderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val engine = viewModel.engine
    val context = LocalContext.current

    var orientationFeedbackText by remember { mutableStateOf<String?>(null) }

    // Dynamic Orientation Handler
    LaunchedEffect(uiState.currentOrientation) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        activity.requestedOrientation = when (uiState.currentOrientation) {
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // Dynamic Screen Brightness Handler
    LaunchedEffect(uiState.brightnessLevel) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        val lp = activity.window.attributes
        lp.screenBrightness = if (uiState.brightnessLevel < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            uiState.brightnessLevel.coerceIn(0.05f, 1.0f)
        }
        activity.window.attributes = lp
    }

    // Reset orientation, brightness and keep-awake when exiting reader
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        viewModel.recordResume()
        onDispose {
            viewModel.recordClose()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.let {
                val lp = it.window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.window.attributes = lp
            }
        }
    }

    // Keep screen awake controller
    LaunchedEffect(uiState.isScreenWakeActive) {
        val activity = context.findActivity()
        if (uiState.isScreenWakeActive) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Auto-dismiss HUD feedback message
    LaunchedEffect(uiState.hudFeedbackMessage) {
        if (uiState.hudFeedbackMessage != null) {
            delay(1800)
            viewModel.clearHudFeedback()
        }
    }

    // Handle system back button: dismiss any active sheet/dialog/fullscreen first, otherwise immediately navigate back
    BackHandler {
        if (uiState.isFullscreen) {
            viewModel.toggleFullscreen()
        } else if (uiState.showBookmarksSheet) {
            viewModel.showBookmarksSheet(false)
        } else if (uiState.showIndexSheet) {
            viewModel.showIndexSheet(false)
        } else if (uiState.showSettingsSheet) {
            viewModel.showSettingsSheet(false)
        } else if (uiState.showJumpDialog) {
            viewModel.showJumpDialog(false)
        } else if (uiState.showInfoDialog) {
            viewModel.showInfoDialog(false)
        } else {
            onNavigateBack()
        }
    }

    if (uiState.showJumpDialog && uiState.totalPages > 0) {
        JumpToPageDialog(
            currentPage = uiState.currentPage + 1,
            totalPages = uiState.totalPages,
            onDismiss = { viewModel.showJumpDialog(false) },
            onJumpToPage = { pageNum -> viewModel.jumpToPage(pageNum) }
        )
    }

    if (uiState.showInfoDialog && uiState.documentInfo != null) {
        DocumentInfoDialog(
            documentInfo = uiState.documentInfo!!,
            onDismiss = { viewModel.showInfoDialog(false) }
        )
    }

    if (uiState.showSettingsSheet) {
        SettingsSheet(
            settings = appSettings,
            cacheUsageBytes = uiState.cacheUsageBytes,
            maxCacheBytes = uiState.maxCacheBytes,
            onDismiss = { viewModel.showSettingsSheet(false) },
            onUpdateSharpness = { viewModel.updateSharpness(it) },
            onUpdatePrefetchMode = { viewModel.updatePrefetchMode(it) },
            onUpdateColorTone = { viewModel.updateColorTone(it) },
            onUpdatePageSpacing = { viewModel.updatePageSpacing(it) },
            onUpdateShowPageBadges = { viewModel.updateShowPageBadges(it) },
            onUpdateKeepAwakeOption = { viewModel.updateKeepAwakeOption(it) },
            onUpdateOrientation = { viewModel.setOrientation(it) },
            onUpdateHighQualityPrintMode = { viewModel.updateHighQualityPrintMode(it) },
            onUpdateDoubleTapZoomMode = { viewModel.updateDoubleTapZoomMode(it) },
            onClearCache = { viewModel.clearCache() }
        )
    }

    if (uiState.showBookmarksSheet) {
        BookmarksSheet(
            bookmarks = uiState.bookmarks,
            currentPageIndex = uiState.currentPage,
            totalPages = uiState.totalPages,
            isCurrentPageBookmarked = uiState.isCurrentPageBookmarked,
            onDismiss = { viewModel.showBookmarksSheet(false) },
            onJumpToPage = { pageNum -> viewModel.jumpToPage(pageNum) },
            onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark) },
            onToggleCurrentPageBookmark = { viewModel.toggleBookmarkCurrentPage() }
        )
    }

    if (uiState.showIndexSheet) {
        IndexSheet(
            outlineItems = uiState.outlineItems,
            isLoading = uiState.isLoadingOutline,
            currentPageIndex = uiState.currentPage,
            documentTitle = uiState.documentInfo?.fileName ?: "Table of Contents",
            expandedStates = uiState.indexExpandedStates,
            initialScrollIndex = uiState.indexScrollIndex,
            initialScrollOffset = uiState.indexScrollOffset,
            onToggleExpand = { id -> viewModel.toggleIndexItemExpansion(id) },
            onSetAllExpanded = { ids, isExpanded -> viewModel.setAllIndexExpanded(ids, isExpanded) },
            onSaveScrollPosition = { index, offset -> viewModel.saveIndexScrollPosition(index, offset) },
            onDismiss = { viewModel.showIndexSheet(false) },
            onJumpToPage = { pageNum -> viewModel.jumpToPage(pageNum) }
        )
    }

    val canvasBackgroundColor = when (appSettings.colorTone) {
        PageColorTone.ORIGINAL -> Color(0xFFE9ECEF)
        PageColorTone.SEPIA -> Color(0xFFEDE3D2)
        PageColorTone.INVERTED_DARK -> Color(0xFF0D0D0D)
        PageColorTone.HIGH_CONTRAST -> Color(0xFFDCDCDC)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBackgroundColor)
    ) {
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("pdf_loading_indicator")
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Rendering crisp document pages...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (uiState.errorMessage != null) {
            val relocateLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { pickedUri: Uri? ->
                if (pickedUri != null) {
                    com.example.data.pdf.PdfDocumentStorage.takePersistablePermissionSafely(context, pickedUri)
                    viewModel.loadPdf(pickedUri)
                }
            }

            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Environment.isExternalStorageManager()
                        } else true
                        if (hasAccess) {
                            viewModel.retry()
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = uiState.errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    androidx.compose.material3.Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                } catch (ignored: Exception) {}
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .testTag("pdf_error_grant_storage_btn")
                    ) {
                        Text("Grant Storage Access")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { relocateLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .testTag("pdf_error_relocate_btn")
                ) {
                    Text("Browse / Re-locate File")
                }
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("pdf_error_back_btn")
                ) {
                    Text("Return to Library")
                }
            }
        } else if (engine != null && uiState.totalPages > 0) {
            // Viewport
            ReaderViewport(
                viewModel = viewModel,
                uiState = uiState,
                appSettings = appSettings,
                engine = engine
            )

            // Top Bar Overlay (Unchanged: Back, Title, Reset Zoom, Settings, Doc Info, Fullscreen)
            AnimatedVisibility(
                visible = !uiState.isFullscreen,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    title = uiState.documentInfo?.fileName ?: "MK PDF Reader",
                    currentPage = uiState.currentPage + 1,
                    totalPages = uiState.totalPages,
                    currentChapter = uiState.currentChapterTitle,
                    zoomScale = uiState.zoomScale,
                    onBackClick = onNavigateBack,
                    onResetZoom = { viewModel.resetZoom() },
                    onOpenSettings = { viewModel.showSettingsSheet(true) },
                    onFullscreenClick = { viewModel.toggleFullscreen() },
                    onInfoClick = { viewModel.showInfoDialog(true) }
                )
            }

            // Bottom Bar Overlay: Pages | Orientation | Page X/Y | Bookmark | Index + Brightness Control
            AnimatedVisibility(
                visible = !uiState.isFullscreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ReaderBottomBar(
                    currentPage = uiState.currentPage + 1,
                    totalPages = uiState.totalPages,
                    readingMode = uiState.readingMode,
                    currentOrientation = uiState.currentOrientation,
                    isBookmarked = uiState.isCurrentPageBookmarked,
                    bookmarkCount = uiState.bookmarks.size,
                    brightnessLevel = uiState.brightnessLevel,
                    onPageSeek = { targetPage1Indexed ->
                        viewModel.jumpToPage(targetPage1Indexed)
                    },
                    onOpenJumpDialog = { viewModel.showJumpDialog(true) },
                    onToggleReadingMode = { viewModel.toggleReadingMode() },
                    onCycleOrientation = {
                        val newOrient = viewModel.cycleOrientation()
                        orientationFeedbackText = newOrient.displayName
                    },
                    onToggleBookmark = { viewModel.toggleBookmarkCurrentPage() },
                    onOpenBookmarksList = { viewModel.showBookmarksSheet(true) },
                    onOpenIndex = { viewModel.showIndexSheet(true) },
                    onBrightnessChange = { viewModel.setBrightness(it) }
                )
            }

            // HUD Floating Pill (Orientation change or Bookmark confirmation feedback)
            val activeFeedback = uiState.hudFeedbackMessage ?: orientationFeedbackText
            activeFeedback?.let { text ->
                if (orientationFeedbackText != null) {
                    LaunchedEffect(text) {
                        delay(1600)
                        orientationFeedbackText = null
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.90f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                ) {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            // Exit Fullscreen Floating Button
            AnimatedVisibility(
                visible = uiState.isFullscreen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { viewModel.setFullscreen(false) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("exit_fullscreen_btn")
                        .shadow(4.dp, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = stringResource(R.string.exit_fullscreen),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderViewport(
    viewModel: PdfReaderViewModel,
    uiState: PdfReaderUiState,
    appSettings: AppSettings,
    engine: com.example.data.pdf.PdfRendererEngine
) {
    val totalPages = uiState.totalPages

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        viewModel.toggleDoubleTapZoom(
                            tapX = offset.x,
                            tapY = offset.y,
                            viewWidth = size.width.toFloat(),
                            viewHeight = size.height.toFloat()
                        )
                    },
                    onTap = {
                        viewModel.toggleFullscreen()
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    viewModel.updateZoomAndPan(
                        zoomMultiplier = zoom,
                        panX = pan.x,
                        panY = pan.y
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = uiState.zoomScale
                    scaleY = uiState.zoomScale
                    translationX = uiState.panOffsetX
                    translationY = uiState.panOffsetY
                }
        ) {
            if (uiState.readingMode == ReadingMode.VERTICAL) {
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = uiState.currentPage
                )

                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { index ->
                            viewModel.onPageChanged(index)
                        }
                }

                LaunchedEffect(uiState.currentPage) {
                    if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != uiState.currentPage) {
                        listState.scrollToItem(uiState.currentPage)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("vertical_pdf_list"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (uiState.isFullscreen) 16.dp else 84.dp,
                        bottom = if (uiState.isFullscreen) 16.dp else 168.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(appSettings.pageSpacing.dpValue),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(
                        count = totalPages,
                        key = { it }
                    ) { pageIdx ->
                        PdfPageView(
                            pageIndex = pageIdx,
                            engine = engine,
                            sharpness = appSettings.sharpness,
                            colorTone = appSettings.colorTone,
                            highQualityPrintMode = appSettings.highQualityPrintMode,
                            showPageNumberBadge = appSettings.showPageBadges,
                            isBookmarked = pageIdx in uiState.bookmarkedPages,
                            onPageNumberClick = {
                                viewModel.toggleBookmarkPage(pageIdx)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pdf_page_$pageIdx")
                        )
                    }
                }
            } else {
                val pagerState = rememberPagerState(
                    initialPage = uiState.currentPage,
                    pageCount = { totalPages }
                )

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .collect { page ->
                            viewModel.onPageChanged(page)
                        }
                }

                LaunchedEffect(uiState.currentPage) {
                    if (!pagerState.isScrollInProgress && pagerState.currentPage != uiState.currentPage) {
                        pagerState.scrollToPage(uiState.currentPage)
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("horizontal_pdf_pager"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (uiState.isFullscreen) 16.dp else 76.dp,
                        bottom = if (uiState.isFullscreen) 16.dp else 158.dp
                    ),
                    pageSpacing = appSettings.pageSpacing.dpValue,
                    verticalAlignment = Alignment.CenterVertically
                ) { pageIdx ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        PdfPageView(
                            pageIndex = pageIdx,
                            engine = engine,
                            sharpness = appSettings.sharpness,
                            colorTone = appSettings.colorTone,
                            highQualityPrintMode = appSettings.highQualityPrintMode,
                            showPageNumberBadge = appSettings.showPageBadges,
                            isBookmarked = pageIdx in uiState.bookmarkedPages,
                            onPageNumberClick = {
                                viewModel.toggleBookmarkPage(pageIdx)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pdf_page_$pageIdx")
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    currentPage: Int,
    totalPages: Int,
    currentChapter: String? = null,
    zoomScale: Float,
    onBackClick: () -> Unit,
    onResetZoom: () -> Unit,
    onOpenSettings: () -> Unit,
    onFullscreenClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp
    ) {
        TopAppBar(
            modifier = Modifier.statusBarsPadding(),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            navigationIcon = {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.testTag("reader_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (!currentChapter.isNullOrBlank()) {
                            "$currentChapter • $currentPage / $totalPages"
                        } else {
                            "$currentPage / $totalPages pages"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            actions = {
                if (zoomScale > 1.1f) {
                    IconButton(
                        onClick = onResetZoom,
                        modifier = Modifier.testTag("reset_zoom_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.reset_zoom),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("top_settings_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.settings)
                    )
                }
                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier.testTag("document_info_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.file_info)
                    )
                }
                IconButton(
                    onClick = onFullscreenClick,
                    modifier = Modifier.testTag("reader_fullscreen_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.fullscreen_reading)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    readingMode: ReadingMode,
    currentOrientation: ReaderOrientation,
    isBookmarked: Boolean,
    bookmarkCount: Int,
    brightnessLevel: Float,
    onPageSeek: (Int) -> Unit,
    onOpenJumpDialog: () -> Unit,
    onToggleReadingMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenBookmarksList: () -> Unit,
    onOpenIndex: () -> Unit,
    onBrightnessChange: (Float) -> Unit
) {
    var sliderValue by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var showBrightnessBar by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Horizontal Page Seeking Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp)
                )

                Slider(
                    value = if (isDraggingSlider) sliderValue else currentPage.toFloat(),
                    onValueChange = { newValue ->
                        isDraggingSlider = true
                        sliderValue = newValue
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        onPageSeek(sliderValue.roundToInt().coerceIn(1, totalPages))
                    },
                    valueRange = 1f..totalPages.toFloat().coerceAtLeast(1f),
                    steps = if (totalPages > 2) (totalPages - 2).coerceAtMost(100) else 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .testTag("page_seeking_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Text(
                    text = "$totalPages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(26.dp)
                )
            }

            // Expandable Brightness Control Bar (Revealed on Brightness Button tap)
            AnimatedVisibility(
                visible = showBrightnessBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (brightnessLevel < 0f) Icons.Default.BrightnessMedium else if (brightnessLevel < 0.4f) Icons.Default.BrightnessLow else Icons.Default.WbSunny,
                            contentDescription = "Screen Brightness",
                            tint = if (brightnessLevel >= 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Slider(
                            value = if (brightnessLevel < 0f) 0.5f else brightnessLevel,
                            onValueChange = { onBrightnessChange(it) },
                            valueRange = 0.05f..1.0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .testTag("brightness_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = if (brightnessLevel >= 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                activeTrackColor = if (brightnessLevel >= 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Auto / Percent badge button
                        Surface(
                            onClick = {
                                if (brightnessLevel < 0f) {
                                    onBrightnessChange(0.70f)
                                } else {
                                    onBrightnessChange(-1.0f) // Reset to Auto/System
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (brightnessLevel < 0f) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.testTag("brightness_auto_toggle")
                        ) {
                            Text(
                                text = if (brightnessLevel < 0f) "Auto" else "${(brightnessLevel * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (brightnessLevel < 0f) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Toolbar Controls: Pages | Orientation | Page X/Y | Bookmark | Index | Brightness
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Reading Mode Switcher Button (Continuous Vertical vs Page Horizontal)
                FilledTonalButton(
                    onClick = onToggleReadingMode,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("toggle_reading_mode_btn")
                ) {
                    Icon(
                        imageVector = if (readingMode == ReadingMode.VERTICAL) {
                            Icons.Default.ViewCarousel
                        } else {
                            Icons.Default.ViewDay
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (readingMode == ReadingMode.VERTICAL) "Vertical" else "Pages",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // 2. Orientation Button (Manual Orientation Toggle)
                FilledTonalButton(
                    onClick = onCycleOrientation,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("toggle_orientation_btn")
                ) {
                    val orientIcon = when (currentOrientation) {
                        ReaderOrientation.PORTRAIT -> Icons.Default.StayCurrentPortrait
                        ReaderOrientation.LANDSCAPE -> Icons.Default.StayCurrentLandscape
                    }
                    val orientLabel = when (currentOrientation) {
                        ReaderOrientation.PORTRAIT -> "Portrait"
                        ReaderOrientation.LANDSCAPE -> "Landscape"
                    }
                    Icon(
                        imageVector = orientIcon,
                        contentDescription = orientLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = orientLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // 3. Current Page / Total Page Indicator Pill (Tappable -> Jump to Page Dialog)
                Surface(
                    onClick = onOpenJumpDialog,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.testTag("page_indicator_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isDraggingSlider) {
                                "${sliderValue.roundToInt()} / $totalPages"
                            } else {
                                "$currentPage / $totalPages"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // 4. Bookmark Button (Tap to toggle bookmark, Long-press/Secondary to open saved bookmarks sheet)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isBookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = onToggleBookmark,
                            onLongClick = onOpenBookmarksList
                        )
                        .testTag("bottom_bookmark_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Bookmarked (Tap to remove, long press for all)" else "Bookmark page (Long press for all)",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        if (bookmarkCount > 0) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "$bookmarkCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isBookmarked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // 5. Index Button (PDF Table of Contents / Outline)
                FilledTonalIconButton(
                    onClick = onOpenIndex,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("bottom_index_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Toc,
                        contentDescription = "Document Index / Table of Contents",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 6. Dedicated Brightness Button (Beside index button - reveals/hides brightness bar)
                FilledTonalIconButton(
                    onClick = { showBrightnessBar = !showBrightnessBar },
                    shape = RoundedCornerShape(12.dp),
                    colors = if (showBrightnessBar || brightnessLevel >= 0f) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                    modifier = Modifier.testTag("bottom_brightness_btn")
                ) {
                    Icon(
                        imageVector = if (brightnessLevel < 0f) {
                            Icons.Default.BrightnessMedium
                        } else if (brightnessLevel < 0.4f) {
                            Icons.Default.BrightnessLow
                        } else {
                            Icons.Default.WbSunny
                        },
                        contentDescription = "Adjust Brightness",
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
