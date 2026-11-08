package com.spotifydashboard.app.ui

/**
 * One AI-extracted concert, correlated with the user's followed Spotify
 * artists. Rows are held in the ViewModel until the user saves the selection,
 * mirroring the React scan dialog (Followed/All filter, per-row checkboxes).
 */
data class ConcertScanRow(
    val artist: String,
    val date: String = "",
    val venue: String = "",
    val url: String = "",
    val spotifyId: String = "",
    val sourceUrl: String = "",
    val followed: Boolean = false,
    val selected: Boolean = false,
)