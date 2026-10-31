package com.spotifydashboard.app.api

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/**
 * GET /api/v1/albums/stats is a bare object on FastAPI/Python and
 * {data:{...}} on the JWT backends. Accept both shapes: Gson otherwise
 * leaves StatsEnvelope.data at its default and every stat reads 0 in
 * media-server mode.
 */
class StatsEnvelopeDeserializer : JsonDeserializer<StatsEnvelope> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): StatsEnvelope {
        if (!json.isJsonObject) return StatsEnvelope()
        val obj = json.asJsonObject
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
        return StatsEnvelope(context.deserialize(data, StatsData::class.java))
    }
}
