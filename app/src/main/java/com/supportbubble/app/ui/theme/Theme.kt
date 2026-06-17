package com.supportbubble.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = BubblePrimary,
    primaryContainer = BubblePrimaryContainer,
    onPrimaryContainer = BubbleOnPrimaryContainer,
    secondary = BubbleSecondary,
    background = BubbleBackground,
    surface = BubbleSurface,
    error = BubbleError,
)

private val DarkColors = darkColorScheme(
    primary = BubblePrimaryContainer,
    onPrimary = BubbleOnPrimaryContainer,
    primaryContainer = BubblePrimary,
    secondary = BubbleSecondary,
    error = BubbleError,
)

@Composable
fun SupportBubbleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColor && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
