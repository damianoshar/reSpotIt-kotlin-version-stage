package com.spotifydashboard.app.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The one active app-generated queue playlist. */
data class QueuePlaylistRef(
    val playlistId: String,
    val name: String,
    /** The URIs the playlist holds, used for status and append deduplication. */
    val uris: List<String> = emptyList(),
)

/** Last position seen while the local playlist queue was playing. */
data class LocalQueueCursor(
    val playlistId: String,
    val index: Int,
    val uri: String?,
)

/**
 * A queue playlist that is waiting for the current track to finish before
 * playback moves over to it.
 */
data class PendingQueueSwitch(
    val playlistId: String,
    val name: String,
    val deviceId: String?,
    /** The playback context that has to still be active for the switch to run. */
    val expectedContextUri: String,
    /** The queue playlist this one replaces; deleted once the switch happened. */
    val previousPlaylistId: String?,
)

/**
 * Remembers the one active queue playlist, so append operations keep using the
 * same playlist across app restarts. A reset replaces the pointer and removes
 * the previous playlist once the new one is playing.
 */
object QueuePlaylistStore {
    private const val PREFS = "queue_playlist"
    private const val KEY_ID = "playlist_id"
    private const val KEY_NAME = "playlist_name"
    private const val KEY_URIS = "playlist_uris"
    private const val KEY_PENDING = "pending_switch"

    // Local-playlist mode keeps its own active playlist so both queue models
    // can be tested side by side.
    private const val KEY_LOCAL_ID = "local_playlist_id"
    private const val KEY_LOCAL_NAME = "local_playlist_name"
    private const val KEY_LOCAL_URIS = "local_playlist_uris"
    private const val KEY_LOCAL_CURSOR_PLAYLIST = "local_cursor_playlist"
    private const val KEY_LOCAL_CURSOR_INDEX = "local_cursor_index"
    private const val KEY_LOCAL_CURSOR_URI = "local_cursor_uri"
    private const val KEY_MODE = "queue_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): QueuePlaylistRef? {
        val id = prefs(context).getString(KEY_ID, null).orEmpty()
        if (id.isBlank()) return null
        return QueuePlaylistRef(
            playlistId = id,
            name = prefs(context).getString(KEY_NAME, "").orEmpty(),
            uris = uris(context),
        )
    }

    fun set(context: Context, playlistId: String, name: String, uris: List<String> = emptyList()) {
        prefs(context).edit()
            .putString(KEY_ID, playlistId)
            .putString(KEY_NAME, name)
            .putString(KEY_URIS, JSONArray(uris).toString())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ID).remove(KEY_NAME).remove(KEY_URIS).apply()
    }

    // --- local-playlist queue ----------------------------------------------

    fun getLocal(context: Context): QueuePlaylistRef? {
        val id = prefs(context).getString(KEY_LOCAL_ID, null).orEmpty()
        if (id.isBlank()) return null
        return QueuePlaylistRef(
            playlistId = id,
            name = prefs(context).getString(KEY_LOCAL_NAME, "").orEmpty(),
            uris = uris(context, KEY_LOCAL_URIS),
        )
    }

    fun setLocal(context: Context, playlistId: String, name: String, uris: List<String> = emptyList()) {
        prefs(context).edit()
            .putString(KEY_LOCAL_ID, playlistId)
            .putString(KEY_LOCAL_NAME, name)
            .putString(KEY_LOCAL_URIS, JSONArray(uris).toString())
            .apply()
    }

    fun clearLocal(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOCAL_ID)
            .remove(KEY_LOCAL_NAME)
            .remove(KEY_LOCAL_URIS)
            .apply()
    }

    fun getLocalCursor(context: Context): LocalQueueCursor? {
        val playlistId = prefs(context).getString(KEY_LOCAL_CURSOR_PLAYLIST, null).orEmpty()
        if (playlistId.isBlank()) return null
        return LocalQueueCursor(
            playlistId = playlistId,
            index = prefs(context).getInt(KEY_LOCAL_CURSOR_INDEX, -1),
            uri = prefs(context).getString(KEY_LOCAL_CURSOR_URI, null),
        )
    }

    fun setLocalCursor(context: Context, playlistId: String, index: Int, uri: String?) {
        prefs(context).edit()
            .putString(KEY_LOCAL_CURSOR_PLAYLIST, playlistId)
            .putInt(KEY_LOCAL_CURSOR_INDEX, index)
            .putString(KEY_LOCAL_CURSOR_URI, uri)
            .apply()
    }

    fun clearLocalCursor(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOCAL_CURSOR_PLAYLIST)
            .remove(KEY_LOCAL_CURSOR_INDEX)
            .remove(KEY_LOCAL_CURSOR_URI)
            .apply()
    }

    fun getMode(context: Context): QueueMode {
        val raw = prefs(context).getString(KEY_MODE, null).orEmpty()
        return if (raw == QueueMode.MERGE.name) QueueMode.MERGE else QueueMode.LOCAL
    }

    fun setMode(context: Context, mode: QueueMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    // --- pending switch -----------------------------------------------------

    fun pending(context: Context): PendingQueueSwitch? {
        val raw = prefs(context).getString(KEY_PENDING, null).orEmpty()
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val id = o.optString("playlistId")
            if (id.isBlank()) null
            else PendingQueueSwitch(
                playlistId = id,
                name = o.optString("name"),
                deviceId = o.optString("deviceId").ifBlank { null },
                expectedContextUri = o.optString("expectedContextUri"),
                previousPlaylistId = o.optString("previousPlaylistId").ifBlank { null },
            )
        } catch (_: Exception) {
            null
        }
    }

    fun setPending(context: Context, pending: PendingQueueSwitch?) {
        val editor = prefs(context).edit()
        if (pending == null) {
            editor.remove(KEY_PENDING)
        } else {
            editor.putString(
                KEY_PENDING,
                JSONObject().apply {
                    put("playlistId", pending.playlistId)
                    put("name", pending.name)
                    put("deviceId", pending.deviceId.orEmpty())
                    put("expectedContextUri", pending.expectedContextUri)
                    put("previousPlaylistId", pending.previousPlaylistId.orEmpty())
                }.toString(),
            )
        }
        editor.apply()
    }

    private fun uris(context: Context, key: String = KEY_URIS): List<String> {
        val raw = prefs(context).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
