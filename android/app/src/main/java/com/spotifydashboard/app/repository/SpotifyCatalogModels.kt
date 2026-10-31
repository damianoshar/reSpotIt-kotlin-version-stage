package com.spotifydashboard.app.repository

/**
 * Phone-side catalogue items used by the Tools tab. These are deliberately
 * small UI models: the Retrofit DTOs stay in the api package.
 */
data class SpotifyCatalogAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val year: Int,
    val albumType: String,
    val albumGroup: String,
    val imageUrl: String,
    val totalTracks: Int,
    val releaseDate: String,
) {
    /** "Album", "Single", "Appears on" or a readable fallback. */
    val kindLabel: String
        get() = when (albumGroup.ifBlank { albumType }.lowercase()) {
            "album" -> "Album"
            "single" -> "Single"
            "appears_on" -> "Appears on"
            "compilation" -> "Compilation"
            else -> albumType.replaceFirstChar { it.uppercase() }
        }
}

data class SpotifyCatalogArtist(
    val id: String,
    val name: String,
    val imageUrl: String,
    val genres: List<String>,
    val followers: Long,
) {
    val subtitle: String
        get() = when {
            genres.isNotEmpty() -> genres.take(3).joinToString(", ")
            followers > 0 -> "${"%,d".format(followers)} followers"
            else -> "Spotify artist"
        }
}
