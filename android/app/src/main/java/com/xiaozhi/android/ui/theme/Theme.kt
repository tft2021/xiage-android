package com.xiaozhi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5EE),
    onPrimaryContainer = Color(0xFF04342C),
    secondary = Color(0xFF185FA5),
    onSecondary = Color.White,
    error = Color(0xFFA32D2D),
    surface = Color(0xFFFDFDFB),
    onSurface = Color(0xFF2C2C2A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF04342C),
    primaryContainer = Color(0xFF085041),
    onPrimaryContainer = Color(0xFF9FE1CB),
    secondary = Color(0xFF85B7EB),
    onSecondary = Color(0xFF042C53),
    error = Color(0xFFF09595),
    surface = Color(0xFF1A1A18),
    onSurface = Color(0xFFE8E8E4),
)

private val AppTypography = Typography()

@Composable
fun XiaozhiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
