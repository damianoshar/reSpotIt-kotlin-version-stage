package com.spotifydashboard.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether this device should fetch and render artwork at all.
 *
 * The Settings "Sync photos" switch (card 276) flips this off for low-end /
 * low-RAM phones. Returning null from [artworkModel] makes every existing
 * SubcomposeAsyncImage fall back to its placeholder without a Coil request,
 * so no network traffic or bitmap memory is spent on covers.
 */
val LocalArtworkEnabled = staticCompositionLocalOf { true }

/** Coil model for an image URL — null (placeholder) when photos are off. */
@Composable
fun artworkModel(url: String?): Any? =
    if (LocalArtworkEnabled.current) url?.takeIf { it.isNotBlank() } else null
