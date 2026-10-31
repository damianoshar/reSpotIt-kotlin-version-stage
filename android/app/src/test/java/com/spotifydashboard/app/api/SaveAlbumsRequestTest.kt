package com.spotifydashboard.app.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveAlbumsRequestTest {
    @Test
    fun bodySerializesIdsArray() {
        val json = Gson().toJson(SaveAlbumsRequest(listOf("album-a", "album-b")))
        assertEquals("{\"ids\":[\"album-a\",\"album-b\"]}", json)
    }
}
