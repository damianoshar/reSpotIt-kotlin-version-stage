package com.spotifydashboard.app.utils

import com.spotifydashboard.app.utils.ConcertCalendarDates.Month
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcertCalendarDatesTest {

    @Test
    fun `grid has six weeks of seven days`() {
        val grid = ConcertCalendarDates.monthGrid(Month(2026, 10)) // November 2026
        assertEquals(42, grid.size)
    }

    @Test
    fun `grid starts on a monday and flags the neighbouring days`() {
        // 1 November 2026 is a Sunday, so the grid starts on 26 October.
        val grid = ConcertCalendarDates.monthGrid(Month(2026, 10))
        assertEquals("2026-10-26", grid.first().iso)
        assertFalse(grid.first().inMonth)
        assertEquals("2026-11-01", grid[6].iso)
        assertTrue(grid[6].inMonth)
        assertEquals("2026-11-30", grid[35].iso)
        assertTrue(grid[35].inMonth)
    }

    @Test
    fun `month can be shifted forwards and backwards`() {
        val month = Month(2026, 10)
        assertEquals(Month(2026, 11), month.shifted(1))
        assertEquals(Month(2027, 0), month.shifted(2))
        assertEquals(Month(2026, 9), month.shifted(-1))
        assertEquals(Month(2026, 8), month.shifted(-1).shifted(-1))
    }

    @Test
    fun `month label is readable`() {
        assertEquals("November 2026", Month(2026, 10).label())
        assertEquals("January 2027", Month(2027, 0).label())
    }

    @Test
    fun `date key is only returned for real dates`() {
        assertEquals("2026-11-20", ConcertCalendarDates.dateKey("2026-11-20"))
        assertEquals("2026-11-20", ConcertCalendarDates.dateKey(" 2026-11-20 "))
        assertEquals("2026-11-20", ConcertCalendarDates.dateKey("2026-11-20T21:00:00"))
        assertNull(ConcertCalendarDates.dateKey(""))
        assertNull(ConcertCalendarDates.dateKey("date TBD"))
        assertNull(ConcertCalendarDates.dateKey("2026-13-01"))
        assertNull(ConcertCalendarDates.dateKey(null))
    }
}
