package com.spotifydashboard.app.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A shortlived playlist scheduled on-device (local mode only). */
data class LocalRetentionEntry(
    val playlistId: String,
    val name: String,
    /** ISO-8601 UTC, e.g. 2026-10-20T18:00:00Z. */
    val expiresAt: String,
    val lastError: String? = null,
)

/**
 * Best-effort on-device retention store for local mode. Remote mode keeps
 * retention on the server, which owns the sweep worker there.
 *
 * Stored in SharedPreferences as a small JSON array so WorkManager can read
 * it without opening Room on the worker thread.
 */
object PlaylistRetentionStore {
    private const val PREFS = "playlist_retention"
    private const val KEY = "entries"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<LocalRetentionEntry> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("playlistId")
                val expiry = o.optString("expiresAt")
                if (id.isBlank() || expiry.isBlank()) null
                else LocalRetentionEntry(
                    playlistId = id,
                    name = o.optString("name"),
                    expiresAt = expiry,
                    lastError = o.optString("lastError").ifBlank { null },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, playlistId: String): LocalRetentionEntry? =
        all(context).firstOrNull { it.playlistId == playlistId }

    fun set(context: Context, entry: LocalRetentionEntry) {
        val merged = all(context).filterNot { it.playlistId == entry.playlistId } + entry
        save(context, merged)
    }

    fun clear(context: Context, playlistId: String) {
        save(context, all(context).filterNot { it.playlistId == playlistId })
    }

    fun markError(context: Context, playlistId: String, message: String) {
        val current = all(context)
        val updated = current.map {
            if (it.playlistId == playlistId) it.copy(lastError = message) else it
        }
        save(context, updated)
    }

    fun due(context: Context, now: Long = System.currentTimeMillis()): List<LocalRetentionEntry> =
        all(context).filter { entry ->
            parseIso(entry.expiresAt)?.let { it <= now } == true
        }

    /** ISO string the Python/Elixir/C# backends all accept. */
    fun isoUtc(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    fun parseIso(iso: String): Long? {
        val cleaned = iso.trim()
            .replace(Regex("\\.\\d+"), "")       // drop fractional seconds
            .replace("Z", "+00:00")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (!pattern.contains("XXX")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.isLenient = false
                val date = fmt.parse(cleaned) ?: continue
                return date.time
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    private fun save(context: Context, entries: List<LocalRetentionEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("playlistId", entry.playlistId)
                put("name", entry.name)
                put("expiresAt", entry.expiresAt)
                entry.lastError?.let { put("lastError", it) }
            })
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
