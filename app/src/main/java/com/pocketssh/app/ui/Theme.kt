package com.pocketssh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF65D6AD),
    onPrimary = Color(0xFF062019),
    secondary = Color(0xFF93B7FF),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF111820),
    surfaceVariant = Color(0xFF1A242E),
    onBackground = Color(0xFFE5EDF5),
    onSurface = Color(0xFFE5EDF5),
    onSurfaceVariant = Color(0xFFAAB8C5),
    error = Color(0xFFFF8A80),
)

@Composable
fun PocketSshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = MaterialTheme.typography, content = content)
}
