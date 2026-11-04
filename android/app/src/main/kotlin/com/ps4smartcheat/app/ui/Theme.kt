package com.ps4smartcheat.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ps4smartcheat.app.data.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF7FD6C2),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF2A2F3A),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0),
    primaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF006A5B),
    tertiary = Color(0xFF8B5000),
)

@Composable
fun SmartCheatTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
