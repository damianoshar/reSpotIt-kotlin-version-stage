package com.spotifydashboard.app.utils

import com.spotifydashboard.app.api.SpotifyPlaylist

/**
 * Search and sort for the playlist list (the Playlists tab, before a playlist
 * is opened). Both run on the phone: the playlist list is small and the
 * server does not sort it by name.
 */
object PlaylistListOptions {
    const val SORT_NAME = "name"
    const val SORT_TRACKS = "tracks"
    const val SORT_ADDED = "added"

    /** Playlists whose name contains [query] (case-insensitive). */
    fun filter(playlists: List<SpotifyPlaylist>, query: String): List<SpotifyPlaylist> {
        val needle = query.trim()
        if (needle.isEmpty()) return playlists
        return playlists.filter { it.name.contains(needle, ignoreCase = true) }
    }

    /**
     * Sort by name (A-Z or Z-A), track count (most/least first) or date added
     * (newest/oldest first, from Spotify's /me/playlists recency order;
     * backends that don't store the position fall back to name order).
     */
    fun sort(playlists: List<SpotifyPlaylist>, sortField: String, sortOrder: String = "asc"): List<SpotifyPlaylist> {
        val desc = sortOrder == "desc"
        return when (sortField) {
            SORT_TRACKS -> {
                // Plain ascending/descending by track count, like every other
                // field. The Tracks chip's natural default is "desc" (most
                // tracks first), so the common case still leads with the
                // biggest playlists.
                if (desc) playlists.sortedWith(compareByDescending<SpotifyPlaylist> { it.tracks.total }.thenBy { it.name.lowercase() })
                else playlists.sortedWith(compareBy<SpotifyPlaylist> { it.tracks.total }.thenBy { it.name.lowercase() })
            }
            SORT_ADDED -> {
                // addedOrder is Spotify's /me/playlists position, 0-based where
                // 0 = most recently added. Asc  -> oldest -> newest (larger index).
                // Desc -> newest -> oldest (smaller index). Nulls always last so
                // rows that have no known order (remote backends without the
                // index) stay out of the way.
                if (desc) playlists.sortedWith(compareBy<SpotifyPlaylist>({ it.addedOrder == null }, { it.addedOrder ?: Int.MAX_VALUE }).thenBy { it.name.lowercase() })
                else playlists.sortedWith(compareBy<SpotifyPlaylist>({ it.addedOrder == null }).thenByDescending { it.addedOrder ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
            }
            else -> if (desc) playlists.sortedByDescending { it.name.lowercase() }
                    else playlists.sortedBy { it.name.lowercase() }
        }
    }

    /** Filter and sort in one go - what the Playlists tab shows. */
    fun apply(
        playlists: List<SpotifyPlaylist>,
        query: String,
        sortField: String,
        sortOrder: String = "asc",
    ): List<SpotifyPlaylist> = sort(filter(playlists, query), sortField, sortOrder)
}
