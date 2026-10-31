package com.spotifydashboard.app.utils

import com.spotifydashboard.app.api.OwnerInfo
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.api.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search and sort for the playlist list. */
class PlaylistListOptionsTest {
    private fun playlist(name: String, tracks: Int, addedOrder: Int? = null) = SpotifyPlaylist(
        id = name.lowercase().replace(" ", "-"),
        name = name,
        images = emptyList(),
        tracks = TrackInfo(total = tracks),
        owner = OwnerInfo(id = "me"),
        addedOrder = addedOrder,
    )

    private val playlists = listOf(
        playlist("Roadside", 67),
        playlist("all library up to 1974", 8123),
        playlist("pavement", 12),
    )

    @Test
    fun anEmptyQueryKeepsEverything() {
        assertEquals(playlists, PlaylistListOptions.filter(playlists, ""))
        assertEquals(playlists, PlaylistListOptions.filter(playlists, "   "))
    }

    @Test
    fun theSearchIgnoresCase() {
        val hits = PlaylistListOptions.filter(playlists, "PAVEMENT")
        assertEquals(listOf("pavement"), hits.map { it.name })
    }

    @Test
    fun sortingByNameIsCaseInsensitive() {
        val sorted = PlaylistListOptions.sort(playlists, PlaylistListOptions.SORT_NAME)
        assertEquals(
            listOf("all library up to 1974", "pavement", "Roadside"),
            sorted.map { it.name },
        )
    }

    @Test
    fun sortingByTracksDescendingPutsTheBiggestFirst() {
        val sorted = PlaylistListOptions.sort(playlists, PlaylistListOptions.SORT_TRACKS, "desc")
        assertEquals(
            listOf("all library up to 1974", "Roadside", "pavement"),
            sorted.map { it.name },
        )
    }

    @Test
    fun sortingByTracksAscendingPutsTheSmallestFirst() {
        val sorted = PlaylistListOptions.sort(playlists, PlaylistListOptions.SORT_TRACKS, "asc")
        assertEquals(
            listOf("pavement", "Roadside", "all library up to 1974"),
            sorted.map { it.name },
        )
    }

    @Test
    fun sortingByNameDescendingReversesTheList() {
        val sorted = PlaylistListOptions.sort(playlists, PlaylistListOptions.SORT_NAME, "desc")
        assertEquals(
            listOf("Roadside", "pavement", "all library up to 1974"),
            sorted.map { it.name },
        )
    }

    @Test
    fun sortingByDateAddedDescendingPutsTheNewestFirst() {
        // addedOrder from Spotify is 0-based where 0 = most recently added.
        // "desc" is newest-first and matches Spotify's native /me/playlists order.
        val dated = listOf(
            playlist("old", 1, addedOrder = 2),
            playlist("new", 1, addedOrder = 0),
            playlist("middle", 1, addedOrder = 1),
        )
        val sorted = PlaylistListOptions.sort(dated, PlaylistListOptions.SORT_ADDED, "desc")
        assertEquals(listOf("new", "middle", "old"), sorted.map { it.name })
    }

    @Test
    fun sortingByDateAddedAscendingPutsTheOldestFirst() {
        val dated = listOf(
            playlist("old", 1, addedOrder = 2),
            playlist("new", 1, addedOrder = 0),
            playlist("middle", 1, addedOrder = 1),
        )
        val sorted = PlaylistListOptions.sort(dated, PlaylistListOptions.SORT_ADDED, "asc")
        assertEquals(listOf("old", "middle", "new"), sorted.map { it.name })
    }

    @Test
    fun playlistsWithoutAnAddedOrderSortLastInBothDirections() {
        val mixed = listOf(
            playlist("no-order-a", 1, addedOrder = null),
            playlist("dated", 1, addedOrder = 0),
            playlist("no-order-b", 1, addedOrder = null),
        )
        val asc = PlaylistListOptions.sort(mixed, PlaylistListOptions.SORT_ADDED, "asc")
        assertEquals(listOf("dated", "no-order-a", "no-order-b"), asc.map { it.name })
        val desc = PlaylistListOptions.sort(mixed, PlaylistListOptions.SORT_ADDED, "desc")
        assertEquals(listOf("dated", "no-order-a", "no-order-b"), desc.map { it.name })
    }

    @Test
    fun applyFiltersAndSortsInOneGo() {
        val result = PlaylistListOptions.apply(playlists, "a", PlaylistListOptions.SORT_NAME)
        assertEquals(
            listOf("all library up to 1974", "pavement", "Roadside"),
            result.map { it.name },
        )
    }
}