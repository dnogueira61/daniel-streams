package com.dlive.ptstream.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val primary: Color,
    val primaryDark: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary
)

fun getAppThemeColors(themeMode: String = "DARK", accentColor: String = "RED"): AppThemeColors {
    val isOled = themeMode.equals("OLED", ignoreCase = true)
    val bg = if (isOled) BackgroundOled else BackgroundDark
    val surface = if (isOled) SurfaceOled else SurfaceDark
    val surfaceVariant = if (isOled) SurfaceVariantOled else SurfaceVariantDark
    val border = if (isOled) BorderOled else BorderDark

    val (prim, primDark) = when (accentColor.uppercase()) {
        "CYAN" -> Pair(AccentCyan, AccentCyanDark)
        "PURPLE" -> Pair(AccentPurple, AccentPurpleDark)
        "GREEN" -> Pair(AccentGreenBright, AccentGreenDark)
        else -> Pair(RedPrimary, RedDark)
    }

    return AppThemeColors(
        primary = prim,
        primaryDark = primDark,
        background = bg,
        surface = surface,
        surfaceVariant = surfaceVariant,
        border = border
    )
}

val LocalCustomColors = staticCompositionLocalOf {
    getAppThemeColors("DARK", "RED")
}

@Composable
fun DLivePTStreamTheme(
    themeMode: String = "DARK",
    accentColor: String = "RED",
    content: @Composable () -> Unit
) {
    val customColors = getAppThemeColors(themeMode, accentColor)
    val colorScheme = darkColorScheme(
        primary = customColors.primary,
        onPrimary = TextPrimary,
        secondary = AccentGold,
        background = customColors.background,
        surface = customColors.surface,
        surfaceVariant = customColors.surfaceVariant,
        onBackground = TextPrimary,
        onSurface = TextPrimary,
        onSurfaceVariant = TextSecondary
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
