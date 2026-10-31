package com.spotifydashboard.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Spotify outbound links — one place, shared by every card that links out.
 *
 * Only a 22-char base62 string is a Spotify id; anything else (CSV ids like
 * "SP1") gets no link rather than a dead one. Mirrors `utils/spotifyLinks`
 * in the web frontends.
 */
private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")

fun spotifyAlbumUrl(id: String?): String? =
    id?.takeIf { SPOTIFY_ID.matches(it) }?.let { "https://open.spotify.com/album/$it" }

fun spotifyPlaylistUrl(id: String?): String? =
    id?.takeIf { SPOTIFY_ID.matches(it) }?.let { "https://open.spotify.com/playlist/$it" }

fun openSpotifyUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // No browser / no handler — the chip does nothing rather than crash.
    }
}
