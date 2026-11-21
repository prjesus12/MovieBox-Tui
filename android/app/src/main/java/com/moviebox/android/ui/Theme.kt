package com.moviebox.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val background = Color(0xFF0B0B0F)
    val surface = Color(0xFF17171E)
    val surfaceHigh = Color(0xFF23232C)
    val accent = Color(0xFFE50914)
    val text = Color(0xFFF2F2F5)
    val subtle = Color(0xFF9A9AA6)
}

@Composable
fun MovieBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.background,
            onSurface = Palette.text,
            surfaceVariant = Palette.surface,
            onSurfaceVariant = Palette.subtle,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerLow = Palette.surface,
            secondaryContainer = Palette.surfaceHigh,
            onSecondaryContainer = Palette.text,
        ),
        content = content,
    )
}
