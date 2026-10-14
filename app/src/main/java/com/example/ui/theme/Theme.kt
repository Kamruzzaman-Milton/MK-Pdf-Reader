package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun MyApplicationTheme(
  appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val isSystemDark = isSystemInDarkTheme()
  val colorScheme =
    when (appThemeMode) {
      AppThemeMode.AMOLED -> AmoledDarkColorScheme
      AppThemeMode.DARK -> DarkColorScheme
      AppThemeMode.LIGHT -> LightColorScheme
      AppThemeMode.SYSTEM -> {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val context = LocalContext.current
          if (isSystemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isSystemDark) {
          DarkColorScheme
        } else {
          LightColorScheme
        }
      }
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
