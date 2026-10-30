package com.spotifydashboard.app.api

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/**
 * GET /api/v1/playlists is a bare array on FastAPI and {data:[...]} on the
 * JWT backends. Accept both shapes so remote playlists work everywhere.
 */
class PlaylistListEnvelopeDeserializer : JsonDeserializer<PlaylistListEnvelope> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): PlaylistListEnvelope {
        val array = when {
            json.isJsonArray -> json
            json.isJsonObject -> json.asJsonObject.get("data")
            else -> null
        }
        if (array == null || !array.isJsonArray) return PlaylistListEnvelope()
        val items = context.deserialize<Array<RemotePlaylistItem>>(
            array,
            Array<RemotePlaylistItem>::class.java
        )
        return PlaylistListEnvelope(items?.toList() ?: emptyList())
    }
}
