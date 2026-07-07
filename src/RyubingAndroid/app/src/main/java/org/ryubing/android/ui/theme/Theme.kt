package org.ryubing.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RyubingBlue = Color(0xFF5B8DEF)

private val DarkColors = darkColorScheme(
    primary = RyubingBlue,
    background = Color(0xFF121218),
    surface = Color(0xFF1C1C24),
)

private val LightColors = lightColorScheme(
    primary = RyubingBlue,
)

@Composable
fun RyubingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
