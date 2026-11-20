package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RenderSharpness(val scaleMultiplier: Float, val displayName: String, val description: String) {
    BALANCED(2.2f, "Balanced (2.2x)", "Fast render speed, energy efficient"),
    HIGH_CLARITY(3.2f, "High Clarity (3.2x)", "Crisp vector text, optimized for modern screens"),
    ULTRA_HD(4.8f, "Ultra HD (4.8x)", "100% razor-sharp fine typography, formulas & details")
}

enum class PrefetchMode(val pageRadius: Int, val displayName: String, val description: String) {
    OFF(0, "Disabled", "Only renders currently visible pages"),
    STANDARD(1, "Standard (±1 Page)", "Pre-renders adjacent pages in background"),
    AGGRESSIVE(2, "Aggressive (±2 Pages)", "Instant smooth scrolling with zero lag")
}

enum class PageColorTone(val displayName: String, val description: String) {
    ORIGINAL("Default", "Standard clean white paper"),
    SEPIA("Warm Sepia", "Eye-friendly parchment tone for long reading"),
    INVERTED_DARK("Night Inverted", "OLED dark mode with high contrast text"),
    HIGH_CONTRAST("Monochrome", "High contrast black and white")
}

enum class PageSpacing(val dpValue: Dp, val displayName: String) {
    COMPACT(6.dp, "Compact (6dp)"),
    STANDARD(14.dp, "Standard (14dp)"),
    SPACIOUS(24.dp, "Spacious (24dp)")
}

enum class KeepAwakeOption(val durationMs: Long, val displayName: String) {
    OFF(0L, "Off"),
    MINUTES_5(5 * 60 * 1000L, "5 Minutes"),
    MINUTES_15(15 * 60 * 1000L, "15 Minutes"),
    MINUTES_30(30 * 60 * 1000L, "30 Minutes"),
    ALWAYS_ON(-1L, "Always On"),
    SYSTEM_DEFAULT(0L, "Off")
}

