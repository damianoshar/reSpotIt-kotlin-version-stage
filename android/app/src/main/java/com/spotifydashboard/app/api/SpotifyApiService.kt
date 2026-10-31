package com.spotifydashboard.app.api

import retrofit2.http.*
import retrofit2.Response
import com.google.gson.annotations.SerializedName

// --- Models ---

data class SpotifyAlbumResponse(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtistResponse>,
    val images: List<SpotifyImageResponse>,
    val release_date: String,
    val album_type: String = "album",
    val genres: List<String> = emptyList()
)

data class SpotifyArtistResponse(
    val id: String,
    val name: String,
    val genres: List<String> = emptyList()
)

data class SpotifyImageResponse(
    val url: String,
    val width: Int,
    val height: Int
)

data class SavedAlbumItem(
    val added_at: String,
    val album: SpotifyAlbumResponse
)

data class SavedAlbumsPage(
    val items: List<SavedAlbumItem>,
    val next: String?,
    val total: Int
)

/** Response of `GET /albums?ids=...` (max 20 ids per call). */
data class SpotifyAlbumsResponse(
    val albums: List<SpotifyAlbumResponse?> = emptyList(),
)

data class ArtistPage(
    val artists: List<SpotifyArtistResponse>
)

// --- Album Tracks ---

data class AlbumTracksPage(
    val items: List<AlbumTrackItem>,
    val next: String?,
    val total: Int
)

data class AlbumTrackItem(
    val id: String? = null,
    val uri: String = "",
    val name: String = "",
    val artists: List<SpotifyArtistResponse> = emptyList(),
    val disc_number: Int? = null,
    val track_number: Int? = null,
    val duration_ms: Long? = null,
    val explicit: Boolean = false,
    val preview_url: String? = null
)

// --- Playback (read-only) ---

data class SpotifyPlaybackAlbum(
    val name: String = "",
    val images: List<SpotifyImageResponse> = emptyList()
)

data class SpotifyPlaybackTrack(
    val id: String? = null,
    val name: String = "",
    val uri: String = "",
    val duration_ms: Int = 0,
    val artists: List<SpotifyArtistResponse> = emptyList(),
    val album: SpotifyPlaybackAlbum? = null,
    val explicit: Boolean = false
)

data class SpotifyPlayerDevice(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    val volume_percent: Int? = null
)

data class SpotifyPlaybackContext(
    val type: String = "",
    val uri: String = "",
    val href: String? = null
)

data class CurrentlyPlayingResponse(
    val is_playing: Boolean = false,
    val progress_ms: Int? = null,
    val item: SpotifyPlaybackTrack? = null,
    val device: SpotifyPlayerDevice? = null,
    val context: SpotifyPlaybackContext? = null,
    val timestamp: Long? = null,
    /** "off", "track" or "context". Repeat adds whole cycles to the queue. */
    val repeat_state: String = "off"
)

data class QueueResponse(
    val currently_playing: SpotifyPlaybackTrack? = null,
    val queue: List<SpotifyPlaybackTrack> = emptyList()
)

/** One Spotify Connect device (phone, TV, speaker...). */
data class SpotifyDevice(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    val is_active: Boolean = false,
    val is_restricted: Boolean = false,
    val volume_percent: Int? = null,
)

data class DevicesResponse(val devices: List<SpotifyDevice> = emptyList())

/**
 * Body of `PUT /me/player/play`. Only one of [context_uri] / [uris] is used;
 * [offset] is the index inside the context where playback continues.
 */
data class PlayRequest(
    val context_uri: String? = null,
    val uris: List<String>? = null,
    val offset: PlayOffset? = null,
)

/**
 * Where inside the context playback should start. Spotify expects an object
 * here, not a bare number: `{"position": 5}` or `{"uri": "spotify:track:..."}`.
 */
data class PlayOffset(
    val position: Int? = null,
    val uri: String? = null,
)

/**
 * Body for `PUT /me/albums`. Spotify still accepts the deprecated `ids`
 * query parameter, but that caps at 20 IDs and can exceed proxy URL limits;
 * the JSON body accepts 50 and avoids 414 responses for larger discographies.
 */
data class SaveAlbumsRequest(val ids: List<String>)

// --- Service ---

