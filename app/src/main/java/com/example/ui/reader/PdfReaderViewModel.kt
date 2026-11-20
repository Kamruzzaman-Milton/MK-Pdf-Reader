package com.example.ui.reader

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.PdfBookmarkEntity
import com.example.data.pdf.OutlineHelper
import com.example.data.pdf.OutlineIndex
import com.example.data.pdf.PdfDocumentInfo
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.pdf.PdfOutlineItem
import com.example.data.pdf.PdfOutlineParser
import com.example.data.pdf.PdfRendererEngine
import com.example.data.pdf.ReadingMode
import com.example.data.repository.PdfRepository
import com.example.data.settings.AppSettings
import com.example.data.settings.DoubleTapZoomMode
import com.example.data.settings.KeepAwakeOption
import com.example.data.settings.MarginTrim
import com.example.data.settings.PageColorTone
import com.example.data.settings.PageSpacing
import com.example.data.settings.PrefetchMode
import com.example.data.settings.ReaderOrientation
import com.example.data.settings.ReadingStats
import com.example.data.settings.RenderSharpness
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PdfReaderUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val documentInfo: PdfDocumentInfo? = null,
    val currentPage: Int = 0, // 0-indexed internally
    val totalPages: Int = 0,
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val isFullscreen: Boolean = false,
    val zoomScale: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val showJumpDialog: Boolean = false,
    val showInfoDialog: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    val showIndexSheet: Boolean = false,
    val showPageOverview: Boolean = false,
    val isAutoScrolling: Boolean = false,
    val isScreenWakeActive: Boolean = true,
    val currentOrientation: ReaderOrientation = ReaderOrientation.PORTRAIT,
    val cacheUsageBytes: Int = 0,
    val maxCacheBytes: Int = 0,
    val bookmarks: List<PdfBookmarkEntity> = emptyList(),
    val bookmarkedPages: Set<Int> = emptySet(),
    val isCurrentPageBookmarked: Boolean = false,
    val outlineItems: List<PdfOutlineItem> = emptyList(),
    val outlineIndex: OutlineIndex? = null,
    val currentChapterTitle: String? = null,
    val outlineDiagnostic: String = "",
    val isLoadingOutline: Boolean = false,
    val hasOutlineLoaded: Boolean = false,
    val indexExpandedStates: Map<String, Boolean> = emptyMap(),
    val indexScrollIndex: Int = -1,
    val indexScrollOffset: Int = 0,
    val brightnessLevel: Float = -1f, // -1f = auto/system default, 0.01f..1.0f = custom
    val extraDimLevel: Float = 0f, // 0f = off, 0.01f..0.80f = dark room extra dim scrim
    val hudFeedbackMessage: String? = null
)

class PdfReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository = run {
        val db = AppDatabase.getDatabase(application)
        PdfRepository(application, db.pdfRecentDao(), db.pdfBookmarkDao())
    }

    private val settingsRepository: SettingsRepository = SettingsRepository.getInstance(application)
    val appSettings: StateFlow<AppSettings> = settingsRepository.settings

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    val navCommands = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    var engine: PdfRendererEngine? = null
        private set

    private var keepAwakeJob: Job? = null
    private var bookmarksJob: Job? = null
    private var outlineJob: Job? = null
    private var readingTickerJob: Job? = null

    @Volatile
    private var isReaderForeground: Boolean = true

    @Volatile
    private var lastUserActivityMs: Long = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(currentOrientation = settings.orientation) }
                resetKeepAwakeTimer(settings.keepAwakeOption)
            }
        }
    }

    var currentLoadedUri: Uri? = null
        private set

    fun retry() {
        currentLoadedUri?.let { loadPdf(it) }
    }

    fun loadPdf(uri: Uri, isSample: Boolean = false) {
        currentLoadedUri = uri
        PdfOutlineParser.activeDocumentUri = uri.toString()
        outlineJob?.cancel()
        outlineJob = null

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    outlineItems = emptyList(),
                    outlineIndex = null,
                    currentChapterTitle = null,
                    outlineDiagnostic = "",
                    isLoadingOutline = true,
                    hasOutlineLoaded = false,
                    indexExpandedStates = emptyMap(),
                    indexScrollIndex = -1,
                    indexScrollOffset = 0
                )
            }

            // Synchronous L1/L2 cache check for instant hit, then launch outlineJob immediately in parallel with engine init
            val cachedOutline = PdfOutlineParser.loadCachedOutline(getApplication(), uri)
            val pageCountDeferred = CompletableDeferred<Int>()
            val initialPageDeferred = CompletableDeferred<Int>()

            outlineJob = viewModelScope.launch(Dispatchers.IO) {
                val outline = cachedOutline ?: PdfOutlineParser.parseOutline(getApplication(), uri, 0)
                val resolvedPageCount = pageCountDeferred.await()
                if (resolvedPageCount <= 0) return@launch
                val index = withContext(Dispatchers.Default) {
                    OutlineHelper.buildIndex(outline, resolvedPageCount)
                }
                val startPage = initialPageDeferred.await()
                val activeChapter = OutlineHelper.getCurrentChapter(startPage, index)
                val diag = if (outline.isEmpty()) PdfOutlineParser.lastDiagnostic(uri) else ""
                _uiState.update {
                    it.copy(
                        outlineItems = outline,
                        outlineIndex = index,
                        currentChapterTitle = activeChapter?.title,
                        outlineDiagnostic = diag,
                        isLoadingOutline = false,
                        hasOutlineLoaded = true
                    )
                }
            }

            // Clean up previous engine if any
            engine?.close()

            var newEngine = PdfRendererEngine(getApplication(), uri)
            var initialized = newEngine.initialize()

            if (!initialized || newEngine.pageCount == 0) {
                newEngine.close()
                val relinked = repository.tryRelink(uri)
                if (relinked) {
                    newEngine = PdfRendererEngine(getApplication(), uri)
                    initialized = newEngine.initialize()
                }
            }

            if (!initialized || newEngine.pageCount == 0) {
                outlineJob?.cancel()
                pageCountDeferred.cancel()
                initialPageDeferred.cancel()

                val hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true

                val message = if (!hasStorageAccess) {
                    "Storage access required to read this PDF from your device storage."
                } else {
                    "Could not open this PDF document. The file may have moved or is in an unsupported format."
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingOutline = false,
                        errorMessage = message
                    )
                }
                return@launch
            }

            engine = newEngine
            val pageCount = newEngine.pageCount
            pageCountDeferred.complete(pageCount)

            // Resolve document metadata and check for saved progress
            val docInfo = repository.resolveDocumentInfo(uri, pageCount, isSample)
            val recentRecord = repository.getRecentByUri(uri.toString())

            val initialPage = recentRecord?.lastReadPage?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
            initialPageDeferred.complete(initialPage)
            val initialMode = recentRecord?.readingMode?.let {
                try {
                    ReadingMode.valueOf(it)
                } catch (e: Exception) {
                    ReadingMode.VERTICAL
                }
            } ?: ReadingMode.VERTICAL

            val keepAwake = appSettings.value.keepAwakeOption
            val initialWake = keepAwake == KeepAwakeOption.ALWAYS_ON || (keepAwake != KeepAwakeOption.OFF && keepAwake != KeepAwakeOption.SYSTEM_DEFAULT && keepAwake.durationMs > 0L)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    documentInfo = docInfo,
                    currentPage = initialPage,
                    totalPages = pageCount,
                    readingMode = initialMode,
                    zoomScale = 1.0f,
                    panOffsetX = 0f,
                    panOffsetY = 0f,
                    isAutoScrolling = false,
                    showPageOverview = false,
                    isScreenWakeActive = initialWake,
                    cacheUsageBytes = newEngine.getCacheUsageBytes(),
                    maxCacheBytes = newEngine.getMaxCacheBytes()
                )
            }

            // Start listening to persistent bookmarks for this document
            bookmarksJob?.cancel()
            bookmarksJob = viewModelScope.launch {
                repository.getBookmarksForUri(uri.toString()).collect { list ->
                    val set = list.map { it.pageIndex }.toSet()
                    _uiState.update { state ->
                        state.copy(
                            bookmarks = list,
                            bookmarkedPages = set,
                            isCurrentPageBookmarked = state.currentPage in set
                        )
                    }
                }
            }

            resetKeepAwakeTimer(keepAwake)

            // Trigger background prefetch around initial page
            triggerPrefetch(initialPage)

            // Save / record initial open to database with thumbnail
            val thumb = newEngine.renderThumbnail(0)
            repository.recordDocumentOpened(
                uri = uri,
                fileName = docInfo.fileName,
                fileSize = docInfo.fileSize,
                pageCount = pageCount,
                lastReadPage = initialPage,
                scrollOffset = 0f,
                readingMode = initialMode,
                thumbnailBitmap = thumb
            )

            // Start 10-second reading time tracker (off main thread)
            lastUserActivityMs = System.currentTimeMillis()
            val docKey = PdfDocumentStorage.getDocumentKey(uri)
            readingTickerJob?.cancel()
            readingTickerJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000L)
                    val idleMs = System.currentTimeMillis() - lastUserActivityMs
                    if (isReaderForeground && idleMs < 3 * 60 * 1000L) {
                        ReadingStats.addTime(getApplication(), docKey, 10_000L)
                    }
                }
            }
        }
    }

    fun reloadOutline() {
        val uri = currentLoadedUri ?: _uiState.value.documentInfo?.uri ?: return
        val totalPages = _uiState.value.totalPages
        if (totalPages <= 0) return

        outlineJob?.cancel()
        PdfOutlineParser.deleteOutlineCache(getApplication(), uri)

        _uiState.update {
            it.copy(
                outlineItems = emptyList(),
                outlineIndex = null,
                currentChapterTitle = null,
                outlineDiagnostic = "",
                isLoadingOutline = true,
                hasOutlineLoaded = false,
                indexExpandedStates = emptyMap(),
                indexScrollIndex = -1,
                indexScrollOffset = 0
            )
        }

        outlineJob = viewModelScope.launch(Dispatchers.IO) {
            val outline = PdfOutlineParser.parseOutline(getApplication(), uri, totalPages)
            val index = withContext(Dispatchers.Default) {
                OutlineHelper.buildIndex(outline, totalPages)
            }
            val currentPage = _uiState.value.currentPage
            val activeChapter = OutlineHelper.getCurrentChapter(currentPage, index)
            val diag = if (outline.isEmpty()) PdfOutlineParser.lastDiagnostic(uri) else ""
            _uiState.update {
                it.copy(
                    outlineItems = outline,
                    outlineIndex = index,
                    currentChapterTitle = activeChapter?.title,
                    outlineDiagnostic = diag,
                    isLoadingOutline = false,
                    hasOutlineLoaded = true
                )
            }
        }
    }

    fun onPageChanged(newPageIndex: Int) {
        val state = _uiState.value
        val total = state.totalPages
        if (total == 0) return
        val clamped = newPageIndex.coerceIn(0, total - 1)
        if (state.currentPage != clamped) {
            val chapter = state.outlineIndex?.let { OutlineHelper.getCurrentChapter(clamped, it) }
            _uiState.update {
                it.copy(
                    currentPage = clamped,
                    currentChapterTitle = chapter?.title,
                    isCurrentPageBookmarked = clamped in it.bookmarkedPages,
                    cacheUsageBytes = engine?.getCacheUsageBytes() ?: 0
                )
            }
            saveReadingProgress(clamped)
            recordUserActivity()
            triggerPrefetch(clamped)
        }
    }

    private fun triggerPrefetch(pageIdx: Int) {
        val currentEngine = engine ?: return
        val settings = appSettings.value
        val radius = settings.prefetchMode.pageRadius
        if (radius <= 0) return

        currentEngine.prefetchAdjacentPages(
            scope = viewModelScope,
            currentPage = pageIdx,
            radius = radius,
            targetWidth = 1200,
            targetHeight = 1600,
            sharpness = settings.sharpness,
            colorTone = settings.colorTone,
            highQualityPrintMode = settings.highQualityPrintMode
        )
    }

    fun jumpToPage(pageNumber1Indexed: Int) {
        val state = _uiState.value
        val target0Indexed = (pageNumber1Indexed - 1).coerceIn(0, (state.totalPages - 1).coerceAtLeast(0))
        val chapter = state.outlineIndex?.let { OutlineHelper.getCurrentChapter(target0Indexed, it) }
        _uiState.update {
            it.copy(
                currentPage = target0Indexed,
                currentChapterTitle = chapter?.title,
                isCurrentPageBookmarked = target0Indexed in it.bookmarkedPages,
                zoomScale = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f
            )
        }
        saveReadingProgress(target0Indexed)
        recordUserActivity()
        triggerPrefetch(target0Indexed)
    }

    fun toggleReadingMode() {
        _uiState.update {
            val nextMode = if (it.readingMode == ReadingMode.VERTICAL) ReadingMode.HORIZONTAL else ReadingMode.VERTICAL
            it.copy(
                readingMode = nextMode,
                isAutoScrolling = if (nextMode == ReadingMode.HORIZONTAL) false else it.isAutoScrolling,
                zoomScale = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f
            )
        }
        saveReadingProgress(_uiState.value.currentPage)
        recordUserActivity()
    }

    fun setReadingMode(mode: ReadingMode) {
        _uiState.update {
            it.copy(
                readingMode = mode,
                isAutoScrolling = if (mode == ReadingMode.HORIZONTAL) false else it.isAutoScrolling,
                zoomScale = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f
            )
        }
        saveReadingProgress(_uiState.value.currentPage)
        recordUserActivity()
    }

    fun stepPage(delta: Int) {
        navCommands.tryEmit(delta)
        recordUserActivity()
    }

    fun setAutoScrolling(active: Boolean) {
        _uiState.update { state ->
            state.copy(isAutoScrolling = active && state.readingMode == ReadingMode.VERTICAL)
        }
        recordUserActivity()
    }

    fun toggleAutoScrolling() {
        setAutoScrolling(!_uiState.value.isAutoScrolling)
    }

    /**
     * Manual Orientation toggle: Portrait <-> Landscape
     */
    fun cycleOrientation(): ReaderOrientation {
        val next = when (_uiState.value.currentOrientation) {
            ReaderOrientation.PORTRAIT -> ReaderOrientation.LANDSCAPE
            ReaderOrientation.LANDSCAPE -> ReaderOrientation.PORTRAIT
        }
        setOrientation(next)
        return next
    }

    fun setOrientation(orientation: ReaderOrientation) {
        settingsRepository.updateOrientation(orientation)
        _uiState.update { it.copy(currentOrientation = orientation) }
        recordUserActivity()
    }

    // Bookmark actions
    fun toggleBookmarkCurrentPage() {
        toggleBookmarkPage(_uiState.value.currentPage)
    }

    fun toggleBookmarkPage(pageIndex: Int) {
        val doc = _uiState.value.documentInfo ?: return
        val pageNum = pageIndex + 1
        val chapterTitle = if (pageIndex == _uiState.value.currentPage) {
            _uiState.value.currentChapterTitle?.takeIf { it.isNotBlank() }
        } else {
            _uiState.value.outlineIndex?.let { OutlineHelper.getCurrentChapter(pageIndex, it)?.title?.takeIf { t -> t.isNotBlank() } }
        }
        viewModelScope.launch {
            val added = repository.toggleBookmark(doc.uri.toString(), pageIndex, title = chapterTitle)
            val msg = if (added) "Page $pageNum bookmarked" else "Bookmark removed (Page $pageNum)"
            _uiState.update { it.copy(hudFeedbackMessage = msg) }
        }
        recordUserActivity()
    }

    fun renameBookmark(bookmark: PdfBookmarkEntity, newTitle: String) {
        val resolvedTitle = newTitle.trim().ifBlank { "Page ${bookmark.pageNumber}" }
        viewModelScope.launch {
            repository.updateBookmarkTitle(bookmark.id, resolvedTitle)
        }
        recordUserActivity()
    }

    fun deleteBookmark(bookmark: PdfBookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
            _uiState.update { it.copy(hudFeedbackMessage = "Bookmark removed (Page ${bookmark.pageNumber})") }
        }
        recordUserActivity()
    }

    fun showBookmarksSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showBookmarksSheet = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling
            )
        }
        recordUserActivity()
    }

    fun showIndexSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showIndexSheet = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling
            )
        }
        recordUserActivity()
    }

    fun showPageOverview(show: Boolean) {
        _uiState.update {
            it.copy(
                showPageOverview = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling
            )
        }
        recordUserActivity()
    }

    fun toggleIndexItemExpansion(id: String) {
        _uiState.update { state ->
            val current = state.indexExpandedStates[id] ?: true
            val updated = state.indexExpandedStates.toMutableMap()
            updated[id] = !current
            state.copy(indexExpandedStates = updated)
        }
    }

    fun setAllIndexExpanded(expandableIds: List<String>, isExpanded: Boolean) {
        _uiState.update { state ->
            val newMap = if (isExpanded) {
                emptyMap()
            } else {
                state.outlineIndex?.collapsedAllMap ?: expandableIds.associateWith { false }
            }
            state.copy(indexExpandedStates = newMap)
        }
    }

    fun saveIndexScrollPosition(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        _uiState.update {
            it.copy(
                indexScrollIndex = firstVisibleIndex,
                indexScrollOffset = firstVisibleOffset
            )
        }
    }

    fun setBrightness(brightness: Float) {
        val clamped = if (brightness < 0f) -1f else brightness.coerceIn(0.01f, 1.0f)
        _uiState.update { it.copy(brightnessLevel = clamped) }
        recordUserActivity()
    }

    fun setExtraDim(level: Float) {
        val clamped = if (level <= 0.005f) 0f else level.coerceIn(0.01f, 0.80f)
        _uiState.update { it.copy(extraDimLevel = clamped) }
        recordUserActivity()
    }

    fun setUnifiedBrightness(brightness: Float, extraDim: Float) {
        val clampedBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.01f, 1.0f)
        val clampedExtraDim = if (extraDim <= 0.005f) 0f else extraDim.coerceIn(0.01f, 0.80f)
        _uiState.update {
            it.copy(
                brightnessLevel = clampedBrightness,
                extraDimLevel = clampedExtraDim
            )
        }
        recordUserActivity()
    }

    fun clearHudFeedback() {
        _uiState.update { it.copy(hudFeedbackMessage = null) }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
        recordUserActivity()
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = fullscreen) }
        recordUserActivity()
    }

    fun updateDoubleTapZoomMode(mode: DoubleTapZoomMode) {
        settingsRepository.updateDoubleTapZoomMode(mode)
        recordUserActivity()
    }

    fun toggleDoubleTapZoom(tapX: Float, tapY: Float, viewWidth: Float, viewHeight: Float) {
        _uiState.update { current ->
            if (current.zoomScale > 1.15f) {
                // Reset to 1.0x (unzoomed)
                current.copy(zoomScale = 1.0f, panOffsetX = 0f, panOffsetY = 0f)
            } else {
                val zoomMode = appSettings.value.doubleTapZoomMode

                // Scale factor: Smart Auto uses 2.08x which fits a 42-46% column with ~10% margin padding.
                val targetScale = if (zoomMode == DoubleTapZoomMode.SMART_COLUMN) {
                    2.08f
                } else {
                    zoomMode.zoomScale
                }

                // Determine tap location: Left column or Right column
                val isLeftTap = tapX < (viewWidth * 0.5f)

                // Margin breathing room to guarantee text is NEVER cut off sideways (at least 16dp equivalent)
                val safeMarginPx = 16f * (viewWidth / 360f).coerceIn(1.0f, 3.0f)
                val maxAllowedPanX = ((viewWidth * (targetScale - 1f)) / 2f - safeMarginPx).coerceAtLeast(0f)

                val targetOffsetX = if (zoomMode == DoubleTapZoomMode.SMART_COLUMN) {
                    if (isLeftTap) {
                        // Left column center is around 28% of page width.
                        // Shift right so the left column is perfectly centered with breathing room on both edges.
                        val colCenterX = viewWidth * 0.28f
                        (viewWidth / 2f - colCenterX) * (targetScale - 1f)
                    } else {
                        // Right column center is around 72% of page width.
                        // Shift left so the right column is perfectly centered with breathing room on both edges.
                        val colCenterX = viewWidth * 0.72f
                        (viewWidth / 2f - colCenterX) * (targetScale - 1f)
                    }
                } else {
                    (viewWidth / 2f - tapX) * (targetScale - 1f)
                }

                // Clamp to safe margins so neither left nor right text column borders are ever cut off
                val clampedOffsetX = targetOffsetX.coerceIn(-maxAllowedPanX, maxAllowedPanX)

                // Vertical pan: center comfortably around tapY within page bounds
                val maxAllowedPanY = (viewHeight * (targetScale - 1f)) / 2f
                val targetOffsetY = (viewHeight / 2f - tapY) * (targetScale - 1f)
                val clampedOffsetY = targetOffsetY.coerceIn(-maxAllowedPanY, maxAllowedPanY)

                current.copy(
                    zoomScale = targetScale,
                    panOffsetX = clampedOffsetX,
                    panOffsetY = clampedOffsetY
                )
            }
        }
        recordUserActivity()
    }

    fun updateZoomAndPan(zoomMultiplier: Float, panX: Float, panY: Float) {
        _uiState.update { current ->
            val newScale = (current.zoomScale * zoomMultiplier).coerceIn(1.0f, 4.0f)
            if (newScale == 1.0f) {
                current.copy(zoomScale = 1.0f, panOffsetX = 0f, panOffsetY = 0f)
            } else {
                current.copy(
                    zoomScale = newScale,
                    panOffsetX = current.panOffsetX + panX,
                    panOffsetY = current.panOffsetY + panY
                )
            }
        }
        recordUserActivity()
    }

    fun resetZoom() {
        _uiState.update { it.copy(zoomScale = 1.0f, panOffsetX = 0f, panOffsetY = 0f) }
        recordUserActivity()
    }

    fun showJumpDialog(show: Boolean) {
        _uiState.update {
            it.copy(
                showJumpDialog = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling
            )
        }
        recordUserActivity()
    }

    fun showInfoDialog(show: Boolean) {
        _uiState.update {
            it.copy(
                showInfoDialog = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling
            )
        }
        recordUserActivity()
    }

    fun showSettingsSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showSettingsSheet = show,
                isAutoScrolling = if (show) false else it.isAutoScrolling,
                cacheUsageBytes = engine?.getCacheUsageBytes() ?: 0,
                maxCacheBytes = engine?.getMaxCacheBytes() ?: 0
            )
        }
        recordUserActivity()
    }

    fun updateSharpness(sharpness: RenderSharpness) {
        settingsRepository.updateSharpness(sharpness)
        engine?.clearCache()
        triggerPrefetch(_uiState.value.currentPage)
    }

    fun updatePrefetchMode(mode: PrefetchMode) {
        settingsRepository.updatePrefetchMode(mode)
        triggerPrefetch(_uiState.value.currentPage)
    }

    fun updateColorTone(tone: PageColorTone) {
        settingsRepository.updateColorTone(tone)
        engine?.clearCache()
        triggerPrefetch(_uiState.value.currentPage)
    }

    fun updatePageSpacing(spacing: PageSpacing) {
        settingsRepository.updatePageSpacing(spacing)
    }

    fun updateShowPageBadges(show: Boolean) {
        settingsRepository.updateShowPageBadges(show)
    }

    fun updateKeepAwakeOption(option: KeepAwakeOption) {
        settingsRepository.updateKeepAwakeOption(option)
        resetKeepAwakeTimer(option)
    }

    fun updateHighQualityPrintMode(enabled: Boolean) {
        settingsRepository.updateHighQualityPrintMode(enabled)
        engine?.clearCache()
        triggerPrefetch(_uiState.value.currentPage)
    }

    fun updateMarginTrim(trim: MarginTrim) {
        settingsRepository.updateMarginTrim(trim)
    }

    fun updateWarmFilterPercent(percent: Int) {
        settingsRepository.updateWarmFilterPercent(percent)
    }

    fun updateTapZoneNavigation(enabled: Boolean) {
        settingsRepository.updateTapZoneNavigation(enabled)
    }

    fun updateVolumeKeyNavigation(enabled: Boolean) {
        settingsRepository.updateVolumeKeyNavigation(enabled)
    }

    fun updateAutoScrollSpeed(speed: Int) {
        settingsRepository.updateAutoScrollSpeed(speed)
        recordUserActivity()
    }

    fun clearCache() {
        engine?.clearCache()
        viewModelScope.launch(Dispatchers.IO) {
            PdfDocumentStorage.purgeExcessStorageAndCaches(getApplication())
        }
        _uiState.update {
            it.copy(
                cacheUsageBytes = engine?.getCacheUsageBytes() ?: 0
            )
        }
    }

    fun setForeground(foreground: Boolean) {
        isReaderForeground = foreground
        if (foreground) {
            lastUserActivityMs = System.currentTimeMillis()
        }
    }

    fun recordUserActivity() {
        lastUserActivityMs = System.currentTimeMillis()
        val option = appSettings.value.keepAwakeOption
        if (option == KeepAwakeOption.ALWAYS_ON || (option != KeepAwakeOption.OFF && option != KeepAwakeOption.SYSTEM_DEFAULT && option.durationMs > 0L)) {
            _uiState.update { it.copy(isScreenWakeActive = true) }
        }
        resetKeepAwakeTimer(option)
    }

    private fun resetKeepAwakeTimer(option: KeepAwakeOption) {
        keepAwakeJob?.cancel()
        if (option == KeepAwakeOption.ALWAYS_ON) {
            _uiState.update { it.copy(isScreenWakeActive = true) }
            return
        }
        if (option == KeepAwakeOption.OFF || option == KeepAwakeOption.SYSTEM_DEFAULT || option.durationMs <= 0L) {
            _uiState.update { it.copy(isScreenWakeActive = false) }
            return
        }

        _uiState.update { it.copy(isScreenWakeActive = true) }
        keepAwakeJob = viewModelScope.launch {
            delay(option.durationMs)
            _uiState.update { it.copy(isScreenWakeActive = false) }
        }
    }

    private fun saveReadingProgress(pageIndex: Int) {
        val doc = _uiState.value.documentInfo ?: return
        val mode = _uiState.value.readingMode
        viewModelScope.launch {
            repository.updateReadingProgress(
                uri = doc.uri,
                lastReadPage = pageIndex,
                scrollOffset = 0f,
                readingMode = mode
            )
        }
    }

    /**
     * Requirement 5: Called when user resumes a PDF.
     */
    fun recordResume() {
        try {
            val doc = _uiState.value.documentInfo ?: return
            repository.recordDocumentResumed(
                uri = doc.uri,
                currentPage = _uiState.value.currentPage,
                scrollOffset = 0f,
                readingMode = _uiState.value.readingMode
            )
        } catch (e: Exception) {
            android.util.Log.e("PdfReaderViewModel", "Error recording resume", e)
        }
    }

    /**
     * Requirement 5: Called when user closes a PDF.
     */
    fun recordClose() {
        outlineJob?.cancel()
        outlineJob = null
        PdfOutlineParser.activeDocumentUri = null
        readingTickerJob?.cancel()
        readingTickerJob = null
        _uiState.update {
            it.copy(
                isAutoScrolling = false,
                brightnessLevel = -1f,
                extraDimLevel = 0f
            )
        }
        try {
            val doc = _uiState.value.documentInfo ?: return
            repository.recordDocumentResumed(
                uri = doc.uri,
                currentPage = _uiState.value.currentPage,
                scrollOffset = 0f,
                readingMode = _uiState.value.readingMode
            )
        } catch (e: Exception) {
            android.util.Log.e("PdfReaderViewModel", "Error recording close", e)
        }
    }

    override fun onCleared() {
        recordClose()
        super.onCleared()
        readingTickerJob?.cancel()
        outlineJob?.cancel()
        bookmarksJob?.cancel()
        keepAwakeJob?.cancel()
        engine?.close()
        engine = null
    }
}
