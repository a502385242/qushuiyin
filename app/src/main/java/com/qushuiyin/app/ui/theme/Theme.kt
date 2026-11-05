package com.qushuiyin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B3A80),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F4F7),
    onSurface = Color(0xFF111418),
    onSurfaceVariant = Color(0xFF5A6169),
    error = Color(0xFFD93F3F),
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF7A1C1C),
    outline = Color(0xFFD5D9DF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CB4FF),
    onPrimary = Color(0xFF06214D),
    primaryContainer = Color(0xFF16356B),
    onPrimaryContainer = Color(0xFFDCE8FF),
    surface = Color(0xFF15181C),
    surfaceVariant = Color(0xFF1F242A),
    onSurface = Color(0xFFE7EAEE),
    onSurfaceVariant = Color(0xFFA6ADB5),
    error = Color(0xFFFF9B9B),
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF333A42)
)

@Composable
fun QuShuiYinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
