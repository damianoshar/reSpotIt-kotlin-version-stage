package com.spotifydashboard.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
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

// --- Spotify PKCE hand-off + catalogue (server-side Spotify account) ---
//
// A phone on the LAN cannot finish the server's loopback OAuth callback, so the
// app authorises with its custom scheme and POSTs the resulting tokens here.
// The catalogue endpoints then let remote mode search, browse discographies
// and edit the saved library through the same server-side account.

data class SpotifyTokenImportRequest(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
)
data class SpotifyTokenImportResponse(val connected: Boolean = false)

data class RemoteSpotifyArtist(
    val id: String = "",
    val name: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    val genres: List<String> = emptyList(),
    val followers: Long = 0L,
)

data class RemoteSpotifyAlbum(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    @SerializedName("artist_ids") val artistIds: List<String> = emptyList(),
    val year: Int = 0,
    val genre: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    @SerializedName("album_type") val albumType: String = "",
    @SerializedName("album_group") val albumGroup: String = "",
    @SerializedName("total_tracks") val totalTracks: Int = 0,
    @SerializedName("spotify_url") val spotifyUrl: String = "",
)

data class SpotifyCatalogResponse(
    val artists: List<RemoteSpotifyArtist> = emptyList(),
    val albums: List<RemoteSpotifyAlbum> = emptyList(),
)

data class SpotifyArtistAlbumsResponse(
    @SerializedName("artist_id") val artistId: String = "",
    val albums: List<RemoteSpotifyAlbum> = emptyList(),
)

data class SpotifyAlbumSelection(@SerializedName("album_ids") val albumIds: List<String>)
data class SpotifySavedResponse(val saved: Int = 0)
data class SpotifyRemovedResponse(val removed: Int = 0)

// --- Library (flat contract items — the JSON twin of the CSV columns) ---

