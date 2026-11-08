package com.mikhaildezprofi.mobileaiagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ChatStyleColors = lightColorScheme(
    primary = Color(0xFF212121),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF202020),
    secondary = Color(0xFF5D5D5D),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5D5D5D),
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE8E8E8)
)

@Composable
fun MobileAIAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChatStyleColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
