package com.spotifydashboard.app.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.spotifydashboard.app.api.SpotifyPlaylistApiService

import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.api.PlaylistTrackItem
import com.spotifydashboard.app.api.AddTracksRequest
import com.spotifydashboard.app.api.CreatePlaylistRequest
import com.spotifydashboard.app.api.ChangePlaylistDetailsRequest
import com.spotifydashboard.app.api.SpotifyApiClient
import com.spotifydashboard.app.api.SpotifyApiService
import com.spotifydashboard.app.api.RemoteAlbumTrack
import com.spotifydashboard.app.api.TrackUri
import com.spotifydashboard.app.api.RemoveTracksRequest
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.data.*
import com.spotifydashboard.app.utils.BackupFile
import com.spotifydashboard.app.utils.CSVHelper
import com.spotifydashboard.app.utils.LocalQueueCursor
import com.spotifydashboard.app.utils.LocalRetentionEntry
import com.spotifydashboard.app.utils.PlaylistRetentionStore
import com.spotifydashboard.app.utils.QueueMode
import com.spotifydashboard.app.utils.QueuePlaylistRef
import com.spotifydashboard.app.utils.QueuePlaylistStore
import com.spotifydashboard.app.utils.PendingQueueSwitch
import com.spotifydashboard.app.workers.QueueSwitchWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val SPOTIFY_PLAYLIST_TRACK_LIMIT = 10_000

data class PlaylistDashboardState(
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val selectedPlaylist: SpotifyPlaylist? = null,
    val playlistMeta: PlaylistEntity? = null,
    val albums: List<PlaylistAlbumEntity> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val syncState: PlaylistSyncState = PlaylistSyncState.Idle
)

sealed class PlaylistSyncState {
    object Idle : PlaylistSyncState()
    data class Progress(val stage: SyncStage, val fetched: Int, val total: Int) : PlaylistSyncState()
    data class Error(val message: String) : PlaylistSyncState()
    data class Completed(val albumCount: Int) : PlaylistSyncState()
    /** User stopped the sync — partial progress already saved. */
    object Cancelled : PlaylistSyncState()
}

/** Result of the Tools "create playlist from catalogue" flows. */
data class CatalogPlaylistResult(
    val playlistId: String,
    val playlistName: String,
    val albumsAdded: Int,
    val tracksAdded: Int,
    val duplicatesSkipped: Int,
)

/** Name + editability of the playlist currently providing playback context. */
data class PlaylistAccess(
    val name: String,
    val editable: Boolean
)

