package com.spotifydashboard.app.utils

import android.content.Context
import com.spotifydashboard.app.data.AlbumEntity
import java.io.*
import java.util.*

/** Result of a CSV import, mirroring TS `SyncResult` (imported/skipped/errors). */
data class CsvImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
    /** Albums deleted by the server's mirror mode (0 for a plain import). */
    val removed: Int = 0,
    /** Albums that already existed server-side (0 for a local import). */
    val existing: Int = 0
)

/** A CSV file written to storage, mirroring TS `BackupResult`. */
data class BackupFile(val path: String, val filename: String, val bytes: Long)

data class ParsedLibraryRow(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    val imageUrl: String,
    val addedAt: String,
    /** Spotify release type; old backups without the column default to album. */
    val albumType: String = "album"
)

data class ParsedLibraryCsv(val rows: List<ParsedLibraryRow>, val skipped: Int, val errors: List<String>)

object CSVHelper {
    fun exportAlbumsToCSV(context: Context, albums: List<AlbumEntity>): File? {
        if (albums.isEmpty()) return null
        
        val csv = buildString {
            // album_type stays last so older positional readers still see the
            // original seven columns in the same order.
            appendLine("id,title,artist,year,genre,image_url,added_at,album_type")
            albums.forEach { album ->
                appendLine("${escapeCsv(album.id)},${escapeCsv(album.title)},${escapeCsv(album.artist)},${album.year},${escapeCsv(album.genre)},${escapeCsv(album.imageUrl)},${escapeCsv(album.addedAt)},${escapeCsv(album.albumType)}")
            }
        }
        
        return try {
            val documentsDir = context.getExternalFilesDir(null)
            val filename = "spotify-library-${java.time.LocalDate.now()}.csv"
            val file = File(documentsDir, filename)
            file.writeText(csv)
            file
        } catch (e: Exception) {
            null
        }
    }

    fun importFromCSV(csvContent: String): List<AlbumEntity> {
        val albums = mutableListOf<AlbumEntity>()
        val lines = csvContent.split("\n").drop(1)
        
        for (line in lines) {
            if (line.isBlank()) continue
            val cols = parseCSVLine(line)
            if (cols.size < 6) continue
            
            val id = cols[0].trim()
            val title = cols[1].trim()
            val artist = cols[2].trim()
            
            if (id.isEmpty() || title.isEmpty() || artist.isEmpty()) continue
            
            albums.add(
                AlbumEntity(
                    id = id,
                    title = title,
                    artist = artist,
                    year = cols[3].trim().toIntOrNull() ?: 0,
                    genre = cols[4].trim(),
                    imageUrl = cols[5].trim(),
                    imagePath = "",
                    addedAt = if (cols.size > 6) cols[6].trim() else "",
                    albumType = if (cols.size > 7) cols[7].trim().ifBlank { "album" } else "album"
                )
            )
        }
        
        return albums
    }

    private fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' -> {
                    // Escaped quote ("") inside a quoted field
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun normalizeHeader(h: String): String =
        h.trim().lowercase().replace("\\s+".toRegex(), "_")

    /** Maps known header aliases to canonical field names (mirrors TS transformHeader + row access). */
    private fun canonicalField(header: String): String? = when (normalizeHeader(header)) {
        "id", "spotify_id", "album_id" -> "id"
        "title", "album_title", "album", "name", "album_name" -> "title"
        "artist", "artists", "artist_name" -> "artist"
        "year", "release_year" -> "year"
        "genre", "genres" -> "genre"
        "image_url", "imageurl", "image", "cover", "cover_url", "artwork", "artwork_url" -> "image_url"
        "added_at", "date_added", "added" -> "added_at"
        "album_type", "albumtype", "release_type", "type" -> "album_type"
        else -> null
    }

    /**
     * Parses a library CSV with tolerant headers (case/whitespace-insensitive,
     * supports id/spotify_id, title/album_title, image_url aliases).
     * Rows missing id/title/artist are skipped with an error message.
     */
    fun parseLibraryCsv(csvText: String): ParsedLibraryCsv {
        val rows = mutableListOf<ParsedLibraryRow>()
        val errors = mutableListOf<String>()
        var skipped = 0

        val lines = csvText.split("\n")
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return ParsedLibraryCsv(emptyList(), 0, listOf("CSV file is empty"))
        }

        val headers = parseCSVLine(lines[0])
        val colIndex = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, h ->
            canonicalField(h)?.let { field -> colIndex.putIfAbsent(field, index) }
        }

        val missing = listOf("id", "title", "artist").filter { it !in colIndex }
        if (missing.isNotEmpty()) {
            return ParsedLibraryCsv(
                emptyList(), 0,
                listOf("Missing required column(s): ${missing.joinToString(", ")}")
            )
        }

        fun cell(cols: List<String>, field: String): String {
            val idx = colIndex[field] ?: return ""
            return if (idx < cols.size) cols[idx].trim() else ""
        }

        for ((lineNo, line) in lines.drop(1).withIndex()) {
            val cols = parseCSVLine(line)
            val id = cell(cols, "id")
            val title = cell(cols, "title")
            val artist = cell(cols, "artist")
            if (id.isEmpty() || title.isEmpty() || artist.isEmpty()) {
                skipped++
                if (errors.size < 20) errors.add("Line ${lineNo + 2}: skipped — missing id/title/artist")
                continue
            }
            rows.add(
                ParsedLibraryRow(
                    id = id,
                    title = title,
                    artist = artist,
                    year = cell(cols, "year").toIntOrNull() ?: 0,
                    genre = cell(cols, "genre"),
                    imageUrl = cell(cols, "image_url"),
                    addedAt = cell(cols, "added_at"),
                    albumType = cell(cols, "album_type").ifBlank { "album" }.lowercase()
                )
            )
        }
        return ParsedLibraryCsv(rows, skipped, errors)
    }

    /** Builds a CSV string from headers + rows with proper escaping. */
    fun buildCsv(headers: List<String>, rows: List<List<Any?>>): String = buildString {
        appendLine(headers.joinToString(","))
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escapeCsv(it?.toString() ?: "") })
        }
    }.trimEnd('\n')

    /**
     * Writes CSV text into Documents/[subdir]/filename under the app-specific
     * external dir (no storage permission needed). Shared by library + playlist export.
     */
    fun writeBackupFile(context: Context, csv: String, subdir: String, filename: String): BackupFile {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val dir = if (subdir.isBlank()) base else File(base, subdir.trim()).also { it.mkdirs() }
        val file = File(dir, filename)
        file.writeText(csv)
        return BackupFile(file.absolutePath, filename, file.length())
    }

    private fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }
}
