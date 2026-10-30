package com.spotifydashboard.app.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Media-server contract (/api/v1) — mirrors the TypeScript remote layer
// (services/remote/*) which is live-tested against the Elixir backend.
// Field names match the JSON keys exactly (Gson maps by name), so the
// snake_case keys stay snake_case here and camelCase stays camelCase.
// ---------------------------------------------------------------------------

// --- Health ---

data class HealthResponse(val status: String = "")

// --- Auth (email+password JWT pair, Elixir reference) ---

data class AuthRequest(val email: String, val password: String)
data class AuthUser(val id: String = "", val email: String = "")
data class AuthPair(
    val access_token: String = "",
    val refresh_token: String = "",
    val token_type: String = "Bearer",
    val expires_in: Int = 3600,
    val user: AuthUser? = null,
    // Python returns user_id/email at the top level (Elixir nests them in
    // "user"), so accept both without breaking either backend.
    val user_id: String? = null,
    val email: String? = null
)
data class RefreshRequest(val refresh_token: String)
data class RefreshResponse(
    val access_token: String = "",
    val token_type: String = "Bearer",
    val expires_in: Int = 3600
)
data class LogoutResponse(val logged_out: Boolean = false)
data class AuthStatus(
    val authenticated: Boolean = false,
    val user_id: String? = null,
    val email: String? = null
)

// --- Spotify link (server holds the Spotify tokens) ---

data class SpotifyLinkStatus(val connected: Boolean = false, val display_name: String? = null)
data class SpotifyAuthUrl(val auth_url: String = "")

// --- Library (flat contract items — the JSON twin of the CSV columns) ---

data class RemoteAlbumItem(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val image_url: String? = null,
    val added_at: String? = null
)

data class AlbumsPage(
    val items: List<RemoteAlbumItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 50
)

data class StatsCount(val name: String = "", val count: Int = 0)
data class StatsYearCount(val year: Int = 0, val count: Int = 0)

data class StatsData(
    // Python/FastAPI returns camelCase; Elixir's JWT backend uses snake_case.
    @SerializedName(value = "totalAlbums", alternate = ["total_albums"])
    val totalAlbums: Int = 0,
    @SerializedName(value = "totalArtists", alternate = ["total_artists"])
    val totalArtists: Int = 0,
    @SerializedName(value = "totalGenres", alternate = ["total_genres"])
    val totalGenres: Int = 0,
    @SerializedName(value = "newestYear", alternate = ["newest_year"])
    val newestYear: Int? = null,
    @SerializedName(value = "oldestYear", alternate = ["oldest_year"])
    val oldestYear: Int? = null,
    // V9 optional stats extras — canonical camelCase; elixir's snake_case
    // aliases are accepted too, so either backend flavor parses.
    @SerializedName(value = "topGenres", alternate = ["top_genres"])
    val topGenres: List<StatsCount>? = null,
    @SerializedName(value = "topArtists", alternate = ["top_artists"])
    val topArtists: List<StatsCount>? = null,
    @SerializedName(value = "albumsByYear", alternate = ["albums_by_year"])
    val albumsByYear: List<StatsYearCount>? = null
)
data class StatsEnvelope(val data: StatsData = StatsData())

data class StringListEnvelope(val data: List<String> = emptyList())
data class IntListEnvelope(val data: List<Int> = emptyList())

// --- Jobs (every pipeline: library sync, playlist sync, queue) ---

data class JobStart(val job_id: String = "")
data class JobResultData(
    val imported: Int = 0,
    val existing: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
)
data class JobDto(
    val job_id: String = "",
    val type: String = "",
    val status: String = "",
    val stage: String = "albums",
    val fetched: Int = 0,
    val total: Int = 0,
    val result: JobResultData? = null,
    val error: String? = null,
    // Optional extras on GET /api/v1/sync/history rows: when the run started and
    // finished. Only some backends add them (python today) — elixir and C# send
    // the bare envelope — so history rendering must not require them.
    val created_at: String? = null,
    val finished_at: String? = null
)
data class JobCancelResponse(val status: String = "")
data class JobHistoryEnvelope(val data: List<JobDto> = emptyList())

data class SyncStartRequest(
    val clean: Boolean = false,
    @SerializedName("include_tracks") val includeTracks: Boolean = false
)
data class CsvImportRequest(
    val csv_content: String,
    /**
     * Mirror mode (phone → server push): after upserting, the server deletes
     * this user's albums that the payload does not contain. Old servers ignore
     * the field, so this stays backward compatible.
     */
    val replace: Boolean = false
)
data class CsvImportResponse(
    val imported: Int = 0,
    val existing: Int = 0,
    val skipped: Int = 0,
    /** Albums deleted by mirror mode (replace=true); 0 for a plain import. */
    val removed: Int = 0,
    val errors: List<String> = emptyList()
)
data class DeleteLibraryResponse(val deleted: Int = 0)

