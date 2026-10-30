package com.spotifydashboard.app.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class AddTracksRequestTest {
    @Test
    fun appendRequestOmitsPosition() {
        val json = Gson().toJson(AddTracksRequest(uris = listOf("spotify:track:a")))
        assertEquals("{\"uris\":[\"spotify:track:a\"]}", json)
    }

    @Test
    fun insertRequestIncludesZeroBasedPosition() {
        val json = Gson().toJson(AddTracksRequest(uris = listOf("spotify:track:a"), position = 1))
        assertEquals("{\"uris\":[\"spotify:track:a\"],\"position\":1}", json)
    }
}