package com.spotifydashboard.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the CSV helpers behind library backup/import — the pure
 * parsing/escaping logic (the android.content.Context paths are not touched;
 * the mockable android.jar keeps the class loadable in local unit tests).
 */
class CSVHelperTest {

    // -------------------------------------------------------------------
    // buildCsv / escaping
    // -------------------------------------------------------------------

    @Test
    fun `buildCsv escapes commas, quotes and keeps nulls empty`() {
        val csv = CSVHelper.buildCsv(
            headers = listOf("id", "title", "note"),
            rows = listOf(
                listOf("1", "Hello, World", "He said \"hi\""),
                listOf("2", "Plain", null),
            ),
        )
        assertEquals(
            "id,title,note\n1,\"Hello, World\",\"He said \"\"hi\"\"\"\n2,Plain,",
            csv,
        )
    }

    @Test
    fun `buildCsv quotes embedded newlines`() {
        val csv = CSVHelper.buildCsv(listOf("a"), listOf(listOf("line1\nline2")))
        assertEquals("a\n\"line1\nline2\"", csv)
    }

    @Test
    fun `headers-only CSV has no trailing newline`() {
        assertEquals("a,b", CSVHelper.buildCsv(listOf("a", "b"), emptyList()))
    }

    // -------------------------------------------------------------------
    // parseLibraryCsv
    // -------------------------------------------------------------------

    @Test
    fun `empty input reports an error`() {
        val parsed = CSVHelper.parseLibraryCsv("")
        assertEquals(0, parsed.rows.size)
        assertEquals(listOf("CSV file is empty"), parsed.errors)
    }

    @Test
    fun `missing required columns are reported by name`() {
        val parsed = CSVHelper.parseLibraryCsv("title,artist\nA,B")
        assertEquals(0, parsed.rows.size)
        assertEquals(listOf("Missing required column(s): id"), parsed.errors)
    }

    @Test
    fun `header aliases and case are tolerated`() {
        val csv = listOf(
            "Spotify ID,Album Title,Artist Name,Release Year,Genres,Cover URL,Date Added",
            "abc123,Discovery,Daft Punk,2001,Electronic,http://img/1,2026-01-02",
        ).joinToString("\n")
        val parsed = CSVHelper.parseLibraryCsv(csv)
        assertEquals(0, parsed.skipped)
        assertEquals(1, parsed.rows.size)
        val row = parsed.rows[0]
        assertEquals("abc123", row.id)
        assertEquals("Discovery", row.title)
        assertEquals("Daft Punk", row.artist)
        assertEquals(2001, row.year)
        assertEquals("Electronic", row.genre)
        assertEquals("http://img/1", row.imageUrl)
        assertEquals("2026-01-02", row.addedAt)
        assertEquals("album", row.albumType)
    }

    @Test
    fun `album type header is parsed and defaults to album`() {
        val csv = "id,title,artist,Album Type\n" +
            "1,A,B,Single\n" +
            "2,C,D,\n"
        val parsed = CSVHelper.parseLibraryCsv(csv)
        assertEquals(2, parsed.rows.size)
        assertEquals("single", parsed.rows[0].albumType)
        assertEquals("album", parsed.rows[1].albumType)
    }

    @Test
    fun `quoted fields with commas and escaped quotes parse`() {
        val csv = "id,title,artist,year,genre,image_url,added_at\n" +
            "1,\"Hello, World\",\"The \"\"Quotes\"\"\",2020,G,U,\n"
        val parsed = CSVHelper.parseLibraryCsv(csv)
        assertEquals(1, parsed.rows.size)
        assertEquals("Hello, World", parsed.rows[0].title)
        assertEquals("The \"Quotes\"", parsed.rows[0].artist)
    }

    @Test
    fun `CRLF and blank lines are tolerated`() {
        val csv = "id,title,artist\r\n1,A,B\r\n\r\n2,C,D\r\n"
        val parsed = CSVHelper.parseLibraryCsv(csv)
        assertEquals(2, parsed.rows.size)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `rows missing id title or artist are skipped with line numbers`() {
        val parsed = CSVHelper.parseLibraryCsv("id,title,artist\n1,,B\n2,C,D\n,E,F")
        assertEquals(1, parsed.rows.size)
        assertEquals(2, parsed.skipped)
        assertEquals("Line 2: skipped — missing id/title/artist", parsed.errors[0])
        assertEquals("Line 4: skipped — missing id/title/artist", parsed.errors[1])
    }

    @Test
    fun `non-numeric year falls back to zero`() {
        val parsed = CSVHelper.parseLibraryCsv("id,title,artist,year\n1,A,B,not-a-year")
        assertEquals(0, parsed.rows[0].year)
    }

    @Test
    fun `error list is capped at 20 but the skip count is not`() {
        val badRows = (1..25).joinToString("\n") { ",," }
        val parsed = CSVHelper.parseLibraryCsv("id,title,artist\n$badRows")
        assertEquals(25, parsed.skipped)
        assertEquals(20, parsed.errors.size)
    }

    // -------------------------------------------------------------------
    // legacy importFromCSV
    // -------------------------------------------------------------------

    @Test
    fun `legacy import drops the header and short rows`() {
        val csv = "id,title,artist,year,genre,image_url\n" +
            "1,A,B,1999,Rock,img\n" +
            "2,short,row\n" +
            "\n" +
            ",,,\n"
        val albums = CSVHelper.importFromCSV(csv)
        assertEquals(1, albums.size)
        assertEquals("1", albums[0].id)
        assertEquals("A", albums[0].title)
        assertEquals(1999, albums[0].year)
    }
}
