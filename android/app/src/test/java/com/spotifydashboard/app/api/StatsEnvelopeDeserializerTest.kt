package com.spotifydashboard.app.api

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEnvelopeDeserializerTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(
            StatsEnvelope::class.java,
            StatsEnvelopeDeserializer()
        )
        .create()

    @Test
    fun parsesFastApiBareStats() {
        val stats = gson.fromJson(
            """{"totalAlbums":6897,"totalArtists":3122,"totalGenres":338,
               "newestYear":2026,"oldestYear":1955,
               "topGenres":[{"name":"rock","count":900}],
               "topArtists":[{"name":"A","count":12}],
               "albumsByYear":[{"year":2020,"count":3}]}""",
            StatsEnvelope::class.java
        ).data

        assertEquals(6897, stats.totalAlbums)
        assertEquals(3122, stats.totalArtists)
        assertEquals(338, stats.totalGenres)
        assertEquals(2026, stats.newestYear)
        assertEquals(1955, stats.oldestYear)
        assertEquals(1, stats.topGenres?.size)
        assertEquals("rock", stats.topGenres?.get(0)?.name)
        assertEquals(900, stats.topGenres?.get(0)?.count)
        assertEquals(1, stats.albumsByYear?.size)
        assertEquals(2020, stats.albumsByYear?.get(0)?.year)
    }

    @Test
    fun parsesWrappedStats() {
        val stats = gson.fromJson(
            """{"data":{"totalAlbums":5,"totalArtists":3,"totalGenres":2,
               "newestYear":2024,"oldestYear":1990}}""",
            StatsEnvelope::class.java
        ).data

        assertEquals(5, stats.totalAlbums)
        assertEquals(3, stats.totalArtists)
        assertEquals(2, stats.totalGenres)
        assertEquals(2024, stats.newestYear)
    }

    @Test
    fun parsesSnakeCaseWrappedStats() {
        val stats = gson.fromJson(
            """{"data":{"total_albums":7,"total_artists":4,"total_genres":3,
               "newest_year":2025,"oldest_year":1970}}""",
            StatsEnvelope::class.java
        ).data

        assertEquals(7, stats.totalAlbums)
        assertEquals(4, stats.totalArtists)
        assertEquals(3, stats.totalGenres)
        assertEquals(2025, stats.newestYear)
        assertEquals(1970, stats.oldestYear)
    }

    @Test
    fun unknownShapeBecomesEmpty() {
        val stats = gson.fromJson("""[1,2,3]""", StatsEnvelope::class.java).data

        assertEquals(0, stats.totalAlbums)
        assertTrue(stats.topGenres.isNullOrEmpty())
    }
}
