package com.spotifydashboard.app.api

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistListEnvelopeDeserializerTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(
            PlaylistListEnvelope::class.java,
            PlaylistListEnvelopeDeserializer()
        )
        .create()

    @Test
    fun parsesFastApiBareArray() {
        val envelope = gson.fromJson(
            """[{"id":"p1","name":"Pavement","track_count":67}]""",
            PlaylistListEnvelope::class.java
        )

        assertEquals(1, envelope.data.size)
        assertEquals("p1", envelope.data[0].id)
        assertEquals("Pavement", envelope.data[0].name)
        assertEquals(67, envelope.data[0].track_count)
    }

    @Test
    fun parsesWrappedEnvelope() {
        val envelope = gson.fromJson(
            """{"data":[{"id":"p2","name":"Top tracks","track_count":18}]}""",
            PlaylistListEnvelope::class.java
        )

        assertEquals(1, envelope.data.size)
        assertEquals("p2", envelope.data[0].id)
        assertEquals(18, envelope.data[0].track_count)
    }

    @Test
    fun unknownShapeBecomesEmpty() {
        val envelope = gson.fromJson("""{"unexpected":true}""", PlaylistListEnvelope::class.java)

        assertTrue(envelope.data.isEmpty())
    }
}
