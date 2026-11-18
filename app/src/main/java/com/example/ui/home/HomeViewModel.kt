package com.example.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.PdfRecentItem
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.pdf.SamplePdfGenerator
import com.example.data.repository.PdfRepository
import com.example.data.settings.AppAccent
import com.example.data.settings.AppSettings
import com.example.data.settings.AppThemeMode
import com.example.data.settings.LibraryFilter
import com.example.data.settings.LibrarySort
import com.example.data.settings.RecentViewMode
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository = run {
        val db = AppDatabase.getDatabase(application)
        PdfRepository(application, db.pdfRecentDao(), db.pdfBookmarkDao())
    }

    private val settingsRepository: SettingsRepository = SettingsRepository.getInstance(application)
    val appSettings: StateFlow<AppSettings> = settingsRepository.settings

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.syncRecentsOnStartup()
        }
    }

    val recentPdfs: StateFlow<List<PdfRecentItem>> = repository.recentPdfs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = repository.getRecentPDFs()
        )

    val visibleRecents: StateFlow<List<PdfRecentItem>> = combine(recentPdfs, appSettings) { items, settings ->
        val filtered = when (settings.libraryFilter) {
            LibraryFilter.ALL -> items
            LibraryFilter.FINISHED -> items.filter { it.pageCount > 0 && it.lastReadPage >= it.pageCount - 1 }
            LibraryFilter.IN_PROGRESS -> items.filter {
                val isFinished = it.pageCount > 0 && it.lastReadPage >= it.pageCount - 1
                it.lastReadPage > 0 && !isFinished
            }
            LibraryFilter.FAVORITES -> items.filter { item ->
                val key = PdfDocumentStorage.getDocumentKey(Uri.parse(item.uriString))
                key in settings.favoriteKeys
            }
        }
        when (settings.librarySort) {
            LibrarySort.RECENT -> filtered.sortedByDescending { it.recent_timestamp }
            LibrarySort.NAME -> filtered.sortedBy { it.fileName.lowercase(Locale.ROOT) }
            LibrarySort.SIZE -> filtered.sortedByDescending { it.fileSize }
            LibrarySort.PROGRESS -> filtered.sortedByDescending {
                if (it.pageCount > 0) it.lastReadPage.toFloat() / it.pageCount.toFloat() else 0f
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun refreshRecents() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.reloadRecentsFromDirectory()
        }
    }

    private val _isGeneratingSample = MutableStateFlow(false)
    val isGeneratingSample: StateFlow<Boolean> = _isGeneratingSample.asStateFlow()

    fun setRecentViewMode(viewMode: RecentViewMode) {
        settingsRepository.updateRecentViewMode(viewMode)
    }

    fun toggleRecentViewMode() {
        val current = appSettings.value.recentViewMode
        val next = if (current == RecentViewMode.GRID) RecentViewMode.LIST else RecentViewMode.GRID
        settingsRepository.updateRecentViewMode(next)
    }

    fun setAppThemeMode(themeMode: AppThemeMode) {
        settingsRepository.updateAppThemeMode(themeMode)
    }

    fun setAppAccent(accent: AppAccent) {
        settingsRepository.updateAppAccent(accent)
    }

    fun setLibrarySort(sort: LibrarySort) {
        settingsRepository.updateLibrarySort(sort)
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        settingsRepository.updateLibraryFilter(filter)
    }

    fun toggleFavorite(item: PdfRecentItem) {
        val key = PdfDocumentStorage.getDocumentKey(Uri.parse(item.uriString))
        settingsRepository.toggleFavoriteKey(key)
    }

    fun removeRecent(pathOrUri: String) {
        viewModelScope.launch {
            repository.deleteRecent(pathOrUri)
        }
    }

    fun clearAllRecents() {
        viewModelScope.launch {
            repository.clearAllRecents()
        }
    }

    fun openSamplePdf(onPdfReady: (Uri) -> Unit) {
        viewModelScope.launch {
            _isGeneratingSample.value = true
            try {
                val uri = SamplePdfGenerator.getOrCreateSamplePdf(getApplication())
                onPdfReady(uri)
            } finally {
                _isGeneratingSample.value = false
            }
        }
    }
}
