package com.spotifydashboard.app.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.spotifydashboard.app.api.SpotifyApiClient
import com.spotifydashboard.app.api.SpotifyApiService
import com.spotifydashboard.app.api.CurrentlyPlayingResponse
import com.spotifydashboard.app.api.PlayOffset
import com.spotifydashboard.app.api.PlayRequest
import com.spotifydashboard.app.api.SaveAlbumsRequest
import com.spotifydashboard.app.api.SpotifyDevice
import com.spotifydashboard.app.api.SpotifyPlaybackTrack
import com.spotifydashboard.app.api.SpotifyAlbumResponse
import com.spotifydashboard.app.api.SpotifyArtistSearchItem
import com.spotifydashboard.app.api.SpotifyCatalogAlbumResponse
import com.spotifydashboard.app.api.SavedTracksPage
import com.spotifydashboard.app.api.SpotifySavedTrack
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.data.*
import com.spotifydashboard.app.data.ViewMode
import com.spotifydashboard.app.utils.BackupFile
import com.spotifydashboard.app.utils.CSVHelper
import com.spotifydashboard.app.utils.CsvImportResult
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

sealed class SyncState {
    object Idle : SyncState()
    data class Progress(val stage: SyncStage, val fetched: Int, val total: Int) : SyncState()
    data class Error(val message: String) : SyncState()
    data class Completed(val result: SyncResult) : SyncState()
    /** User stopped the sync — partial progress already saved. */
    data class Cancelled(val result: SyncResult) : SyncState()
}

enum class SyncStage { ALBUMS, GENRES, SAVING }

data class SyncResult(val imported: Int, val skipped: Int, val existing: Int, val errors: List<String>)
data class QueueResult(val success: Int, val failed: Int, val error: String?)

/** Result of Tools -> Add selected catalogue releases to the library. */
data class CatalogLibraryResult(
    val requested: Int,
    val saved: Int,
    val imported: Int,
)

/**
 * Auto-backup preferences, mirroring TS `BackupSettings` (useBackupSettings).
 * Persisted in SharedPreferences so they survive app restarts.
 */
data class BackupSettings(
    val enabled: Boolean = false,
    val subdirName: String = "SpotifyBackups",
    val lastBackup: String = "",
    val lastPath: String = ""
)

