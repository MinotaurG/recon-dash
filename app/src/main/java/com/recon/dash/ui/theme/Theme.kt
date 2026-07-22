package com.recon.dash.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { LIGHT, DARK, AUTO }

private val DarkColorScheme = darkColorScheme(
    primary = GoldAccent,
    secondary = GoldAccent,
    tertiary = GoldAccent,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceElevated,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = OnSurface,
    onSurface = OnSurface,
    outline = Separator,
    error = Error,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF9A7B4F),
    secondary = Color(0xFF6B5B3E),
    tertiary = Color(0xFF4A6741),
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFF3EFE8),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1A16),
    onSurface = Color(0xFF1C1A16),
    outline = Color(0xFFE2DDD5),
    error = Color(0xFFBA1A1A),
)

object ThemeState {
    var mode by mutableStateOf(ThemeMode.AUTO)
    var forceRiding by mutableStateOf(false)
}

@Composable
fun ReconDashTheme(
    content: @Composable () -> Unit,
) {
    val useDark = when {
        ThemeState.forceRiding -> true
        ThemeState.mode == ThemeMode.DARK -> true
        ThemeState.mode == ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
