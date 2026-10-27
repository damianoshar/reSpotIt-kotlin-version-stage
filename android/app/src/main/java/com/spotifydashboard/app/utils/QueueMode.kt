package com.spotifydashboard.app.utils

/**
 * How the app builds and maintains its queue playlist.
 *
 * LOCAL keeps the playlist itself as the queue: the playlist ID is the queue
 * identity, and the app remembers the current track position. It never merges
 * Spotify's manual queue.
 *
 * MERGE is the older behavior: the previous playlist tail is merged with
 * Spotify's manual queue when a queue playlist is created or reset.
 */
enum class QueueMode {
    LOCAL,
    MERGE,
}
