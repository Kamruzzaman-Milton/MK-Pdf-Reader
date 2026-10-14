package com.example.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.PdfRecentItem
import com.example.data.pdf.SamplePdfGenerator
import com.example.data.repository.PdfRepository
import com.example.data.settings.AppSettings
import com.example.data.settings.AppThemeMode
import com.example.data.settings.RecentViewMode
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
