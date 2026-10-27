package com.spotifydashboard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Spotify brand palette (neutral dark grays + Spotify green).
val SpotifyGreen = Color(0xFF1DB954)
val SpotifyRed = Color(0xFFE91429)
val SpotifyLightGray = Color(0xFFB3B3B3)
val SpotifyCardGray = Color(0xFF181818)
val SpotifyChipGray = Color(0xFF2A2A2A)

/**
 * Dark color scheme approximating the Spotify client:
 * - Neutral gray surfaces (no Material purple tint — all container roles
 *   overridden, tonal elevation tint neutralized via surfaceTint).
 * - Spotify green primary, so buttons / chips-selected / progress /
 *   switches / checkboxes / tab indicators pick it up automatically.
 * - Light-on-dark inverse roles so snackbars stay dark.
 */
private val SpotifyColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = SpotifyGreen,
    onPrimaryContainer = Color.Black,
    inversePrimary = SpotifyGreen,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF282828),
    onSecondaryContainer = Color.White,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = SpotifyLightGray,
    surfaceTint = Color(0xFF282828),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color.White,
    error = SpotifyRed,
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF535353),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color.Black
)

@Composable
fun SpotifyDashboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SpotifyColorScheme,
        content = content
    )
}