// --- Playlists ---

data class RemotePlaylistItem(
    val id: String = "",
    val name: String = "",
    val image_url: String? = null,
    val track_count: Int = 0,
    val public: Boolean = false,
    val synced_at: String? = null,
    // Additive: Spotify's page order index when the backend syncs a user's
    // playlists (captured from /me/playlists results, 0-based; lower = more
    // recently added). Older backends that don't send it remain null -> chip
    // falls back to name order.
    val added_order: Int? = null
)
data class PlaylistListEnvelope(val data: List<RemotePlaylistItem> = emptyList())

data class RemotePlaylistGroup(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val image_url: String = "",
    val track_count: Int = 0,
    val track_titles: List<String> = emptyList()
)
data class PlaylistAlbumsPage(
    val items: List<RemotePlaylistGroup> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 50
)
data class PlaylistMetaDto(
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val years: List<Int> = emptyList()
)
data class QueueStartRequest(val album_ids: List<String> = emptyList())
data class AddAlbumsRequest(val album_ids: List<String> = emptyList())
data class TracksAddedResponse(val tracks_added: Int = 0)
data class CreateRemotePlaylistRequest(
    val name: String,
    val album_ids: List<String> = emptyList(),
    val uris: List<String> = emptyList(),
    @SerializedName("retention_hours") val retention_hours: Double? = null,
    @SerializedName("retention_until") val retention_until: String? = null
)
data class CreatedRemotePlaylist(
    val playlist_id: String = "",
    val playlist_name: String = "",
    val tracks_added: Int = 0,
    val expires_at: String? = null
)

// --- Shortlived playlists (retention) ---

data class RetentionItem(
    val playlist_id: String = "",
    val expires_at: String? = null,
    val keep_forever: Boolean = false,
    val last_error: String? = null
)
data class RetentionListEnvelope(val items: List<RetentionItem> = emptyList())
data class SetRetentionRequest(@SerializedName("expires_at") val expires_at: String)

// --- Album track sheet ---

data class RemoteAlbumTrack(
    val spotify_id: String = "",
    val name: String = "",
    val artist_names: String = "",
    val disc_number: Int? = null,
    val track_number: Int? = null,
    val duration_ms: Long? = null,
    val uri: String = "",
    val explicit: Boolean = false,
    val preview_url: String? = null,
    val album_name: String = "",
    val album_image_url: String? = null,
    val release_year: Int? = null
)
data class AlbumTracksEnvelope(
    val album_id: String = "",
    val cached: Boolean = false,
    val items: List<RemoteAlbumTrack> = emptyList()
)

data class RemoveAlbumsRequest(@SerializedName("album_ids") val album_ids: List<String>)
data class RemoveRemoteTracksRequest(val uris: List<String>)
data class TrackUrisRequest(val uris: List<String>)
data class RemovedTracksResponse(@SerializedName("tracks_removed") val tracks_removed: Int = 0)
data class QueuedTracksResponse(val queued: Int = 0)

// --- Service ---