data class RemoteAlbumItem(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val image_url: String? = null,
    val added_at: String? = null,
    @SerializedName("album_type") val albumType: String? = null
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

// --- Local LLM (additive /api/v1/llm/*, proxied through the media server) ---
//
// The Open WebUI credential never reaches the phone: every call carries the
// same user JWT as the rest of /api/v1. Chat history is stored by Open WebUI;
// the server only remembers which chat the user is currently in.

data class LLMStatusDto(
    val configured: Boolean = false,
    val available: Boolean = false,
    @SerializedName("base_url") val baseUrl: String = "",
    val model: String? = null,
    val models: Int = 0,
    val error: String? = null,
    @SerializedName("mcp_configured") val mcpConfigured: Boolean = false,
    @SerializedName("mcp_tools") val mcpTools: Int = 0,
    /** True when the server can store chats for this user. */
    @SerializedName("chat_history") val chatHistory: Boolean = false,
)
data class LLMStatusEnvelope(val data: LLMStatusDto = LLMStatusDto())

data class LLMMessageDto(val role: String, val content: String)

data class LLMChatRequest(
    val messages: List<LLMMessageDto>,
    val model: String? = null,
    val system: String? = null,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    @SerializedName("use_tools") val useTools: Boolean = true,
    @SerializedName("chat_id") val chatId: String? = null,
    @SerializedName("use_history") val useHistory: Boolean = false,
    @SerializedName("history_limit") val historyLimit: Int = 20,
    val persist: Boolean = false,
    /** Chat type for filtering (assistant, extract_albums, extract_concerts, etc.) */
    @SerializedName("chat_type") val chatType: String? = null,
)

data class LLMToolCallDto(
    val name: String = "",
    val arguments: com.google.gson.JsonElement? = null,
    val result: String = "",
)
data class LLMUsageDto(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0,
)
data class LLMChatResultDto(
    val content: String = "",
    @SerializedName("reasoning_content") val reasoningContent: String = "",
    @SerializedName("finish_reason") val finishReason: String? = null,
    val model: String = "",
    val usage: LLMUsageDto? = null,
    @SerializedName("tool_calls") val toolCalls: List<LLMToolCallDto> = emptyList(),
    /** The chat this turn belongs to (new or continued). */
    @SerializedName("chat_id") val chatId: String? = null,
    /** Whether the exchange was written to Open WebUI. */
    val persisted: Boolean = false,
)
data class LLMChatEnvelope(val data: LLMChatResultDto = LLMChatResultDto())

data class LLMChatSummaryDto(
    val id: String = "",
    val title: String? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    val models: List<String>? = null,
)
data class LLMChatListEnvelope(val data: List<LLMChatSummaryDto> = emptyList())

/** Open WebUI stores message content as a string or a list of text parts. */
data class LLMStoredMessageDto(
    val id: String? = null,
    val role: String? = null,
    val content: com.google.gson.JsonElement? = null,
    val timestamp: Long? = null,
)
data class LLMChatHistoryDto(
    val messages: Map<String, LLMStoredMessageDto>? = null,
    @SerializedName("currentId") val currentId: String? = null,
)
data class LLMChatBodyDto(
    val messages: List<LLMStoredMessageDto>? = null,
    val history: LLMChatHistoryDto? = null,
)
data class LLMChatRowDto(
    val id: String = "",
    val title: String? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    val models: List<String>? = null,
    val chat: LLMChatBodyDto? = null,
)
data class LLMChatRowEnvelope(val data: LLMChatRowDto? = null)
data class LLMClearChatResponse(val cleared: Boolean = false)
data class LLMClearChatEnvelope(val data: LLMClearChatResponse = LLMClearChatResponse())
data class LLMDeleteChatResponse(
    val deleted: Boolean = false,
    @SerializedName("chat_id") val chatId: String = "",
)
data class LLMDeleteChatEnvelope(val data: LLMDeleteChatResponse = LLMDeleteChatResponse())

// --- Service ---

// --- Phase 0: AI URL scan response models ---
// Concrete request bodies (Retrofit/Gson reject Map<String, Any> / wildcards).
data class ExtractBody(val url: String? = null, val text: String? = null)
data class ResolveAlbumItem(val artist: String = "", val album: String = "")
data class ResolveAlbumsBody(val items: List<ResolveAlbumItem> = emptyList())
data class ResolveArtistsBody(val names: List<String> = emptyList())
data class ExtractedAlbumDto(val artist: String = "", val album: String = "")
data class ExtractAlbumsResponse(val data: ExtractAlbumsData? = null)
data class ExtractAlbumsData(val albums: List<ExtractedAlbumDto> = emptyList())

data class ExtractedConcertDto(val artist: String = "", val date: String = "", val venue: String = "", val url: String = "")
data class ExtractConcertsResponse(val data: ExtractConcertsData? = null)
data class ExtractConcertsData(val concerts: List<ExtractedConcertDto> = emptyList())

data class FollowedArtistDto(val id: String = "", val name: String = "", val image_url: String = "", val genres: List<String> = emptyList())
data class FollowedArtistsResponse(val artists: List<FollowedArtistDto> = emptyList())

// POST /api/v1/spotify/follow-artists (server-side Spotify account).
data class FollowArtistsResult(
    val total: Int = 0,
    val already_followed: Int = 0,
    val followed: Int = 0
)

data class ResolvedAlbumDto(val artist: String = "", val album: String = "", val spotify_id: String? = null, val title: String = "", val artist_name: String = "", val year: Int = 0, val image_url: String = "", val spotify_url: String = "")
data class ResolveAlbumsResponse(val resolved: List<ResolvedAlbumDto> = emptyList(), val unresolved: List<Map<String, String>> = emptyList())

data class ResolvedArtistDto(val name: String = "", val spotify_id: String = "", val matched_name: String = "", val image_url: String = "", val genres: List<String> = emptyList(), val followed: Boolean = false)
data class ResolveArtistsResponse(val resolved: List<ResolvedArtistDto> = emptyList(), val unresolved: List<String> = emptyList())

// --- Concerts (server-canonical — docs/STORAGE-POLICY.md) ---

data class RemoteConcertItem(
    val id: String = "",
    val artist: String = "",
    val artist_id: String = "",
    val title: String = "",
    val date: String = "",
    val venue: String = "",
    val url: String = "",
    val source_url: String = "",
    val followed: Boolean = false,
    val created_at: String = ""
)
data class ConcertsEnvelope(val total: Int = 0, val concerts: List<RemoteConcertItem> = emptyList())
data class ConcertsUpsertBody(val items: List<RemoteConcertItem> = emptyList())
data class ConcertsImportBody(val items: List<RemoteConcertItem> = emptyList(), val replace: Boolean = false)
data class ConcertsImportResponse(
    val imported: Int = 0,
    val existing: Int = 0,
    val removed: Int = 0,
    val total: Int = 0,
    val errors: List<String> = emptyList()
)

// --- Concert scans (click-and-forget AI scan, pending review) ---

data class ConcertScanStartBody(val url: String? = null, val text: String? = null)

data class ConcertScanItemDto(
    val artist: String = "",
    val date: String = "",
    val venue: String = "",
    val url: String = ""
)

data class ConcertScanDto(
    val id: String = "",
    val source: String = "",
    val source_url: String = "",
    val source_kind: String = "text",
    val status: String = "running",
    val stage: String = "queued",
    val message: String = "",
    val items: List<ConcertScanItemDto> = emptyList(),
    val error: String = "",
    val chat_id: String = "",
    val source_id: String? = null,
    val chunk_count: Int = 0,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class ConcertScanLatestEnvelope(val scan: ConcertScanDto? = null)

// --- Hidden duplicate blocklist (server-canonical) ---

data class RemoteHiddenAlbumItem(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int = 0,
    val genre: String = "",
    @SerializedName("album_type") val albumType: String = "album",
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("added_at") val addedAt: String = ""
)
data class HiddenAlbumsEnvelope(val total: Int = 0, val albums: List<RemoteHiddenAlbumItem> = emptyList())
data class HiddenAlbumsUpsertBody(val items: List<RemoteHiddenAlbumItem> = emptyList())
data class HiddenAlbumsImportBody(val items: List<RemoteHiddenAlbumItem> = emptyList(), val replace: Boolean = false)
data class HiddenAlbumsImportResponse(
    val imported: Int = 0,
    val existing: Int = 0,
    val removed: Int = 0,
    val total: Int = 0,
    val errors: List<String> = emptyList()
)

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

    /** Hand the app's PKCE tokens to the media server (remote-mode linking). */
    @POST("api/v1/auth/spotify/token")
    suspend fun importSpotifyToken(
        @Header("Authorization") auth: String,
        @Body body: SpotifyTokenImportRequest
    ): Response<SpotifyTokenImportResponse>

    // --- Spotify catalogue through the server-side account ---

    @GET("api/v1/spotify/catalog/search")
    suspend fun searchSpotifyCatalog(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("limit") limit: Int
    ): Response<SpotifyCatalogResponse>

    @GET("api/v1/spotify/artists/{id}/albums")
    suspend fun getArtistDiscography(
        @Header("Authorization") auth: String,
        @Path("id") artistId: String
    ): Response<SpotifyArtistAlbumsResponse>

    @POST("api/v1/spotify/albums/save")
    suspend fun saveSpotifyAlbums(
        @Header("Authorization") auth: String,
        @Body body: SpotifyAlbumSelection
    ): Response<SpotifySavedResponse>

    @HTTP(method = "DELETE", path = "api/v1/spotify/albums", hasBody = true)
    suspend fun removeSpotifyAlbums(
        @Header("Authorization") auth: String,
        @Body body: SpotifyAlbumSelection
    ): Response<SpotifyRemovedResponse>

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

    // --- Concerts (server-canonical) ---

    @GET("api/v1/concerts")
    suspend fun getConcerts(@Header("Authorization") auth: String): Response<ConcertsEnvelope>

    @POST("api/v1/concerts")
    suspend fun upsertConcerts(
        @Header("Authorization") auth: String,
        @Body body: ConcertsUpsertBody
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/concerts/{id}", hasBody = false)
    suspend fun deleteConcert(
        @Header("Authorization") auth: String,
        @Path("id") concertId: String
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/concerts", hasBody = false)
    suspend fun clearConcerts(@Header("Authorization") auth: String): Response<Unit>

    @POST("api/v1/concerts/import")
    suspend fun importConcerts(
        @Header("Authorization") auth: String,
        @Body body: ConcertsImportBody
    ): Response<ConcertsImportResponse>

    // --- Concert scans (click-and-forget AI scan, pending review) ---

    @POST("api/v1/concert-scans")
    suspend fun startConcertScan(
        @Header("Authorization") auth: String,
        @Body body: ConcertScanStartBody
    ): Response<ConcertScanDto>

    @Multipart
    @POST("api/v1/concert-scans/upload")
    suspend fun uploadConcertScan(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        // The page a WebView snapshot came from. Empty for a plain file upload;
        // the server keeps it for the scan title and never re-fetches it.
        @Part("source_url") sourceUrl: RequestBody
    ): Response<ConcertScanDto>

    @GET("api/v1/concert-scans/latest")
    suspend fun latestConcertScan(
        @Header("Authorization") auth: String
    ): Response<ConcertScanLatestEnvelope>

    @GET("api/v1/concert-scans/{id}")
    suspend fun getConcertScan(
        @Header("Authorization") auth: String,
        @Path("id") scanId: String
    ): Response<ConcertScanDto>

    @HTTP(method = "DELETE", path = "api/v1/concert-scans/{id}", hasBody = false)
    suspend fun deleteConcertScan(
        @Header("Authorization") auth: String,
        @Path("id") scanId: String
    ): Response<Unit>

    // --- Hidden duplicate blocklist (server-canonical) ---

    @GET("api/v1/albums/hidden")
    suspend fun getHiddenAlbums(@Header("Authorization") auth: String): Response<HiddenAlbumsEnvelope>

    @POST("api/v1/albums/hidden")
    suspend fun hideAlbums(
        @Header("Authorization") auth: String,
        @Body body: HiddenAlbumsUpsertBody
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/albums/hidden/{id}", hasBody = false)
    suspend fun restoreHiddenAlbum(
        @Header("Authorization") auth: String,
        @Path("id") albumId: String
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/albums/hidden", hasBody = false)
    suspend fun restoreAllHiddenAlbums(@Header("Authorization") auth: String): Response<Unit>

    @POST("api/v1/albums/hidden/import")
    suspend fun importHiddenAlbums(
        @Header("Authorization") auth: String,
        @Body body: HiddenAlbumsImportBody
    ): Response<HiddenAlbumsImportResponse>

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

    // --- Phase 0: AI URL scan helpers ---
    @POST("api/v1/llm/extract-albums")
    suspend fun extractAlbums(
        @Header("Authorization") auth: String,
        @Body body: ExtractBody
    ): Response<ExtractAlbumsResponse>

    @POST("api/v1/llm/extract-concerts")
    suspend fun extractConcerts(
        @Header("Authorization") auth: String,
        @Body body: ExtractBody
    ): Response<ExtractConcertsResponse>

    @GET("api/v1/spotify/followed-artists")
    suspend fun getFollowedArtists(
        @Header("Authorization") auth: String
    ): Response<FollowedArtistsResponse>

    @POST("api/v1/spotify/follow-artists")
    suspend fun followLibraryArtists(
        @Header("Authorization") auth: String
    ): Response<FollowArtistsResult>

    @POST("api/v1/spotify/resolve-albums")
    suspend fun resolveAlbums(
        @Header("Authorization") auth: String,
        @Body body: ResolveAlbumsBody
    ): Response<ResolveAlbumsResponse>

    @POST("api/v1/spotify/resolve-artists")
    suspend fun resolveArtists(
        @Header("Authorization") auth: String,
        @Body body: ResolveArtistsBody
    ): Response<ResolveArtistsResponse>

    // --- Local LLM (Open WebUI proxy) ---

    @GET("api/v1/llm/status")
    suspend fun llmStatus(@Header("Authorization") auth: String): Response<LLMStatusEnvelope>

    @POST("api/v1/llm/chat")
    suspend fun llmChat(
        @Header("Authorization") auth: String,
        @Body body: LLMChatRequest
    ): Response<LLMChatEnvelope>

    @GET("api/v1/llm/chats")
    suspend fun llmChats(
        @Header("Authorization") auth: String,
        @Query("chat_type") chatType: String? = null
    ): Response<LLMChatListEnvelope>

    @GET("api/v1/llm/chats/current")
    suspend fun llmCurrentChat(@Header("Authorization") auth: String): Response<LLMChatRowEnvelope>

    @GET("api/v1/llm/chats/{id}")
    suspend fun llmChatById(
        @Header("Authorization") auth: String,
        @Path("id") chatId: String
    ): Response<LLMChatRowEnvelope>

    @DELETE("api/v1/llm/chats/{id}")
    suspend fun llmDeleteChat(
        @Header("Authorization") auth: String,
        @Path("id") chatId: String
    ): Response<LLMDeleteChatEnvelope>

    @DELETE("api/v1/llm/chats/current")
    suspend fun llmClearCurrentChat(
        @Header("Authorization") auth: String
    ): Response<LLMClearChatEnvelope>
}
