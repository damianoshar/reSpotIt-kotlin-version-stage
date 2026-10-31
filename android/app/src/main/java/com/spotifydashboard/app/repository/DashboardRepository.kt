package com.spotifydashboard.app.repository

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import com.spotifydashboard.app.api.*
import com.spotifydashboard.app.data.AlbumEntity
import com.spotifydashboard.app.data.FilterState
import com.spotifydashboard.app.data.LibraryStats
import com.spotifydashboard.app.data.StatsBucket
import com.spotifydashboard.app.data.PlaylistAlbumEntity
import com.spotifydashboard.app.utils.BackupFile
import com.spotifydashboard.app.utils.CSVHelper
import com.spotifydashboard.app.utils.CsvImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of checking whether the server is reachable and the JWT is valid. */
data class AuthProbeResult(val reachable: Boolean, val authenticated: Boolean)

/**
 * Media-server data source — the Kotlin twin of the TypeScript remote layer
 * (services/remote). Talks to any conformant /api/v1 backend (Elixir is the
 * reference; Python and .NET implement the same contract).
 *
 * Session (server URL + JWT pair) lives in SharedPreferences, mirroring how
 * the TS layer uses Capacitor Preferences. Spotify tokens stay server-side;
 * only the app JWT crosses the wire, with one 401 refresh-and-retry.
 *
 * UI wiring (mode toggle, ViewModel branching) is the next slice — this file
 * is the complete data layer with zero UI dependencies.
 */
class DashboardRepository(private val context: Context) {

    companion object {
        const val TAG = "DashboardRepo"
        const val POLL_INTERVAL_MS = 2000L
    }

    private val prefs
        get() = context.getSharedPreferences("dashboard_remote_prefs", Context.MODE_PRIVATE)

    // -- Settings -----------------------------------------------------------

    fun getMode(): String = prefs.getString("mode", "local") ?: "local"
    fun isRemote(): Boolean = getMode() == "remote"
    fun setMode(mode: String) {
        prefs.edit().putString("mode", if (mode == "remote") "remote" else "local").apply()
    }

    fun getServerUrl(): String =
        prefs.getString("server_url", DashboardApiClient.DEFAULT_SERVER_URL)
            ?: DashboardApiClient.DEFAULT_SERVER_URL

    // -- Display preferences ------------------------------------------------

    /** Bottom navigation hides on scroll-down; persisted across restarts. */
    fun getAutohideNav(): Boolean = prefs.getBoolean("autohide_nav", true)
    fun setAutohideNav(enabled: Boolean) {
        prefs.edit().putBoolean("autohide_nav", enabled).apply()
    }

    /** Autohide for the search/sort bar - independent of the bottom nav. */
    fun getAutohideBoardBar(): Boolean = prefs.getBoolean("autohide_board_bar", false)

    fun setAutohideBoardBar(enabled: Boolean) {
        prefs.edit().putBoolean("autohide_board_bar", enabled).apply()
    }

    /**
     * Device-level artwork switch (card 276). Off = no cover fetches/renders,
     * which keeps low-RAM phones responsive. Independent of the server's
     * optional download_images sync flag.
     */
    fun getSyncPhotos(): Boolean = prefs.getBoolean("sync_photos", true)

    fun setSyncPhotos(enabled: Boolean) {
        prefs.edit().putBoolean("sync_photos", enabled).apply()
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", DashboardApiClient.normalizeServerUrl(url)).apply()
    }

    // -- Session ------------------------------------------------------------

    private fun accessJwt(): String? = prefs.getString("access_jwt", null)
    private fun refreshJwt(): String? = prefs.getString("refresh_jwt", null)
    private fun authHeader(): String = "Bearer ${accessJwt() ?: ""}"

    fun hasSession(): Boolean = !accessJwt().isNullOrEmpty()

    fun getSessionEmail(): String? = prefs.getString("session_email", null)

    fun clearSession() {
        prefs.edit()
            .remove("access_jwt")
            .remove("refresh_jwt")
            .remove("session_email")
            .apply()
    }

    private fun service(): DashboardApiService =
        DashboardApiClient.serviceFor(getServerUrl())

