package com.spotifydashboard.app.api

import retrofit2.Response
import retrofit2.http.*

interface SpotifyPlaylistApiService {
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") authHeader: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PlaylistPage

    @GET("me")
    suspend fun getCurrentUser(
        @Header("Authorization") authHeader: String
    ): SpotifyUser

    @POST("users/{user_id}/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") authHeader: String,
        @Path("user_id") userId: String,
        @Body body: CreatePlaylistRequest
    ): SpotifyPlaylist

    @GET("playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String? = null
    ): PlaylistTrackPage

    @GET("playlists/{playlist_id}")
    suspend fun getPlaylistDetails(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String,
        @Query("fields") fields: String = "id,name,owner.id,collaborative"
    ): SpotifyPlaylistDetails

    /**
     * Spotify's current add-items endpoint. The old /tracks path is deprecated;
     * the body is still {uris:[...]} and the limit is still 100 per request.
     */
    @POST("playlists/{playlist_id}/items")
    suspend fun addTracksToPlaylist(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String,
        @Body body: AddTracksRequest
    ): AddTracksResponse

    @HTTP(method = "DELETE", path = "playlists/{playlist_id}/items", hasBody = true)
    suspend fun removeTracksFromPlaylist(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String,
        @Body body: RemoveTracksRequest
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "playlists/{playlist_id}/followers", hasBody = false)
    suspend fun unfollowPlaylist(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String
    ): Response<Unit>

    @PUT("playlists/{playlist_id}")
    suspend fun changePlaylistDetails(
        @Header("Authorization") authHeader: String,
        @Path("playlist_id") playlistId: String,
        @Body body: ChangePlaylistDetailsRequest,
    ): Response<Unit>
}

data class PlaylistPage(val items: List<SpotifyPlaylist>, val next: String?, val total: Int)

data class ChangePlaylistDetailsRequest(val public: Boolean?)
/**
 * `addedOrder` captures Spotify's /me/playlists return order (0-based, lower
 * = more recently added) so the list page can sort newest/oldest. Spotify does
 * not send any date fields on a playlist object; this is the only chronological
 * signal actually available in local mode. Remote rows may not carry it, in
 * which case it is null and the chip falls back to name order.
 */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val images: List<SpotifyImageResponse>,
    val tracks: TrackInfo,
    val owner: OwnerInfo,
    val addedOrder: Int? = null,
    val public: Boolean? = null
)
data class SpotifyUser(val id: String)
data class TrackInfo(val total: Int)
data class OwnerInfo(val id: String = "")
data class SpotifyPlaylistDetails(
    val id: String = "",
    val name: String = "",
    val owner: OwnerInfo = OwnerInfo(),
    val collaborative: Boolean = false
)
data class CreatePlaylistRequest(val name: String, val public: Boolean = false)
data class PlaylistTrackPage(val items: List<PlaylistTrackItem>, val next: String?, val total: Int)
// Track wrapper — track is null for local/unavailable items (mirrors TS RawTrackItem)
data class PlaylistTrackItem(val track: FullTrackInfo?)
data class FullTrackInfo(
    val id: String? = null,
    val uri: String = "",
    val name: String = "",
    val duration_ms: Int = 0,
    val artists: List<PlaylistAlbumArtist> = emptyList(),
    val album: FullAlbumInfo? = null
)
// Full album payload needed for dedup + enrichment (mirrors TS RawAlbum)
data class FullAlbumInfo(
    val id: String,
    val name: String,
    val artists: List<PlaylistAlbumArtist> = emptyList(),
    val images: List<SpotifyImageResponse> = emptyList(),
    val release_date: String? = null
)
data class PlaylistAlbumArtist(val id: String, val name: String)
/**
 * [position] is zero-based; null appends. Gson omits null fields, so the
 * existing append calls keep sending the original {uris:[...]} body.
 */
data class AddTracksRequest(
    val uris: List<String>,
    val position: Int? = null,
)
data class AddTracksResponse(val snapshot_id: String)
data class TrackUri(val uri: String)
data class RemoveTracksRequest(val items: List<TrackUri>)
