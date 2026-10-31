package com.spotifydashboard.app.utils

import com.spotifydashboard.app.data.AlbumEntity
import java.text.Normalizer
import java.util.Locale

/**
 * One group of library albums that look like the same release under different
 * Spotify IDs (deluxe/expanded editions, remasters, regional duplicates).
 */
data class DuplicateGroup(
    val key: String,
    val title: String,
    val artist: String,
    val albums: List<AlbumEntity>
)

/**
 * Conservative title/artist normalisation for the duplicate finder (card 269).
 *
 * The goal is to bring deluxe/regional copies together without merging
 * genuinely different releases. Bracketed words are kept unless they are a
 * variant tag ("Deluxe Edition", "Remastered", "UK") — so "(Live)" and
 * "(Acoustic)" stay distinct. Everything is reviewable in the UI anyway.
 */
object DuplicateFinder {

    private val bracketRegex = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")
    private val suffixSplit = Regex("\\s+[-–—]\\s+")
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val featSplit = Regex("\\s+(feat\\.?|featuring|ft\\.?)\\s+")

    /** Words that make a bracket/suffix a disposable variant tag. */
    private val variantWords = listOf(
        "deluxe", "expanded", "edition", "remaster", "remastered", "reissue",
        "bonus", "anniversary", "special", "collector", "collectors", "limited",
        "version", "explicit", "clean", "mono", "stereo", "digipak"
    )

    /** Country/region tags that only distinguish otherwise identical copies. */
    private val regionTags = setOf(
        "uk", "us", "usa", "eu", "europe", "european", "international",
        "japan", "japanese", "worldwide"
    )

    /** Phrases removed from titles after brackets/suffixes were handled. */
    private val removablePhrases = listOf(
        "deluxe edition", "deluxe version", "deluxe",
        "expanded edition", "expanded",
        "special edition", "collector's edition", "collectors edition",
        "limited edition", "anniversary edition", "remastered edition",
        "remastered", "remaster", "reissue",
        "bonus track version", "bonus tracks", "bonus disc",
        "explicit version", "clean version", "international version",
        "japanese edition", "uk version", "us version", "digipak"
    )

    fun find(albums: List<AlbumEntity>): List<DuplicateGroup> {
        val buckets = LinkedHashMap<String, MutableList<AlbumEntity>>()
        albums.forEach { album ->
            val title = normalizeTitle(album.title)
            val artist = primaryArtist(album.artist)
            if (title.isBlank() || artist.isBlank()) return@forEach
            buckets.getOrPut("$title|$artist") { mutableListOf() }.add(album)
        }

        return buckets.mapNotNull { (key, list) ->
            if (list.size < 2) return@mapNotNull null
            val sorted = list.sortedWith(
                compareBy<AlbumEntity>(
                    { if (it.year > 0) it.year else Int.MAX_VALUE },
                    { it.title.lowercase(Locale.ROOT) },
                    { it.id }
                )
            )
            DuplicateGroup(
                key = key,
                title = sorted.first().title,
                artist = sorted.first().artist,
                albums = sorted
            )
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun normalizeTitle(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(combiningMarks, "")
        s = s.replace("&", " and ")

        // Keep the content of ordinary brackets, but drop variant/region tags:
        // "Album (Deluxe Edition)" -> "album", "Album (Live)" -> "album live".
        s = bracketRegex.replace(s) { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim()
            if (isVariantTag(inner)) " " else " $inner "
        }

        // "Album - Deluxe Edition" / "Album - 2011 Remaster" lose the suffix.
        val parts = suffixSplit.split(s)
        if (parts.size > 1 && isVariantTag(parts.last())) {
            s = parts.dropLast(1).joinToString(" ")
        }

        removablePhrases.forEach { phrase ->
            s = s.replace(Regex("\\b${Regex.escape(phrase)}\\b"), " ")
        }

        s = nonAlnum.replace(s, " ").trim()
        if (s.startsWith("the ")) s = s.removePrefix("the ").trim()
        return s
    }

    fun primaryArtist(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(combiningMarks, "")
        s = featSplit.split(s).firstOrNull() ?: s
        s = s.substringBefore(",")
        s = nonAlnum.replace(s, " ").trim()
        if (s.startsWith("the ")) s = s.removePrefix("the ").trim()
        return s
    }

    private fun isVariantTag(text: String): Boolean {
        val t = nonAlnum.replace(text.lowercase(Locale.ROOT), " ").trim()
        if (t.isBlank()) return true
        if (t in regionTags) return true
        return variantWords.any { word -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(t) }
    }
}
