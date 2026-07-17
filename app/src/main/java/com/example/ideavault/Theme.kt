package com.example.ideavault

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B53A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFF5E5D72),
    background = Color(0xFFF9F7FF),
    surface = Color(0xFFF9F7FF),
    surfaceVariant = Color(0xFFE6E0EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    primaryContainer = Color(0xFF433A8D),
    secondary = Color(0xFFC8C5DC),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
)

@Composable
fun IdeaVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
