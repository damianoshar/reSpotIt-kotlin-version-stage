package com.spotifydashboard.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSnapshotQueueTailTest {
    @Test
    fun previousTailAndManualQueueItemsAreKeptInSpotifyOrder() {
        val queue = listOf("spotify:track:c", "spotify:track:d", "spotify:track:manual")
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )

        assertEquals(
            queue,
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun manualItemBeforeTheTailKeepsItsPlace() {
        val queue = listOf("spotify:track:manual", "spotify:track:c", "spotify:track:d")
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )

        assertEquals(
            queue,
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun currentTrackIsNotRepeated() {
        val queue = listOf("spotify:track:b", "spotify:track:c")
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
        )

        assertEquals(
            listOf("spotify:track:c"),
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun missingPreviousTailIsAppended() {
        val queue = listOf("spotify:track:manual")
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )

        assertEquals(
            listOf("spotify:track:manual", "spotify:track:c", "spotify:track:d"),
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun unknownCurrentTrackFallsBackToTheQueue() {
        val queue = listOf("spotify:track:x", "spotify:track:y")

        assertEquals(
            queue,
            QueueSnapshot.upNextIncludingPrevious(queue, listOf("spotify:track:a"), null),
        )
        assertEquals(
            queue,
            QueueSnapshot.upNextIncludingPrevious(queue, listOf("spotify:track:a"), "spotify:track:other"),
        )
    }

    @Test
    fun duplicateTracksInThePreviousTailAreCounted() {
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:c",
        )
        val queue = listOf("spotify:track:c", "spotify:track:c", "spotify:track:manual")

        assertEquals(
            queue,
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun theRightOccurrenceOfADuplicateCurrentTrackIsUsed() {
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:a",
            "spotify:track:c",
        )
        val queue = listOf("spotify:track:c")

        assertEquals(
            listOf("spotify:track:c"),
            QueueSnapshot.upNextIncludingPrevious(
                queue,
                previous,
                "spotify:track:a",
                currentIndex = 2,
            ),
        )
    }

    @Test
    fun repeatCyclesAfterThePreviousTailAreNotCopied() {
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )
        val queue = listOf(
            "spotify:track:manual",
            "spotify:track:c",
            "spotify:track:d",
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )

        assertEquals(
            listOf("spotify:track:manual", "spotify:track:c", "spotify:track:d"),
            QueueSnapshot.upNextIncludingPrevious(
                queue,
                previous,
                "spotify:track:b",
                dropRepeats = true,
            ),
        )
    }

    @Test
    fun repeatTrackDoesNotCopyTheRepeatedCurrentTrack() {
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )
        val queue = listOf("spotify:track:b", "spotify:track:b", "spotify:track:b")

        assertEquals(
            listOf("spotify:track:c", "spotify:track:d"),
            QueueSnapshot.upNextIncludingPrevious(
                queue,
                previous,
                "spotify:track:b",
                dropRepeats = true,
            ),
        )
    }

    @Test
    fun unsupportedQueueItemsAreSkipped() {
        val queue = listOf(
            "spotify:local:artist:album:track:1",
            "spotify:track:c",
            "",
        )
        val previous = listOf("spotify:track:a", "spotify:track:b", "spotify:track:c")

        assertEquals(
            listOf("spotify:track:c"),
            QueueSnapshot.upNextIncludingPrevious(queue, previous, "spotify:track:b"),
        )
    }

    @Test
    fun localResetKeepsOnlyTheUnplayedTail() {
        val previous = listOf(
            "spotify:track:a",
            "spotify:track:b",
            "spotify:track:c",
            "spotify:track:d",
        )

        assertEquals(
            listOf("spotify:track:c", "spotify:track:d"),
            QueueSnapshot.upNextIncludingPrevious(
                queueUris = emptyList(),
                previousUris = previous,
                currentUri = null,
                currentIndex = 1,
            ),
        )
    }

    @Test
    fun newQueueContentUsesTheUpNextQueueWhenPresent() {
        assertEquals(
            listOf("spotify:track:b", "spotify:track:c"),
            QueueSnapshot.newQueueContent(
                "spotify:track:a",
                listOf("spotify:track:b", "spotify:track:c"),
            ),
        )
    }

    @Test
    fun newQueueContentFallsBackToTheCurrentTrack() {
        assertEquals(
            listOf("spotify:track:a"),
            QueueSnapshot.newQueueContent("spotify:track:a", emptyList()),
        )
        assertEquals(
            emptyList<String>(),
            QueueSnapshot.newQueueContent("spotify:local:artist:album:track:1", emptyList()),
        )
        assertEquals(
            emptyList<String>(),
            QueueSnapshot.newQueueContent(null, emptyList()),
        )
    }
}
