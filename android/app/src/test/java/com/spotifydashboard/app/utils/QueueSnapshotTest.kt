package com.spotifydashboard.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotTest {
    @Test
    fun currentTrackIsFirstThenQueueOrderIsPreserved() {
        val queue = listOf(
            "spotify:track:b",
            "spotify:track:a",
            "spotify:track:b",
        )

        assertEquals(
            listOf("spotify:track:now", "spotify:track:b", "spotify:track:a", "spotify:track:b"),
            QueueSnapshot.playlistUris("spotify:track:now", queue),
        )
    }

    @Test
    fun tracksAndEpisodesAreAcceptedButLocalFilesAreNot() {
        assertTrue(QueueSnapshot.isPlayable("spotify:track:t1"))
        assertTrue(QueueSnapshot.isPlayable(" spotify:episode:e1 "))
        assertFalse(QueueSnapshot.isPlayable("spotify:local:artist:album:track:1"))
        assertFalse(QueueSnapshot.isPlayable("spotify:album:a1"))
        assertFalse(QueueSnapshot.isPlayable(""))
        assertFalse(QueueSnapshot.isPlayable(null))
    }

    @Test
    fun blankAndUnsupportedItemsAreSkipped() {
        val queue = listOf(
            "",
            "spotify:local:artist:album:track:1",
            " spotify:episode:e1 ",
        )

        assertEquals(
            listOf("spotify:episode:e1"),
            QueueSnapshot.playlistUris(null, queue),
        )
    }

    @Test
    fun appendHelperNeverIncludesTheCurrentTrack() {
        val queue = listOf("spotify:track:next")

        assertEquals(
            listOf("spotify:track:next"),
            QueueSnapshot.queueUris(queue),
        )
    }

    @Test
    fun queuePlaylistHeadKeepsTheCurrentTrackFirstAndContinuesAtTheQueue() {
        val head = QueueSnapshot.queuePlaylistHead(
            "spotify:track:now",
            listOf("spotify:track:a", "spotify:episode:b"),
        )

        assertEquals(
            listOf("spotify:track:now", "spotify:track:a", "spotify:episode:b"),
            head.uris,
        )
        // Item 0 is the track that is playing, so playback continues at 1.
        assertEquals(1, head.offset)
        assertEquals(0, head.skipped)
    }

    @Test
    fun queuePlaylistHeadStartsAtZeroWhenNothingIsPlaying() {
        val head = QueueSnapshot.queuePlaylistHead(null, listOf("spotify:track:a"))

        assertEquals(listOf("spotify:track:a"), head.uris)
        assertEquals(0, head.offset)
    }

    @Test
    fun queuePlaylistHeadCountsUnsupportedItemsAsSkipped() {
        val head = QueueSnapshot.queuePlaylistHead(
            "spotify:local:artist:album:track:1",
            listOf("spotify:track:a", "spotify:local:artist:album:track:2"),
        )

        assertEquals(listOf("spotify:track:a"), head.uris)
        assertEquals(0, head.offset)
        assertEquals(2, head.skipped)
    }

    @Test
    fun ownQueuePlaylistItemsAreLeftOutOfTheNextOne() {
        // The generated playlist holds [a, b, c] and "b" is playing, so the
        // queue is [manual, c] - "c" is ours and must not be copied again.
        val queue = listOf("spotify:track:manual", "spotify:track:c")
        val own = listOf("spotify:track:a", "spotify:track:b", "spotify:track:c")

        assertEquals(
            listOf("spotify:track:manual"),
            QueueSnapshot.upNextWithoutOwnItems(queue, own, "spotify:track:b"),
        )
    }

    @Test
    fun everythingIsDroppedWhenOurOwnItemsCannotBeLocated() {
        val queue = listOf("spotify:track:a", "spotify:track:b")

        // No current track -> we cannot tell what is ours: drop the lot
        // rather than repeat the album.
        assertEquals(
            emptyList<String>(),
            QueueSnapshot.upNextWithoutOwnItems(queue, listOf("spotify:track:x"), null),
        )
        assertEquals(
            emptyList<String>(),
            QueueSnapshot.upNextWithoutOwnItems(queue, listOf("spotify:track:x"), "spotify:track:other"),
        )
    }

    @Test
    fun aQueueWithoutOwnItemsIsKeptAsItIs() {
        val queue = listOf("spotify:track:a", "spotify:track:b")

        assertEquals(queue, QueueSnapshot.upNextWithoutOwnItems(queue, emptyList(), "spotify:track:z"))
    }
}
