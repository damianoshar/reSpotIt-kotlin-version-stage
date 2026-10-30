package com.spotifydashboard.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    // Simple query - no filters, for debugging
    @Query("SELECT * FROM albums ORDER BY LOWER(title) ASC LIMIT :limit OFFSET :offset")
    fun getAllAlbums(limit: Int, offset: Int): Flow<List<AlbumEntity>>

    // Count all albums
    @Query("SELECT COUNT(*) FROM albums")
    suspend fun countAllAlbums(): Int

    // Filtered queries with simpler null handling
    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(artist) ASC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByArtist(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(artist) DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByArtistDesc(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(title) ASC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByTitle(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(title) DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByTitleDesc(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY year ASC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByYear(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY year DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByYearDesc(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(genre) ASC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByGenre(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY LOWER(genre) DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByGenreDesc(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY addedAt ASC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByAddedAt(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("""
        SELECT * FROM albums 
        WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        AND (
            :albumType = '' OR
            (:albumType = 'album' AND LOWER(albumType) != 'single') OR
            (:albumType = 'single' AND LOWER(albumType) = 'single')
        )
        ORDER BY addedAt DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getAlbumsByAddedAtDesc(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("SELECT COUNT(*) FROM albums WHERE (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query) AND (:genre = '' OR LOWER(genre) = :genre) AND (:artistFilter = '' OR LOWER(artist) = :artistFilter) AND (:yearFrom IS NULL OR year >= :yearFrom) AND (:yearTo IS NULL OR year <= :yearTo) AND (:albumType = '' OR (:albumType = 'album' AND LOWER(albumType) != 'single') OR (:albumType = 'single' AND LOWER(albumType) = 'single'))")
    suspend fun countAlbums(query: String, genre: String, artistFilter: String, albumType: String, yearFrom: Int?, yearTo: Int?): Int

    @Query("SELECT DISTINCT genre FROM albums WHERE genre != '' ORDER BY genre")
    suspend fun getDistinctGenres(): List<String>

    @Query("SELECT DISTINCT artist FROM albums WHERE artist != '' ORDER BY artist")
    suspend fun getDistinctArtists(): List<String>

    @Query("SELECT DISTINCT year FROM albums WHERE year > 0 ORDER BY year")
    suspend fun getDistinctYears(): List<Int>

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getTotalAlbumCount(): Int

    @Query("SELECT COUNT(DISTINCT artist) FROM albums")
    suspend fun getTotalArtistCount(): Int

    @Query("SELECT COUNT(DISTINCT genre) FROM albums WHERE genre != ''")
    suspend fun getTotalGenreCount(): Int

    @Query("SELECT MAX(year) FROM albums")
    suspend fun getNewestYear(): Int?

    @Query("SELECT MIN(year) FROM albums")
    suspend fun getOldestYear(): Int?

    // ---- Stats breakdowns (Stats tab) ------------------------------------
    @Query("SELECT genre AS label, COUNT(*) AS count FROM albums WHERE genre != '' GROUP BY LOWER(genre) ORDER BY count DESC, LOWER(genre) ASC LIMIT :limit")
    suspend fun getTopGenreCounts(limit: Int): List<StatsBucket>

    @Query("SELECT artist AS label, COUNT(*) AS count FROM albums WHERE artist != '' GROUP BY LOWER(artist) ORDER BY count DESC, LOWER(artist) ASC LIMIT :limit")
    suspend fun getTopArtistCounts(limit: Int): List<StatsBucket>

    @Query("SELECT CAST(year AS TEXT) AS label, COUNT(*) AS count FROM albums WHERE year > 0 GROUP BY year ORDER BY year ASC")
    suspend fun getYearCounts(): List<StatsBucket>

    @Query("DELETE FROM albums")
    suspend fun clearLibrary()

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteAlbumsByIds(ids: List<String>)

    @Query("SELECT id FROM albums")
    suspend fun getExistingAlbumIds(): List<String>

    @Query("SELECT id FROM albums WHERE albumType = ''")
    suspend fun getAlbumsMissingType(): List<String>

    @Query("UPDATE albums SET albumType = :albumType WHERE id IN (:ids)")
    suspend fun updateAlbumType(ids: List<String>, albumType: String)

    @Query("SELECT * FROM albums ORDER BY LOWER(artist), LOWER(title)")
    suspend fun getAllAlbumsList(): List<AlbumEntity>
}

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistAlbum(entity: PlaylistAlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistAlbums(entities: List<PlaylistAlbumEntity>)

    @Query("SELECT * FROM playlist_albums WHERE playlistId = :playlistId")
    fun getAlbumsForPlaylist(playlistId: String): Flow<List<PlaylistAlbumEntity>>

    @Query("DELETE FROM playlist_albums WHERE playlistId = :playlistId")
    suspend fun clearPlaylistAlbums(playlistId: String)

    @Query("DELETE FROM playlist_albums WHERE playlistId = :playlistId AND albumId IN (:albumIds)")
    suspend fun deletePlaylistAlbums(playlistId: String, albumIds: List<String>)

    @Query("UPDATE playlists SET albumCount = (SELECT COUNT(*) FROM playlist_albums WHERE playlistId = :playlistId) WHERE id = :playlistId")
    suspend fun updatePlaylistAlbumCount(playlistId: String)

    @Query("UPDATE playlists SET trackCount = MAX(trackCount - :removed, 0) WHERE id = :playlistId")
    suspend fun decrementPlaylistTrackCount(playlistId: String, removed: Int)

    // ---- Filtered playlist-album queries (mirror AlbumDao, scoped to playlist) ----
    // Sort: title
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(title) ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByTitle(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(title) DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByTitleDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    // Sort: artist
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(artist) ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByArtist(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(artist) DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByArtistDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    // Sort: year
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY year ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByYear(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY year DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByYearDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    // Sort: genre
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(genre) ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByGenre(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY LOWER(genre) DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByGenreDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    // Sort: track count
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY trackCount ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByTrackCount(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY trackCount DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByTrackCountDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    // Sort: date added
    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY addedAt ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByDateAdded(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("""
        SELECT * FROM playlist_albums
        WHERE playlistId = :playlistId
        AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query)
        AND (:genre = '' OR LOWER(genre) = :genre)
        AND (:artistFilter = '' OR LOWER(artist) = :artistFilter)
        AND (:yearFrom IS NULL OR year >= :yearFrom)
        AND (:yearTo IS NULL OR year <= :yearTo)
        ORDER BY addedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPlaylistAlbumsByDateAddedDesc(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?, limit: Int, offset: Int): Flow<List<PlaylistAlbumEntity>>

    @Query("SELECT COUNT(*) FROM playlist_albums WHERE playlistId = :playlistId AND (:query = '' OR LOWER(title) LIKE :query OR LOWER(artist) LIKE :query) AND (:genre = '' OR LOWER(genre) = :genre) AND (:artistFilter = '' OR LOWER(artist) = :artistFilter) AND (:yearFrom IS NULL OR year >= :yearFrom) AND (:yearTo IS NULL OR year <= :yearTo)")
    suspend fun countPlaylistAlbums(playlistId: String, query: String, genre: String, artistFilter: String, yearFrom: Int?, yearTo: Int?): Int

    @Query("SELECT DISTINCT genre FROM playlist_albums WHERE playlistId = :playlistId AND genre != '' ORDER BY genre")
    suspend fun getPlaylistGenres(playlistId: String): List<String>

    @Query("SELECT DISTINCT artist FROM playlist_albums WHERE playlistId = :playlistId AND artist != '' ORDER BY LOWER(artist)")
    suspend fun getPlaylistArtists(playlistId: String): List<String>

    @Query("SELECT DISTINCT year FROM playlist_albums WHERE playlistId = :playlistId AND year > 0 ORDER BY year DESC")
    suspend fun getPlaylistYears(playlistId: String): List<Int>

    @Query("SELECT * FROM playlist_albums WHERE playlistId = :playlistId ORDER BY LOWER(title)")
    suspend fun getPlaylistAlbumsList(playlistId: String): List<PlaylistAlbumEntity>
}
