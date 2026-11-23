package com.example.rssreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// M1 先用一套静态配色（平移自 todo-app 验证过的调色板）；
// M2 按 ReadYou 模式换 dynamicColorScheme + 静态 fallback
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6E8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE5F2),
    onPrimaryContainer = Color(0xFF0B2838),
    secondary = Color(0xFF4C7A5B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8DA),
    onSecondaryContainer = Color(0xFF10281A),
    tertiary = Color(0xFF8A6D3B),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE3E7EA),
    onSurfaceVariant = Color(0xFF43474A),
    outline = Color(0xFF73777A),
    outlineVariant = Color(0xFFC3C7CA),
    surfaceDim = Color(0xFFDCDEDF),
    surfaceBright = Color(0xFFF7F9FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F4),
    surfaceContainer = Color(0xFFEBEDEE),
    surfaceContainerHigh = Color(0xFFE5E7E8),
    surfaceContainerHighest = Color(0xFFDFE1E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB8D0),
    onPrimary = Color(0xFF04202F),
    primaryContainer = Color(0xFF21485C),
    onPrimaryContainer = Color(0xFFCFE5F2),
    secondary = Color(0xFF9BC4A8),
    onSecondary = Color(0xFF0B2A19),
    secondaryContainer = Color(0xFF2A4A34),
    onSecondaryContainer = Color(0xFFD5E8DA),
    tertiary = Color(0xFFCBB27A),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE3E6E8),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE3E6E8),
    surfaceVariant = Color(0xFF3F4346),
    onSurfaceVariant = Color(0xFFC3C7CA),
    outline = Color(0xFF8D9194),
    outlineVariant = Color(0xFF3F4346),
    surfaceDim = Color(0xFF111416),
    surfaceBright = Color(0xFF37393B),
    surfaceContainerLowest = Color(0xFF0C0F10),
    surfaceContainerLow = Color(0xFF191C1E),
    surfaceContainer = Color(0xFF1D2022),
    surfaceContainerHigh = Color(0xFF272A2C),
    surfaceContainerHighest = Color(0xFF323537),
)

@Composable
fun RssReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