    private fun savePair(pair: AuthPair, fallbackEmail: String) {
        val email = pair.user?.email ?: pair.email ?: fallbackEmail
        prefs.edit()
            .putString("access_jwt", pair.access_token)
            .putString("refresh_jwt", pair.refresh_token)
            .putString("session_email", email)
            .apply()
    }

    suspend fun testConnection(url: String = getServerUrl()): Boolean = withContext(Dispatchers.IO) {
        val res = DashboardApiClient.serviceFor(url).health()
        res.isSuccessful && (res.body()?.status == "ok" || res.body()?.status == "healthy")
    }

    suspend fun register(email: String, password: String): String = withContext(Dispatchers.IO) {
        val res = service().register(AuthRequest(email, password))
        if (!res.isSuccessful || res.body()?.access_token.isNullOrEmpty())
            throw Exception(errorMessage(res.code(), "Registration failed"))
        val pair = res.body()!!
        savePair(pair, email)
        pair.user?.email ?: pair.email ?: email
    }

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val res = service().login(AuthRequest(email, password))
        if (!res.isSuccessful || res.body()?.access_token.isNullOrEmpty())
            throw Exception(errorMessage(res.code(), "Email or password is incorrect."))
        val pair = res.body()!!
        savePair(pair, email)
        pair.user?.email ?: pair.email ?: email
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            service().logout(authHeader())
        } catch (e: Exception) {
            Log.w(TAG, "Server logout failed (clearing local session anyway)", e)
        }
        clearSession()
    }

    /**
     * True when the stored JWT is accepted by the server. Refreshes once when
     * the access token is stale. Network failures are allowed to propagate so
     * callers can distinguish "offline" from "not signed in".
     */
    suspend fun checkAuth(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext false

        val svc = service()
        val first = svc.authStatus(authHeader())
        if (first.isSuccessful && first.body()?.authenticated == true) {
            return@withContext true
        }

        // Python answers 200 {authenticated:false} for an expired token;
        // Elixir answers 401. Treat both as "try the refresh token".
        val refreshToken = refreshJwt()
        if (!refreshToken.isNullOrEmpty()) {
            val refresh = svc.refresh(RefreshRequest(refreshToken))
            val fresh = refresh.body()?.access_token
            if (refresh.isSuccessful && !fresh.isNullOrEmpty()) {
                prefs.edit().putString("access_jwt", fresh).apply()
                val second = svc.authStatus(authHeader())
                return@withContext second.isSuccessful &&
                    second.body()?.authenticated == true
            }
        }
        false
    }

    /**
     * Reachability + JWT validity in one probe. Used at cold start and when
     * remote mode is selected, so the login screen only appears when the
     * server is up but the user is not signed in.
     */
    suspend fun probeAuth(): AuthProbeResult = withContext(Dispatchers.IO) {
        val reachable = try {
            testConnection()
        } catch (e: Exception) {
            Log.w(TAG, "Server health check failed", e)
            false
        }
        if (!reachable) return@withContext AuthProbeResult(false, false)

        val authenticated = try {
            checkAuth()
        } catch (e: Exception) {
            Log.w(TAG, "Auth check failed", e)
            false
        }
        AuthProbeResult(true, authenticated)
    }

    suspend fun spotifyLink(): SpotifyLinkStatus = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.spotifyStatus(header) }
            ?: SpotifyLinkStatus(connected = false)
    }

    suspend fun probeServer(): Int = withContext(Dispatchers.IO) {
        try {
            val svc = service()
            svc.spotifyStatus(authHeader()).code()
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun spotifyAuthUrl(): String = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.spotifyAuthUrl(header) }?.auth_url
            ?: throw Exception("Could not get Spotify login URL")
    }

    suspend fun disconnectSpotify() = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.disconnectSpotify(header) }
        Unit
    }

    /**
     * Runs [block] with the stored JWT; on 401 with a refresh token available,
     * refreshes once and retries. Returns the response body or null on
     * unsuccessful responses (callers throw with context).
     */
    private suspend fun <T> authed(block: suspend (DashboardApiService, String) -> retrofit2.Response<T>): T? {
        val svc = service()
        var res = block(svc, authHeader())
        if (res.code() == 401 && !refreshJwt().isNullOrEmpty()) {
            try {
                val refresh = svc.refresh(RefreshRequest(refreshJwt()!!))
                val fresh = refresh.body()?.access_token
                if (refresh.isSuccessful && !fresh.isNullOrEmpty()) {
                    prefs.edit().putString("access_jwt", fresh).apply()
                    res = block(svc, authHeader())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh failed", e)
            }
        }
        return if (res.isSuccessful) res.body() else null
    }

    private fun errorMessage(code: Int, fallback: String): String = when (code) {
        401 -> "Session expired — please log in again."
        404 -> "Not found on the server."
        409 -> "Already in progress on the server."
        else -> "$fallback (HTTP $code)"
    }

    // -- Library reads ------------------------------------------------------

    // Null means "omit the query parameter". Sending year_from= makes FastAPI
    // reject the request as a validation error.
    private fun yearParam(v: Int?): String? = v?.toString()

    suspend fun queryAlbumsAsync(f: FilterState, limit: Int, offset: Int): Pair<List<AlbumEntity>, Int> =
        withContext(Dispatchers.IO) {
            val page = offset / limit + 1
            val data = authed { svc, header ->
                svc.getAlbums(
                    header, f.search, f.genre, f.artist,
                    yearParam(f.yearFrom), yearParam(f.yearTo),
                    f.sortField, f.sortOrder, page, limit
                )
            } ?: throw Exception("Failed to load library from server")
            (data.items ?: emptyList()).map { mapAlbum(it) } to data.total
        }

    private fun mapAlbum(item: RemoteAlbumItem): AlbumEntity {
        return AlbumEntity(
            id = item.id,
            title = item.title,
            artist = item.artist,
            year = item.year,
            genre = item.genre,
            imageUrl = item.image_url ?: "",
            imagePath = "",
            addedAt = item.added_at ?: ""
        )
    }

    suspend fun fetchStats(): LibraryStats = withContext(Dispatchers.IO) {
        val s = authed { svc, header -> svc.getStats(header) }?.data
            ?: throw Exception("Failed to load stats from server")
        LibraryStats(
            totalAlbums = s.totalAlbums,
            totalArtists = s.totalArtists,
            totalGenres = s.totalGenres,
            newestYear = s.newestYear ?: 0,
            oldestYear = s.oldestYear ?: 0,
            topGenres = s.topGenres?.map { StatsBucket(it.name, it.count) } ?: emptyList(),
            topArtists = s.topArtists?.map { StatsBucket(it.name, it.count) } ?: emptyList(),
            albumsByYear = s.albumsByYear?.map { StatsBucket(it.year.toString(), it.count) } ?: emptyList()
        )
    }

    /**
     * Media-server CSV export: the backend builds the canonical CSV (and
     * records the export audit row); we save it to the same app Documents
     * folder as local backups. Returns null for an empty server library.
     */
    suspend fun exportLibraryCsv(subdir: String): BackupFile? = withContext(Dispatchers.IO) {
        val body = authed { svc, header -> svc.exportLibraryCsv(header) }
            ?: throw Exception("Failed to export library from server")
        val csv = body.use { it.string() }
        if (csv.isBlank()) return@withContext null
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        CSVHelper.writeBackupFile(context, csv, subdir, "spotify-library-$date.csv")
    }

    suspend fun fetchFilterOptions(): Triple<List<String>, List<String>, List<Int>> =
        withContext(Dispatchers.IO) {
            val g = authed { svc, header -> svc.getGenres(header) }?.data ?: emptyList()
            val a = authed { svc, header -> svc.getArtists(header) }?.data ?: emptyList()
            val y = authed { svc, header -> svc.getYears(header) }?.data ?: emptyList()
            Triple(g, a, y)
        }

    // -- Library sync (start → poll → cancel) --------------------------------

    private fun mapStage(stage: String): SyncStage =
        when (stage) {
            "albums" -> SyncStage.ALBUMS
            "genres" -> SyncStage.GENRES
            "saving" -> SyncStage.SAVING
            // Queue-only stages never surface here, but stay total-safe.
            else -> SyncStage.SAVING
        }

    private fun jobResult(job: JobDto): SyncResult {
        val r = job.result
        return SyncResult(
            imported = r?.imported ?: 0,
            skipped = r?.skipped ?: 0,
            existing = r?.existing ?: 0,
            errors = r?.errors ?: emptyList()
        )
    }

    suspend fun startSync(clean: Boolean, includeTracks: Boolean = false): String = withContext(Dispatchers.IO) {
        val start = authed { svc, header ->
            svc.startSync(header, SyncStartRequest(clean, includeTracks))
        } ?: throw Exception("Server did not return a sync job")
        if (start.job_id.isEmpty()) throw Exception("Server did not return a sync job")
        start.job_id
    }

    suspend fun cancelSyncJob(jobId: String) = withContext(Dispatchers.IO) {
        try {
            authed { svc, header -> svc.cancelJob(header, jobId) }
        } catch (e: Exception) {
            Log.w(TAG, "Server cancel failed", e)
        }
    }

    /**
     * Polls a library sync job every 2s, driving the same [SyncState] UI as
     * local sync. Returns partial counts when [signal] fires (after a
     * best-effort server cancel), throws on job error.
     */
    suspend fun pollSync(
        jobId: String,
        signal: SyncCancelSignal = SyncCancelSignal(),
        onProgress: (SyncState) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        while (true) {
            if (signal.cancelled) {
                cancelSyncJob(jobId)
                val job = authed { svc, header -> svc.getJob(header, jobId) }
                val partial = if (job != null) jobResult(job) else SyncResult(0, 0, 0, emptyList())
                onProgress(SyncState.Cancelled(partial))
                return@withContext partial
            }
            val job = authed { svc, header -> svc.getJob(header, jobId) }
                ?: throw Exception("Lost contact with sync job")
            onProgress(SyncState.Progress(mapStage(job.stage), job.fetched, job.total))
            when (job.status) {
                "done" -> {
                    val done = jobResult(job)
                    onProgress(SyncState.Completed(done))
                    return@withContext done
                }
                "cancelled" -> {
                    val done = jobResult(job)
                    onProgress(SyncState.Cancelled(done))
                    return@withContext done
                }
                "error" -> {
                    val msg = job.error ?: "Remote sync failed"
                    onProgress(SyncState.Error(msg))
                    throw Exception(msg)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        SyncResult(0, 0, 0, emptyList())
    }

    suspend fun syncLibraryRemote(
        signal: SyncCancelSignal = SyncCancelSignal(),
        clean: Boolean = false,
        includeTracks: Boolean = false,
        onProgress: (SyncState) -> Unit
    ): SyncResult {
        onProgress(SyncState.Progress(SyncStage.ALBUMS, 0, 0))
        val jobId = startSync(clean, includeTracks)
        return pollSync(jobId, signal, onProgress)
    }

    // -- Sync history --------------------------------------------------------

    /**
     * The server's recent runs, newest first — the canonical {data: [job
     * envelope]} shape, listing every job type (callers filter to finished
     * syncs). History is a nicety: an unreachable or failing fetch yields an
     * empty list, never an exception that could block the sync UI.
     */
    suspend fun fetchSyncHistory(limit: Int = 5): List<JobDto> = withContext(Dispatchers.IO) {
        try {
            authed { svc, header -> svc.getSyncHistory(header, limit) }?.data
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Sync history fetch failed", e)
            emptyList()
        }
    }

    // -- CSV import / clear ---------------------------------------------------

    /**
     * Import CSV into the signed-in account's server library.
     * [replace] mirrors the payload: albums absent from it are deleted, which
     * is what the phone → server push uses. Default false keeps the plain
     * merge behaviour of the manual import screen.
     */
    suspend fun importCsvText(
        csvText: String,
        replace: Boolean = false
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val data = authed { svc, header ->
            svc.importCsv(header, CsvImportRequest(csvText, replace))
        } ?: throw Exception("CSV import failed on server")
        CsvImportResult(
            imported = data.imported,
            skipped = data.existing + data.skipped,
            errors = data.errors,
            removed = data.removed,
            existing = data.existing
        )
    }

    suspend fun clearRemoteLibrary() = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.clearLibrary(header) }
        Unit
    }

    suspend fun exportPlaylistCsv(playlistId: String, playlistName: String): File? =
        withContext(Dispatchers.IO) {
            val header = authHeader()
            val safe = playlistName.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("").trim('-').ifEmpty { "playlist" }
            val req = okhttp3.Request.Builder()
                .url(
                    DashboardApiClient.normalizeServerUrl(getServerUrl()) +
                        "/api/v1/playlists/$playlistId/export"
                )
                .header("Authorization", header)
                .build()
            OkHttpClientHolder.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw Exception("Export failed: HTTP ${res.code}")
                val body = res.body ?: throw Exception("Empty export response")
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                val file = File(context.getExternalFilesDir(null), "playlist-$safe-$date.csv")
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
                file
            }
        }

    // -- Queue (start → poll → result) ----------------------------------------

    suspend fun queueAlbums(albumIds: Set<String>): QueueResult = withContext(Dispatchers.IO) {
        val start = authed { svc, header -> svc.startQueue(header, QueueStartRequest(albumIds.toList())) }
            ?: throw Exception("Server did not return a queue job")
        while (true) {
            val job = authed { svc, header -> svc.getJob(header, start.job_id) }
                ?: throw Exception("Lost contact with queue job")
            if (job.status == "done" || job.status == "cancelled") {
                val r = job.result
                return@withContext QueueResult(
                    success = r?.imported ?: 0,
                    failed = r?.skipped ?: 0,
                    error = r?.errors?.firstOrNull()
                )
            }
            if (job.status == "error") throw Exception(job.error ?: "Queue failed")
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        QueueResult(0, 0, "Queue failed")
    }

    // -- Playlists ------------------------------------------------------------

    suspend fun listPlaylists(): Pair<List<com.spotifydashboard.app.api.SpotifyPlaylist>, Map<String, String>> =
        withContext(Dispatchers.IO) {
            val items = authed { svc, header -> svc.getPlaylists(header) }?.data ?: emptyList()
            val syncedAt = mutableMapOf<String, String>()
            val mapped = items.map { p ->
                if (p.synced_at != null) syncedAt[p.id] = p.synced_at
                com.spotifydashboard.app.api.SpotifyPlaylist(
                    id = p.id,
                    name = p.name,
                    images = if (p.image_url != null)
                        listOf(SpotifyImageResponse(p.image_url, 0, 0)) else emptyList(),
                    tracks = TrackInfo(p.track_count),
                    owner = OwnerInfo(""),
                    addedOrder = p.added_order
                )
            }
            mapped to syncedAt
        }

    suspend fun queryPlaylistAlbums(
        playlistId: String, f: FilterState, limit: Int, offset: Int
    ): Pair<List<PlaylistAlbumEntity>, Int> = withContext(Dispatchers.IO) {
        val page = offset / limit + 1
        val sort = if (f.sortField == "added_at") "artist" else f.sortField
        val data = authed { svc, header ->
            svc.getPlaylistAlbums(
                header, playlistId, f.search, f.genre, f.artist,
                yearParam(f.yearFrom), yearParam(f.yearTo),
                sort, f.sortOrder, page, limit
            )
        } ?: throw Exception("Failed to load playlist from server")
        val albums = (data.items ?: emptyList()).map { g ->
            PlaylistAlbumEntity(
                playlistId = playlistId,
                albumId = g.id,
                title = g.title,
                artist = g.artist,
                year = g.year,
                genre = g.genre,
                imageUrl = g.image_url,
                trackCount = g.track_count,
                trackTitles = JSONArray(g.track_titles).toString()
            )
        }
        albums to data.total
    }

    suspend fun playlistMeta(playlistId: String): Triple<List<String>, List<String>, List<Int>> =
        withContext(Dispatchers.IO) {
            val m = authed { svc, header -> svc.getPlaylistMeta(header, playlistId) }
                ?: throw Exception("Failed to load playlist filters")
            Triple(m.genres, m.artists, m.years)
        }

    /**
     * Remote playlist sync — server stages collapse onto the local tracks
     * progress (mirrors TS pollRemotePlaylistSync). Returns the album count.
     */
    suspend fun syncPlaylistRemote(
        playlistId: String,
        signal: SyncCancelSignal = SyncCancelSignal(),
        onProgress: (PlaylistSyncState) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val start = authed { svc, header -> svc.startPlaylistSync(header, playlistId) }
            ?: throw Exception("Server did not return a playlist sync job")
        val jobId = start.job_id
        while (true) {
            if (signal.cancelled) {
                cancelSyncJob(jobId)
                onProgress(PlaylistSyncState.Cancelled)
                return@withContext 0
            }
            val job = authed { svc, header -> svc.getJob(header, jobId) }
                ?: throw Exception("Lost contact with playlist sync job")
            onProgress(PlaylistSyncState.Progress(SyncStage.SAVING, job.fetched, job.total))
            when (job.status) {
                "done", "cancelled" -> {
                    val n = job.result?.let { it.imported + it.existing } ?: 0
                    onProgress(PlaylistSyncState.Completed(n))
                    return@withContext n
                }
                "error" -> {
                    val msg = job.error ?: "Playlist sync failed"
                    onProgress(PlaylistSyncState.Error(msg))
                    throw Exception(msg)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        0
    }

    suspend fun addAlbumsToPlaylist(albumIds: Set<String>, playlistId: String): Int =
        withContext(Dispatchers.IO) {
            authed { svc, header ->
                svc.addTracks(header, playlistId, AddAlbumsRequest(albumIds.toList()))
            }?.tracks_added ?: 0
        }

    suspend fun createAndAddPlaylist(
        name: String,
        albumIds: Set<String>,
        retentionUntil: String? = null,
        uris: List<String> = emptyList(),
    ): CreatedRemotePlaylist =
        withContext(Dispatchers.IO) {
            authed { svc, header ->
                svc.createPlaylist(
                    header,
                    CreateRemotePlaylistRequest(
                        name = name,
                        album_ids = albumIds.toList(),
                        uris = uris,
                        retention_hours = null,
                        retention_until = retentionUntil,
                    ),
                )
            } ?: throw Exception(
                "The media server could not create the playlist. " +
                    "Check that its Spotify link is connected and can edit playlists."
            )
        }

    /** Append direct track/episode URIs to a playlist through the server. */
    suspend fun addTrackUrisToPlaylist(playlistId: String, uris: List<String>): Int =
        withContext(Dispatchers.IO) {
            val result = authed { svc, header ->
                svc.addItems(header, playlistId, TrackUrisRequest(uris))
            } ?: throw Exception(
                "The media server could not append to this playlist. " +
                    "Check that its Spotify link can edit this playlist."
            )
            result.tracks_added
        }

    // -- Album track sheet / playlist removal --------------------------------

    suspend fun albumTracks(albumId: String): List<RemoteAlbumTrack> = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.getAlbumTracks(header, albumId) }?.items ?: emptyList()
    }

    suspend fun removeAlbumsFromPlaylist(playlistId: String, albumIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            authed { svc, header ->
                svc.removeAlbums(header, playlistId, RemoveAlbumsRequest(albumIds.toList()))
            }?.tracks_removed ?: 0
        }

    suspend fun removeTrackUris(playlistId: String, uris: List<String>): Int =
        withContext(Dispatchers.IO) {
            authed { svc, header ->
                svc.removeTracks(header, playlistId, RemoveRemoteTracksRequest(uris))
            }?.tracks_removed ?: 0
        }

    suspend fun queueTrackUris(uris: List<String>): Int = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.queueTrackUris(header, TrackUrisRequest(uris)) }?.queued ?: 0
    }

    // -- Shortlived playlists -------------------------------------------------

    /** playlistId -> ISO expiry, for the open playlist's badge. */
    suspend fun retentionMap(): Map<String, String> = withContext(Dispatchers.IO) {
        val items = authed { svc, header -> svc.getRetention(header) }?.items ?: emptyList()
        items.mapNotNull { item ->
            val expiry = item.expires_at ?: return@mapNotNull null
            item.playlist_id to expiry
        }.toMap()
    }

    suspend fun setPlaylistRetention(playlistId: String, expiresAt: String) =
        withContext(Dispatchers.IO) {
            authed { svc, header -> svc.setRetention(header, playlistId, SetRetentionRequest(expiresAt)) }
            Unit
        }

    suspend fun clearPlaylistRetention(playlistId: String) = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.clearRetention(header, playlistId) }
        Unit
    }

    suspend fun deletePlaylistNow(playlistId: String) = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.deletePlaylistNow(header, playlistId) }
        Unit
    }

    suspend fun setPlaylistPublic(playlistId: String, public: Boolean) = withContext(Dispatchers.IO) {
        authed { svc, header -> svc.setPlaylistPublic(header, playlistId, mapOf("public" to public)) }
        Unit
    }
}

/** Shared OkHttp for raw file downloads (export CSV). */
private object OkHttpClientHolder {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
