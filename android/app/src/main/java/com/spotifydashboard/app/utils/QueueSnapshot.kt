package com.spotifydashboard.app.utils

/**
 * Pure helpers for the player's queue actions.
 *
 * Spotify playlists accept tracks and episodes, but not local files. The
 * current track is placed first so a snapshot begins with what is playing.
 * Order and duplicates are preserved deliberately: a queue is a sequence, and
 * collapsing duplicates would silently change it.
 */
object QueueSnapshot {
    private val playablePrefixes = listOf("spotify:track:", "spotify:episode:")

    fun isPlayable(uri: String?): Boolean {
        val trimmed = uri?.trim().orEmpty()
        return trimmed.isNotEmpty() && playablePrefixes.any { trimmed.startsWith(it) }
    }

    /** Current track followed by the up-next queue, both filtered to playable items. */
    fun playlistUris(currentUri: String?, queueUris: List<String>): List<String> {
        val out = ArrayList<String>(queueUris.size + 1)
        if (isPlayable(currentUri)) out.add(currentUri!!.trim())
        queueUris.forEach { uri ->
            if (isPlayable(uri)) out.add(uri.trim())
        }
        return out
    }

    /** Only the up-next queue, for appending to the playlist that is already playing. */
    fun queueUris(queueUris: List<String>): List<String> = playlistUris(null, queueUris)

    /**
     * Content for a new queue playlist. Prefer what Spotify will play next;
     * fall back to the current track when the side queue is empty (a paused
     * single track still has to produce a playable queue).
     */
    fun newQueueContent(currentUri: String?, queueUris: List<String>): List<String> {
        val upNext = queueUris(queueUris)
        if (upNext.isNotEmpty()) return upNext
        return if (isPlayable(currentUri)) listOf(currentUri!!.trim()) else emptyList()
    }

    /**
     * Head of a "queue playlist": the current track followed by the up-next
     * queue.
     *
     * [offset] is where playback should start inside that list. The current
     * track is item 0, so a context switch continues at item 1 (the first
     * queued track) instead of replaying the track that is playing now.
     */
    fun queuePlaylistHead(currentUri: String?, queueUris: List<String>): QueuePlaylistHead {
        val uris = playlistUris(currentUri, queueUris)
        val raw = listOfNotNull(currentUri) + queueUris
        val skipped = raw.count { it.isNotBlank() } - uris.size
        val hasCurrent = isPlayable(currentUri)
        return QueuePlaylistHead(
            uris = uris,
            offset = if (hasCurrent) 1 else 0,
            skipped = skipped,
        )
    }

    /**
     * The up-next items that are **not** part of a queue playlist this app
     * generated earlier.
     *
     * When the generated playlist is the playing context, its own remaining
     * tracks sit at the tail of the up-next list. Copying them into the next
     * generated playlist would repeat them (the same album twice), so they
     * are dropped and only what the user queued themselves is kept.
     *
     * [ownUris] is what the generated playlist holds, [currentUri] the track
     * that is playing from it. If the current track cannot be located, the
     * up-next list is dropped entirely: a duplicate album is worse than a
     * missing one.
     */
    fun upNextWithoutOwnItems(
        queueUris: List<String>,
        ownUris: List<String>,
        currentUri: String?,
    ): List<String> {
        if (ownUris.isEmpty()) return queueUris
        val current = currentUri?.trim()
        val index = if (current.isNullOrEmpty()) -1 else ownUris.indexOf(current)
        if (index < 0) return emptyList()
        // The current track is playing, so only the items after it are left.
        val ownRemaining = (ownUris.size - index - 1).coerceAtLeast(0)
        return queueUris.take(maxOf(0, queueUris.size - ownRemaining))
    }

    /**
     * Up-next items when the playing context is the app's own queue playlist.
     *
     * The new queue must continue what is already in flight:
     *  - the unplayed remainder of [previousUris] (the old queue playlist),
     *  - anything the user queued in Spotify in between,
     *  - and it must not repeat the track that is currently playing.
     *
     * Spotify's queue is the source of truth for order. The old playlist's
     * remainder is consumed from it count-aware, so duplicate URIs are not
     * collapsed. Any remainder Spotify did not report is appended afterwards;
     * this keeps the tail when the stored snapshot is incomplete.
     *
     * If the current track cannot be located in [previousUris], the queue is
     * returned unchanged. Dropping it would lose exactly the tracks this action
     * is meant to preserve.
     *
     * [dropRepeats] should be true when Spotify's repeat mode is on: the queue
     * then contains whole extra cycles of the old playlist after its remaining
     * tail. Those repeats are not new music and must not be copied.
     *
     * [currentIndex] selects the right occurrence when the same URI appears
     * more than once in [previousUris]. Without it the first occurrence wins.
     */
    fun upNextIncludingPrevious(
        queueUris: List<String>,
        previousUris: List<String>,
        currentUri: String?,
        dropRepeats: Boolean = false,
        currentIndex: Int? = null,
    ): List<String> {
        val current = currentUri?.trim().orEmpty()
        val previous = previousUris.map { it.trim() }.filter { it.isNotEmpty() }
        if (previous.isEmpty()) return playable(queueUris)

        val located = currentIndex?.takeIf { it in previous.indices }
            ?: if (current.isEmpty()) -1 else previous.indexOf(current)
        if (located < 0) return playable(queueUris)

        val remaining = previous.subList(located + 1, previous.size)
        val remainingCounts = remaining.groupingBy { it }.eachCount().toMutableMap()
        val previousSet = previous.toSet()
        val out = ArrayList<String>(queueUris.size + remaining.size)
        var tailComplete = remainingCounts.isEmpty()

        for (raw in queueUris) {
            val uri = raw.trim()
            if (!isPlayable(uri)) continue
            if (uri == current) continue
            val count = remainingCounts[uri] ?: 0
            if (count > 0) {
                remainingCounts[uri] = count - 1
                out.add(uri)
                if (remainingCounts.values.all { it == 0 }) tailComplete = true
                continue
            }
            if (dropRepeats && tailComplete && uri in previousSet) {
                // A new cycle of the old playlist (repeat=context), or the
                // old playlist's track repeated after its tail. Not new.
                continue
            }
            out.add(uri)
        }

        // Spotify may not report the context's remaining tracks. Keep them.
        for (uri in remaining) {
            val count = remainingCounts[uri] ?: 0
            if (count > 0) {
                remainingCounts[uri] = count - 1
                out.add(uri)
            }
        }
        return out
    }

    private fun playable(uris: List<String>): List<String> =
        uris.mapNotNull { uri -> uri.trim().takeIf { isPlayable(it) } }
}

/** The playlist content that precedes the albums a user wants "queued to end". */
data class QueuePlaylistHead(
    val uris: List<String>,
    /** Index into [uris] where playback should continue. */
    val offset: Int,
    /** Items dropped because Spotify playlists cannot hold them. */
    val skipped: Int,
)