interface SpotifyApiService {
    @GET("me/albums")
    suspend fun getSavedAlbums(
        @Header("Authorization") authHeader: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SavedAlbumsPage>

    @DELETE("me/albums")
    suspend fun removeSavedAlbums(
        @Header("Authorization") authHeader: String,
        @Query("ids") ids: String
    ): Response<Unit>

    /** Save albums to the user's Spotify library (max 50 ids per body). */
    @PUT("me/albums")
    suspend fun saveAlbums(
        @Header("Authorization") authHeader: String,
        @Body body: SaveAlbumsRequest,
    ): Response<Unit>

    /** Batch album lookup for importing saved catalogue releases locally. */
    @GET("albums")
    suspend fun getAlbums(
        @Header("Authorization") authHeader: String,
        @Query("ids") ids: String,
        @Query("market") market: String = "from_token",
    ): Response<SpotifyAlbumsResponse>

    @GET("artists")
    suspend fun getArtists(
        @Header("Authorization") authHeader: String,
        @Query("ids") ids: String
    ): Response<ArtistPage>

    @GET("albums/{album_id}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") authHeader: String,
        @Path("album_id") albumId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<AlbumTracksPage>

    @GET("albums/{album_id}")
    suspend fun getAlbumDetails(
        @Header("Authorization") authHeader: String,
        @Path("album_id") albumId: String
    ): Response<SpotifyAlbumResponse>

    @POST("me/player/queue")
    suspend fun addToQueue(
        @Header("Authorization") authHeader: String,
        @Query("uri") uri: String
    ): Response<Unit>

    @GET("me/player")
    suspend fun getCurrentlyPlaying(
        @Header("Authorization") authHeader: String
    ): Response<CurrentlyPlayingResponse>

    @GET("me/player/queue")
    suspend fun getQueue(
        @Header("Authorization") authHeader: String
    ): Response<QueueResponse>

    /** Devices that can play right now (needs `user-read-playback-state`). */
    @GET("me/player/devices")
    suspend fun getDevices(
        @Header("Authorization") authHeader: String
    ): Response<DevicesResponse>

    /**
     * Start (or resume) playback of a context — used to hand the queue over
     * to a playlist. `offset` is the index inside the context to continue at.
     */
    @PUT("me/player/play")
    suspend fun play(
        @Header("Authorization") authHeader: String,
        @Query("device_id") deviceId: String? = null,
        @Body body: PlayRequest
    ): Response<Unit>

    // --- Follow library artists (Spotify Web API) ---
    @GET("me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") authHeader: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SavedTracksPage>

    @GET("me/following/contains")
    suspend fun checkFollowingArtists(
        @Header("Authorization") authHeader: String,
        @Query("type") type: String = "artist",
        @Query("ids") ids: String
    ): Response<List<Boolean>>

    @PUT("me/following")
    suspend fun followArtists(
        @Header("Authorization") authHeader: String,
        @Query("type") type: String = "artist",
        @Query("ids") ids: String,
        @Body body: Map<String, String> = emptyMap()
    ): Response<Unit>

    // --- Spotify catalogue search / artist discography ---------------------

    @GET("search")
    suspend fun searchAlbums(
        @Header("Authorization") authHeader: String,
        @Query("q") query: String,
        @Query("type") type: String = "album",
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String = "from_token",
    ): Response<SpotifyAlbumSearchResponse>

    @GET("search")
    suspend fun searchArtists(
        @Header("Authorization") authHeader: String,
        @Query("q") query: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String = "from_token",
    ): Response<SpotifyArtistSearchResponse>

    @GET("artists/{artist_id}/albums")
    suspend fun getArtistAlbums(
        @Header("Authorization") authHeader: String,
        @Path("artist_id") artistId: String,
        @Query("include_groups") includeGroups: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String = "from_token",
    ): Response<SpotifyArtistAlbumsPage>
}

// --- Spotify catalogue search / artist discography models ------------------

data class SpotifyAlbumSearchResponse(
    val albums: SpotifyAlbumSearchPage? = null,
)

data class SpotifyAlbumSearchPage(
    val items: List<SpotifyCatalogAlbumResponse>? = emptyList(),
    val total: Int = 0,
)

data class SpotifyArtistSearchResponse(
    val artists: SpotifyArtistSearchPage? = null,
)

data class SpotifyArtistSearchPage(
    val items: List<SpotifyArtistSearchItem>? = emptyList(),
    val total: Int = 0,
)

data class SpotifyArtistSearchItem(
    val id: String,
    val name: String,
    val images: List<SpotifyImageResponse>? = emptyList(),
    val genres: List<String>? = emptyList(),
    val followers: SpotifyFollowers? = null,
)

data class SpotifyFollowers(val total: Long = 0)

data class SpotifyArtistAlbumsPage(
    val items: List<SpotifyCatalogAlbumResponse>? = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

/**
 * Shared album shape for both `search?type=album` and
 * `artists/{id}/albums`. `album_group` is only returned by the latter.
 */
data class SpotifyCatalogAlbumResponse(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtistResponse>? = emptyList(),
    val images: List<SpotifyImageResponse>? = emptyList(),
    val release_date: String = "",
    val album_type: String = "album",
    val total_tracks: Int = 0,
    val album_group: String? = null,
)


// --- Follow library artists models ---
data class SpotifySavedTrack(
    val id: String? = null,
    val artists: List<SpotifyArtistResponse> = emptyList()
)

data class SavedTrackItem(
    val added_at: String = "",
    val track: SpotifySavedTrack? = null
)

data class SavedTracksPage(
    val items: List<SavedTrackItem> = emptyList(),
    val next: String? = null,
    val total: Int = 0
)
