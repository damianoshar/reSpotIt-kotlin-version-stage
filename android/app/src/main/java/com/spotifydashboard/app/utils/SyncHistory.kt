package com.spotifydashboard.app.utils

import com.spotifydashboard.app.api.JobDto
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sync-history helpers — the Kotlin twin of the TS `utils/syncHistory.ts`, so
 * the sync sheet keeps one definition of "which rows of GET /api/v1/sync/history
 * are recent syncs, and how do we word them".
 *
 * The endpoint is canonical (elixir, C# and python all serve it): {data: [job
 * envelope]} listing EVERY job type, newest first. `created_at`/`finished_at`
 * are optional extras — python adds them, elixir and C# send the bare envelope
 * — so nothing here may require them.
 *
 * minSdk is 24 (no java.time without desugaring), so the parser below is a
 * small hand-rolled equivalent of JS `Date.parse`, tolerant of python's
 * microsecond fractions and "+hh:mm" / "+hhmm" / "Z" offsets.
 */
object SyncHistory {

    private val TERMINAL = setOf("done", "error", "cancelled")

    fun isTerminal(status: String?): Boolean = TERMINAL.contains(status)

    /**
     * The last few FINISHED library syncs, newest first (server order kept).
     *
     * History lists queue and playlist jobs too, which are not "recent syncs",
     * and a still-running job is not a finished run — both are filtered out
     * rather than rendered as if they had an outcome.
     */
    fun recentSyncs(items: List<JobDto>, max: Int = 3): List<JobDto> =
        items.filter { isTerminal(it.status) && it.type == "sync" }.take(max)

    /** "12 added, 3 updated" for a finished run, or why it did not finish. */
    fun outcome(job: JobDto): String = when (job.status) {
        "error" -> job.error?.takeIf { it.isNotBlank() } ?: "Sync failed"
        "cancelled" -> "Cancelled"
        else -> "${job.result?.imported ?: 0} added, ${job.result?.existing ?: 0} updated"
    }

    /**
     * One history line: the outcome plus the age when a timestamp exists.
     * Elixir and C# send no timestamps, so most rows are just the outcome.
     */
    fun rowText(job: JobDto): String {
        val age = relativeTime(job.finished_at ?: job.created_at)
        return if (age.isEmpty()) outcome(job) else "${outcome(job)} · $age"
    }

    /**
     * "4 min ago" style age; "" when the stamp is absent or unparseable.
     *
     * A missing stamp must render as no age at all — "" rather than an invented
     * date. `now` is injectable so callers can pin boundaries.
     */
    fun relativeTime(iso: String?, now: Long = System.currentTimeMillis()): String {
        val t = parseIsoMillis(iso) ?: return ""
        val secs = ((now - t) / 1000).coerceAtLeast(0)
        if (secs < 60) return "just now"
        val mins = secs / 60
        if (mins < 60) return "$mins min ago"
        val hours = mins / 60
        if (hours < 24) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
        val days = hours / 24
        if (days < 30) return if (days == 1L) "1 day ago" else "$days days ago"
        return DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(t))
    }

    // -----------------------------------------------------------------------
    // ISO-8601 parsing
    // -----------------------------------------------------------------------

    private val ISO = Regex(
        """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$"""
    )

    /** ISO-8601 → epoch millis; null for null/blank/unparseable input. */
    fun parseIsoMillis(raw: String?): Long? {
        val m = ISO.matchEntire(raw?.trim() ?: return null) ?: return null
        // JS tolerates any fraction length; SimpleDateFormat's SSS does not, so
        // truncate to milliseconds.
        val millis = (m.groupValues[2] + "000").take(3)
        val offset = when {
            m.groupValues[3].isEmpty() || m.groupValues[3] == "Z" -> "+00:00"
            m.groupValues[3].contains(':') -> m.groupValues[3]
            else -> m.groupValues[3].substring(0, 3) + ":" + m.groupValues[3].substring(3)
        }
        val normalized = "${m.groupValues[1]}.$millis$offset"
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.isLenient = false
        return try {
            fmt.parse(normalized)?.time
        } catch (e: Exception) {
            null
        }
    }
}
