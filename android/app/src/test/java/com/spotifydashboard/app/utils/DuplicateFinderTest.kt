package com.spotifydashboard.app.utils

import com.spotifydashboard.app.data.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun album(id: String, title: String, artist: String, year: Int): AlbumEntity =
        AlbumEntity(
            id = id,
            title = title,
            artist = artist,
            year = year,
            genre = "",
            imageUrl = "",
            imagePath = "",
            addedAt = ""
        )

    @Test
    fun `deluxe edition groups with the base album`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("base", "Random Access Memories", "Daft Punk", 2013),
                album("deluxe", "Random Access Memories (Deluxe Edition)", "Daft Punk", 2013)
            )
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("base", "deluxe"), groups[0].albums.map { it.id })
    }

    @Test
    fun `remaster suffix groups with the base album`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "The Night Is Young", "The Band", 1985),
                album("b", "The Night Is Young - 2011 Remaster", "The Band", 2011)
            )
        )

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].albums.size)
    }

    @Test
    fun `regional bracket tag groups with the base album`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "Easter Everywhere", "13th Floor Elevators", 1967),
                album("b", "Easter Everywhere (UK)", "13th Floor Elevators", 1967)
            )
        )

        assertEquals(1, groups.size)
    }

    @Test
    fun `live version is not merged with the studio album`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("studio", "The End Is Begun", "3", 2007),
                album("live", "The End Is Begun (Live)", "3", 2008)
            )
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `different artists do not group`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "Greatest Hits", "Queen", 1981),
                album("b", "Greatest Hits", "ABBA", 1992)
            )
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `accents and articles are normalised`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "Café Tacvba", "Café Tacvba", 2000),
                album("b", "Cafe Tacvba", "Cafe Tacvba", 2000)
            )
        )

        assertEquals(1, groups.size)
    }

    @Test
    fun `featuring credit falls back to the primary artist`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "Song", "Artist feat. Guest", 2020),
                album("b", "Song", "Artist", 2020)
            )
        )

        assertEquals(1, groups.size)
    }

    @Test
    fun `distinct titles stay separate`() {
        val groups = DuplicateFinder.find(
            listOf(
                album("a", "Album One", "Artist", 2000),
                album("b", "Album Two", "Artist", 2001)
            )
        )

        assertTrue(groups.isEmpty())
    }
}