enum class ReaderOrientation(val displayName: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class AppThemeMode(val displayName: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED Dark")
}

enum class RecentViewMode(val displayName: String) {
    GRID("Grid View"),
    LIST("List View")
}

enum class DoubleTapZoomMode(val zoomScale: Float, val displayName: String, val description: String) {
    SMART_COLUMN(0f, "Smart Column Fit (Auto)", "Detects column width and fits perfectly without cutoff"),
    COMPACT_1_8X(1.85f, "1.85x Compact", "Broader overview, fits wider layouts"),
    STANDARD_2_1X(2.08f, "2.08x Standard 2-Column", "Balanced fit for most medical and academic textbooks"),
    LARGE_2_3X(2.30f, "2.30x Large Text", "Comfortable reading for fine print & dense tables"),
    MAX_2_6X(2.60f, "2.60x Ultra Close", "High magnification for fine figures and diagrams")
}

enum class MarginTrim(val horizontalFraction: Float, val displayName: String) {
    OFF(0f, "Off"),
    LIGHT(0.04f, "Light"),
    MEDIUM(0.08f, "Medium"),
    STRONG(0.12f, "Strong");

    val verticalFraction: Float
        get() = horizontalFraction / 2f
}

enum class AppAccent(val displayName: String) {
    SCARLET("Scarlet"),
    OCEAN("Ocean"),
    EMERALD("Emerald"),
    VIOLET("Violet"),
    AMBER("Amber"),
    DYNAMIC("Dynamic")
}

enum class LibrarySort(val displayName: String) {
    RECENT("Recent"),
    NAME("Name"),
    SIZE("Size"),
    PROGRESS("Progress")
}

enum class LibraryFilter(val displayName: String) {
    ALL("All"),
    IN_PROGRESS("In Progress"),
    FINISHED("Finished"),
    FAVORITES("Favorites")
}

enum class BrowserSort(val displayName: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    DATE_NEW("Newest first"),
    DATE_OLD("Oldest first"),
    SIZE_LARGE("Largest first"),
    SIZE_SMALL("Smallest first")
}

data class AppSettings(
    val sharpness: RenderSharpness = RenderSharpness.HIGH_CLARITY,
    val prefetchMode: PrefetchMode = PrefetchMode.AGGRESSIVE,
    val colorTone: PageColorTone = PageColorTone.ORIGINAL,
    val pageSpacing: PageSpacing = PageSpacing.STANDARD,
    val showPageBadges: Boolean = true,
    val keepAwakeOption: KeepAwakeOption = KeepAwakeOption.MINUTES_15,
    val orientation: ReaderOrientation = ReaderOrientation.PORTRAIT,
    val doubleTapZoomMode: DoubleTapZoomMode = DoubleTapZoomMode.SMART_COLUMN,
    val doubleTapZoomLevel: Float = 2.08f,
    val highQualityPrintMode: Boolean = true,
    val appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val recentViewMode: RecentViewMode = RecentViewMode.GRID,
    val marginTrim: MarginTrim = MarginTrim.OFF,
    val warmFilterPercent: Int = 0,
    val tapZoneNavigation: Boolean = false,
    val volumeKeyNavigation: Boolean = false,
    val autoScrollSpeed: Int = 3,
    val appAccent: AppAccent = AppAccent.SCARLET,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val favoriteKeys: Set<String> = emptySet(),
    val browserSort: BrowserSort = BrowserSort.NAME_ASC,
    val browserFoldersFirst: Boolean = true,
    val libraryAutoRescan: Boolean = true
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mk_pdf_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val sharpnessName = prefs.getString(KEY_SHARPNESS, RenderSharpness.HIGH_CLARITY.name)
        val prefetchName = prefs.getString(KEY_PREFETCH, PrefetchMode.AGGRESSIVE.name)
        val colorToneName = prefs.getString(KEY_COLOR_TONE, PageColorTone.ORIGINAL.name)
        val spacingName = prefs.getString(KEY_SPACING, PageSpacing.STANDARD.name)
        val showBadges = prefs.getBoolean(KEY_SHOW_BADGES, true)
        val keepAwakeName = prefs.getString(KEY_KEEP_AWAKE, KeepAwakeOption.MINUTES_15.name)
        val orientationName = prefs.getString(KEY_ORIENTATION, ReaderOrientation.PORTRAIT.name)
        val zoomModeName = prefs.getString(KEY_ZOOM_MODE, DoubleTapZoomMode.SMART_COLUMN.name)
        val zoomLevel = prefs.getFloat(KEY_ZOOM_LEVEL, 2.08f)
        val printMode = prefs.getBoolean(KEY_PRINT_MODE, true)
        val themeName = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
        val viewModeName = prefs.getString(KEY_VIEW_MODE, RecentViewMode.GRID.name)
        val marginTrimName = prefs.getString(KEY_MARGIN_TRIM, MarginTrim.OFF.name)
        val warmFilterPercent = prefs.getInt(KEY_WARM_FILTER_PERCENT, 0).coerceIn(0, 60)
        val tapZoneNav = prefs.getBoolean(KEY_TAP_ZONE_NAV, false)
        val volumeKeyNav = prefs.getBoolean(KEY_VOLUME_KEY_NAV, false)
        val autoScrollSpeed = prefs.getInt(KEY_AUTO_SCROLL_SPEED, 3).coerceIn(1, 20)
        val accentName = prefs.getString(KEY_APP_ACCENT, AppAccent.SCARLET.name)
        val librarySortName = prefs.getString(KEY_LIBRARY_SORT, LibrarySort.RECENT.name)
        val libraryFilterName = prefs.getString(KEY_LIBRARY_FILTER, LibraryFilter.ALL.name)
        val favoriteKeys = prefs.getStringSet(KEY_FAVORITE_KEYS, emptySet())?.toSet() ?: emptySet()
        val browserSortName = prefs.getString(KEY_BROWSER_SORT, BrowserSort.NAME_ASC.name)
        val browserFoldersFirst = prefs.getBoolean(KEY_BROWSER_FOLDERS_FIRST, true)
        val libraryAutoRescan = prefs.getBoolean(KEY_LIBRARY_AUTO_RESCAN, true)

        return AppSettings(
            sharpness = parseEnum(sharpnessName, RenderSharpness.HIGH_CLARITY),
            prefetchMode = parseEnum(prefetchName, PrefetchMode.AGGRESSIVE),
            colorTone = parseEnum(colorToneName, PageColorTone.ORIGINAL),
            pageSpacing = parseEnum(spacingName, PageSpacing.STANDARD),
            showPageBadges = showBadges,
            keepAwakeOption = parseEnum(keepAwakeName, KeepAwakeOption.MINUTES_15),
            orientation = parseEnum(orientationName, ReaderOrientation.PORTRAIT),
            doubleTapZoomMode = parseEnum(zoomModeName, DoubleTapZoomMode.SMART_COLUMN),
            doubleTapZoomLevel = zoomLevel,
            highQualityPrintMode = printMode,
            appThemeMode = parseEnum(themeName, AppThemeMode.SYSTEM),
            recentViewMode = parseEnum(viewModeName, RecentViewMode.GRID),
            marginTrim = parseEnum(marginTrimName, MarginTrim.OFF),
            warmFilterPercent = warmFilterPercent,
            tapZoneNavigation = tapZoneNav,
            volumeKeyNavigation = volumeKeyNav,
            autoScrollSpeed = autoScrollSpeed,
            appAccent = parseEnum(accentName, AppAccent.SCARLET),
            librarySort = parseEnum(librarySortName, LibrarySort.RECENT),
            libraryFilter = parseEnum(libraryFilterName, LibraryFilter.ALL),
            favoriteKeys = favoriteKeys,
            browserSort = parseEnum(browserSortName, BrowserSort.NAME_ASC),
            browserFoldersFirst = browserFoldersFirst,
            libraryAutoRescan = libraryAutoRescan
        )
    }

    fun updateAppThemeMode(themeMode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, themeMode.name).apply()
        _settings.value = _settings.value.copy(appThemeMode = themeMode)
    }

    fun updateRecentViewMode(viewMode: RecentViewMode) {
        prefs.edit().putString(KEY_VIEW_MODE, viewMode.name).apply()
        _settings.value = _settings.value.copy(recentViewMode = viewMode)
    }

    fun updateSharpness(sharpness: RenderSharpness) {
        prefs.edit().putString(KEY_SHARPNESS, sharpness.name).apply()
        _settings.value = _settings.value.copy(sharpness = sharpness)
    }

    fun updatePrefetchMode(mode: PrefetchMode) {
        prefs.edit().putString(KEY_PREFETCH, mode.name).apply()
        _settings.value = _settings.value.copy(prefetchMode = mode)
    }

    fun updateColorTone(tone: PageColorTone) {
        prefs.edit().putString(KEY_COLOR_TONE, tone.name).apply()
        _settings.value = _settings.value.copy(colorTone = tone)
    }

    fun updatePageSpacing(spacing: PageSpacing) {
        prefs.edit().putString(KEY_SPACING, spacing.name).apply()
        _settings.value = _settings.value.copy(pageSpacing = spacing)
    }

    fun updateShowPageBadges(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BADGES, show).apply()
        _settings.value = _settings.value.copy(showPageBadges = show)
    }

    fun updateKeepAwakeOption(option: KeepAwakeOption) {
        prefs.edit().putString(KEY_KEEP_AWAKE, option.name).apply()
        _settings.value = _settings.value.copy(keepAwakeOption = option)
    }

    fun updateOrientation(orientation: ReaderOrientation) {
        prefs.edit().putString(KEY_ORIENTATION, orientation.name).apply()
        _settings.value = _settings.value.copy(orientation = orientation)
    }

    fun updateDoubleTapZoomMode(mode: DoubleTapZoomMode) {
        prefs.edit().putString(KEY_ZOOM_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(doubleTapZoomMode = mode)
    }

    fun updateDoubleTapZoomLevel(zoom: Float) {
        prefs.edit().putFloat(KEY_ZOOM_LEVEL, zoom).apply()
        _settings.value = _settings.value.copy(doubleTapZoomLevel = zoom)
    }

    fun updateHighQualityPrintMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRINT_MODE, enabled).apply()
        _settings.value = _settings.value.copy(highQualityPrintMode = enabled)
    }

    fun updateMarginTrim(trim: MarginTrim) {
        prefs.edit().putString(KEY_MARGIN_TRIM, trim.name).apply()
        _settings.value = _settings.value.copy(marginTrim = trim)
    }

    fun updateWarmFilterPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 60)
        prefs.edit().putInt(KEY_WARM_FILTER_PERCENT, clamped).apply()
        _settings.value = _settings.value.copy(warmFilterPercent = clamped)
    }

    fun updateTapZoneNavigation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TAP_ZONE_NAV, enabled).apply()
        _settings.value = _settings.value.copy(tapZoneNavigation = enabled)
    }

    fun updateVolumeKeyNavigation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOLUME_KEY_NAV, enabled).apply()
        _settings.value = _settings.value.copy(volumeKeyNavigation = enabled)
    }

    fun updateAutoScrollSpeed(speed: Int) {
        val clamped = speed.coerceIn(1, 20)
        prefs.edit().putInt(KEY_AUTO_SCROLL_SPEED, clamped).apply()
        _settings.value = _settings.value.copy(autoScrollSpeed = clamped)
    }

    fun updateAppAccent(accent: AppAccent) {
        prefs.edit().putString(KEY_APP_ACCENT, accent.name).apply()
        _settings.value = _settings.value.copy(appAccent = accent)
    }

    fun updateLibrarySort(sort: LibrarySort) {
        prefs.edit().putString(KEY_LIBRARY_SORT, sort.name).apply()
        _settings.value = _settings.value.copy(librarySort = sort)
    }

    fun updateLibraryFilter(filter: LibraryFilter) {
        prefs.edit().putString(KEY_LIBRARY_FILTER, filter.name).apply()
        _settings.value = _settings.value.copy(libraryFilter = filter)
    }

    fun toggleFavoriteKey(docKey: String) {
        val updated = HashSet(_settings.value.favoriteKeys)
        if (!updated.add(docKey)) {
            updated.remove(docKey)
        }
        val immutableCopy = updated.toSet()
        prefs.edit().putStringSet(KEY_FAVORITE_KEYS, HashSet(immutableCopy)).apply()
        _settings.value = _settings.value.copy(favoriteKeys = immutableCopy)
    }

    fun addFavoriteKey(docKey: String) {
        if (docKey in _settings.value.favoriteKeys) return
        val updated = HashSet(_settings.value.favoriteKeys).apply { add(docKey) }
        val immutableCopy = updated.toSet()
        prefs.edit().putStringSet(KEY_FAVORITE_KEYS, HashSet(immutableCopy)).apply()
        _settings.value = _settings.value.copy(favoriteKeys = immutableCopy)
    }

    fun removeFavoriteKey(docKey: String) {
        if (docKey !in _settings.value.favoriteKeys) return
        val updated = HashSet(_settings.value.favoriteKeys).apply { remove(docKey) }
        val immutableCopy = updated.toSet()
        prefs.edit().putStringSet(KEY_FAVORITE_KEYS, HashSet(immutableCopy)).apply()
        _settings.value = _settings.value.copy(favoriteKeys = immutableCopy)
    }

    fun reloadFromPrefs() {
        _settings.value = loadSettings()
    }

    fun updateBrowserSort(sort: BrowserSort) {
        prefs.edit().putString(KEY_BROWSER_SORT, sort.name).apply()
        _settings.value = _settings.value.copy(browserSort = sort)
    }

    fun updateBrowserFoldersFirst(foldersFirst: Boolean) {
        prefs.edit().putBoolean(KEY_BROWSER_FOLDERS_FIRST, foldersFirst).apply()
        _settings.value = _settings.value.copy(browserFoldersFirst = foldersFirst)
    }

    fun updateLibraryAutoRescan(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIBRARY_AUTO_RESCAN, enabled).apply()
        _settings.value = _settings.value.copy(libraryAutoRescan = enabled)
    }

    private inline fun <reified T : Enum<T>> parseEnum(name: String?, default: T): T {
        if (name == null) return default
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: Exception) {
            default
        }
    }

    companion object {
        private const val KEY_SHARPNESS = "render_sharpness"
        private const val KEY_PREFETCH = "prefetch_mode"
        private const val KEY_COLOR_TONE = "color_tone"
        private const val KEY_SPACING = "page_spacing"
        private const val KEY_SHOW_BADGES = "show_page_badges"
        private const val KEY_KEEP_AWAKE = "keep_awake_option"
        private const val KEY_ORIENTATION = "reader_orientation"
        private const val KEY_ZOOM_MODE = "double_tap_zoom_mode"
        private const val KEY_ZOOM_LEVEL = "double_tap_zoom_level"
        private const val KEY_PRINT_MODE = "high_quality_print_mode"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_VIEW_MODE = "recent_view_mode"
        private const val KEY_MARGIN_TRIM = "margin_trim"
        private const val KEY_WARM_FILTER_PERCENT = "warm_filter_percent"
        private const val KEY_TAP_ZONE_NAV = "tap_zone_navigation"
        private const val KEY_VOLUME_KEY_NAV = "volume_key_navigation"
        private const val KEY_AUTO_SCROLL_SPEED = "auto_scroll_speed"
        private const val KEY_APP_ACCENT = "app_accent"
        private const val KEY_LIBRARY_SORT = "library_sort"
        private const val KEY_LIBRARY_FILTER = "library_filter"
        private const val KEY_FAVORITE_KEYS = "favorite_keys"
        private const val KEY_BROWSER_SORT = "browser_sort"
        private const val KEY_BROWSER_FOLDERS_FIRST = "browser_folders_first"
        private const val KEY_LIBRARY_AUTO_RESCAN = "library_auto_rescan"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
