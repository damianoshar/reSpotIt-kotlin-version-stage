package com.spotifydashboard.app.utils

/**
 * Pure planning helpers for the one persistent queue playlist.
 *
 * The playlist itself is the queue. Spotify's side queue can still contain
 * manually queued tracks; when the app starts a new playlist context it copies
 * those tracks in, but when it appends to the already-playing playlist it must
 * leave them alone. Otherwise the same track would be queued twice.
 */
object ActiveQueuePlanner {
    /**
     * Album tracks that are safe to append after [upcomingUris].
     *
     * A selected album can contain a track that is already in the active
     * playlist tail or already waiting in Spotify's manual queue. Appending it
     * again would play it twice, so those URIs are left out. Duplicates inside
     * the selected album itself are preserved: they are an intentional album
     * sequence, not a duplicate of the previous queue.
     */
    fun newAlbumUris(
        albumUris: List<String>,
        upcomingUris: List<String>,
    ): List<String> {
        val upcoming = upcomingUris
            .mapNotNull { raw -> raw.trim().takeIf { QueueSnapshot.isPlayable(it) } }
            .toSet()
        val out = ArrayList<String>(albumUris.size)
        for (raw in albumUris) {
            val uri = raw.trim()
            if (!QueueSnapshot.isPlayable(uri)) continue
            if (uri in upcoming) continue
            out.add(uri)
        }
        return out
    }

    /**
     * Album tracks to insert immediately after the current track.
     *
     * The currently playing track and the unplayed tail are both skipped:
     * inserting the current track again would replay it, and the tail is
     * already queued. Duplicates inside the selected album itself are kept.
     */
    fun nextAlbumUris(
        albumUris: List<String>,
        currentUri: String?,
        upcomingUris: List<String>,
    ): List<String> {
        val avoid = ArrayList<String>(upcomingUris.size + 1)
        currentUri?.let { avoid.add(it) }
        avoid.addAll(upcomingUris)
        return newAlbumUris(albumUris, avoid)
    }

    /**
     * Items in Spotify's manual queue that are not part of the active
     * playlist's unplayed tail. The result is count-aware, so an intentional
     * duplicate in either list is not collapsed.
     */
    fun extraUpcoming(
        queueUris: List<String>,
        activeTailUris: List<String>,
    ): List<String> {
        val remaining = activeTailUris
            .map { it.trim() }
            .filter { QueueSnapshot.isPlayable(it) }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val out = ArrayList<String>(queueUris.size)
        for (raw in queueUris) {
            val uri = raw.trim()
            if (!QueueSnapshot.isPlayable(uri)) continue
            val count = remaining[uri] ?: 0
            if (count > 0) {
                remaining[uri] = count - 1
            } else {
                out.add(uri)
            }
        }
        return out
    }
}
