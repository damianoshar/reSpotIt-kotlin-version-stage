package com.spotifydashboard.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    /** Spotify `album_type`: album, single, compilation (or "" on pre-v3 rows). */
    @ColumnInfo(defaultValue = "''")
    val albumType: String = "album",
    val imageUrl: String,
    val imagePath: String,
    val addedAt: String
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String,
    val trackCount: Int,
    val albumCount: Int,
    val syncedAt: String
)

@Entity(
    tableName = "playlist_albums",
    primaryKeys = ["playlistId", "albumId"]
)
data class PlaylistAlbumEntity(
    val playlistId: String,
    val albumId: String,
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    val imageUrl: String,
    val trackCount: Int,
    val trackTitles: String,
    val addedAt: Long = 0
)

data class LibraryStats(
    val totalAlbums: Int,
    val totalArtists: Int,
    val totalGenres: Int,
    val newestYear: Int,
    val oldestYear: Int,
    // V-pass stats extras (Stats tab). Empty when the source has none — the
    // server's extras are optional (V9) and local mode always fills them.
    val topGenres: List<StatsBucket> = emptyList(),
    val topArtists: List<StatsBucket> = emptyList(),
    val albumsByYear: List<StatsBucket> = emptyList()
)

/** One named count in a stats breakdown (label = genre / artist / year). */
data class StatsBucket(
    val label: String,
    val count: Int
)

data class FilterState(
    val search: String = "",
    val genre: String = "",
    val artist: String = "",
    /** "" = all releases, "album" = albums/compilations, "single" = singles. */
    val albumType: String = "",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val sortField: String = "artist",
    val sortOrder: String = "asc"
)
