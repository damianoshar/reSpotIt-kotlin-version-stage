package com.spotifydashboard.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class PlaylistRetentionStoreTest {

    private val sample = "2026-10-20T21:36:00Z"

    @Test
    fun `parses UTC and numeric offsets to the same instant`() {
        val utc = PlaylistRetentionStore.parseIso(sample)
        val offset = PlaylistRetentionStore.parseIso("2026-10-21T00:36:00+03:00")

        assertEquals(utc, offset)
    }

    @Test
    fun `drops fractional seconds`() {
        assertEquals(
            PlaylistRetentionStore.parseIso(sample),
            PlaylistRetentionStore.parseIso("2026-10-20T21:36:00.987Z"),
        )
    }

    @Test
    fun `date-only values are midnight UTC`() {
        assertEquals(
            PlaylistRetentionStore.parseIso("2026-10-20T00:00:00Z"),
            PlaylistRetentionStore.parseIso("2026-10-20"),
        )
    }

    @Test
    fun `rejects invalid values`() {
        assertNull(PlaylistRetentionStore.parseIso("not-a-date"))
        assertNull(PlaylistRetentionStore.parseIso("2026-13-40T99:99:99Z"))
    }

    @Test
    fun `isoUtc round-trips the expiry`() {
        val millis = PlaylistRetentionStore.parseIso(sample)!!

        assertEquals(sample, PlaylistRetentionStore.isoUtc(millis))
    }

    @Test
    fun `isoUtc stays in UTC regardless of the device time zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val millis = PlaylistRetentionStore.parseIso(sample)!!

            assertEquals(sample, PlaylistRetentionStore.isoUtc(millis))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
