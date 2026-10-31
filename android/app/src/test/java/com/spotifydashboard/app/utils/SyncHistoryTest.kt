package com.spotifydashboard.app.utils

import com.spotifydashboard.app.api.JobDto
import com.spotifydashboard.app.api.JobResultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the sync-history twin (utils/SyncHistory.kt) — the pure
 * logic behind the sync sheet's "Recent syncs" block. Pins the same behavior
 * the TS `utils/syncHistory.ts` contract covers on the other side.
 */
class SyncHistoryTest {

    private fun job(
        type: String = "sync",
        status: String = "done",
        error: String? = null,
        imported: Int = 0,
        existing: Int = 0,
        created: String? = null,
        finished: String? = null,
        result: JobResultData? = JobResultData(imported = imported, existing = existing),
    ) = JobDto(
        type = type,
        status = status,
        error = error,
        result = result,
        created_at = created,
        finished_at = finished,
    )

    // -------------------------------------------------------------------
    // parseIsoMillis
    // -------------------------------------------------------------------

    @Test
    fun `epoch zero parses`() {
        assertEquals(0L, SyncHistory.parseIsoMillis("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `z and numeric offsets describe the same instant`() {
        val z = SyncHistory.parseIsoMillis("2026-10-18T14:27:57Z")!!
        assertEquals(z, SyncHistory.parseIsoMillis("2026-10-18T14:27:57+00:00"))
        assertEquals(z, SyncHistory.parseIsoMillis("2026-10-18T16:27:57+02:00"))
        assertEquals(z, SyncHistory.parseIsoMillis("2026-10-18T16:27:57+0200"))
        assertEquals(z, SyncHistory.parseIsoMillis("2026-10-18T12:27:57-02:00"))
        // No offset at all = UTC; elixir/C# send stamps like this.
        assertEquals(z, SyncHistory.parseIsoMillis("2026-10-18T14:27:57"))
    }

    @Test
    fun `fractions truncate to milliseconds`() {
        val base = SyncHistory.parseIsoMillis("2026-10-18T14:27:57Z")!!
        assertEquals(base + 123L, SyncHistory.parseIsoMillis("2026-10-18T14:27:57.123Z"))
        assertEquals(base + 123L, SyncHistory.parseIsoMillis("2026-10-18T14:27:57.123456Z"))
        assertEquals(base, SyncHistory.parseIsoMillis("2026-10-18T14:27:57.000Z"))
    }

    @Test
    fun `invalid stamps are null, not exceptions`() {
        assertEquals(null, SyncHistory.parseIsoMillis(null))
        assertEquals(null, SyncHistory.parseIsoMillis("   "))
        assertEquals(null, SyncHistory.parseIsoMillis("not a date"))
        assertEquals(null, SyncHistory.parseIsoMillis("2026-13-99T99:99:99Z"))
        assertEquals(null, SyncHistory.parseIsoMillis("2026-10-18"))
        assertEquals(null, SyncHistory.parseIsoMillis("2026-10-18T14:27:57+2"))
    }

    // -------------------------------------------------------------------
    // relativeTime
    // -------------------------------------------------------------------

    @Test
    fun `age phrasing boundaries`() {
        val base = SyncHistory.parseIsoMillis("2026-10-18T14:27:57Z")!!
        val iso = "2026-10-18T14:27:57Z"
        assertEquals("just now", SyncHistory.relativeTime(iso, now = base + 30_000))
        assertEquals("1 min ago", SyncHistory.relativeTime(iso, now = base + 60_000))
        assertEquals("59 min ago", SyncHistory.relativeTime(iso, now = base + 59L * 60_000))
        assertEquals("1 hour ago", SyncHistory.relativeTime(iso, now = base + 60L * 60_000))
        assertEquals("3 hours ago", SyncHistory.relativeTime(iso, now = base + 3L * 3_600_000))
        assertEquals("1 day ago", SyncHistory.relativeTime(iso, now = base + 25L * 3_600_000))
        assertEquals("3 days ago", SyncHistory.relativeTime(iso, now = base + 3L * 24 * 3_600_000))
    }

    @Test
    fun `missing or unparseable stamps render no age`() {
        assertEquals("", SyncHistory.relativeTime(null))
        assertEquals("", SyncHistory.relativeTime(""))
        assertEquals("", SyncHistory.relativeTime("garbage"))
    }

    @Test
    fun `a future stamp clamps to just now`() {
        val base = SyncHistory.parseIsoMillis("2026-10-18T14:27:57Z")!!
        assertEquals("just now", SyncHistory.relativeTime("2026-10-18T14:27:57Z", now = base - 5_000))
    }

    @Test
    fun `stamps older than 30 days fall back to a date`() {
        val base = SyncHistory.parseIsoMillis("2026-10-18T14:27:57Z")!!
        val text = SyncHistory.relativeTime("2026-10-18T14:27:57Z", now = base + 31L * 24 * 3_600_000)
        assertTrue(text.isNotEmpty())
        assertFalse(text.contains("ago"))
    }

    // -------------------------------------------------------------------
    // recentSyncs / isTerminal
    // -------------------------------------------------------------------

    @Test
    fun `keeps finished sync jobs only, newest first, capped`() {
        val items = listOf(
            job(status = "done"),
            job(status = "running"),
            job(type = "playlist", status = "done"),
            job(status = "error"),
            job(status = "cancelled"),
            job(status = "done"),
            job(type = "queue", status = "done"),
        )
        val recent = SyncHistory.recentSyncs(items)
        assertEquals(3, recent.size)
        assertEquals(listOf("done", "error", "cancelled"), recent.map { it.status })
    }

    @Test
    fun `max is configurable`() {
        val items = List(5) { job() }
        assertEquals(5, SyncHistory.recentSyncs(items, max = 10).size)
        assertEquals(1, SyncHistory.recentSyncs(items, max = 1).size)
    }

    @Test
    fun `isTerminal covers exactly the three terminal states`() {
        assertTrue(SyncHistory.isTerminal("done"))
        assertTrue(SyncHistory.isTerminal("error"))
        assertTrue(SyncHistory.isTerminal("cancelled"))
        assertFalse(SyncHistory.isTerminal("running"))
        assertFalse(SyncHistory.isTerminal("queued"))
        assertFalse(SyncHistory.isTerminal(null))
    }

    // -------------------------------------------------------------------
    // outcome / rowText
    // -------------------------------------------------------------------

    @Test
    fun `outcome wording`() {
        assertEquals("12 added, 3 updated", SyncHistory.outcome(job(imported = 12, existing = 3)))
        assertEquals("0 added, 0 updated", SyncHistory.outcome(job()))
        assertEquals("0 added, 0 updated", SyncHistory.outcome(job(result = null)))
        assertEquals("Sync failed", SyncHistory.outcome(job(status = "error")))
        assertEquals("Sync failed", SyncHistory.outcome(job(status = "error", error = "   ")))
        assertEquals("rate limited", SyncHistory.outcome(job(status = "error", error = "rate limited")))
        assertEquals("Cancelled", SyncHistory.outcome(job(status = "cancelled")))
    }

    @Test
    fun `rowText appends an age only when a stamp exists`() {
        // elixir/C# rows carry no timestamps — outcome only.
        assertEquals("2 added, 0 updated", SyncHistory.rowText(job(imported = 2)))

        val finished = SyncHistory.rowText(
            job(imported = 2, finished = "2026-10-18T14:27:57Z"),
        )
        assertTrue(finished.startsWith("2 added, 0 updated · "))

        // created_at is the fallback when finished_at is absent.
        val createdOnly = SyncHistory.rowText(job(imported = 2, created = "2026-10-18T14:27:57Z"))
        assertTrue(createdOnly.startsWith("2 added, 0 updated · "))
    }
}
