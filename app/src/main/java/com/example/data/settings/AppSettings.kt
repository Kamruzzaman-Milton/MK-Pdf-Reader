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
    val recentViewMode: RecentViewMode = RecentViewMode.GRID
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
            recentViewMode = parseEnum(viewModeName, RecentViewMode.GRID)
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

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
