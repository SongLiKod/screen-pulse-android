package com.screenpulse.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ScreenPulseColors.BrandGreen,
    onPrimary = Color.White,
    primaryContainer = ScreenPulseColors.LightGreenBg,
    onPrimaryContainer = ScreenPulseColors.BrandGreen,
    secondary = ScreenPulseColors.AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = ScreenPulseColors.LightGreenBg,
    onSecondaryContainer = ScreenPulseColors.AccentGreen,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121),
    surfaceVariant = ScreenPulseColors.LightGreenBg,
    onSurfaceVariant = Color(0xFF757575),
    error = ScreenPulseColors.ErrorRed,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = ScreenPulseColors.BrandGreenDark,
    onPrimary = Color.White,
    primaryContainer = ScreenPulseColors.DarkGreenBg,
    onPrimaryContainer = ScreenPulseColors.AccentGreenDark,
    secondary = ScreenPulseColors.AccentGreenDark,
    onSecondary = Color.White,
    secondaryContainer = ScreenPulseColors.DarkGreenBg,
    onSecondaryContainer = ScreenPulseColors.AccentGreenDark,
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = ScreenPulseColors.DarkGreenBg,
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = ScreenPulseColors.ErrorRedDark,
    onError = Color.Black,
)

@Composable
fun ScreenPulseTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
