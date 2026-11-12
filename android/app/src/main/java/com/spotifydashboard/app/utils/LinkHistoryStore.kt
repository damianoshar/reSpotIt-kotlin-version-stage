package com.spotifydashboard.app.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent searches + saved links for the "add from a page" entry points.
 *
 * Buckets are kept separate so the concerts list does not mix with album scans:
 *   concerts — ConcertsDialog
 *   albums   — AiUrlScanDialog (AI scan and pasted Spotify links)
 *   tracks   — AiTrackScanDialog (AI track scan)
 *   site     — AddFromSiteDialog (in-app browser)
 *
 * Device-local SharedPreferences: small, per-device, and survives restarts.
 * Nothing here is user-scoped server data.
 */
enum class LinkTarget { URL, TEXT }

/**
 * Compose-observable write counter. A dialog that records a recent (e.g. on
 * scan) and the chip row are different composables, so a bump here is what
 * makes an open dialog re-read the store.
 */
object LinkHistoryBus {
    var version by mutableIntStateOf(0)
        private set

    fun bump() { version++ }
}

data class LinkHistoryEntry(
    val value: String,
    val label: String,
    val target: LinkTarget,
    val usedAt: Long,
    val id: String? = null,
)

class LinkHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recent(kind: String): List<LinkHistoryEntry> = read(kind).first

    fun saved(kind: String): List<LinkHistoryEntry> = read(kind).second

    fun isSaved(kind: String, value: String): Boolean =
        saved(kind).any { it.value.trim() == value.trim() }

    fun recordRecent(kind: String, value: String, target: LinkTarget) {
        if (!isStorable(value, target)) return
        val (recent, savedLinks) = read(kind)
        val entry = LinkHistoryEntry(
            value = value,
            label = describeLink(value, target),
            target = target,
            usedAt = System.currentTimeMillis(),
        )
        val next = (listOf(entry) + recent.filterNot { it.value.trim() == value.trim() })
            .take(RECENT_LIMIT)
        write(kind, next, savedLinks)
    }

    fun save(kind: String, value: String, target: LinkTarget) {
        if (!isStorable(value, target)) return
        val (recent, savedLinks) = read(kind)
        if (savedLinks.any { it.value.trim() == value.trim() }) return
        val entry = LinkHistoryEntry(
            value = value,
            label = describeLink(value, target),
            target = target,
            usedAt = System.currentTimeMillis(),
            id = "saved-${System.currentTimeMillis()}-${(0..0xFFFFFF).random().toString(16)}",
        )
        write(kind, recent, (listOf(entry) + savedLinks).take(SAVED_LIMIT))
    }

    fun removeSaved(kind: String, id: String) {
        val (recent, savedLinks) = read(kind)
        write(kind, recent, savedLinks.filterNot { it.id == id })
    }

    /** Returns true when the value is now saved. */
    fun toggleSaved(kind: String, value: String, target: LinkTarget): Boolean {
        val existing = saved(kind).firstOrNull { it.value.trim() == value.trim() }
        if (existing != null) {
            existing.id?.let { removeSaved(kind, it) }
            return false
        }
        save(kind, value, target)
        return true
    }

    // --- storage -----------------------------------------------------------

    private fun read(kind: String): Pair<List<LinkHistoryEntry>, List<LinkHistoryEntry>> {
        val raw = prefs.getString(key(kind), null) ?: return emptyList<LinkHistoryEntry>() to emptyList()
        return try {
            val root = JSONObject(raw)
            parse(root.optJSONArray("recent"), withId = false) to
                parse(root.optJSONArray("saved"), withId = true)
        } catch (_: Exception) {
            emptyList<LinkHistoryEntry>() to emptyList()
        }
    }

    private fun parse(array: JSONArray?, withId: Boolean): List<LinkHistoryEntry> {
        if (array == null) return emptyList()
        val out = ArrayList<LinkHistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val value = o.optString("value")
            if (value.isBlank()) continue
            val target = if (o.optString("target") == "text") LinkTarget.TEXT else LinkTarget.URL
            out.add(
                LinkHistoryEntry(
                    value = value,
                    label = o.optString("label").ifBlank { describeLink(value, target) },
                    target = target,
                    usedAt = o.optLong("usedAt"),
                    id = if (withId) o.optString("id").ifBlank { null } else null,
                )
            )
        }
        return out
    }

    private fun write(
        kind: String,
        recent: List<LinkHistoryEntry>,
        savedLinks: List<LinkHistoryEntry>,
    ) {
        val root = JSONObject()
        root.put("recent", toJson(recent, withId = false))
        root.put("saved", toJson(savedLinks, withId = true))
        prefs.edit().putString(key(kind), root.toString()).apply()
        LinkHistoryBus.bump()
    }

    private fun toJson(entries: List<LinkHistoryEntry>, withId: Boolean): JSONArray {
        val array = JSONArray()
        for (entry in entries) {
            val o = JSONObject()
            o.put("value", entry.value)
            o.put("label", entry.label)
            o.put("target", if (entry.target == LinkTarget.TEXT) "text" else "url")
            o.put("usedAt", entry.usedAt)
            if (withId && entry.id != null) o.put("id", entry.id)
            array.put(o)
        }
        return array
    }

    private fun key(kind: String) = "history.$kind"

    companion object {
        private const val PREFS = "link_history_v1"
        private const val RECENT_LIMIT = 8
        private const val SAVED_LIMIT = 40
        private const val MAX_URL_CHARS = 2_000
        private const val MAX_TEXT_CHARS = 50_000

        private fun isStorable(value: String, target: LinkTarget): Boolean {
            val limit = if (target == LinkTarget.TEXT) MAX_TEXT_CHARS else MAX_URL_CHARS
            return value.isNotBlank() && value.length <= limit
        }

        /** Short, human label for a chip: host+path for URLs, first line for text. */
        fun describeLink(value: String, target: LinkTarget): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return ""
            if (target == LinkTarget.TEXT) {
                val line = trimmed.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    ?: "Pasted text"
                return if (line.length > 60) line.take(57) + "…" else line
            }
            val noScheme = trimmed.substringAfter("://", trimmed)
            val hostAndPath = noScheme.substringBefore('?').substringBefore('#').trimEnd('/')
            val label = hostAndPath.removePrefix("www.")
            return if (label.length > 60) label.take(57) + "…" else label.ifEmpty { trimmed.take(60) }
        }
    }
}
