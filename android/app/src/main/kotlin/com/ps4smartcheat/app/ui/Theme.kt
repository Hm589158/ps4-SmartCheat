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
    secondaryContainer = Color(0xFF1C4A42),
    onSecondaryContainer = Color(0xFFA6F2DE),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF5A3A0E),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF2A2F3A),
    // Neutral blue-grey containers to match the background (Material's defaults are tinted purple).
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF242832),
    surfaceContainerHighest = Color(0xFF2B303B),
    outline = Color(0xFF8C919D),
    outlineVariant = Color(0xFF3A3F4B),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0),
    primaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF006A5B),
    secondaryContainer = Color(0xFFB8EFE1),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF8B5000),
    background = Color(0xFFF8F9FD),
    surface = Color(0xFFF8F9FD),
    surfaceVariant = Color(0xFFE0E3EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEBEEF4),
    surfaceContainerHigh = Color(0xFFE5E8EF),
    surfaceContainerHighest = Color(0xFFDFE2EA),
    outlineVariant = Color(0xFFC4C7D0),
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
