package com.spotifydashboard.app.utils

import java.util.Calendar
import java.util.Locale

/**
 * Date helpers for the concert calendar.
 *
 * The app supports API 24 and does not enable core library desugaring, so
 * `java.time` is off limits here: everything below uses `java.util.Calendar`.
 */
object ConcertCalendarDates {

    val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    val WEEKDAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** A month on the calendar: [year] plus a zero-based [month]. */
    data class Month(val year: Int, val month: Int) {
        fun shifted(delta: Int): Month {
            val c = Calendar.getInstance()
            c.clear()
            c.set(year, month, 1)
            c.add(Calendar.MONTH, delta)
            return Month(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        }

        fun label(): String = "${MONTH_NAMES[month]} $year"

        companion object {
            fun current(): Month {
                val c = Calendar.getInstance()
                return Month(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
            }
        }
    }

    /** One cell of the month grid. */
    data class Day(val iso: String, val day: Int, val inMonth: Boolean)

    /** Today as YYYY-MM-DD in the device's timezone. */
    fun todayIso(): String {
        val c = Calendar.getInstance()
        return format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * A Monday-first 6x7 grid covering [month]. The first and last rows can
     * belong to the neighbouring months (they are flagged `inMonth = false`).
     */
    fun monthGrid(month: Month): List<Day> {
        val c = Calendar.getInstance()
        c.clear()
        c.set(month.year, month.month, 1)
        // Calendar.SUNDAY == 1; Monday-first offset is (weekday + 5) % 7.
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_MONTH, -offset)
        return (0 until 42).map { index ->
            val d = c.clone() as Calendar
            d.add(Calendar.DAY_OF_MONTH, index)
            Day(
                iso = format(d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH)),
                day = d.get(Calendar.DAY_OF_MONTH),
                inMonth = d.get(Calendar.MONTH) == month.month && d.get(Calendar.YEAR) == month.year,
            )
        }
    }

    /**
     * The YYYY-MM-DD key of a concert date, or null when the value is not a
     * date (an empty string, free text, …).
     */
    fun dateKey(date: String?): String? {
        val raw = date?.trim().orEmpty()
        if (raw.length < 10) return null
        val key = raw.substring(0, 10)
        val parts = key.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return key
    }

    private fun format(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
}
