package com.spotifydashboard.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveQueuePlannerTest {
    @Test
    fun albumTracksAlreadyUpcomingAreNotAppendedAgain() {
        val album = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
        )
        val upcoming = listOf("spotify:track:b")

        assertEquals(
            listOf("spotify:track:a", "spotify:track:c"),
            ActiveQueuePlanner.newAlbumUris(album, upcoming),
        )
    }

    @Test
    fun duplicateTracksInsideTheSelectedAlbumArePreserved() {
        val album = listOf(
            "spotify:track:a",
            "spotify:track:a",
            "spotify:track:b",
        )

        assertEquals(
            album,
            ActiveQueuePlanner.newAlbumUris(album, emptyList()),
        )
    }

    @Test
    fun unsupportedAndBlankAlbumItemsAreDropped() {
        val album = listOf(
            "",
            "spotify:local:artist:album:track:1",
            "spotify:episode:e1",
        )

        assertEquals(
            listOf("spotify:episode:e1"),
            ActiveQueuePlanner.newAlbumUris(album, emptyList()),
        )
    }

    @Test
    fun queueNextSkipsTheCurrentTrackAndTheUnplayedTail() {
        val album = listOf(
            "spotify:track:current",
            "spotify:track:next",
            "spotify:track:after",
            "spotify:track:new",
        )
        val upcoming = listOf("spotify:track:next", "spotify:track:after")

        assertEquals(
            listOf("spotify:track:new"),
            ActiveQueuePlanner.nextAlbumUris(
                albumUris = album,
                currentUri = "spotify:track:current",
                upcomingUris = upcoming,
            ),
        )
    }

    @Test
    fun queueNextKeepsDuplicatesInsideTheSelectedAlbum() {
        val album = listOf(
            "spotify:track:a",
            "spotify:track:a",
            "spotify:track:b",
        )

        assertEquals(
            album,
            ActiveQueuePlanner.nextAlbumUris(
                albumUris = album,
                currentUri = "spotify:track:current",
                upcomingUris = emptyList(),
            ),
        )
    }

    @Test
    fun manualQueueItemsAreCountedSeparatelyFromTheActiveTail() {
        val queue = listOf(
            "spotify:track:manual",
            "spotify:track:c",
            "spotify:track:c",
            "spotify:track:d",
        )
        val tail = listOf("spotify:track:c", "spotify:track:d")

        assertEquals(
            listOf("spotify:track:manual", "spotify:track:c"),
            ActiveQueuePlanner.extraUpcoming(queue, tail),
        )
    }

    @Test
    fun aQueueThatIsExactlyTheActiveTailHasNoExtras() {
        val tail = listOf("spotify:track:c", "spotify:track:d")

        assertEquals(
            emptyList<String>(),
            ActiveQueuePlanner.extraUpcoming(tail, tail),
        )
    }
}
