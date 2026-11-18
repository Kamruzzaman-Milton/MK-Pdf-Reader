package com.example.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.example.data.settings.AppAccent
import com.example.data.settings.AppThemeMode

private val AmoledDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFFF5252),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF38080B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFF8A80),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2E0E12),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF101010),
    onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070707),
    surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF202020),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1A1A1A)
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = PrimaryRedDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF5C0003),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = AccentCoralLight,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4A1015),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFE2C46D),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PrimaryRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = SecondaryRed,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEBEE),
    onSecondaryContainer = Color(0xFF5C0003),
    tertiary = Color(0xFF705D00),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
  )

fun applyAccentToColorScheme(
  scheme: ColorScheme,
  accent: AppAccent,
  isDark: Boolean,
  isAmoled: Boolean,
  context: Context,
): ColorScheme {
  if (accent == AppAccent.SCARLET) return scheme

  if (accent == AppAccent.DYNAMIC) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      return if (isAmoled) {
        dynamicScheme.copy(
          background = scheme.background,
          surface = scheme.surface,
          surfaceContainerLowest = scheme.surfaceContainerLowest,
          surfaceContainerLow = scheme.surfaceContainerLow,
          surfaceContainer = scheme.surfaceContainer,
          surfaceContainerHigh = scheme.surfaceContainerHigh,
          surfaceContainerHighest = scheme.surfaceContainerHighest,
        )
      } else {
        dynamicScheme
      }
    }
    return scheme
  }

  val primary = when (accent) {
    AppAccent.OCEAN -> if (isDark) Color(0xFF64B5F6) else Color(0xFF1E88E5)
    AppAccent.EMERALD -> if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    AppAccent.VIOLET -> if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
    AppAccent.AMBER -> if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
    AppAccent.SCARLET, AppAccent.DYNAMIC -> scheme.primary
  }
  val onPrimary = if (isDark) Color.Black else Color.White
  val primaryContainer = primary.copy(alpha = if (isDark) 0.24f else 0.14f).compositeOver(scheme.surface)
  val onPrimaryContainer = if (isDark) {
    lerp(primary, Color.White, 0.72f)
  } else {
    lerp(primary, Color.Black, 0.55f)
  }
  val secondary = if (isDark) {
    lerp(primary, Color.White, 0.20f)
  } else {
    lerp(primary, Color.Black, 0.20f)
  }
  val secondaryContainer = primary.copy(alpha = if (isDark) 0.16f else 0.10f).compositeOver(scheme.surface)

  return scheme.copy(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onPrimary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onPrimaryContainer,
  )
}

@Composable
fun MyApplicationTheme(
  appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
  appAccent: AppAccent = AppAccent.SCARLET,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val isSystemDark = isSystemInDarkTheme()
  val isAmoled = appThemeMode == AppThemeMode.AMOLED
  val isDark = when (appThemeMode) {
    AppThemeMode.AMOLED, AppThemeMode.DARK -> true
    AppThemeMode.LIGHT -> false
    AppThemeMode.SYSTEM -> isSystemDark
  }
  val baseScheme =
    when (appThemeMode) {
      AppThemeMode.AMOLED -> AmoledDarkColorScheme
      AppThemeMode.DARK -> DarkColorScheme
      AppThemeMode.LIGHT -> LightColorScheme
      AppThemeMode.SYSTEM -> {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (isSystemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isSystemDark) {
          DarkColorScheme
        } else {
          LightColorScheme
        }
      }
    }
  val colorScheme = applyAccentToColorScheme(
    scheme = baseScheme,
    accent = appAccent,
    isDark = isDark,
    isAmoled = isAmoled,
    context = context,
  )

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
