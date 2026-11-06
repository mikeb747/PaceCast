package com.pacecast.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MintGreen,
    onPrimary = CharcoalCard,
    secondary = MintGreenBright,
    onSecondary = CharcoalCard,
    background = CharcoalOuter,
    surface = CharcoalCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun PaceCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
