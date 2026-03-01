package com.bismaya.mediadl.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MediaDLDarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = TextPrimary,
    primaryContainer = VioletDim,
    secondary = Cyan,
    onSecondary = TextPrimary,
    secondaryContainer = CyanDim,
    tertiary = Emerald,
    tertiaryContainer = EmeraldDim,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink2,
    onSurface = TextPrimary,
    surfaceVariant = Ink3,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorder,
    outlineVariant = SurfaceHighlight,
    error = Rose,
    onError = TextPrimary,
)

@Composable
fun MediaDLTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = MediaDLDarkScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = Ink.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}