class SpotifyRepository(
    private val context: Context,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val authManager: SpotifyAuthManager
) {
    companion object {
        const val PAGE_SIZE = 48
    }

    private val apiService: SpotifyApiService = SpotifyApiClient.service

    /**
     * Mirrors TS buildWhere(): search becomes a %...% LIKE pattern
     * (empty stays '' to match the `:query = ''` fast path), genre/artist
     * are lowercased to match the LOWER(col) comparisons in the DAO.
     */
    private fun likeQuery(query: String): String {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) "" else "%${trimmed.lowercase()}%"
    }

    fun getAlbumsFlow(
        query: String, genre: String, artist: String, albumType: String,
        yearFrom: Int?, yearTo: Int?,
        sortField: String, sortOrder: String, limit: Int, offset: Int
    ): Flow<List<AlbumEntity>> {
        val yFrom = yearFrom
        val yTo = yearTo
        val desc = sortOrder == "desc"
        val q = likeQuery(query)
        val g = genre.lowercase()
        val a = artist.lowercase()
        val t = albumType.lowercase()

        Log.d("SpotifyRepo", "getAlbumsFlow - query=$query, genre=$genre, artist=$artist, albumType=$albumType, yearFrom=$yFrom, yearTo=$yTo, sortField=$sortField, sortOrder=$sortOrder")

        return when (sortField.lowercase()) {
            "title" -> if (desc) albumDao.getAlbumsByTitleDesc(q, g, a, t, yFrom, yTo, limit, offset)
                       else albumDao.getAlbumsByTitle(q, g, a, t, yFrom, yTo, limit, offset)
            "year" -> if (desc) albumDao.getAlbumsByYearDesc(q, g, a, t, yFrom, yTo, limit, offset)
                      else albumDao.getAlbumsByYear(q, g, a, t, yFrom, yTo, limit, offset)
            "genre" -> if (desc) albumDao.getAlbumsByGenreDesc(q, g, a, t, yFrom, yTo, limit, offset)
                       else albumDao.getAlbumsByGenre(q, g, a, t, yFrom, yTo, limit, offset)
            "added_at" -> if (desc) albumDao.getAlbumsByAddedAtDesc(q, g, a, t, yFrom, yTo, limit, offset)
                          else albumDao.getAlbumsByAddedAt(q, g, a, t, yFrom, yTo, limit, offset)
            else -> if (desc) albumDao.getAlbumsByArtistDesc(q, g, a, t, yFrom, yTo, limit, offset)
                    else albumDao.getAlbumsByArtist(q, g, a, t, yFrom, yTo, limit, offset)
        }
    }

    suspend fun countAlbums(
        query: String, genre: String, artist: String, albumType: String,
        yearFrom: Int?, yearTo: Int?
    ): Int = withContext(Dispatchers.IO) {
        try {
            val count = albumDao.countAlbums(
                likeQuery(query), genre.lowercase(), artist.lowercase(),
                albumType.lowercase(), yearFrom, yearTo,
            )
            Log.d("SpotifyRepo", "countAlbums returned: $count")
            count
        } catch (e: Exception) {
            Log.e("SpotifyRepo", "Count failed", e)
            0
        }
    }

    /**
     * Full library sync, mirroring TS `useLibrary.syncFromSpotify`.
     *
     * - [clean]: delete the local library first (clean sync from zero).
     * - Otherwise albums already in the DB are skipped via an [getExistingAlbumIds]
     *   snapshot, making repeat syncs fast (incremental sync).
     * - [signal] is checked at every page fetch, genre batch, and save chunk;
     *   on cancel the sync stops gracefully and keeps partial progress.
     * - New albums are upserted in chunks of 100 with SAVING progress.
     *
     * @return per-run counts for the result summary UI.
     */
    suspend fun syncLibrary(
        signal: SyncCancelSignal = SyncCancelSignal(),
        clean: Boolean = false,
        onProgress: (SyncState) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        val mutableErrors = mutableListOf<String>()
        var imported = 0
        var skipped = 0
        var existing = 0

        fun snapshot() = SyncResult(imported, skipped, existing, mutableErrors.toList())

        try {
            // Auto-refreshes when expired (mirrors TS getValidAccessToken)
            val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
            val authHeader = "Bearer $token"

            if (clean) {
                Log.d("SpotifyRepo", "Clean sync — clearing library first")
                albumDao.clearLibrary()
            }
            // Snapshot of stored IDs before the loop (incremental sync)
            val existingIds: Set<String> = if (clean) emptySet() else {
                try {
                    albumDao.getExistingAlbumIds().toSet()
                } catch (e: Exception) {
                    Log.w("SpotifyRepo", "Could not load existing IDs, doing full upsert", e)
                    emptySet()
                }
            }

            Log.d("SpotifyRepo", "Starting sync (clean=$clean, knownIds=${existingIds.size})")

            val allSavedAlbums = mutableListOf<com.spotifydashboard.app.api.SavedAlbumItem>()
            var offset = 0
            val limit = 50
            var total = 0

            while (true) {
                if (signal.cancelled) {
                    Log.d("SpotifyRepo", "Sync cancelled during fetch")
                    onProgress(SyncState.Cancelled(snapshot()))
                    return@withContext snapshot()
                }
                Log.d("SpotifyRepo", "Fetching albums at offset $offset")
                val response = apiService.getSavedAlbums(authHeader, limit, offset)
                if (!response.isSuccessful) {
                    if (response.code() == 401) throw Exception("Session expired — please log in again")
                    throw Exception("Spotify API error ${response.code()}")
                }
                val body = response.body() ?: break
                total = body.total
                Log.d("SpotifyRepo", "Got ${body.items.size} items, total: $total")
                allSavedAlbums.addAll(body.items)
                onProgress(SyncState.Progress(SyncStage.ALBUMS, allSavedAlbums.size, total))
                if (allSavedAlbums.size >= total) break
                offset += limit
                delay(100)
            }

            Log.d("SpotifyRepo", "Total albums fetched: ${allSavedAlbums.size}")

            if (allSavedAlbums.isEmpty()) {
                Log.w("SpotifyRepo", "No albums to save")
                val done = snapshot()
                onProgress(SyncState.Completed(done))
                return@withContext done
            }

            val artistIds = allSavedAlbums.flatMap { it.album.artists }.map { it.id }.distinct()
            val genreMap = mutableMapOf<String, List<String>>()

            Log.d("SpotifyRepo", "Fetching genres for ${artistIds.size} artists")
            artistIds.chunked(50).forEachIndexed { index, batch ->
                if (signal.cancelled) {
                    Log.d("SpotifyRepo", "Sync cancelled during genre enrichment")
                    onProgress(SyncState.Cancelled(snapshot()))
                    return@withContext snapshot()
                }
                try {
                    val batchIds = batch.joinToString(",")
                    val response = apiService.getArtists(authHeader, batchIds)
                    response.body()?.artists?.forEach { artist ->
                        genreMap[artist.id] = artist.genres
                    }
                } catch (e: Exception) {
                    // Genre enrichment is best-effort (mirrors TS)
                    Log.w("SpotifyRepo", "Genre batch failed", e)
                }
                onProgress(
                    SyncState.Progress(
                        SyncStage.GENRES,
                        min((index + 1) * 50, artistIds.size),
                        artistIds.size
                    )
                )
                delay(100)
            }

            Log.d("SpotifyRepo", "Creating ${allSavedAlbums.size} AlbumEntities")
            val entities = allSavedAlbums.map { item ->
                val album = item.album
                val primaryArtistId = album.artists.firstOrNull()?.id ?: ""
                val genres = genreMap[primaryArtistId] ?: album.genres

                AlbumEntity(
                    id = album.id,
                    title = album.name,
                    artist = album.artists.joinToString(", ") { it.name },
                    year = album.release_date.split("-").firstOrNull()?.toIntOrNull() ?: 0,
                    genre = genres.firstOrNull() ?: "",
                    albumType = album.album_type.ifBlank { "album" }.lowercase(),
                    imageUrl = pickBestImage(album.images.map { it.url to it.width }),
                    imagePath = "",
                    addedAt = item.added_at
                )
            }

            // Incremental save: skip albums already in the DB, upsert the rest
            // in chunks so progress stays smooth and partial data survives cancel.
            // Duplicates the owner removed (card 269) are blocked here too, so
            // a later Spotify sync cannot resurrect them.
            val hiddenIds = loadHiddenAlbumIds()
            val fresh = entities.filter { entity ->
                when {
                    hiddenIds.contains(entity.id) -> {
                        skipped++
                        false
                    }
                    existingIds.contains(entity.id) -> {
                        existing++
                        false
                    }
                    else -> true
                }
            }
            Log.d("SpotifyRepo", "Saving ${fresh.size} new albums (${existing} already up to date)")

            // Existing rows still get their release type refreshed. This is
            // how v2 libraries gain albumType even when the startup backfill
            // could not run (e.g. the user logged in afterwards).
            entities
                .filter { !hiddenIds.contains(it.id) && existingIds.contains(it.id) }
                .groupBy { it.albumType }
                .forEach { (type, rows) ->
                    rows.map { it.id }.chunked(500).forEach { ids ->
                        albumDao.updateAlbumType(ids, type)
                    }
                }

            var saved = 0
            fresh.chunked(100).forEach { chunk ->
                if (signal.cancelled) {
                    Log.d("SpotifyRepo", "Sync cancelled during save ($saved/${fresh.size} saved)")
                    onProgress(SyncState.Cancelled(snapshot()))
                    return@withContext snapshot()
                }
                try {
                    albumDao.upsertAlbums(chunk)
                    saved += chunk.size
                    imported += chunk.size
                } catch (e: Exception) {
                    Log.e("SpotifyRepo", "Save chunk failed", e)
                    skipped += chunk.size
                    mutableErrors.add("Save failed for ${chunk.size} albums: ${e.message}")
                }
                onProgress(SyncState.Progress(SyncStage.SAVING, saved + existing, entities.size))
            }

            val savedCount = albumDao.getTotalAlbumCount()
            Log.d("SpotifyRepo", "Save complete. Total albums in DB: $savedCount")

            val done = snapshot()
            onProgress(SyncState.Completed(done))
            done
        } catch (e: Exception) {
            Log.e("SpotifyRepo", "Sync failed", e)
            onProgress(SyncState.Error(e.message ?: "Unknown error"))
            snapshot()
        }
    }

    /**
     * Queue every track of each selected album to the active Spotify player.
     * Fetches all track URIs from each album via GET albums/{id}/tracks,
     * then calls POST me/player/queue for each URI individually.
     * Returns QueueResult mirroring TS addAlbumsToQueue (success/failed counts
     * with Premium / no-device errors surfaced immediately).
     */
    suspend fun queueAlbums(albumIds: Set<String>): QueueResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val authHeader = "Bearer $token"
        var success = 0
        var failed = 0
        var lastError: String? = null

        for (albumId in albumIds) {
            var offset = 0
            while (true) {
                val tracksResp = apiService.getAlbumTracks(authHeader, albumId, 50, offset)
                if (!tracksResp.isSuccessful) {
                    Log.e("SpotifyRepo", "getAlbumTracks failed for $albumId: ${tracksResp.code()}")
                    if (tracksResp.code() == 401) throw Exception("Session expired — please log in again")
                    break
                }
                val page = tracksResp.body() ?: break
                for (track in page.items) {
                    val queueResp = apiService.addToQueue(authHeader, track.uri)
                    if (queueResp.isSuccessful) {
                        success++
                    } else when (queueResp.code()) {
                        403 -> throw Exception("Premium required or no active device")
                        404 -> throw Exception("No active device found — open Spotify on a device first")
                        else -> {
                            failed++
                            lastError = "HTTP ${queueResp.code()}"
                            Log.e("SpotifyRepo", "addToQueue failed for ${track.uri}: ${queueResp.code()}")
                        }
                    }
                    delay(120)
                }
                if (page.next == null || page.items.size < 50) break
                offset += 50
            }
        }
        QueueResult(success, failed, lastError)
    }

    /** Read-only snapshot of Spotify's current playback state. */
    suspend fun getCurrentPlayback(): CurrentlyPlayingResponse? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        val authHeader = "Bearer $token"
        val resp = apiService.getCurrentlyPlaying(authHeader)
        if (resp.code() == 204) return@withContext null
        if (!resp.isSuccessful) {
            Log.w("SpotifyRepo", "getCurrentPlayback failed: ${resp.code()}")
            throw Exception("Spotify playback unavailable (HTTP ${resp.code()})")
        }
        resp.body()
    }

    /** Read-only list of tracks Spotify will play next. */
    suspend fun getQueue(): List<SpotifyPlaybackTrack> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val authHeader = "Bearer $token"
        val resp = apiService.getQueue(authHeader)
        if (resp.code() == 204) return@withContext emptyList()
        if (!resp.isSuccessful) {
            Log.w("SpotifyRepo", "getQueue failed: ${resp.code()}")
            throw Exception("Spotify queue unavailable (HTTP ${resp.code()})")
        }
        resp.body()?.queue ?: emptyList()
    }

    /** Devices that can play right now (phone, TV, speaker...). */
    suspend fun getDevices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val resp = apiService.getDevices("Bearer $token")
        if (!resp.isSuccessful) {
            Log.w("SpotifyRepo", "getDevices failed: ${resp.code()}")
            return@withContext emptyList()
        }
        resp.body()?.devices ?: emptyList()
    }

    /**
     * Hand playback over to a playlist, continuing at [offset] inside it.
     * This is how "queue to end" works: the playlist holds the current track,
     * the up-next queue and the selected albums, and playback continues at the
     * first queued track.
     */
    suspend fun playPlaylistAsContext(
        playlistId: String,
        deviceId: String?,
        offset: Int,
    ) = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
        val resp = apiService.play(
            "Bearer $token",
            deviceId,
            PlayRequest(
                context_uri = "spotify:playlist:$playlistId",
                offset = PlayOffset(position = offset),
            ),
        )
        if (!resp.isSuccessful) {
            throw Exception(
                when (resp.code()) {
                    403 -> "Premium required or no active device"
                    404 -> "No active device found - open Spotify on a device first"
                    else -> "Spotify could not start playback (HTTP ${resp.code()})"
                }
            )
        }
    }

    suspend fun getStats(): LibraryStats? = withContext(Dispatchers.IO) {
        try {
            LibraryStats(
                totalAlbums = albumDao.getTotalAlbumCount(),
                totalArtists = albumDao.getTotalArtistCount(),
                totalGenres = albumDao.getTotalGenreCount(),
                newestYear = albumDao.getNewestYear() ?: 0,
                oldestYear = albumDao.getOldestYear() ?: 0,
                topGenres = albumDao.getTopGenreCounts(8),
                topArtists = albumDao.getTopArtistCounts(8),
                albumsByYear = albumDao.getYearCounts()
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDistinctGenres(): List<String> = withContext(Dispatchers.IO) {
        albumDao.getDistinctGenres()
    }

    suspend fun getDistinctArtists(): List<String> = withContext(Dispatchers.IO) {
        albumDao.getDistinctArtists()
    }

    suspend fun getDistinctYears(): List<Int> = withContext(Dispatchers.IO) {
        albumDao.getDistinctYears()
    }

    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        albumDao.clearLibrary()
        // A deliberate clear resets the duplicate blocklist too, so the next
        // sync starts from the owner's real Spotify library again.
        saveHiddenAlbums(emptyList())
    }

    // ------------------------------------------------------------------
    // Duplicate cleanup (card 269) — local, reversible, Spotify untouched
    // ------------------------------------------------------------------

    /** Every visible album, for the duplicate scan (no pagination). */
    suspend fun getAllAlbums(): List<AlbumEntity> = withContext(Dispatchers.IO) {
        albumDao.getAllAlbumsList()
    }

    suspend fun getHiddenAlbums(): List<AlbumEntity> = withContext(Dispatchers.IO) {
        loadHiddenAlbums()
    }

    /**
     * Hide [albums] from this device: delete the rows and remember the Spotify
     * IDs so sync/import will skip them. Returns how many were hidden.
     */
    suspend fun hideAlbums(albums: List<AlbumEntity>): Int = withContext(Dispatchers.IO) {
        if (albums.isEmpty()) return@withContext 0
        albumDao.deleteAlbumsByIds(albums.map { it.id })
        val merged = (loadHiddenAlbums().associateBy { it.id } +
            albums.associateBy { it.id }).values.toList()
        saveHiddenAlbums(merged)
        albums.size
    }

    /**
     * Permanently removes the selected albums from the user's Spotify library
     * (DELETE me/albums), then drops their local rows. Irreversible; callers
     * should confirm with the user beforehand. Returns how many were removed.
     */
    suspend fun removeSavedAlbumsPermanently(albums: List<AlbumEntity>): Int =
        removeSavedAlbumsPermanentlyByIds(albums.map { it.id })

    /** ID-based overload used by the library multi-delete toolbar. */
    suspend fun removeSavedAlbumsPermanentlyByIds(albumIds: Collection<String>): Int =
        withContext(Dispatchers.IO) {
            val ids = albumIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty()) return@withContext 0
            val token = authManager.getValidAccessToken() ?: throw Exception("Authentication required")
            val auth = "Bearer $token"
            var removed = 0
            for (chunk in ids.chunked(50)) {
                val resp = apiService.removeSavedAlbums(auth, ids = chunk.joinToString(","))
                val code = resp.code()
                if (code != 204 && code !in 200..299) {
                    val detail = libraryRemovalErrorMessage(code)
                    throw Exception(
                        if (removed == 0) detail
                        else "Deleted $removed of ${ids.size} before Spotify stopped. $detail"
                    )
                }
                // Apply local changes chunk by chunk, so a later failure never
                // leaves successfully removed albums visible on the board.
                albumDao.deleteAlbumsByIds(chunk)
                removed += chunk.size
            }
            removed
        }

    /**
     * Remove IDs from the blocklist and fetch their metadata again, so the
     * albums reappear immediately when Spotify is reachable. A failed fetch is
     * fine: the next normal sync will bring the unblocked album back.
     */
    suspend fun restoreHiddenAlbums(): Int = withContext(Dispatchers.IO) {
        val hidden = loadHiddenAlbums()
        if (hidden.isEmpty()) return@withContext 0
        saveHiddenAlbums(emptyList())

        val token = authManager.getValidAccessToken()
        if (token != null) {
            val authHeader = "Bearer $token"
            hidden.forEach { album ->
                try {
                    val resp = apiService.getAlbumDetails(authHeader, album.id)
                    if (resp.isSuccessful) {
                        resp.body()?.let {
                            albumDao.upsertAlbum(it.toAlbumEntity(album.addedAt.ifBlank { nowIso() }))
                        }
                    } else {
                        Log.w("SpotifyRepo", "Restore ${album.id} failed: HTTP ${resp.code()}")
                    }
                } catch (e: Exception) {
                    Log.w("SpotifyRepo", "Restore ${album.id} failed", e)
                }
                delay(80)
            }
        }
        hidden.size
    }

    /** Unblock IDs that are being added back from another screen (card 270). */
    suspend fun unhideAlbums(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val hidden = loadHiddenAlbums()
        val keep = hidden.filterNot { it.id in ids }
        saveHiddenAlbums(keep)
        hidden.size - keep.size
    }

    /** True when the current Spotify authorization can write the saved library. */
    fun hasLibraryModifyScope(): Boolean = authManager.hasGrantedScope("user-library-modify")

    /** Fetch one album from Spotify and map it into the local model. */
    suspend fun fetchAlbumEntity(albumId: String): AlbumEntity? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        val resp = apiService.getAlbumDetails("Bearer $token", albumId)
        if (!resp.isSuccessful) {
            Log.w("SpotifyRepo", "Album $albumId lookup failed: HTTP ${resp.code()}")
            return@withContext null
        }
        resp.body()?.toAlbumEntity(nowIso())
    }

    /**
     * Fill `albumType` for rows created before the v3 Room migration. They
     * start as "" and are looked up from the saved-albums pages (50 per call),
     * so the Albums/Singles filter works without a full library re-sync. If a
     * row is no longer in the Spotify library, it is defaulted to "album" and
     * left for the next sync to remove instead of rescanning on every launch.
     */
    suspend fun backfillAlbumTypes(): Int = withContext(Dispatchers.IO) {
        val missing = albumDao.getAlbumsMissingType().toSet()
        if (missing.isEmpty()) return@withContext 0
        val token = authManager.getValidAccessToken() ?: return@withContext 0
        val auth = "Bearer $token"
        val remaining = missing.toMutableSet()
        var offset = 0
        var updated = 0
        var complete = false

        while (remaining.isNotEmpty()) {
            try {
                val resp = apiService.getSavedAlbums(auth, limit = 50, offset = offset)
                if (!resp.isSuccessful) {
                    Log.w("SpotifyRepo", "Album type backfill failed: HTTP ${resp.code()}")
                    // Stop rather than hammering a rate limit or an expired
                    // session; the next app start or library sync retries.
                    break
                }
                val page = resp.body() ?: break
                val found = page.items
                    .map { it.album }
                    .filter { it.id in remaining }
                found.groupBy { it.album_type.ifBlank { "album" }.lowercase() }
                    .forEach { (type, albums) ->
                        val ids = albums.map { it.id }
                        albumDao.updateAlbumType(ids, type)
                        updated += ids.size
                    }
                remaining.removeAll(found.map { it.id }.toSet())

                offset += page.items.size
                if (page.items.isEmpty() || page.items.size < 50 || offset >= page.total) {
                    complete = true
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SpotifyRepo", "Album type backfill page failed", e)
                break
            }
            delay(220)
        }

        if (complete && remaining.isNotEmpty()) {
            // These IDs are no longer in the saved library. Classify them as
            // albums so a failed/absent sync does not trigger a full rescan on
            // every launch; the next sync will drop the stale rows.
            remaining.chunked(500).forEach { ids ->
                albumDao.updateAlbumType(ids, "album")
            }
            updated += remaining.size
        }
        updated
    }

    private fun hiddenPrefs() = context.getSharedPreferences("dedupe_prefs", Context.MODE_PRIVATE)

    private fun loadHiddenAlbumIds(): Set<String> =
        hiddenPrefs().getStringSet("hidden_ids", emptySet())?.toSet() ?: emptySet()

    private fun loadHiddenAlbums(): List<AlbumEntity> {
        val raw = hiddenPrefs().getString("hidden_albums", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        AlbumEntity(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            artist = o.optString("artist"),
                            year = o.optInt("year"),
                            genre = o.optString("genre"),
                            albumType = o.optString("albumType", "album").ifBlank { "album" },
                            imageUrl = o.optString("imageUrl"),
                            imagePath = o.optString("imagePath"),
                            addedAt = o.optString("addedAt")
                        )
                    )
                }
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            Log.w("SpotifyRepo", "Could not read hidden albums", e)
            emptyList()
        }
    }

    private fun saveHiddenAlbums(albums: List<AlbumEntity>) {
        val array = JSONArray()
        albums.forEach { a ->
            array.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("title", a.title)
                    put("artist", a.artist)
                    put("year", a.year)
                    put("genre", a.genre)
                    put("albumType", a.albumType)
                    put("imageUrl", a.imageUrl)
                    put("imagePath", a.imagePath)
                    put("addedAt", a.addedAt)
                }
            )
        }
        hiddenPrefs().edit()
            .putStringSet("hidden_ids", albums.map { it.id }.toSet())
            .putString("hidden_albums", array.toString())
            .apply()
    }

    private fun SpotifyAlbumResponse.toAlbumEntity(addedAt: String): AlbumEntity = AlbumEntity(
        id = id,
        title = name,
        artist = artists.joinToString(", ") { it.name },
        year = release_date.split("-").firstOrNull()?.toIntOrNull() ?: 0,
        genre = genres.firstOrNull() ?: "",
        albumType = album_type.ifBlank { "album" }.lowercase(),
        imageUrl = pickBestImage(images.map { it.url to it.width }),
        imagePath = "",
        addedAt = addedAt
    )

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())

    // ------------------------------------------------------------------
    // CSV import — mirrors TS importFromCSV (tolerant headers, per-row errors)
    // ------------------------------------------------------------------

    suspend fun importCsvText(csvText: String): CsvImportResult = withContext(Dispatchers.IO) {
        if (csvText.isBlank()) return@withContext CsvImportResult(0, 0, listOf("CSV file is empty"))
        val parsed = CSVHelper.parseLibraryCsv(csvText)
        var imported = 0
        var failed = 0
        var hiddenSkipped = 0
        val errors = parsed.errors.toMutableList()
        val hiddenIds = loadHiddenAlbumIds()
        parsed.rows.forEach { row ->
            // Respect the duplicate blocklist (card 269); the add-from-site
            // path explicitly unhides before importing.
            if (row.id in hiddenIds) {
                hiddenSkipped++
                return@forEach
            }
            try {
                albumDao.upsertAlbum(
                    AlbumEntity(
                        id = row.id,
                        title = row.title,
                        artist = row.artist,
                        year = row.year,
                        genre = row.genre,
                        albumType = row.albumType,
                        imageUrl = row.imageUrl,
                        imagePath = "",
                        addedAt = row.addedAt
                    )
                )
                imported++
            } catch (e: Exception) {
                failed++
                if (errors.size < 20) errors.add("\"${row.title}\": ${e.message}")
            }
        }
        CsvImportResult(imported, parsed.skipped + failed + hiddenSkipped, errors)
    }

    // ------------------------------------------------------------------
    // Backup export — mirrors TS ExportService (Documents dir + date filename)
    // ------------------------------------------------------------------

    /**
     * The local library as canonical CSV text, or null when the phone has no
     * albums yet. Used by the backup export and by the phone → server push.
     */
    suspend fun buildLibraryCsv(): String? = withContext(Dispatchers.IO) {
        val albums = albumDao.getAllAlbumsList()
        if (albums.isEmpty()) return@withContext null
        CSVHelper.buildCsv(
            // album_type goes last, so positional CSV readers still find the
            // original columns in their old slots.
            listOf("id", "title", "artist", "year", "genre", "image_url", "added_at", "album_type"),
            albums.map { a ->
                listOf(
                    a.id, a.title, a.artist, a.year, a.genre, a.imageUrl, a.addedAt, a.albumType,
                )
            }
        )
    }

    suspend fun exportLibraryBackup(subdir: String): BackupFile? = withContext(Dispatchers.IO) {
        val csv = buildLibraryCsv() ?: return@withContext null
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        writeBackupFile(csv, subdir, "spotify-library-$date.csv")
    }

    /** Writes CSV text into Documents/[subdir]/filename (app-specific, no permission needed). */
    fun writeBackupFile(csv: String, subdir: String, filename: String): BackupFile =
        CSVHelper.writeBackupFile(context, csv, subdir, filename)

    // ------------------------------------------------------------------
    // Backup settings persistence — mirrors TS useBackupSettings
    // ------------------------------------------------------------------

    private val backupPrefs
        get() = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    suspend fun getBackupSettings(): BackupSettings = withContext(Dispatchers.IO) {
        val prefs = backupPrefs
        BackupSettings(
            enabled = prefs.getBoolean("enabled", false),
            subdirName = prefs.getString("subdir", "SpotifyBackups") ?: "SpotifyBackups",
            lastBackup = prefs.getString("lastBackup", "") ?: "",
            lastPath = prefs.getString("lastPath", "") ?: ""
        )
    }

    suspend fun saveBackupSettings(settings: BackupSettings) = withContext(Dispatchers.IO) {
        backupPrefs.edit().apply {
            putBoolean("enabled", settings.enabled)
            putString("subdir", settings.subdirName)
            putString("lastBackup", settings.lastBackup)
            putString("lastPath", settings.lastPath)
            apply()
        }
    }

    // ------------------------------------------------------------------
    // Display settings (persisted grid size)
    // ------------------------------------------------------------------

    suspend fun getGridSize(): Int = withContext(Dispatchers.IO) {
        backupPrefs.getInt("gridSize", 160).coerceIn(80, 280)
    }

    suspend fun saveGridSize(size: Int) = withContext(Dispatchers.IO) {
        backupPrefs.edit().putInt("gridSize", size.coerceIn(80, 280)).apply()
    }

    // --- Board view modes (one per board) ------------------------------------

    suspend fun getLibraryViewMode(): ViewMode = withContext(Dispatchers.IO) {
        ViewMode.fromKey(backupPrefs.getString("viewModeLibrary", ViewMode.Grid.key))
    }

    suspend fun saveLibraryViewMode(mode: ViewMode) = withContext(Dispatchers.IO) {
        backupPrefs.edit().putString("viewModeLibrary", mode.key).apply()
    }

    suspend fun getPlaylistViewMode(): ViewMode = withContext(Dispatchers.IO) {
        ViewMode.fromKey(backupPrefs.getString("viewModePlaylist", ViewMode.Grid.key))
    }

    suspend fun savePlaylistViewMode(mode: ViewMode) = withContext(Dispatchers.IO) {
        backupPrefs.edit().putString("viewModePlaylist", mode.key).apply()
    }

    /**
     * Mirrors TS pickBestImage(): sort descending, prefer ~300px card size,
     * fall back to largest available.
     */
    private fun pickBestImage(images: List<Pair<String, Int>>): String {
        if (images.isEmpty()) return ""
        val sorted = images.sortedByDescending { it.second }
        return (sorted.find { it.second in 200..640 } ?: sorted[0]).first
    }

    data class FollowLibraryResult(
        val source: String,
        val uniqueArtists: Int,
        val notFollowing: Int,
        val followed: Int,
        val dryRun: Boolean
    )

    suspend fun followLibraryArtists(
        source: String = "both",
        dryRun: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): FollowLibraryResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: throw Exception("Authentication required")
        val auth = "Bearer $token"
        val artists = linkedMapOf<String, String>()

        if (source in listOf("tracks", "both")) {
            onProgress?.invoke("Reading Liked Songs…")
            var offset = 0
            while (true) {
                val resp = apiService.getSavedTracks(auth, 50, offset).body()
                val page = resp
                if (page == null) throw Exception("Could not read saved tracks")
                for (item in page.items) {
                    for (a in item.track?.artists ?: emptyList()) {
                        artists[a.id] = a.name
                    }
                }
                if (page.items.isEmpty() || page.next == null) break
                offset += 50
                if (offset > 200_000) break
            }
        }
        if (source in listOf("albums", "both")) {
            onProgress?.invoke("Reading saved albums…")
            var offset = 0
            while (true) {
                val page = apiService.getSavedAlbums(auth, 50, offset).body()
                if (page == null) throw Exception("Could not read saved albums")
                for (item in page.items) {
                    for (a in item.album.artists) {
                        artists[a.id] = a.name
                    }
                }
                if (page.items.isEmpty() || page.next == null) break
                offset += 50
                if (offset > 200_000) break
            }
        }

        val allIds = artists.keys.toList()
        if (allIds.isEmpty()) {
            return@withContext FollowLibraryResult(source, 0, 0, 0, dryRun)
        }

        onProgress?.invoke("Checking who you already follow…")
        val notFollowing = mutableListOf<String>()
        for (i in allIds.indices step 50) {
            val batch = allIds.subList(i, minOf(i + 50, allIds.size))
            val flags = apiService.checkFollowingArtists(auth, ids = batch.joinToString(","))
                .body() ?: emptyList()
            for (j in batch.indices) {
                val following = flags.getOrElse(j) { false }
                if (!following) notFollowing.add(batch[j])
            }
            withContext(Dispatchers.IO) { delay(100) }
        }

        var followed = 0
        if (!dryRun) {
            onProgress?.invoke("Following ${notFollowing.size} artists…")
            for (i in notFollowing.indices step 50) {
                val batch = notFollowing.subList(i, minOf(i + 50, notFollowing.size))
                val resp = apiService.followArtists(
                    auth,
                    ids = batch.joinToString(","),
                    body = emptyMap()
                )
                // retrofit Response is Unit body; ensure success
                val code = resp.code()
                if (code !in 200..299 && code != 204) {
                    throw Exception("Follow request failed: HTTP $code")
                }
                followed += batch.size
                delay(200)
            }
        } else {
            followed = 0
        }

        FollowLibraryResult(source, allIds.size, notFollowing.size, followed, dryRun)
    }

    // --- Spotify catalogue search / artist discography ---------------------

    suspend fun searchCatalogAlbums(
        query: String,
        limit: Int = 30,
    ): List<SpotifyCatalogAlbum> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val token = authManager.getValidAccessToken()
            ?: throw Exception("Spotify login required")
        val auth = "Bearer $token"
        val response = apiService.searchAlbums(auth, q, limit = limit.coerceIn(1, 50))
        if (!response.isSuccessful) {
            throw Exception(catalogErrorMessage(response.code(), "search Spotify"))
        }
        response.body()?.albums?.items.orEmpty()
            .mapNotNull { it.toCatalogAlbumOrNull() }
            .distinctBy { it.id }
    }

    suspend fun searchCatalogArtists(
        query: String,
        limit: Int = 10,
    ): List<SpotifyCatalogArtist> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val token = authManager.getValidAccessToken()
            ?: throw Exception("Spotify login required")
        val auth = "Bearer $token"
        val response = apiService.searchArtists(auth, q, limit = limit.coerceIn(1, 50))
        if (!response.isSuccessful) {
            throw Exception(catalogErrorMessage(response.code(), "search Spotify artists"))
        }
        response.body()?.artists?.items.orEmpty()
            .mapNotNull { it.toCatalogArtistOrNull() }
            .distinctBy { it.id }
    }

    /**
     * Fetch the complete artist discography for the selected release groups.
     * Spotify returns 50 per page, so follow `next`/total until exhausted.
     */
    suspend fun getArtistCatalogAlbums(
        artistId: String,
        includeGroups: Set<String>,
    ): List<SpotifyCatalogAlbum> = withContext(Dispatchers.IO) {
        val id = artistId.trim()
        if (id.isEmpty()) return@withContext emptyList()
        val groups = includeGroups
            .map { it.trim().lowercase() }
            .filter { it in setOf("album", "single", "appears_on", "compilation") }
            .distinct()
        if (groups.isEmpty()) return@withContext emptyList()
        val token = authManager.getValidAccessToken()
            ?: throw Exception("Spotify login required")
        val auth = "Bearer $token"
        val out = mutableListOf<SpotifyCatalogAlbum>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (offset < 2_000) {
            val response = apiService.getArtistAlbums(
                auth,
                id,
                includeGroups = groups.joinToString(","),
                limit = 50,
                offset = offset,
            )
            if (!response.isSuccessful) {
                throw Exception(catalogErrorMessage(response.code(), "load the artist discography"))
            }
            val page = response.body() ?: break
            val items = page.items.orEmpty()
            items.forEach { item ->
                val album = item.toCatalogAlbumOrNull() ?: return@forEach
                if (seen.add(album.id)) out.add(album)
            }
            if (page.next == null || items.isEmpty()) break
            offset += 50
            delay(80)
        }
        out
    }

    /**
     * Save catalogue releases to the user's Spotify library and mirror them
     * into the on-device Room library so the board updates immediately.
     *
     * The Spotify save is chunked at the API limit (50). If a later chunk
     * fails, the releases already saved are still imported locally and the
     * error names how many made it. Detail lookups are batched at 20; any
     * missing metadata falls back to the catalogue item instead of dropping
     * an album that Spotify has already accepted.
     */
    suspend fun addCatalogAlbumsToLibrary(
        albums: List<SpotifyCatalogAlbum>,
        onProgress: ((done: Int, total: Int, stage: String) -> Unit)? = null,
    ): CatalogLibraryResult = withContext(Dispatchers.IO) {
        val unique = albums
            .map { it.copy(id = it.id.trim()) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }
        if (unique.isEmpty()) throw Exception("No releases selected")

        val token = authManager.getValidAccessToken()
            ?: throw Exception("Spotify login required")
        val authHeader = "Bearer $token"
        var saved = 0
        try {
            unique.chunked(50).forEach { chunk ->
                val response = apiService.saveAlbums(
                    authHeader,
                    SaveAlbumsRequest(chunk.map { it.id }),
                )
                if (!response.isSuccessful) {
                    throw Exception(catalogLibraryErrorMessage(response.code()))
                }
                saved += chunk.size
                onProgress?.invoke(saved, unique.size, "Saving to Spotify")
                delay(100)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (saved == 0) throw e
            val imported = importSavedCatalogAlbums(unique.take(saved), onProgress)
            throw Exception(
                "Saved $saved of ${unique.size} releases before Spotify stopped: ${e.message}. " +
                    "$imported of them are already in this dashboard.",
                e,
            )
        }

        onProgress?.invoke(unique.size, unique.size, "Updating dashboard")
        val imported = importSavedCatalogAlbums(unique, onProgress)
        CatalogLibraryResult(unique.size, saved, imported)
    }

    private suspend fun importSavedCatalogAlbums(
        albums: List<SpotifyCatalogAlbum>,
        onProgress: ((done: Int, total: Int, stage: String) -> Unit)? = null,
    ): Int {
        if (albums.isEmpty()) return 0
        // A release the user previously hid as a duplicate is explicitly being
        // added again, so clear the blocklist entries before inserting.
        unhideAlbums(albums.map { it.id })

        val entities = mutableListOf<AlbumEntity>()
        val found = mutableSetOf<String>()
        val token = authManager.getValidAccessToken()
        if (token != null) {
            val authHeader = "Bearer $token"
            albums.chunked(20).forEach { chunk ->
                try {
                    val response = apiService.getAlbums(authHeader, chunk.joinToString(","))
                    if (response.isSuccessful) {
                        response.body()?.albums.orEmpty().filterNotNull().forEach { album ->
                            if (album.id.isNotBlank() && found.add(album.id)) {
                                entities.add(album.toAlbumEntity(nowIso()))
                            }
                        }
                    } else {
                        Log.w("SpotifyRepo", "Batch album lookup failed: HTTP ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w("SpotifyRepo", "Batch album lookup failed", e)
                }
                delay(80)
            }
        }

        albums.filter { it.id !in found }.forEach { album ->
            entities.add(
                AlbumEntity(
                    id = album.id,
                    title = album.name,
                    artist = album.artist,
                    year = album.year,
                    genre = "",
                    albumType = album.albumType.ifBlank { "album" }.lowercase(),
                    imageUrl = album.imageUrl,
                    imagePath = "",
                    addedAt = nowIso(),
                )
            )
        }

        var imported = 0
        entities.chunked(100).forEach { chunk ->
            try {
                albumDao.upsertAlbums(chunk)
                imported += chunk.size
            } catch (e: Exception) {
                // The Spotify save succeeded. Keep the result successful and
                // let the next normal sync repair the local rows.
                Log.e("SpotifyRepo", "Local library import failed for ${chunk.size} albums", e)
            }
        }
        onProgress?.invoke(albums.size, albums.size, "Dashboard updated")
        return imported
    }

    private fun catalogLibraryErrorMessage(code: Int): String = when {
        code == 401 -> "Spotify session expired. Log in again, then retry."
        code == 403 -> "Spotify refused the library update (HTTP 403). Log out and sign in again to grant library access."
        code == 429 -> "Spotify rate limit reached. Wait a moment, then retry."
        code in 500..599 -> "Spotify is unavailable right now (HTTP $code). Retry shortly."
        else -> "Could not add the selected releases to your Spotify library (HTTP $code)."
    }

    private fun libraryRemovalErrorMessage(code: Int): String = when {
        code == 401 -> "Spotify session expired. Log in again, then retry."
        code == 403 -> "Spotify refused the library deletion (HTTP 403). Log out and sign in again to grant library access."
        code == 429 -> "Spotify rate limit reached. Wait a moment, then retry."
        code in 500..599 -> "Spotify is unavailable right now (HTTP $code). Retry shortly."
        else -> "Could not remove the selected albums from Spotify (HTTP $code)."
    }

    private fun SpotifyCatalogAlbumResponse.toCatalogAlbumOrNull(): SpotifyCatalogAlbum? {
        val albumId = id.trim()
        if (albumId.isEmpty()) return null
        val image = images.orEmpty().maxByOrNull { it.width * it.height }?.url.orEmpty()
        val year = release_date.take(4).toIntOrNull() ?: 0
        return SpotifyCatalogAlbum(
            id = albumId,
            name = name.ifBlank { "Unknown album" },
            artist = artists.orEmpty().joinToString(", ") { it.name }.ifBlank { "Unknown artist" },
            year = year,
            albumType = album_type.ifBlank { "album" },
            albumGroup = album_group.orEmpty(),
            imageUrl = image,
            totalTracks = total_tracks,
            releaseDate = release_date,
        )
    }

    private fun SpotifyArtistSearchItem.toCatalogArtistOrNull(): SpotifyCatalogArtist? {
        val artistId = id.trim()
        if (artistId.isEmpty()) return null
        return SpotifyCatalogArtist(
            id = artistId,
            name = name.ifBlank { "Unknown artist" },
            imageUrl = images.orEmpty().maxByOrNull { it.width * it.height }?.url.orEmpty(),
            genres = genres.orEmpty(),
            followers = followers?.total ?: 0L,
        )
    }

    private fun catalogErrorMessage(code: Int, action: String): String = when (code) {
        401 -> "Spotify session expired. Log in again, then retry."
        403 -> "Spotify refused to $action (HTTP 403). Try logging in again."
        404 -> "Not found on Spotify while trying to $action."
        429 -> "Spotify rate limit reached. Wait a moment and retry."
        in 500..599 -> "Spotify is unavailable right now (HTTP $code). Retry shortly."
        else -> "Could not $action (HTTP $code)."
    }
}

/**
 * The device that should receive a playback command: the active one, or the
 * first controllable device when nothing is active yet. Restricted devices
 * (private sessions) refuse every Web API command.
 */
internal fun pickPlaybackDevice(devices: List<SpotifyDevice>): SpotifyDevice? =
    devices.firstOrNull { it.is_active && !it.is_restricted && !it.id.isNullOrBlank() }
        ?: devices.firstOrNull { !it.is_restricted && !it.id.isNullOrBlank() }
