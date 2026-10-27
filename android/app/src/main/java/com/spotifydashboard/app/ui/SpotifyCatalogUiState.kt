package com.spotifydashboard.app.ui

import com.spotifydashboard.app.repository.SpotifyCatalogAlbum
import com.spotifydashboard.app.repository.SpotifyCatalogArtist

/** State for Tools -> Playlist from Spotify search. */
data class SpotifySearchPlaylistState(
    val query: String = "",
    val results: List<SpotifyCatalogAlbum> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
)

/** State for Tools -> Artist discography playlist. */
data class ArtistDiscographyState(
    val query: String = "",
    val artists: List<SpotifyCatalogArtist> = emptyList(),
    val selectedArtist: SpotifyCatalogArtist? = null,
    val albums: List<SpotifyCatalogAlbum> = emptyList(),
    val loadingArtists: Boolean = false,
    val loadingAlbums: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val includeAlbums: Boolean = true,
    val includeSingles: Boolean = true,
    val includeAppearsOn: Boolean = false,
) {
    fun selectedGroups(): Set<String> = mutableSetOf<String>().apply {
        if (includeAlbums) add("album")
        if (includeSingles) add("single")
        if (includeAppearsOn) add("appears_on")
    }
}

data class CatalogPlaylistProgress(
    val albumsDone: Int,
    val albumsTotal: Int,
    val tracksFound: Int,
)

/** Progress for the catalogue "add to library" action. */
data class CatalogLibraryProgress(
    val done: Int,
    val total: Int,
    val stage: String,
)

/** Success/failure reported back to the Tools dialogs. */
data class CatalogPlaylistOutcome(
    val playlistId: String = "",
    val playlistName: String = "",
    val albumsAdded: Int = 0,
    val tracksAdded: Int = 0,
    val duplicatesSkipped: Int = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/** Success/failure of saving catalogue releases to the Spotify library. */
data class CatalogLibraryOutcome(
    val albumsAdded: Int = 0,
    val albumsImported: Int = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}
