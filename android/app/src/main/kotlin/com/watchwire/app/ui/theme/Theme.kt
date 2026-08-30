package com.watchwire.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF0B0F14)
val SurfaceDark = Color(0xFF171F29)
val AccentBlue = Color(0xFF3AA0FF)
val DangerRed = Color(0xFFFF4D4F)
val SuccessGreen = Color(0xFF34C759)
val TextMuted = Color(0xFF8FA1B3)

private val WatchWireColorScheme = darkColorScheme(
    primary = AccentBlue,
    background = BackgroundDark,
    surface = SurfaceDark,
    error = DangerRed,
    onPrimary = Color(0xFF071018),
    onBackground = Color(0xFFE8EEF5),
    onSurface = Color(0xFFE8EEF5),
)

@Composable
fun WatchWireTheme(content: @Composable () -> Unit) {
    // Dark-only by design: matches the web client and suits a low-light security-camera use case.
    MaterialTheme(colorScheme = WatchWireColorScheme) {
        // A Surface is what actually applies colorScheme.background/onBackground as the
        // content color -- MaterialTheme alone only publishes the color scheme, it doesn't
        // paint anything or set LocalContentColor.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