class PlaylistRepository(
    private val context: Context,
    private val playlistDao: PlaylistDao,
    private val authManager: SpotifyAuthManager
) {
    private val playlistApi: com.spotifydashboard.app.repository.SpotifyPlaylistApiService = SpotifyPlaylistApiService

    suspend fun getUserPlaylists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
            val authHeader = "Bearer $token"
            // Only show playlists owned by the current user (mirrors TS filter)
            val userId = try {
                playlistApi.getCurrentUserId(authHeader)
            } catch (e: Exception) {
                Log.w("PlaylistRepo", "Could not resolve user id, returning unfiltered", e)
                null
            }

            val all = mutableListOf<SpotifyPlaylist>()
            var offset = 0

            while (true) {
                val response = playlistApi.getUserPlaylists(authHeader, 50, offset)
                all.addAll(response.items.filter { playlist: SpotifyPlaylist ->
                    userId == null || playlist.owner.id == userId
                })
                if (response.next == null || all.size >= response.total) break
                offset += 50
                delay(100)
            }

            all.forEachIndexed { i, p ->
                // Spotify sometimes returns a null `images` array (a blank playlist
                // or a 404 from Spotify's image CDN) even though the Kotlin DTO
                // types it non-null. Normalize before the copy, or copy() throws
                // a NullPointerException and kills the whole playlist load.
                all[i] = p.copy(addedOrder = i, images = p.images ?: emptyList(), tracks = p.tracks ?: com.spotifydashboard.app.api.TrackInfo(total = 0))
            }
            all
        } catch (e: Exception) {
            Log.e("PlaylistRepo", "Failed to load playlists", e)
            emptyList()
        }
    }

    /** Flip a playlist's public/private flag through Spotify's own endpoint. */
    suspend fun setPlaylistPublic(playlistId: String, public: Boolean) = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val resp = playlistApi.changePlaylistDetails("Bearer $token", playlistId, public)
        if (!resp.isSuccessful) throw Exception("Spotify could not change visibility (${resp.code()})")
    }
    suspend fun createPlaylist(name: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        val userId = playlistApi.getCurrentUserId(authHeader)
        playlistApi.createPlaylist(authHeader, userId, name)
    }

    /**
     * Create a private playlist from direct track/episode URIs (queue snapshot).
     * The optional retention entry is stored locally and swept by the app.
     */
    suspend fun createPlaylistWithUris(
        name: String,
        uris: List<String>,
        retentionUntil: String? = null,
    ): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val playlist = createPlaylist(name)
        if (uris.isNotEmpty()) addTrackUrisToPlaylist(playlist.id, uris)
        if (retentionUntil != null) {
            setLocalRetention(playlist.id, playlist.name, retentionUntil)
        }
        playlist
    }

    /**
     * Append direct track/episode URIs to an existing playlist, in chunks of
     * 100. Spotify's /items endpoint accepts both media types.
     */
    suspend fun addTrackUrisToPlaylist(playlistId: String, uris: List<String>): Int =
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext 0
            val token = authManager.getValidAccessToken()
                ?: throw Exception("Authentication required")
            val authHeader = "Bearer $token"
            var added = 0
            for (chunk in uris.chunked(100)) {
                playlistApi.addTracksToPlaylist(authHeader, playlistId, chunk)
                added += chunk.size
                delay(60)
            }
            added
        }

    /**
     * Insert track/episode URIs at a zero-based playlist position. Chunks are
     * placed one after another so a >100-track selection keeps its order.
     */
    suspend fun insertTrackUrisToPlaylist(
        playlistId: String,
        uris: List<String>,
        position: Int,
    ): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val token = authManager.getValidAccessToken()
            ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        var added = 0
        var insertAt = position.coerceAtLeast(0)
        for (chunk in uris.chunked(100)) {
            playlistApi.addTracksToPlaylist(authHeader, playlistId, chunk, insertAt)
            added += chunk.size
            insertAt += chunk.size
            delay(60)
        }
        added
    }

    /**
     * Resolve the name of a playlist and whether this Spotify account can edit
     * it. Spotify has no "editable" flag, so ownership or collaboration is the
     * same rule the API enforces for playlist writes.
     */
    suspend fun getPlaylistAccess(playlistId: String): PlaylistAccess =
        withContext(Dispatchers.IO) {
            val token = authManager.getValidAccessToken()
                ?: throw Exception("Authentication required")
            val authHeader = "Bearer $token"
            val details = playlistApi.getPlaylistDetails(authHeader, playlistId)
            val userId = playlistApi.getCurrentUserId(authHeader)
            PlaylistAccess(
                name = details.name,
                editable = details.collaborative || details.owner.id == userId,
            )
        }

    /**
     * Add selected albums to an existing Spotify playlist.
     * Fetches all track URIs for each album, then POSTs them in chunks of 100.
     */
    suspend fun addAlbumsToPlaylist(albumIds: Set<String>, playlistId: String) = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        val trackUris = mutableListOf<String>()

        val albumApiService: SpotifyApiService = SpotifyApiClient.service
        for (albumId in albumIds) {
            var offset = 0
            while (true) {
                val resp = albumApiService.getAlbumTracks(authHeader, albumId, 50, offset)
                if (!resp.isSuccessful) {
                    Log.e("PlaylistRepo", "getAlbumTracks failed for $albumId: ${resp.code()}")
                    break
                }
                val page = resp.body() ?: break
                trackUris.addAll(page.items.map { it.uri })
                if (page.next == null || page.items.size < 50) break
                offset += 50
            }
        }

        // Spotify allows max 100 URIs per request
        trackUris.chunked(100).forEach { chunk ->
            playlistApi.addTracksToPlaylist(authHeader, playlistId, chunk)
        }
        Log.d("PlaylistRepo", "Added ${trackUris.size} tracks to playlist $playlistId")
    }

    /**
     * Create a private playlist from whole albums. Tracks are fetched before
     * the playlist is created, so a failed catalogue read does not leave an
     * empty playlist behind.
     *
     * When [deduplicate] is true, tracks are de-duplicated by normalized
     * title + primary artist. This catches the same song appearing on the
     * album, a single and a compilation. Album-level duplicate titles are
     * resolved by the caller before this method is invoked.
     */
    suspend fun createPlaylistFromAlbumIds(
        name: String,
        albumIds: List<String>,
        deduplicate: Boolean,
        onProgress: ((albumsDone: Int, albumsTotal: Int, tracksFound: Int) -> Unit)? = null,
    ): CatalogPlaylistResult = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw Exception("Playlist name is required")
        val albums = albumIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (albums.isEmpty()) throw Exception("No albums selected")

        val token = authManager.getValidAccessToken()
            ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        val albumApi: SpotifyApiService = SpotifyApiClient.service
        val trackUris = mutableListOf<String>()
        val seenTracks = mutableSetOf<String>()
        var albumsWithTracks = 0
        var duplicatesSkipped = 0

        albums.forEachIndexed { albumIndex, albumId ->
            var albumHadTracks = false
            var offset = 0
            while (true) {
                val resp = albumApi.getAlbumTracks(authHeader, albumId, 50, offset)
                if (!resp.isSuccessful) {
                    val code = resp.code()
                    when {
                        code == 401 -> throw Exception("Spotify session expired. Log in again, then retry.")
                        code == 403 -> throw Exception("Spotify refused to load album tracks (HTTP 403).")
                        code == 429 -> throw Exception("Spotify rate limit reached. Wait a moment, then retry.")
                        code in 500..599 -> throw Exception("Spotify is unavailable right now (HTTP $code). Retry shortly.")
                        else -> {
                            // A single deluxe/regional release can disappear. Do
                            // not throw away an otherwise valid discography.
                            Log.w("PlaylistRepo", "getAlbumTracks failed for $albumId: $code")
                            break
                        }
                    }
                }
                val page = resp.body() ?: break
                for (track in page.items) {
                    val uri = track.uri.trim()
                    if (uri.isEmpty()) continue
                    if (deduplicate) {
                        val key = trackDedupeKey(track.name, track.artists.firstOrNull()?.name.orEmpty())
                        if (key.isNotEmpty() && !seenTracks.add(key)) {
                            duplicatesSkipped++
                            continue
                        }
                    }
                    if (trackUris.size >= SPOTIFY_PLAYLIST_TRACK_LIMIT) {
                        throw Exception(
                            "The selected releases have more than $SPOTIFY_PLAYLIST_TRACK_LIMIT tracks; " +
                                "Spotify playlists cap at $SPOTIFY_PLAYLIST_TRACK_LIMIT. Narrow the selection.",
                        )
                    }
                    trackUris.add(uri)
                    albumHadTracks = true
                }
                if (page.next == null || page.items.isEmpty()) break
                offset += 50
            }
            if (albumHadTracks) albumsWithTracks++
            onProgress?.invoke(albumIndex + 1, albums.size, trackUris.size)
            delay(60)
        }

        if (trackUris.isEmpty()) throw Exception("No playable tracks found for the selected albums")

        val playlist = createPlaylist(trimmedName)
        // Re-read the token after a potentially long track fetch; the header
        // captured before the fetch may have expired while albums were read.
        val freshToken = authManager.getValidAccessToken()
            ?: throw Exception(
                "Playlist \"${playlist.name}\" was created, but the Spotify session expired before " +
                    "tracks could be added.",
            )
        val addAuthHeader = "Bearer $freshToken"
        var addedTracks = 0
        try {
            for (chunk in trackUris.chunked(100)) {
                playlistApi.addTracksToPlaylist(addAuthHeader, playlist.id, chunk)
                addedTracks += chunk.size
                delay(60)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception(
                "Playlist \"${playlist.name}\" was created, but only $addedTracks of " +
                    "${trackUris.size} tracks were added: ${e.message}",
                e,
            )
        }
        Log.d(
            "PlaylistRepo",
            "Created ${playlist.name} (${playlist.id}) with ${trackUris.size} tracks " +
                "from $albumsWithTracks albums; duplicates skipped: $duplicatesSkipped",
        )
        CatalogPlaylistResult(
            playlistId = playlist.id,
            playlistName = playlist.name,
            albumsAdded = albumsWithTracks,
            tracksAdded = trackUris.size,
            duplicatesSkipped = duplicatesSkipped,
        )
    }

    private fun trackDedupeKey(name: String, primaryArtist: String): String {
        val normalizedName = name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalizedName.isEmpty()) return ""
        val normalizedArtist = primaryArtist.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        return "$normalizedName|$normalizedArtist"
    }

    // ── Album track sheet / removal ─────────────────────────────────────────

    /** Lazy, on-demand album track list with the metadata the sheet shows. */
    suspend fun fetchAlbumTracks(albumId: String): List<RemoteAlbumTrack> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        val albumApi: SpotifyApiService = SpotifyApiClient.service
        val album = albumApi.getAlbumDetails(authHeader, albumId).body()
        val imageUrl = album?.images?.sortedByDescending { it.width }?.firstOrNull()?.url ?: ""
        val releaseYear = album?.release_date?.split("-")?.firstOrNull()?.toIntOrNull()
        val out = mutableListOf<RemoteAlbumTrack>()
        var offset = 0
        while (true) {
            val resp = albumApi.getAlbumTracks(authHeader, albumId, 50, offset)
            if (!resp.isSuccessful) throw Exception("Could not load album tracks (${resp.code()})")
            val page = resp.body() ?: break
            for (track in page.items) {
                out.add(
                    RemoteAlbumTrack(
                        spotify_id = track.id ?: "",
                        name = track.name,
                        artist_names = track.artists.joinToString(", ") { it.name },
                        disc_number = track.disc_number,
                        track_number = track.track_number,
                        duration_ms = track.duration_ms,
                        uri = track.uri,
                        explicit = track.explicit,
                        preview_url = track.preview_url,
                        album_name = album?.name ?: "",
                        album_image_url = imageUrl,
                        release_year = releaseYear,
                    )
                )
            }
            if (page.next == null || page.items.size < 50) break
            offset += 50
        }
        out
    }

    /**
     * Remove every track belonging to the given albums from one playlist.
     * Never touches the saved-albums library. The DB rows are deleted only
     * after Spotify confirms the removal.
     */
    suspend fun removeAlbumsFromPlaylist(playlistId: String, albumIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            if (albumIds.isEmpty()) return@withContext 0
            val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
            val authHeader = "Bearer $token"
            val uris = mutableListOf<String>()
            val fields = "items(track(uri,album(id))),next,total"
            var offset = 0
            while (true) {
                val page = playlistApi.getPlaylistTracks(authHeader, playlistId, 100, offset, fields)
                for (item in page.items) {
                    val track = item.track ?: continue
                    val albumId = track.album?.id
                    if (albumId != null && albumId in albumIds && track.uri.isNotBlank()) {
                        uris.add(track.uri)
                    }
                }
                if (page.next == null) break
                offset += 100
                delay(60)
            }
            var removed = 0
            for (chunk in uris.chunked(100)) {
                val response = playlistApi.removeTracksFromPlaylist(authHeader, playlistId, chunk)
                if (!response.isSuccessful) {
                    throw Exception("Spotify rejected removal (${response.code()})")
                }
                removed += chunk.size
                delay(60)
            }
            playlistDao.deletePlaylistAlbums(playlistId, albumIds.toList())
            playlistDao.updatePlaylistAlbumCount(playlistId)
            playlistDao.decrementPlaylistTrackCount(playlistId, removed)
            Log.d("PlaylistRepo", "Removed $removed tracks for ${albumIds.size} albums from $playlistId")
            removed
        }

    /** Remove individual track URIs (album sheet per-row action). */
    suspend fun removeTrackUris(playlistId: String, uris: List<String>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        var removed = 0
        for (chunk in uris.chunked(100)) {
            val response = playlistApi.removeTracksFromPlaylist(authHeader, playlistId, chunk)
            if (!response.isSuccessful) {
                throw Exception("Spotify rejected removal (${response.code()})")
            }
            removed += chunk.size
            delay(60)
        }
        playlistDao.decrementPlaylistTrackCount(playlistId, removed)
        removed
    }

    /** Append album tracks to the playback queue (never replaces it). */
    suspend fun queueTrackUris(uris: List<String>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        var queued = 0
        for (uri in uris) {
            try {
                if (SpotifyApiClient.service.addToQueue(authHeader, uri).isSuccessful) queued++
            } catch (e: Exception) {
                Log.w("PlaylistRepo", "Queue failed for $uri", e)
            }
        }
        queued
    }

    /** Spotify has no playlist delete: "delete" = unfollow + drop local cache. */
    suspend fun unfollowPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val resp = playlistApi.unfollowPlaylist("Bearer $token", playlistId)
        if (!resp.isSuccessful) throw Exception("Spotify could not delete playlist (${resp.code()})")
        playlistDao.clearPlaylistAlbums(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun syncPlaylist(
        playlistId: String,
        signal: SyncCancelSignal = SyncCancelSignal(),
        onProgress: (PlaylistSyncState) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
            val authHeader = "Bearer $token"

            // Step 1: fetch all playlist tracks with sparse fields (cuts payload ~70%, mirrors TS)
            val fields = "items(track(id,name,album(id,name,artists,images,release_date))),next,total"
            val albumIdToAlbum = mutableMapOf<String, com.spotifydashboard.app.api.FullAlbumInfo>()
            val albumIdToTracks = mutableMapOf<String, MutableList<String>>()
            var offset = 0
            var total = 0
            var fetched = 0
            while (true) {
                if (signal.cancelled) {
                    Log.d("PlaylistRepo", "Playlist sync cancelled during track fetch")
                    onProgress(PlaylistSyncState.Cancelled)
                    return@withContext
                }
                val response = playlistApi.getPlaylistTracks(authHeader, playlistId, 100, offset, fields)
                if (total == 0) total = response.total
                for (item in response.items) {
                    val track = item.track ?: continue
                    val album = track.album ?: continue
                    if (album.id.isNullOrBlank()) continue
                    fetched++
                    albumIdToAlbum.putIfAbsent(album.id, album)
                    val names = albumIdToTracks.getOrPut(album.id) { mutableListOf() }
                    if (!track.name.isNullOrBlank() && !names.contains(track.name)) names.add(track.name)
                }
                onProgress(PlaylistSyncState.Progress(SyncStage.ALBUMS, fetched, total))
                if (response.next == null) break
                offset += 100
                delay(80)
            }

            val albumIds = albumIdToAlbum.keys.toList()
            Log.d("PlaylistRepo", "Found ${albumIds.size} distinct albums in playlist $playlistId")

            // Step 2: genre enrichment, batched 50 artists (best-effort, mirrors TS)
            val artistIdForAlbum = albumIds.associateWith { id ->
                albumIdToAlbum[id]?.artists?.firstOrNull()?.id ?: ""
            }
            val genreMap = mutableMapOf<String, String>()
            val distinctArtistIds = artistIdForAlbum.values.filter { it.isNotBlank() }.distinct()
            val albumApiService: SpotifyApiService = SpotifyApiClient.service
            // getArtists lives on the album service, not the playlist service
            distinctArtistIds.chunked(50).forEachIndexed { index, batch ->
                if (signal.cancelled) {
                    Log.d("PlaylistRepo", "Playlist sync cancelled during genre enrichment")
                    onProgress(PlaylistSyncState.Cancelled)
                    return@withContext
                }
                try {
                    val resp = albumApiService.getArtists(authHeader, batch.joinToString(","))
                    resp.body()?.artists?.forEach { artist ->
                        artist.genres.firstOrNull()?.let { genreMap[artist.id] = it }
                    }
                    onProgress(
                        PlaylistSyncState.Progress(
                            SyncStage.GENRES,
                            min((index + 1) * 50, distinctArtistIds.size),
                            distinctArtistIds.size
                        )
                    )
                } catch (e: Exception) {
                    Log.w("PlaylistRepo", "Genre batch failed", e)
                }
                delay(80)
            }

            // Step 3: build entities, sorted by trackCount desc then title (mirrors TS)
            val playlistAlbumEntities = albumIds.mapNotNull { albumId ->
                try {
                    val album = albumIdToAlbum[albumId] ?: return@mapNotNull null
                    val tracks = albumIdToTracks[albumId] ?: emptyList()
                    val primaryArtistId = artistIdForAlbum[albumId] ?: ""
                    val sortedImages = album.images.sortedByDescending { it.width }
                    val imageUrl = (sortedImages.find { it.width <= 300 }
                        ?: sortedImages.lastOrNull())?.url ?: ""
                    PlaylistAlbumEntity(
                        playlistId = playlistId,
                        albumId = albumId,
                        title = album.name,
                        artist = album.artists.joinToString(", ") { it.name },
                        year = album.release_date?.split("-")?.firstOrNull()?.toIntOrNull() ?: 0,
                        genre = genreMap[primaryArtistId] ?: "",
                        imageUrl = imageUrl,
                        trackCount = tracks.size,
                        trackTitles = JSONArray(tracks).toString(),
                        addedAt = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    Log.w("PlaylistRepo", "Failed to build entity for $albumId", e)
                    null
                }
            }.sortedWith(compareByDescending<PlaylistAlbumEntity> { it.trackCount }.thenBy { it.title })

            // Step 4: persist to DB
            playlistDao.clearPlaylistAlbums(playlistId)
            playlistDao.upsertPlaylistAlbums(playlistAlbumEntities)
            Log.d("PlaylistRepo", "Saved ${playlistAlbumEntities.size} playlist album entities for $playlistId")

            // Step 5: update playlist metadata
            val playlistInfo: SpotifyPlaylist? = try {
                playlistApi.getUserPlaylists(authHeader, 1, 0).items.find { it.id == playlistId }
            } catch (e: Exception) { null }

            if (playlistInfo != null) {
                val meta = PlaylistEntity(
                    id = playlistInfo.id,
                    name = playlistInfo.name,
                    imageUrl = playlistInfo.images.firstOrNull()?.url ?: "",
                    trackCount = playlistInfo.tracks.total,
                    albumCount = albumIds.size,
                    syncedAt = Instant.now().toString()
                )
                playlistDao.upsertPlaylist(meta)
            }

            onProgress(PlaylistSyncState.Completed(playlistAlbumEntities.size))
        } catch (e: Exception) {
            Log.e("PlaylistRepo", "Sync failed", e)
            onProgress(PlaylistSyncState.Error(e.message ?: "Unknown error"))
        }
    }

    fun getPlaylistAlbumsFlow(playlistId: String): Flow<List<PlaylistAlbumEntity>> {
        return playlistDao.getAlbumsForPlaylist(playlistId)
    }

    /**
     * Filtered + sorted + paginated playlist albums, mirroring the library
     * query path and TS loadPlaylistAlbums.
     */
    fun getFilteredPlaylistAlbumsFlow(
        playlistId: String,
        query: String, genre: String, artist: String, yearFrom: Int?, yearTo: Int?,
        sortField: String, sortOrder: String, limit: Int, offset: Int
    ): Flow<List<PlaylistAlbumEntity>> {
        val desc = sortOrder == "desc"
        // Same normalization as the library path: %...% LIKE pattern + lowercase exact.
        val q = query.trim().let { if (it.isEmpty()) "" else "%${it.lowercase()}%" }
        val g = genre.lowercase()
        val a = artist.lowercase()
        return when (sortField.lowercase()) {
            "title" -> if (desc) playlistDao.getPlaylistAlbumsByTitleDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                       else playlistDao.getPlaylistAlbumsByTitle(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
            "year" -> if (desc) playlistDao.getPlaylistAlbumsByYearDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                      else playlistDao.getPlaylistAlbumsByYear(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
            "genre" -> if (desc) playlistDao.getPlaylistAlbumsByGenreDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                       else playlistDao.getPlaylistAlbumsByGenre(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
            "track_count", "trackcount" -> if (desc) playlistDao.getPlaylistAlbumsByTrackCountDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                                           else playlistDao.getPlaylistAlbumsByTrackCount(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
            "added_at", "date_added", "dateadded" -> if (desc) playlistDao.getPlaylistAlbumsByDateAddedDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                                            else playlistDao.getPlaylistAlbumsByDateAdded(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
            else -> if (desc) playlistDao.getPlaylistAlbumsByArtistDesc(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
                    else playlistDao.getPlaylistAlbumsByArtist(playlistId, q, g, a, yearFrom, yearTo, limit, offset)
        }
    }

    suspend fun countPlaylistAlbums(
        playlistId: String, query: String, genre: String, artist: String, yearFrom: Int?, yearTo: Int?
    ): Int = withContext(Dispatchers.IO) {
        try {
            val q = query.trim().let { if (it.isEmpty()) "" else "%${it.lowercase()}%" }
            playlistDao.countPlaylistAlbums(playlistId, q, genre.lowercase(), artist.lowercase(), yearFrom, yearTo)
        } catch (e: Exception) {
            Log.e("PlaylistRepo", "Count failed", e)
            0
        }
    }

    suspend fun getPlaylistFilterOptions(playlistId: String): Triple<List<String>, List<String>, List<Int>> =
        withContext(Dispatchers.IO) {
            Triple(
                playlistDao.getPlaylistGenres(playlistId),
                playlistDao.getPlaylistArtists(playlistId),
                playlistDao.getPlaylistYears(playlistId)
            )
        }

    /**
     * Playlist CSV export with track_count + tracks columns (no import path,
     * mirroring TS exportPlaylistCSV).
     */
    suspend fun exportPlaylistCsv(playlistId: String, playlistName: String): BackupFile? =
        withContext(Dispatchers.IO) {
            val albums = playlistDao.getPlaylistAlbumsList(playlistId)
            if (albums.isEmpty()) return@withContext null
            val csv = CSVHelper.buildCsv(
                listOf("id", "album_title", "artist", "year", "genre", "image_url", "track_count", "tracks"),
                albums.map { a ->
                    val tracks = try {
                        JSONArray(a.trackTitles).let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.joinToString("; ")
                        }
                    } catch (e: Exception) {
                        a.trackTitles
                    }
                    listOf(a.albumId, a.title, a.artist, a.year, a.genre, a.imageUrl, a.trackCount, tracks)
                }
            )
            val safe = playlistName.replace("[^a-z0-9]+".toRegex(RegexOption.IGNORE_CASE), "-")
                .replace("-+".toRegex(), "-").lowercase().trim('-').ifBlank { "playlist" }
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            // Reuse the shared backup writer (Documents dir, no permission needed)
            CSVHelper.writeBackupFile(context, csv, "", "playlist-$safe-$date.csv")
        }

    suspend fun getPlaylistMeta(playlistId: String): PlaylistEntity? = withContext(Dispatchers.IO) {
        try {
            playlistDao.getPlaylistById(playlistId)
        } catch (e: Exception) {
            Log.w("PlaylistRepo", "getPlaylistMeta failed", e)
            null
        }
    }

    // ── Local retention (remote mode owns this server-side) ─────────────────

    fun localRetention(playlistId: String): LocalRetentionEntry? =
        PlaylistRetentionStore.get(context, playlistId)

    fun setLocalRetention(playlistId: String, name: String, expiresAt: String) {
        PlaylistRetentionStore.set(
            context, LocalRetentionEntry(playlistId, name, expiresAt),
        )
    }

    fun clearLocalRetention(playlistId: String) {
        PlaylistRetentionStore.clear(context, playlistId)
    }

    // ── Last auto-generated "queue playlist" (replaced by the next one) ─────

    fun lastQueuePlaylist(): QueuePlaylistRef? = QueuePlaylistStore.get(context)

    fun setLastQueuePlaylist(playlistId: String, name: String, uris: List<String> = emptyList()) {
        QueuePlaylistStore.set(context, playlistId, name, uris)
    }

    /**
     * Stop using any auto-generated queue playlist as the "active queue".
     * Spotify itself keeps the playlist, but this app stops appending to it.
     */
    fun clearLastQueuePlaylist() {
        QueuePlaylistStore.clear(context)
    }

    // ── Local-playlist queue mode ───────────────────────────────────────────

    fun localQueuePlaylist(): QueuePlaylistRef? = QueuePlaylistStore.getLocal(context)

    fun setLocalQueuePlaylist(playlistId: String, name: String, uris: List<String> = emptyList()) {
        QueuePlaylistStore.setLocal(context, playlistId, name, uris)
    }

    fun clearLocalQueuePlaylist() {
        QueuePlaylistStore.clearLocal(context)
    }

    fun localQueueCursor(): LocalQueueCursor? = QueuePlaylistStore.getLocalCursor(context)

    fun setLocalQueueCursor(playlistId: String, index: Int, uri: String?) {
        QueuePlaylistStore.setLocalCursor(context, playlistId, index, uri)
    }

    fun clearLocalQueueCursor() {
        QueuePlaylistStore.clearLocalCursor(context)
    }

    fun queueMode(): QueueMode = QueuePlaylistStore.getMode(context)

    fun setQueueMode(mode: QueueMode) {
        QueuePlaylistStore.setMode(context, mode)
    }

    suspend fun getPlaylistTrackUris(playlistId: String): List<String> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val authHeader = "Bearer $token"
        val out = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = playlistApi.getPlaylistTracks(authHeader, playlistId, 100, offset, "items(track(uri)),next")
            for (item in page.items) {
                val uri = item.track?.uri?.trim().orEmpty()
                if (uri.isNotEmpty()) out.add(uri)
            }
            if (page.next == null) break
            offset += 100
            delay(60)
        }
        out
    }

    /** Full track metadata for a playlist, in Spotify's order. Used to seed local playback queues. */
    suspend fun getPlaylistFullTracks(playlistId: String): List<com.spotifydashboard.app.api.FullTrackInfo> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val authHeader = "Bearer $token"
        val out = mutableListOf<com.spotifydashboard.app.api.FullTrackInfo>()
        var offset = 0
        while (true) {
            val page = playlistApi.getPlaylistTracks(
                authHeader,
                playlistId,
                100,
                offset,
                "items(track(id,uri,name,duration_ms,artists(name),album(name,images))),next",
            )
            for (item in page.items) {
                item.track?.let { track -> if (track.uri.isNotBlank()) out.add(track) }
            }
            if (page.next == null) break
            offset += 100
            delay(60)
        }
        out
    }
    // ── Deferred handover: the current track finishes, then playback moves ──

    fun pendingQueueSwitch(): PendingQueueSwitch? = QueuePlaylistStore.pending(context)

    fun setPendingQueueSwitch(pending: PendingQueueSwitch?) {
        QueuePlaylistStore.setPending(context, pending)
    }

    /**
     * Backstop for the handover: a one-off job that runs after the current
     * track should be over. The app does the switch itself while it is alive;
     * this only covers the phone being locked or the app being killed.
     */
    fun enqueueQueueSwitch(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<QueueSwitchWorker>()
            .setInitialDelay(maxOf(0L, delayMs), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            QUEUE_SWITCH_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelQueueSwitch() {
        WorkManager.getInstance(context).cancelUniqueWork(QUEUE_SWITCH_WORK)
    }

    private companion object {
        const val QUEUE_SWITCH_WORK = "queue-switch"
    }
}

object SpotifyPlaylistApiService {
    private val api: com.spotifydashboard.app.api.SpotifyPlaylistApiService = SpotifyApiClient.playlistService

    suspend fun getUserPlaylists(authHeader: String, limit: Int, offset: Int) =
        api.getUserPlaylists(authHeader, limit, offset)

    suspend fun getCurrentUserId(authHeader: String): String =
        api.getCurrentUser(authHeader).id

    suspend fun createPlaylist(authHeader: String, userId: String, name: String): SpotifyPlaylist =
        api.createPlaylist(authHeader, userId, CreatePlaylistRequest(name = name, public = false))

    suspend fun getPlaylistTracks(authHeader: String, playlistId: String, limit: Int, offset: Int, fields: String? = null) =
        api.getPlaylistTracks(authHeader, playlistId, limit, offset, fields)

    suspend fun getPlaylistDetails(authHeader: String, playlistId: String): com.spotifydashboard.app.api.SpotifyPlaylistDetails =
        api.getPlaylistDetails(authHeader, playlistId)

    suspend fun addTracksToPlaylist(authHeader: String, playlistId: String, uris: List<String>) =
        api.addTracksToPlaylist(authHeader, playlistId, AddTracksRequest(uris))

    suspend fun addTracksToPlaylist(
        authHeader: String,
        playlistId: String,
        uris: List<String>,
        position: Int,
    ) = api.addTracksToPlaylist(
        authHeader,
        playlistId,
        AddTracksRequest(uris, position),
    )

    suspend fun removeTracksFromPlaylist(authHeader: String, playlistId: String, uris: List<String>) =
        api.removeTracksFromPlaylist(
            authHeader, playlistId,
            RemoveTracksRequest(uris.map { TrackUri(it) }),
        )

    suspend fun unfollowPlaylist(authHeader: String, playlistId: String) =
        api.unfollowPlaylist(authHeader, playlistId)

    suspend fun changePlaylistDetails(authHeader: String, playlistId: String, public: Boolean?) =
        api.changePlaylistDetails(authHeader, playlistId, ChangePlaylistDetailsRequest(public))
}