interface DashboardApiService {
    @GET("api/v1/health")
    suspend fun health(): Response<HealthResponse>

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: AuthRequest): Response<AuthPair>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: AuthRequest): Response<AuthPair>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<RefreshResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Header("Authorization") auth: String): Response<LogoutResponse>

    @GET("api/v1/auth/status")
    suspend fun authStatus(@Header("Authorization") auth: String): Response<AuthStatus>

    @GET("api/v1/spotify/status")
    suspend fun spotifyStatus(@Header("Authorization") auth: String): Response<SpotifyLinkStatus>

    @GET("api/v1/auth/spotify/login")
    suspend fun spotifyAuthUrl(@Header("Authorization") auth: String): Response<SpotifyAuthUrl>

    @HTTP(method = "DELETE", path = "api/v1/auth/spotify", hasBody = false)
    suspend fun disconnectSpotify(@Header("Authorization") auth: String): Response<Unit>

    @GET("api/v1/albums")
    suspend fun getAlbums(
        @Header("Authorization") auth: String,
        @Query("search") search: String,
        @Query("genre") genre: String,
        @Query("artist") artist: String,
        @Query("year_from") yearFrom: String?,
        @Query("year_to") yearTo: String?,
        @Query("sort") sort: String,
        @Query("order") order: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Response<AlbumsPage>

    @GET("api/v1/albums/stats")
    suspend fun getStats(@Header("Authorization") auth: String): Response<StatsEnvelope>

    /** Canonical CSV of this user's server library (media-server export). */
    @Streaming
    @GET("api/v1/albums/export")
    suspend fun exportLibraryCsv(@Header("Authorization") auth: String): Response<ResponseBody>

    @GET("api/v1/meta/genres")
    suspend fun getGenres(@Header("Authorization") auth: String): Response<StringListEnvelope>

    @GET("api/v1/meta/artists")
    suspend fun getArtists(@Header("Authorization") auth: String): Response<StringListEnvelope>

    @GET("api/v1/meta/years")
    suspend fun getYears(@Header("Authorization") auth: String): Response<IntListEnvelope>

    @POST("api/v1/sync/albums")
    suspend fun startSync(
        @Header("Authorization") auth: String,
        @Body body: SyncStartRequest
    ): Response<JobStart>

    @GET("api/v1/jobs/{id}")
    suspend fun getJob(
        @Header("Authorization") auth: String,
        @Path("id") jobId: String
    ): Response<JobDto>

    @POST("api/v1/jobs/{id}/cancel")
    suspend fun cancelJob(
        @Header("Authorization") auth: String,
        @Path("id") jobId: String
    ): Response<JobCancelResponse>

    @GET("api/v1/sync/history")
    suspend fun getSyncHistory(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int
    ): Response<JobHistoryEnvelope>

    @POST("api/v1/import/csv")
    suspend fun importCsv(
        @Header("Authorization") auth: String,
        @Body body: CsvImportRequest
    ): Response<CsvImportResponse>

    @DELETE("api/v1/albums")
    suspend fun clearLibrary(@Header("Authorization") auth: String): Response<DeleteLibraryResponse>

    @GET("api/v1/playlists")
    suspend fun getPlaylists(@Header("Authorization") auth: String): Response<PlaylistListEnvelope>

    @GET("api/v1/playlists/{id}/albums")
    suspend fun getPlaylistAlbums(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Query("search") search: String,
        @Query("genre") genre: String,
        @Query("artist") artist: String,
        @Query("year_from") yearFrom: String?,
        @Query("year_to") yearTo: String?,
        @Query("sort") sort: String,
        @Query("order") order: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Response<PlaylistAlbumsPage>

    @GET("api/v1/playlists/{id}/meta")
    suspend fun getPlaylistMeta(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String
    ): Response<PlaylistMetaDto>

    @POST("api/v1/playlists/{id}/sync")
    suspend fun startPlaylistSync(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String
    ): Response<JobStart>

    @POST("api/v1/queue")
    suspend fun startQueue(
        @Header("Authorization") auth: String,
        @Body body: QueueStartRequest
    ): Response<JobStart>

    @POST("api/v1/playlists/{id}/tracks")
    suspend fun addTracks(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: AddAlbumsRequest
    ): Response<TracksAddedResponse>

    @POST("api/v1/playlists/{id}/items")
    suspend fun addItems(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: TrackUrisRequest
    ): Response<TracksAddedResponse>

    @POST("api/v1/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") auth: String,
        @Body body: CreateRemotePlaylistRequest
    ): Response<CreatedRemotePlaylist>

    // --- Album track sheet ---

    @GET("api/v1/albums/{id}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") auth: String,
        @Path("id") albumId: String
    ): Response<AlbumTracksEnvelope>

    @HTTP(method = "DELETE", path = "api/v1/playlists/{id}/albums", hasBody = true)
    suspend fun removeAlbums(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: RemoveAlbumsRequest
    ): Response<RemovedTracksResponse>

    @HTTP(method = "DELETE", path = "api/v1/playlists/{id}/tracks", hasBody = true)
    suspend fun removeTracks(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: RemoveRemoteTracksRequest
    ): Response<RemovedTracksResponse>

    @POST("api/v1/queue/tracks")
    suspend fun queueTrackUris(
        @Header("Authorization") auth: String,
        @Body body: TrackUrisRequest
    ): Response<QueuedTracksResponse>

    // --- Shortlived playlists (retention) ---

    @GET("api/v1/playlists/retention")
    suspend fun getRetention(@Header("Authorization") auth: String): Response<RetentionListEnvelope>

    @PUT("api/v1/playlists/{id}/retention")
    suspend fun setRetention(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: SetRetentionRequest
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/playlists/{id}/retention", hasBody = false)
    suspend fun clearRetention(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String
    ): Response<Unit>

    @POST("api/v1/playlists/{id}/retention/delete-now")
    suspend fun deletePlaylistNow(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String
    ): Response<Unit>

    @PUT("api/v1/playlists/{id}/visibility")
    suspend fun setPlaylistPublic(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: Map<String, Any>,
    ): Response<Unit>
}
