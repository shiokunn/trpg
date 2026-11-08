package io.github.shiokunn.trpg.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink900,
    primaryContainer = GoldDim,
    onPrimaryContainer = Parchment,
    secondary = Sage,
    onSecondary = Ink900,
    secondaryContainer = Ink600,
    onSecondaryContainer = Parchment,
    tertiary = Crimson,
    onTertiary = Ink900,
    background = Ink900,
    onBackground = Parchment,
    surface = Ink800,
    onSurface = Parchment,
    surfaceVariant = Ink700,
    onSurfaceVariant = ParchmentDim,
    outline = Ink400,
    outlineVariant = Ink600,
    error = Crimson,
    onError = Ink900,
    errorContainer = Color(0xFF3A1E1E),
    onErrorContainer = Color(0xFFF2C6C6),
)

private val LightColors = lightColorScheme(
    primary = PaperGold,
    onPrimary = Paper50,
    primaryContainer = Paper200,
    onPrimaryContainer = PaperInk,
    secondary = PaperSage,
    onSecondary = Paper50,
    secondaryContainer = Paper200,
    onSecondaryContainer = PaperInk,
    tertiary = PaperCrimson,
    onTertiary = Paper50,
    background = Paper50,
    onBackground = PaperInk,
    surface = Paper100,
    onSurface = PaperInk,
    surfaceVariant = Paper200,
    onSurfaceVariant = PaperInkDim,
    outline = PaperInkDim,
    outlineVariant = Paper200,
    error = PaperCrimson,
    onError = Paper50,
    errorContainer = Color(0xFFF2D6D6),
    onErrorContainer = PaperCrimson,
)

@Composable
fun TrpgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = TrpgTypography,
        content = content,
    )
}
