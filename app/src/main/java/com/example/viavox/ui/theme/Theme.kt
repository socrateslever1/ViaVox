package com.example.viavox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ViaVoxTeal,
    onPrimary = ViaVoxDarkText,
    primaryContainer = ViaVoxBrandIconBg,
    onPrimaryContainer = ViaVoxTeal,
    secondary = ViaVoxCoralStop,
    onSecondary = ViaVoxCoralDarkText,
    background = ViaVoxDarkBg,
    onBackground = ViaVoxTextWhite,
    surface = ViaVoxHeader,
    onSurface = ViaVoxTextWhite,
    surfaceVariant = ViaVoxCard,
    onSurfaceVariant = ViaVoxTextSub
)

@Composable
fun ViaVoxTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
