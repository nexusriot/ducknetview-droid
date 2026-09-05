package com.vlad.ducknetview.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dynamic colour is deliberately not used: the exposure/scope palette in
 * [DuckColors] only reads correctly against a known, low-chroma ground, and a
 * wallpaper-derived scheme would fight it.
 */
private val DuckDarkScheme = darkColorScheme(
    primary = Color(0xFF7FD3F7),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004C6B),
    onPrimaryContainer = Color(0xFFC7E9FF),
    secondary = Color(0xFFAED581),
    onSecondary = Color(0xFF1E3300),
    secondaryContainer = Color(0xFF2E4A11),
    onSecondaryContainer = Color(0xFFD8F2B4),
    tertiary = Color(0xFFE0A8F7),
    onTertiary = Color(0xFF3F2148),
    error = Color(0xFFEF5350),
    onError = Color(0xFF3A0908),
    errorContainer = Color(0xFF5C1614),
    onErrorContainer = Color(0xFFFFD9D7),
    background = Color(0xFF0E1216),
    onBackground = Color(0xFFDDE3E8),
    surface = Color(0xFF0E1216),
    onSurface = Color(0xFFDDE3E8),
    surfaceVariant = Color(0xFF1A2027),
    onSurfaceVariant = Color(0xFFA9B4BE),
    outline = Color(0xFF44505A),
    outlineVariant = Color(0xFF2A333B),
)

private val DuckLightScheme = lightColorScheme(
    primary = Color(0xFF00658C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E9FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF44662B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8F2B4),
    onSecondaryContainer = Color(0xFF0F2000),
    tertiary = Color(0xFF7B4F87),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF7F9FB),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE5EAEF),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CD),
)

@Composable
fun DuckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DuckDarkScheme else DuckLightScheme,
        content = content,
    )
}
