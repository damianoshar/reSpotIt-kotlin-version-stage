package com.spotifydashboard.app.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.spotifydashboard.app.repository.SpotifyRepository
import com.spotifydashboard.app.repository.PlaylistRepository
import com.spotifydashboard.app.repository.DashboardRepository
import com.spotifydashboard.app.repository.BackupSettings
import com.spotifydashboard.app.repository.SyncCancelSignal
import com.spotifydashboard.app.repository.SyncState
import com.spotifydashboard.app.repository.PlaylistSyncState
import com.spotifydashboard.app.repository.PlaylistAccess
import com.spotifydashboard.app.repository.SpotifyCatalogAlbum
import com.spotifydashboard.app.repository.SpotifyCatalogArtist
import com.spotifydashboard.app.repository.pickPlaybackDevice
import com.spotifydashboard.app.playback.AudioFocusMode
import com.spotifydashboard.app.playback.LocalPlaybackController
import com.spotifydashboard.app.playback.LocalPlaybackState
import com.spotifydashboard.app.playback.LocalPlaybackTrack
import com.spotifydashboard.app.data.AlbumEntity
import com.spotifydashboard.app.data.FilterState
import com.spotifydashboard.app.data.LibraryStats
import com.spotifydashboard.app.data.PlaylistAlbumEntity
import com.spotifydashboard.app.data.ViewMode
import com.spotifydashboard.app.api.RemoteAlbumTrack
import com.spotifydashboard.app.utils.ActiveQueuePlanner
import com.spotifydashboard.app.utils.QueueSnapshot
import com.spotifydashboard.app.utils.PendingQueueSwitch
import com.spotifydashboard.app.utils.QueueMode
import com.spotifydashboard.app.utils.QueuePlaylistRef
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.api.JobDto
import com.spotifydashboard.app.utils.CsvImportResult
import com.spotifydashboard.app.utils.CSVHelper
import com.spotifydashboard.app.utils.SyncHistory
import com.spotifydashboard.app.utils.DuplicateFinder
import com.spotifydashboard.app.utils.DuplicateGroup
import android.util.Log

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val spotifyRepository: SpotifyRepository,
    private val playlistRepository: PlaylistRepository,
    private val dashboardRepository: DashboardRepository,
    private val localPlayback: LocalPlaybackController,
) : ViewModel() {

    private companion object {
        /** Below this the track is about to end anyway - switch right away. */
        const val SWITCH_NOW_THRESHOLD_MS = 2_000L

        /** Hand over a moment before the track ends so the next one starts clean. */
        const val SWITCH_LEAD_MS = 600L
    }

    private val _librarySyncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val librarySyncState: StateFlow<SyncState> = _librarySyncState.asStateFlow()

    // Server-side sync history for the sync sheet: null = not loaded yet (a
    // freshly opened sheet shows Loading), [] = loaded and empty. Remote mode
    // only — the local path syncs on-device and has no server runs to list.
    private val _syncHistory = MutableStateFlow<List<JobDto>?>(null)
    val syncHistory: StateFlow<List<JobDto>?> = _syncHistory.asStateFlow()

    private val _libraryFilters = MutableStateFlow(FilterState())
    val libraryFilters: StateFlow<FilterState> = _libraryFilters.asStateFlow()

    // Accumulated album list (mirrors TS useLibrary albums + offsetRef pattern).
    // Previously this was a Flow-of-Flow that never appended on load-more,
    // so the Load More button fetched pages that were discarded.
    private val _libraryAlbumsList = MutableStateFlow<List<AlbumEntity>>(emptyList())
    val libraryAlbums: StateFlow<List<AlbumEntity>> = _libraryAlbumsList.asStateFlow()

    private val _libraryTotal = MutableStateFlow(0)
    val libraryTotal: StateFlow<Int> = _libraryTotal.asStateFlow()

    private val _libraryHasMore = MutableStateFlow(false)
    val libraryHasMore: StateFlow<Boolean> = _libraryHasMore.asStateFlow()

    private val _libraryLoading = MutableStateFlow(true)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()

    private val _libraryLoadingMore = MutableStateFlow(false)
    val libraryLoadingMore: StateFlow<Boolean> = _libraryLoadingMore.asStateFlow()

    private val _libraryGenres = MutableStateFlow<List<String>>(emptyList())
    val libraryGenres: StateFlow<List<String>> = _libraryGenres.asStateFlow()

    private val _libraryArtists = MutableStateFlow<List<String>>(emptyList())
    val libraryArtists: StateFlow<List<String>> = _libraryArtists.asStateFlow()

    private val _libraryYears = MutableStateFlow<List<Int>>(emptyList())
    val libraryYears: StateFlow<List<Int>> = _libraryYears.asStateFlow()

    private val _libraryStats = MutableStateFlow<LibraryStats?>(null)
    val libraryStats: StateFlow<LibraryStats?> = _libraryStats.asStateFlow()

    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<SpotifyPlaylist?>(null)
    val selectedPlaylist: StateFlow<SpotifyPlaylist?> = _selectedPlaylist.asStateFlow()

    private val _playlistSyncState = MutableStateFlow<PlaylistSyncState>(PlaylistSyncState.Idle)
    val playlistSyncState: StateFlow<PlaylistSyncState> = _playlistSyncState.asStateFlow()

    // --- Playlist album state (accumulated list, mirrors the library pattern) ---
    private val _playlistFilters = MutableStateFlow(FilterState())
    val playlistFilters: StateFlow<FilterState> = _playlistFilters.asStateFlow()

    private val _playlistAlbumsList = MutableStateFlow<List<PlaylistAlbumEntity>>(emptyList())
    val playlistAlbums: StateFlow<List<PlaylistAlbumEntity>> = _playlistAlbumsList.asStateFlow()

    private val _playlistTotal = MutableStateFlow(0)
    val playlistTotal: StateFlow<Int> = _playlistTotal.asStateFlow()

    private val _playlistHasMore = MutableStateFlow(false)
    val playlistHasMore: StateFlow<Boolean> = _playlistHasMore.asStateFlow()

    private val _playlistLoadingMore = MutableStateFlow(false)
    val playlistLoadingMore: StateFlow<Boolean> = _playlistLoadingMore.asStateFlow()

    private val _playlistGenres = MutableStateFlow<List<String>>(emptyList())
    val playlistGenres: StateFlow<List<String>> = _playlistGenres.asStateFlow()

    private val _playlistArtists = MutableStateFlow<List<String>>(emptyList())
    val playlistArtists: StateFlow<List<String>> = _playlistArtists.asStateFlow()

    private val _playlistYears = MutableStateFlow<List<Int>>(emptyList())
    val playlistYears: StateFlow<List<Int>> = _playlistYears.asStateFlow()

    private var playlistOffset = 0

    private val _playlistLoading = MutableStateFlow(false)
    val playlistLoading: StateFlow<Boolean> = _playlistLoading.asStateFlow()

    private val _selectedAlbums = MutableStateFlow<Set<String>>(emptySet())
    val selectedAlbums: StateFlow<Set<String>> = _selectedAlbums.asStateFlow()

    /** True while the library multi-delete is removing Spotify + local rows. */
    private val _libraryDeleteBusy = MutableStateFlow(false)
    val libraryDeleteBusy: StateFlow<Boolean> = _libraryDeleteBusy.asStateFlow()

    // --- Playlist picker state ---
    private val _showPlaylistPicker = MutableStateFlow(false)
    val showPlaylistPicker: StateFlow<Boolean> = _showPlaylistPicker.asStateFlow()

    // --- Create playlist dialog state ---
    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _isCreatingPlaylist = MutableStateFlow(false)
    val isCreatingPlaylist: StateFlow<Boolean> = _isCreatingPlaylist.asStateFlow()

    // --- CSV import result ---
    private val _csvImportResult = MutableStateFlow<CsvImportResult?>(null)
    val csvImportResult: StateFlow<CsvImportResult?> = _csvImportResult.asStateFlow()

    // --- Duplicate cleanup (card 269, local mode) ---
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicateGroups: StateFlow<List<DuplicateGroup>?> = _duplicateGroups.asStateFlow()

    private val _hiddenAlbums = MutableStateFlow<List<AlbumEntity>>(emptyList())
    val hiddenAlbums: StateFlow<List<AlbumEntity>> = _hiddenAlbums.asStateFlow()

    private val _duplicatesLoading = MutableStateFlow(false)
    val duplicatesLoading: StateFlow<Boolean> = _duplicatesLoading.asStateFlow()

    // --- Follow library artists (card 344) -------------------------------
    private val _followArtistsBusy = MutableStateFlow(false)
    val followArtistsBusy: StateFlow<Boolean> = _followArtistsBusy.asStateFlow()

    // --- Add from site (card 270) ---------------------------------------
    private val _addingFromSite = MutableStateFlow(false)
    val addingFromSite: StateFlow<Boolean> = _addingFromSite.asStateFlow()

    /** Human-readable "Artist \u2014 Title" labels fetched from Spotify for scanned IDs. */
    private val _siteAlbumLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val siteAlbumLabels: StateFlow<Map<String, String>> = _siteAlbumLabels.asStateFlow()

    // --- Spotify search / artist discography (Tools) ---------------------
    private val _spotifySearchPlaylist = MutableStateFlow(SpotifySearchPlaylistState())
    val spotifySearchPlaylist: StateFlow<SpotifySearchPlaylistState> = _spotifySearchPlaylist.asStateFlow()

    private val _artistDiscography = MutableStateFlow(ArtistDiscographyState())
    val artistDiscography: StateFlow<ArtistDiscographyState> = _artistDiscography.asStateFlow()

    private val _catalogPlaylistBusy = MutableStateFlow(false)
    val catalogPlaylistBusy: StateFlow<Boolean> = _catalogPlaylistBusy.asStateFlow()

    private val _catalogPlaylistProgress = MutableStateFlow<CatalogPlaylistProgress?>(null)
    val catalogPlaylistProgress: StateFlow<CatalogPlaylistProgress?> = _catalogPlaylistProgress.asStateFlow()

    private val _catalogPlaylistOutcome = MutableStateFlow<CatalogPlaylistOutcome?>(null)
    val catalogPlaylistOutcome: StateFlow<CatalogPlaylistOutcome?> = _catalogPlaylistOutcome.asStateFlow()

    private val _catalogLibraryBusy = MutableStateFlow(false)
    val catalogLibraryBusy: StateFlow<Boolean> = _catalogLibraryBusy.asStateFlow()

    private val _catalogLibraryProgress = MutableStateFlow<CatalogLibraryProgress?>(null)
    val catalogLibraryProgress: StateFlow<CatalogLibraryProgress?> = _catalogLibraryProgress.asStateFlow()

    private val _catalogLibraryOutcome = MutableStateFlow<CatalogLibraryOutcome?>(null)
    val catalogLibraryOutcome: StateFlow<CatalogLibraryOutcome?> = _catalogLibraryOutcome.asStateFlow()

    private var discographyAlbumsJob: Job? = null
    private var spotifyAlbumSearchJob: Job? = null
    private var discographyArtistSearchJob: Job? = null
    private var catalogLibraryJob: Job? = null

    /** Best-effort prefetch of Spotify-side metadata so the checklist can show artist/title. */
    fun prefetchSiteAlbums(ids: List<String>) {
        val missing = ids.distinct().filter { it.isNotBlank() && !_siteAlbumLabels.value.containsKey(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            missing.forEach { id ->
                try {
                    val e = spotifyRepository.fetchAlbumEntity(id)
                    if (e != null) { _siteAlbumLabels.value = _siteAlbumLabels.value + (id to "${e.artist} \u2014 ${e.title}") }
                } catch (_: Exception) { }
                delay(90)
            }
        }
    }

    // --- Backup settings (mirrors TS useBackupSettings) ---
    private val _backupSettings = MutableStateFlow(BackupSettings())
    val backupSettings: StateFlow<BackupSettings> = _backupSettings.asStateFlow()

    // --- Display settings (persisted grid size) ---
    private val _gridSize = MutableStateFlow(160)
    val gridSize: StateFlow<Int> = _gridSize.asStateFlow()

    // --- Autohiding bottom navigation (persisted) ---
    private val _autohideNav = MutableStateFlow(dashboardRepository.getAutohideNav())
    val autohideNav: StateFlow<Boolean> = _autohideNav.asStateFlow()

    fun setAutohideNav(enabled: Boolean) {
        _autohideNav.value = enabled
        dashboardRepository.setAutohideNav(enabled)
    }

    // --- Artwork sync switch (card 276, low-end devices) ---
    private val _syncPhotos = MutableStateFlow(dashboardRepository.getSyncPhotos())
    val syncPhotos: StateFlow<Boolean> = _syncPhotos.asStateFlow()

    fun setSyncPhotos(enabled: Boolean) {
        _syncPhotos.value = enabled
        dashboardRepository.setSyncPhotos(enabled)
    }

    // --- Board options (view modes, board-bar autohide, playlist list search) ---

    private val _libraryViewMode = MutableStateFlow(ViewMode.Grid)
    val libraryViewMode: StateFlow<ViewMode> = _libraryViewMode.asStateFlow()

    private val _playlistViewMode = MutableStateFlow(ViewMode.Grid)
    val playlistViewMode: StateFlow<ViewMode> = _playlistViewMode.asStateFlow()

    /** Hide the search/sort bar while scrolling down (independent of the nav). */
    private val _autohideBoardBar = MutableStateFlow(dashboardRepository.getAutohideBoardBar())
    val autohideBoardBar: StateFlow<Boolean> = _autohideBoardBar.asStateFlow()

    fun setLibraryViewMode(mode: ViewMode) {
        _libraryViewMode.value = mode
        viewModelScope.launch { spotifyRepository.saveLibraryViewMode(mode) }
    }

    fun setPlaylistViewMode(mode: ViewMode) {
        _playlistViewMode.value = mode
        viewModelScope.launch { spotifyRepository.savePlaylistViewMode(mode) }
    }

    fun setAutohideBoardBar(enabled: Boolean) {
        _autohideBoardBar.value = enabled
        dashboardRepository.setAutohideBoardBar(enabled)
    }

    // --- Album track sheet (opens from Library and Playlist) ---
    data class AlbumSheetState(
        val albumId: String,
        val title: String,
        val artist: String,
        val imageUrl: String,
        val year: Int,
        /** null when opened from the Library; removal is playlist-only. */
        val playlistId: String? = null,
        val tracks: List<RemoteAlbumTrack> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _albumSheet = MutableStateFlow<AlbumSheetState?>(null)
    val albumSheet: StateFlow<AlbumSheetState?> = _albumSheet.asStateFlow()

    // --- Shortlived playlist state for the open playlist ---
    private val _playlistExpiresAt = MutableStateFlow<String?>(null)
    val playlistExpiresAt: StateFlow<String?> = _playlistExpiresAt.asStateFlow()

    private val _retentionBusy = MutableStateFlow(false)
    val retentionBusy: StateFlow<Boolean> = _retentionBusy.asStateFlow()

    // --- Read-only Spotify playback state (Now playing + queue) ---
    data class PlaybackUiState(
        val loading: Boolean = false,
        val error: String? = null,
        val isPlaying: Boolean = false,
        val progressMs: Int = 0,
        val progressAt: Long = 0L,
        val deviceName: String = "",
        val track: com.spotifydashboard.app.api.SpotifyPlaybackTrack? = null,
        val queue: List<com.spotifydashboard.app.api.SpotifyPlaybackTrack> = emptyList(),
        /** "playlist", "album", "artist", "show", ...; empty when unknown. */
        val contextType: String = "",
        val contextUri: String = "",
        /** Playlist name (or album name as a fallback) for the context line. */
        val contextName: String = "",
        val contextPlaylistId: String? = null,
        /** True only when this account owns the playlist or collaborates on it. */
        val contextEditable: Boolean = false,
        val lastUpdated: Long = 0L,
    )

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    // --- Experimental on-device playback (librespot) ---
    // Independent from the dashboard's Spotify login: the first play opens the
    // Spotify consent page and stores reusable playback credentials on-device.
    val localPlaybackState: StateFlow<LocalPlaybackState> = localPlayback.state

    private val _localPlaybackDeviceName = MutableStateFlow(localPlayback.deviceName())
    val localPlaybackDeviceName: StateFlow<String> = _localPlaybackDeviceName.asStateFlow()

    private val _localPlaybackAudioFocusMode = MutableStateFlow(localPlayback.audioFocusMode().value)
    val localPlaybackAudioFocusMode: StateFlow<String> = _localPlaybackAudioFocusMode.asStateFlow()

    private val _localPlaybackBufferSeconds = MutableStateFlow(localPlayback.bufferSeconds())
    val localPlaybackBufferSeconds: StateFlow<Int> = _localPlaybackBufferSeconds.asStateFlow()

    fun setLocalPlaybackEnabled(enabled: Boolean) = localPlayback.setEnabled(enabled)
fun setLocalPlaybackKeepConnected(enabled: Boolean) = localPlayback.setKeepConnected(enabled)

    /** Plays the tracks of the currently open album sheet (local playback). */
    fun playAlbumLocally(startIndex: Int = 0) {
        val sheet = _albumSheet.value ?: return
        // When an album is opened from a playlist, continue through the rest of
        // that playlist instead of stopping after the album's own tracks.
        if (sheet.playlistId != null) {
            playAlbumInPlaylistLocally()
            return
        }
        val tracks = sheet.tracks.mapNotNull { track ->
            val uri = track.uri.ifBlank {
                track.spotify_id.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" }.orEmpty()
            }
            if (uri.isBlank()) {
                null
            } else {
                LocalPlaybackTrack(
                    uri = uri,
                    title = track.name,
                    artist = track.artist_names,
                    durationMs = (track.duration_ms ?: 0L).toInt(),
                    artUrl = sheet.imageUrl,
                )
            }
        }
        localPlayback.play(tracks, startIndex)
    }

    /** Play a track from an album opened in a playlist, starting at its playlist position. */
    fun playTrackLocally(uri: String) {
        val sheet = _albumSheet.value ?: return
        if (sheet.playlistId != null) {
            playTrackInPlaylistLocally(uri)
            return
        }
        val index = sheet.tracks.indexOfFirst { it.uri == uri }
        if (index >= 0) playAlbumLocally(index)
    }

    private fun playAlbumInPlaylistLocally() {
        val sheet = _albumSheet.value ?: return
        val playlistId = sheet.playlistId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val full = playlistRepository.getPlaylistFullTracks(playlistId)
                val queue = full.mapNotNull { t ->
                    if (t.uri.isBlank()) null else LocalPlaybackTrack(
                        uri = t.uri,
                        title = t.name,
                        artist = t.artists.joinToString { it.name }.ifBlank {
                            t.album?.artists?.joinToString { it.name }.orEmpty()
                        },
                        durationMs = t.duration_ms,
                        artUrl = t.album?.images?.firstOrNull()?.url,
                    )
                }
                if (queue.isEmpty()) return@launch
                val startUri = sheet.tracks.firstOrNull { it.uri.isNotBlank() }?.uri
                val startIndex = if (startUri.isNullOrBlank()) 0
                else full.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)
                withContext(Dispatchers.Main) { localPlayback.play(queue, startIndex) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Playlist local playback failed", e)
                withContext(Dispatchers.Main) {
                    _actionMessage.value = "Could not play playlist locally: ${e.message}"
                }
            }
        }
    }

    private fun playTrackInPlaylistLocally(uri: String) {
        val sheet = _albumSheet.value ?: return
        val playlistId = sheet.playlistId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val full = playlistRepository.getPlaylistFullTracks(playlistId)
                val queue = full.mapNotNull { t ->
                    if (t.uri.isBlank()) null else LocalPlaybackTrack(
                        uri = t.uri,
                        title = t.name,
                        artist = t.artists.joinToString { it.name }.ifBlank {
                            t.album?.artists?.joinToString { it.name }.orEmpty()
                        },
                        durationMs = t.duration_ms,
                        artUrl = t.album?.images?.firstOrNull()?.url,
                    )
                }
                if (queue.isEmpty()) return@launch
                val startIndex = full.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
                withContext(Dispatchers.Main) { localPlayback.play(queue, startIndex) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Playlist local playback failed", e)
                withContext(Dispatchers.Main) {
                    _actionMessage.value = "Could not play playlist locally: ${e.message}"
                }
            }
        }
    }

    fun playLocalQueueIndex(index: Int) = localPlayback.playIndex(index)

    fun setLocalPlaybackDeviceName(name: String) {
        localPlayback.setDeviceName(name)
        _localPlaybackDeviceName.value = localPlayback.deviceName()
    }

    fun setLocalPlaybackAudioFocusMode(value: String) {
        val mode = AudioFocusMode.entries.firstOrNull { it.value == value } ?: AudioFocusMode.Pause
        localPlayback.setAudioFocusMode(mode)
        _localPlaybackAudioFocusMode.value = mode.value
    }

    fun setLocalPlaybackBufferSeconds(seconds: Int) {
        localPlayback.setBufferSeconds(seconds)
        _localPlaybackBufferSeconds.value = localPlayback.bufferSeconds()
    }

    /** Add one track to the end of the currently active local queue. */
    fun enqueueTrackLocally(uri: String, next: Boolean = false) {
        if (uri.isBlank()) return
        val sheet = _albumSheet.value
        val track = LocalPlaybackTrack(
            uri = uri,
            title = sheet?.tracks?.firstOrNull { it.uri == uri }?.name.orEmpty(),
            artist = sheet?.tracks?.firstOrNull { it.uri == uri }?.artist_names.orEmpty(),
            durationMs = (sheet?.tracks?.firstOrNull { it.uri == uri }?.duration_ms ?: 0L).toInt(),
            artUrl = sheet?.imageUrl,
        )
        if (next) localPlayback.insertNext(track) else localPlayback.enqueue(track)
    }

    /** Insert one album's playable tracks into the local queue after the current track. */
    fun enqueueAlbumLocallyNext() {
        val sheet = _albumSheet.value ?: return
        val tracks = sheet.tracks.mapNotNull { track ->
            val uri = track.uri.ifBlank {
                track.spotify_id.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" }.orEmpty()
            }
            if (uri.isBlank()) null else LocalPlaybackTrack(
                uri = uri,
                title = track.name,
                artist = track.artist_names,
                durationMs = (track.duration_ms ?: 0L).toInt(),
                artUrl = sheet.imageUrl,
            )
        }
        tracks.forEach { localPlayback.insertNext(it) }
    }

    /** Remove an item from the visible local Playback queue. */
    fun removeLocalQueuedTrack(index: Int) = localPlayback.removeAt(index)

    /** Reorder two items in the visible local Playback queue. */
    fun moveLocalQueuedTrack(fromIndex: Int, toIndex: Int) = localPlayback.move(fromIndex, toIndex)

    /** Empty the local Playback queue (also stops playback). */
    fun clearLocalQueuedTracks() = localPlayback.clearQueue()

    /** Copy the app's internal local queue to Spotify's active queue via the Web API. */
    fun sendLocalQueueToSpotify() {
        val uris = localPlaybackState.value.queue.map { it.uri }.filter { it.isNotBlank() }
        if (uris.isEmpty()) {
            _actionMessage.value = "No local queue to send."
            return
        }
        viewModelScope.launch {
            var queued = 0
            uris.forEach { uri ->
                val result = if (remoteActive()) dashboardRepository.queueTrackUris(listOf(uri))
                            else playlistRepository.queueTrackUris(listOf(uri))
                if (result > 0) queued++
            }
            _actionMessage.value = if (queued > 0) {
                "Sent $queued/${uris.size} track(s) to the active Spotify queue."
            } else {
                "Open Spotify on a device first, then try again."
            }
        }
    }

    fun toggleLocalPlayback() = localPlayback.toggle()
    fun pauseLocalPlayback() = localPlayback.pause()
    fun seekLocalPlayback(positionMs: Int) = localPlayback.seekTo(positionMs)
    fun stopLocalPlayback() = localPlayback.stop()
    fun nextLocalTrack() = localPlayback.next()
    fun previousLocalTrack() = localPlayback.previous()
    fun forgetLocalAccount() = localPlayback.forgetAccount()

    /** Shared by save-queue and append-queue so the Player tab can lock both. */
    private val _queuePlaylistBusy = MutableStateFlow(false)
    val queuePlaylistBusy: StateFlow<Boolean> = _queuePlaylistBusy.asStateFlow()

    /** Busy flag for the "queue to end" flow (creates, plays and replaces a playlist). */
    private val _queueToEndBusy = MutableStateFlow(false)
    val queueToEndBusy: StateFlow<Boolean> = _queueToEndBusy.asStateFlow()

    /**
     * A queue playlist that waits for the current track to end before
     * playback moves over to it.
     */
    private val _pendingQueueSwitch = MutableStateFlow<PendingQueueSwitch?>(null)
    val pendingQueueSwitch: StateFlow<PendingQueueSwitch?> = _pendingQueueSwitch.asStateFlow()

    data class PendingActiveQueueCreation(
        val albumIds: Set<String> = emptySet(),
        val reset: Boolean = false,
        /** True when the dialog belongs to the local-playlist queue mode. */
        val local: Boolean = false,
        /** Merge mode: build from what is playing now, not the old tail. */
        val newFromCurrent: Boolean = false,
    )

    // One app-generated queue playlist stays the active queue while the user
    // appends albums. Reset replaces it with a fresh playlist.
    private val restoredQueuePlaylist = playlistRepository.lastQueuePlaylist()
    private val _activeQueuePlaylist = MutableStateFlow<QueuePlaylistRef?>(restoredQueuePlaylist)
    val activeQueuePlaylist: StateFlow<QueuePlaylistRef?> = _activeQueuePlaylist.asStateFlow()

    private val _activeQueueBusy = MutableStateFlow(false)
    val activeQueueBusy: StateFlow<Boolean> = _activeQueueBusy.asStateFlow()

    private val _activeQueueStatus = MutableStateFlow(
        restoredQueuePlaylist?.let {
            "Queue playlist: \"${it.name}\" (${it.uris.size} tracks)"
        } ?: "No queue playlist yet"
    )
    val activeQueueStatus: StateFlow<String> = _activeQueueStatus.asStateFlow()

    private val _pendingActiveQueueCreation = MutableStateFlow<PendingActiveQueueCreation?>(null)
    val pendingActiveQueueCreation: StateFlow<PendingActiveQueueCreation?> =
        _pendingActiveQueueCreation.asStateFlow()
    private var queueSwitchJob: Job? = null

    // Local-playlist queue mode: the playlist itself is the queue. It has its
    // own active pointer so the merge mode can be tested alongside it.
    private val restoredLocalQueue = playlistRepository.localQueuePlaylist()
    private val restoredLocalCursor = playlistRepository.localQueueCursor()
    private val _localQueuePlaylist = MutableStateFlow<QueuePlaylistRef?>(restoredLocalQueue)
    val localQueuePlaylist: StateFlow<QueuePlaylistRef?> = _localQueuePlaylist.asStateFlow()
    private val _localQueueBusy = MutableStateFlow(false)
    val localQueueBusy: StateFlow<Boolean> = _localQueueBusy.asStateFlow()
    private val _localQueueStatus = MutableStateFlow(
        restoredLocalQueue?.let {
            "Local queue: \"${it.name}\" (${it.uris.size} tracks)"
        } ?: "No local queue playlist yet"
    )
    val localQueueStatus: StateFlow<String> = _localQueueStatus.asStateFlow()
    private val _queueMode = MutableStateFlow(playlistRepository.queueMode())
    val queueMode: StateFlow<QueueMode> = _queueMode.asStateFlow()

    private var localCursorPlaylistId: String? = restoredLocalCursor?.playlistId
    private var localCursorIndex: Int = restoredLocalCursor?.index ?: -1
    private var localCursorUri: String? = restoredLocalCursor?.uri

    // Which occurrence of the current URI is playing in the active queue. This
    // matters when a playlist deliberately contains the same track twice.
    private var activeQueueCursorPlaylistId: String? = null
    private var activeQueueCursorIndex: Int = -1
    private var activeQueueCursorUri: String? = null

    // Last context seen while something was loaded. Lets reset keep the old
    // queue playlist's tail when playback has stopped (no current item).
    private var lastPlaybackContextUri: String? = null

    private var playbackJob: Job? = null
    private var playbackPlaylistContextId: String? = null
    private var playbackPlaylistAccess: PlaylistAccess? = null
    private var playbackPlaylistAccessResolved = false

    // --- Media-server data source (mirrors TS useDataSource) ---
    // local = on-device Room DB + direct Spotify (today's behavior);
    // remote = conformant /api/v1 backend via DashboardRepository.
    data class DataSourceState(
        val mode: String = "local",
        val serverUrl: String = "",
        val email: String? = null,
        /** True only when a media-server JWT is stored (not merely remote mode). */
        val hasSession: Boolean = false,
        val spotifyConnected: Boolean = false,
        val spotifyName: String? = null
    )
    private val _dataSource = MutableStateFlow(DataSourceState())
    val dataSource: StateFlow<DataSourceState> = _dataSource.asStateFlow()

    private val _serverBusy = MutableStateFlow(false)
    val serverBusy: StateFlow<Boolean> = _serverBusy.asStateFlow()

    private val _serverTestResult = MutableStateFlow<String?>(null)
    val serverTestResult: StateFlow<String?> = _serverTestResult.asStateFlow()

    private val _serverAuthError = MutableStateFlow<String?>(null)
    val serverAuthError: StateFlow<String?> = _serverAuthError.asStateFlow()

    /** True while the phone's local library is being pushed to the server. */
    private val _pushBusy = MutableStateFlow(false)
    val pushBusy: StateFlow<Boolean> = _pushBusy.asStateFlow()

    /** Remote reads/writes only run when the app is in remote mode. */
    private fun remoteActive(): Boolean =
        dashboardRepository.isRemote()

    private fun refreshDataSourceState() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasSession = dashboardRepository.hasSession()
            val link = try {
                if (dashboardRepository.isRemote() && hasSession)
                    dashboardRepository.spotifyLink()
                else null
            } catch (e: Exception) {
                Log.w("MainViewModel", "Spotify link check failed", e)
                null
            }
            _dataSource.value = DataSourceState(
                mode = dashboardRepository.getMode(),
                serverUrl = dashboardRepository.getServerUrl(),
                email = dashboardRepository.getSessionEmail(),
                hasSession = dashboardRepository.hasSession(),
                spotifyConnected = link?.connected == true,
                spotifyName = link?.display_name
            )
        }
    }

    // --- Action feedback ---
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private var libraryOffset = 0
    private val PAGE_SIZE = 48

    // Active sync jobs + their cancel signals (real cancellation, mirrors TS
    // CancellationToken — the Stop button stops the sync, keeping partial data)
    private var librarySyncJob: Job? = null
    private var librarySyncSignal: SyncCancelSignal? = null
    private var playlistSyncJob: Job? = null
    private var playlistSyncSignal: SyncCancelSignal? = null

    init {
        refreshDataSourceState()
        loadLibraryFilterOptions()
        runLibraryQuery()
        restorePendingQueueSwitch()
        viewModelScope.launch(Dispatchers.IO) {
            _backupSettings.value = spotifyRepository.getBackupSettings()
            _gridSize.value = spotifyRepository.getGridSize()
            _libraryViewMode.value = spotifyRepository.getLibraryViewMode()
            _playlistViewMode.value = spotifyRepository.getPlaylistViewMode()
        }
        // Room v3 introduced albumType. Existing rows start blank; fill them in
        // the background from the Spotify API and refresh the board when done.
        viewModelScope.launch(Dispatchers.IO) {
            if (remoteActive()) return@launch
            try {
                val updated = spotifyRepository.backfillAlbumTypes()
                if (updated > 0) {
                    Log.d("MainViewModel", "Backfilled album types for $updated rows")
                    loadLibraryFilterOptions()
                    runLibraryQuery()
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Album type backfill failed", e)
            }
        }
        // Debounced re-query on filter edits (mirrors TS useDebounce 250ms).
        // drop(1) skips the initial value — init() already ran the first query.
        viewModelScope.launch {
            _libraryFilters.drop(1).debounce(250).distinctUntilChanged().collect {
                runLibraryQuery()
            }
        }
        viewModelScope.launch {
            _playlistFilters.drop(1).debounce(250).distinctUntilChanged().collect {
                if (_selectedPlaylist.value != null) runPlaylistQuery()
            }
        }
    }

    private fun loadLibraryFilterOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (remoteActive()) {
                    val (g, a, y) = dashboardRepository.fetchFilterOptions()
                    _libraryGenres.value = g
                    _libraryArtists.value = a
                    _libraryYears.value = y
                    _libraryStats.value = dashboardRepository.fetchStats()
                } else {
                    _libraryGenres.value = spotifyRepository.getDistinctGenres()
                    _libraryArtists.value = spotifyRepository.getDistinctArtists()
                    _libraryYears.value = spotifyRepository.getDistinctYears()
                    _libraryStats.value = spotifyRepository.getStats()
                }
                Log.d("MainViewModel", "Loaded filter options: ${_libraryGenres.value.size} genres")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load filter options", e)
            }
        }
    }

    fun runLibraryQuery() {
        viewModelScope.launch {
            try {
                _libraryLoading.value = true
                libraryOffset = 0

                val f = _libraryFilters.value
                Log.d("MainViewModel", "Running query with filters: search=${f.search}, genre=${f.genre}, artist=${f.artist}")

                if (remoteActive()) {
                    val (albums, total) = dashboardRepository.queryAlbumsAsync(
                        f, PAGE_SIZE, 0
                    )
                    _libraryAlbumsList.value = albums
                    _libraryTotal.value = total
                    _libraryHasMore.value = albums.size < total
                    libraryOffset = albums.size
                    Log.d("MainViewModel", "Remote query complete. Showing ${albums.size}/$total")
                } else {
                    val first = spotifyRepository.getAlbumsFlow(
                        f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo,
                        f.sortField, f.sortOrder, PAGE_SIZE, 0
                    ).first()

                    _libraryAlbumsList.value = first

                    val count = spotifyRepository.countAlbums(
                        f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo
                    )

                    _libraryTotal.value = count
                    _libraryHasMore.value = first.size < count
                    libraryOffset = first.size
                    Log.d("MainViewModel", "Query complete. Showing ${first.size}/$count")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Query failed", e)
                _libraryAlbumsList.value = emptyList()
                _libraryTotal.value = 0
                _libraryHasMore.value = false
            } finally {
                _libraryLoading.value = false
            }
        }
    }

    fun loadLibraryMore() {
        if (!_libraryHasMore.value || _libraryLoadingMore.value || _libraryLoading.value) return
        viewModelScope.launch {
            _libraryLoadingMore.value = true
            try {
                val f = _libraryFilters.value
                if (remoteActive()) {
                    val (albums, _) = dashboardRepository.queryAlbumsAsync(
                        f, PAGE_SIZE, libraryOffset
                    )
                    if (albums.isEmpty()) {
                        _libraryHasMore.value = false
                        return@launch
                    }
                    Log.d("MainViewModel", "Appending ${albums.size} albums at offset $libraryOffset")
                    libraryOffset += albums.size
                    _libraryAlbumsList.value = _libraryAlbumsList.value + albums
                    _libraryHasMore.value = libraryOffset < _libraryTotal.value
                    return@launch
                }
                val more = spotifyRepository.getAlbumsFlow(
                    f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo,
                    f.sortField, f.sortOrder, PAGE_SIZE, libraryOffset
                ).first()

                if (more.isEmpty()) {
                    _libraryHasMore.value = false
                    return@launch
                }
                Log.d("MainViewModel", "Appending ${more.size} albums at offset $libraryOffset")
                libraryOffset += more.size
                _libraryAlbumsList.value = _libraryAlbumsList.value + more
                _libraryHasMore.value = libraryOffset < _libraryTotal.value
            } catch (e: Exception) {
                Log.e("MainViewModel", "Load more failed", e)
            } finally {
                _libraryLoadingMore.value = false
            }
        }
    }

    fun updateLibraryFilter(update: FilterState.() -> FilterState) {
        // Query runs via the debounced collector (mirrors TS queryFilters)
        _libraryFilters.value = _libraryFilters.value.update()
    }

    fun resetLibraryFilters() {
        _libraryFilters.value = FilterState()
    }

    fun startLibrarySync(clean: Boolean = false, includeTracks: Boolean = false) {
        if (librarySyncJob?.isActive == true) {
            Log.d("MainViewModel", "Library sync already running — ignoring")
            return
        }
        val signal = SyncCancelSignal()
        librarySyncSignal = signal
        librarySyncJob = viewModelScope.launch {
            try {
                // Remote: start a server job and poll it — same SyncState UI.
                if (remoteActive()) {
                    try {
                        dashboardRepository.syncLibraryRemote(signal, clean, includeTracks) { state ->
                            _librarySyncState.value = state
                            Log.d("MainViewModel", "Remote sync state: $state")
                            if (state is SyncState.Completed || state is SyncState.Cancelled) {
                                Log.d("MainViewModel", "Remote sync finished, reloading data")
                                loadLibraryFilterOptions()
                                runLibraryQuery()
                            } else if (state is SyncState.Error) {
                                Log.e("MainViewModel", "Remote sync error: ${state.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Remote sync failed with exception", e)
                        _librarySyncState.value = SyncState.Error(e.message ?: "Unknown error")
                    }
                    // The server records every run that started, finished or
                    // failed, so refresh even on failure — a dead run belongs
                    // in the history too.
                    loadSyncHistory()
                    return@launch
                }
                spotifyRepository.syncLibrary(signal, clean) { state ->
                    _librarySyncState.value = state
                    Log.d("MainViewModel", "Sync state: $state")
                    if (state is SyncState.Completed || state is SyncState.Cancelled) {
                        Log.d("MainViewModel", "Sync finished, reloading data")
                        loadLibraryFilterOptions()
                        runLibraryQuery()
                        if (state is SyncState.Completed) {
                            // onProgress is not a suspend lambda — fan out for auto-backup
                            val didImport = state.result.imported > 0 || clean
                            viewModelScope.launch { maybeAutoBackup(didImport) }
                        }
                    } else if (state is SyncState.Error) {
                        Log.e("MainViewModel", "Sync error: ${state.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Sync failed with exception", e)
                _librarySyncState.value = SyncState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Real cancel: the running sync stops at its next checkpoint, partial data kept. */
    fun cancelLibrarySync() {
        val signal = librarySyncSignal
        // Remote poll loop cancels server-side on its next tick via the signal.
        if (signal != null && librarySyncJob?.isActive == true) {
            Log.d("MainViewModel", "Cancelling library sync")
            signal.cancelled = true
        } else {
            _librarySyncState.value = SyncState.Idle
        }
    }

    /** Dismiss the result / error / cancelled banner. */
    fun clearLibrarySyncState() {
        if (_librarySyncState.value !is SyncState.Progress) {
            _librarySyncState.value = SyncState.Idle
        }
    }

    /**
     * Loads the server's recent runs for the sync sheet (up to 3 finished
     * library syncs). Silent on failure — history is a nicety, never a reason
     * to block the sync UI.
     */
    fun loadSyncHistory() {
        val active = remoteActive()
        viewModelScope.launch {
            _syncHistory.value = if (!active) {
                emptyList()
            } else {
                SyncHistory.recentSyncs(dashboardRepository.fetchSyncHistory(5), 3)
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (remoteActive()) {
                    val (list, synced) = dashboardRepository.listPlaylists()
                    _playlists.value = list
                    remoteSyncedIds = synced.keys.toSet()
                } else {
                    _playlists.value = playlistRepository.getUserPlaylists()
                }
                Log.d("MainViewModel", "Loaded ${_playlists.value.size} playlists")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load playlists", e)
            }
        }
    }

    /** Server synced_at ids in remote mode (drives the cached-first check). */
    private var remoteSyncedIds: Set<String> = emptySet()

    /**
     * Selects a playlist and loads its albums from the local DB.
     * If the playlist has never been synced (no DB rows), kicks off a sync automatically.
     */
    fun selectPlaylist(playlist: SpotifyPlaylist?) {
        _selectedPlaylist.value = playlist
        clearSelection()
        if (playlist == null) {
            _playlistAlbumsList.value = emptyList()
            _playlistTotal.value = 0
            _playlistHasMore.value = false
            return
        }
        _playlistFilters.value = FilterState()
        _playlistExpiresAt.value = null
        viewModelScope.launch {
            try {
                _playlistExpiresAt.value = if (remoteActive()) {
                    dashboardRepository.retentionMap()[playlist.id]
                } else {
                    playlistRepository.localRetention(playlist.id)?.expiresAt
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Retention lookup failed for ${playlist.id}", e)
            }
        }
        viewModelScope.launch {
            // Remote: "cached" means the server reports synced_at for it.
            if (remoteActive()) {
                if (remoteSyncedIds.contains(playlist.id)) {
                    Log.d("MainViewModel", "Loading server-cached albums for ${playlist.id}")
                    runPlaylistQuery()
                } else {
                    Log.d("MainViewModel", "No server cache for ${playlist.id} — triggering sync")
                    syncPlaylist(playlist.id)
                }
                return@launch
            }
            val cached = try {
                playlistRepository.countPlaylistAlbums(playlist.id, "", "", "", null, null)
            } catch (e: Exception) {
                0
            }
            if (cached > 0) {
                Log.d("MainViewModel", "Loading $cached cached albums for ${playlist.id}")
                runPlaylistQuery()
            } else {
                Log.d("MainViewModel", "No cached albums for ${playlist.id} — triggering sync")
                syncPlaylist(playlist.id)
            }
        }
    }

    /** Core playlist query — resets the album list (mirrors runLibraryQuery). */
    fun runPlaylistQuery() {
        val playlistId = _selectedPlaylist.value?.id ?: return
        viewModelScope.launch {
            try {
                _playlistLoading.value = true
                playlistOffset = 0
                val f = _playlistFilters.value
                if (remoteActive()) {
                    val (albums, total) = dashboardRepository.queryPlaylistAlbums(
                        playlistId, f, PAGE_SIZE, 0
                    )
                    val (g, a, y) = dashboardRepository.playlistMeta(playlistId)
                    _playlistAlbumsList.value = albums
                    _playlistTotal.value = total
                    _playlistHasMore.value = albums.size < total
                    playlistOffset = albums.size
                    _playlistGenres.value = g
                    _playlistArtists.value = a
                    _playlistYears.value = y
                    return@launch
                }
                val first = playlistRepository.getFilteredPlaylistAlbumsFlow(
                    playlistId, f.search, f.genre, f.artist, f.yearFrom, f.yearTo,
                    f.sortField, f.sortOrder, PAGE_SIZE, 0
                ).first()
                val count = playlistRepository.countPlaylistAlbums(
                    playlistId, f.search, f.genre, f.artist, f.yearFrom, f.yearTo
                )
                val (g, a, y) = playlistRepository.getPlaylistFilterOptions(playlistId)
                _playlistAlbumsList.value = first
                _playlistTotal.value = count
                _playlistHasMore.value = first.size < count
                playlistOffset = first.size
                _playlistGenres.value = g
                _playlistArtists.value = a
                _playlistYears.value = y
            } catch (e: Exception) {
                Log.e("MainViewModel", "Playlist query failed", e)
                _playlistAlbumsList.value = emptyList()
                _playlistTotal.value = 0
                _playlistHasMore.value = false
            } finally {
                _playlistLoading.value = false
            }
        }
    }

    fun loadPlaylistMore() {
        val playlistId = _selectedPlaylist.value?.id ?: return
        if (!_playlistHasMore.value || _playlistLoadingMore.value || _playlistLoading.value) return
        viewModelScope.launch {
            _playlistLoadingMore.value = true
            try {
                val f = _playlistFilters.value
                if (remoteActive()) {
                    val (albums, _) = dashboardRepository.queryPlaylistAlbums(
                        playlistId, f, PAGE_SIZE, playlistOffset
                    )
                    if (albums.isEmpty()) {
                        _playlistHasMore.value = false
                        return@launch
                    }
                    playlistOffset += albums.size
                    _playlistAlbumsList.value = _playlistAlbumsList.value + albums
                    _playlistHasMore.value = playlistOffset < _playlistTotal.value
                    return@launch
                }
                val more = playlistRepository.getFilteredPlaylistAlbumsFlow(
                    playlistId, f.search, f.genre, f.artist, f.yearFrom, f.yearTo,
                    f.sortField, f.sortOrder, PAGE_SIZE, playlistOffset
                ).first()
                if (more.isEmpty()) {
                    _playlistHasMore.value = false
                    return@launch
                }
                playlistOffset += more.size
                _playlistAlbumsList.value = _playlistAlbumsList.value + more
                _playlistHasMore.value = playlistOffset < _playlistTotal.value
            } catch (e: Exception) {
                Log.e("MainViewModel", "Playlist load more failed", e)
            } finally {
                _playlistLoadingMore.value = false
            }
        }
    }

    fun updatePlaylistFilter(update: FilterState.() -> FilterState) {
        // Query runs via the debounced collector
        _playlistFilters.value = _playlistFilters.value.update()
    }

    fun resetPlaylistFilters() {
        _playlistFilters.value = FilterState()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun syncPlaylist(playlistId: String) {
        if (playlistSyncJob?.isActive == true) {
            Log.d("MainViewModel", "Playlist sync already running — ignoring")
            return
        }
        val signal = SyncCancelSignal()
        playlistSyncSignal = signal
        playlistSyncJob = viewModelScope.launch {
            _playlistLoading.value = true
            try {
                // Remote: server job, same progress UI (stages collapse to saving).
                if (remoteActive()) {
                    try {
                        dashboardRepository.syncPlaylistRemote(playlistId, signal) { state ->
                            _playlistSyncState.value = state
                            if (state is PlaylistSyncState.Completed ||
                                state is PlaylistSyncState.Error ||
                                state is PlaylistSyncState.Cancelled
                            ) {
                                _playlistLoading.value = false
                                if (_selectedPlaylist.value?.id == playlistId) runPlaylistQuery()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Remote playlist sync failed", e)
                        _playlistSyncState.value = PlaylistSyncState.Error(e.message ?: "Unknown error")
                        _playlistLoading.value = false
                    }
                    return@launch
                }
                playlistRepository.syncPlaylist(playlistId, signal) { state ->
                    _playlistSyncState.value = state
                    if (state is PlaylistSyncState.Completed ||
                        state is PlaylistSyncState.Error ||
                        state is PlaylistSyncState.Cancelled
                    ) {
                        _playlistLoading.value = false
                        // Refresh the grid from what was saved (incl. partial on cancel)
                        if (_selectedPlaylist.value?.id == playlistId) runPlaylistQuery()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "syncPlaylist failed", e)
                _playlistSyncState.value = PlaylistSyncState.Error(e.message ?: "Unknown error")
                _playlistLoading.value = false
            }
        }
    }

    /** Real cancel for the playlist sync — partial data kept. */
    fun cancelPlaylistSync() {
        val signal = playlistSyncSignal
        if (signal != null && playlistSyncJob?.isActive == true) {
            Log.d("MainViewModel", "Cancelling playlist sync")
            signal.cancelled = true
        } else {
            _playlistSyncState.value = PlaylistSyncState.Idle
        }
    }

    /** Dismiss the playlist result / error / cancelled banner. */
    fun clearPlaylistSyncState() {
        if (_playlistSyncState.value !is PlaylistSyncState.Progress) {
            _playlistSyncState.value = PlaylistSyncState.Idle
        }
    }

    // ── Album track sheet ───────────────────────────────────────────────────

    fun openAlbumSheet(
        albumId: String,
        title: String,
        artist: String,
        imageUrl: String,
        year: Int,
        playlistId: String?,
    ) {
        _albumSheet.value = AlbumSheetState(
            albumId = albumId,
            title = title,
            artist = artist,
            imageUrl = imageUrl,
            year = year,
            playlistId = playlistId,
        )
        viewModelScope.launch {
            try {
                val tracks = if (remoteActive()) dashboardRepository.albumTracks(albumId)
                             else playlistRepository.fetchAlbumTracks(albumId)
                _albumSheet.value = _albumSheet.value?.copy(tracks = tracks, loading = false)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Album tracks failed for $albumId", e)
                _albumSheet.value = _albumSheet.value?.copy(
                    loading = false, error = e.message ?: "Could not load album tracks",
                )
            }
        }
    }

    fun closeAlbumSheet() {
        _albumSheet.value = null
    }

    /** Remove the whole album from the open playlist (never the library). */
    fun removeAlbumFromOpenPlaylist() {
        val sheet = _albumSheet.value ?: return
        val playlistId = sheet.playlistId ?: return
        viewModelScope.launch {
            try {
                val removed = if (remoteActive()) {
                    dashboardRepository.removeAlbumsFromPlaylist(playlistId, setOf(sheet.albumId))
                } else {
                    playlistRepository.removeAlbumsFromPlaylist(playlistId, setOf(sheet.albumId))
                }
                _albumSheet.value = null
                _actionMessage.value = "Removed $removed track(s) from playlist"
                runPlaylistQuery()
                if (remoteActive()) {
                    _playlists.value = dashboardRepository.listPlaylists().first
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Remove album failed", e)
                _actionMessage.value = "Remove failed: ${e.message}"
            }
        }
    }

    /** Remove one track row from the open playlist. */
    fun removeTrackFromOpenPlaylist(uri: String) {
        val sheet = _albumSheet.value ?: return
        val playlistId = sheet.playlistId ?: return
        viewModelScope.launch {
            try {
                val removed = if (remoteActive()) {
                    dashboardRepository.removeTrackUris(playlistId, listOf(uri))
                } else {
                    playlistRepository.removeTrackUris(playlistId, listOf(uri))
                }
                _albumSheet.value = _albumSheet.value?.copy(
                    tracks = _albumSheet.value?.tracks?.filterNot { it.uri == uri } ?: emptyList(),
                )
                _actionMessage.value = "Removed $removed track(s)"
                runPlaylistQuery()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Remove track failed", e)
                _actionMessage.value = "Remove failed: ${e.message}"
            }
        }
    }

    fun queueAlbumSheetTracks() {
        val sheet = _albumSheet.value ?: return
        val uris = sheet.tracks.map { it.uri }.filter { it.isNotBlank() }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val queued = if (remoteActive()) dashboardRepository.queueTrackUris(uris)
                             else playlistRepository.queueTrackUris(uris)
                _actionMessage.value = if (queued > 0)
                    "Added $queued track(s) to the queue"
                else
                    "Open Spotify on a device first"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Queue album tracks failed", e)
                _actionMessage.value = "Queue failed: ${e.message}"
            }
        }
    }

    /** Queue a single track from the album sheet. */
    fun queueTrackUri(uri: String) {
        if (uri.isBlank()) return
        viewModelScope.launch {
            try {
                val queued = if (remoteActive()) dashboardRepository.queueTrackUris(listOf(uri))
                             else playlistRepository.queueTrackUris(listOf(uri))
                _actionMessage.value = if (queued > 0)
                    "Added track to queue"
                else
                    "Open Spotify on a device first"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Queue track failed", e)
                _actionMessage.value = "Queue failed: ${e.message}"
            }
        }
    }

    /** Open the playlist picker for one album shown in the album sheet. */
    fun requestAddAlbumToPlaylist(albumId: String) {
        _selectedAlbums.value = setOf(albumId)
        if (_playlists.value.isEmpty()) loadPlaylists()
        _showPlaylistPicker.value = true
    }

    // --- Read-only Spotify playback state ------------------------------------

    fun refreshPlayback() {
        viewModelScope.launch { refreshPlaybackNow() }
    }

    private suspend fun refreshPlaybackNow() {
        _playbackState.value = _playbackState.value.copy(loading = true, error = null)
        try {
            val current = spotifyRepository.getCurrentPlayback()
            val queue = spotifyRepository.getQueue()
            val context = current?.context
            if (current != null) {
                // Keep the last real context even if a later poll returns 204.
                lastPlaybackContextUri = context?.uri
            }
            val playlistId = context?.uri
                ?.takeIf {
                    context.type == "playlist" && it.startsWith("spotify:playlist:")
                }
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }

            val playlistAccess = if (playlistId != null) {
                if (playlistId != playbackPlaylistContextId) {
                    playbackPlaylistContextId = playlistId
                    playbackPlaylistAccess = null
                    playbackPlaylistAccessResolved = false
                }
                if (!playbackPlaylistAccessResolved) {
                    // Two calls, but only once per playlist context. Playback
                    // polling continues at 5 s and reuses this result. A
                    // transient failure is retried on the next poll.
                    val fetched = try {
                        playlistRepository.getPlaylistAccess(playlistId)
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Playlist access check failed for $playlistId", e)
                        null
                    }
                    playbackPlaylistAccess = fetched
                    playbackPlaylistAccessResolved = fetched != null
                }
                playbackPlaylistAccess
            } else {
                playbackPlaylistContextId = null
                playbackPlaylistAccess = null
                playbackPlaylistAccessResolved = false
                null
            }
            val contextName = if (playlistId != null) {
                playlistAccess?.name.orEmpty()
            } else {
                current?.item?.album?.name.orEmpty()
            }

            _playbackState.value = PlaybackUiState(
                loading = false,
                error = null,
                isPlaying = current?.is_playing == true,
                progressMs = current?.progress_ms ?: 0,
                progressAt = System.currentTimeMillis(),
                deviceName = current?.device?.name ?: "",
                track = current?.item,
                queue = queue,
                contextType = context?.type.orEmpty(),
                contextUri = context?.uri.orEmpty(),
                contextName = contextName,
                contextPlaylistId = playlistId,
                contextEditable = playlistAccess?.editable == true,
                lastUpdated = System.currentTimeMillis(),
            )
            updateActiveQueuePlaybackStatus(current, queue)
            updateLocalQueuePlaybackStatus(current, queue)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Playback refresh failed", e)
            _playbackState.value = _playbackState.value.copy(loading = false, error = e.message ?: "Playback unavailable")
        }
    }

    /**
     * Save the current track + up-next queue as a new private playlist.
     *
     * This is a one-time snapshot: later queue changes are not mirrored. It
     * never changes playback and never tries to insert next/remove/reorder.
     */
    fun saveQueueAsPlaylist(name: String, retentionUntil: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _actionMessage.value = "Playlist name cannot be empty"
            return
        }
        val state = _playbackState.value
        val rawUris = mutableListOf<String>()
        state.track?.uri?.let { rawUris.add(it) }
        rawUris.addAll(state.queue.map { it.uri })
        val uris = QueueSnapshot.playlistUris(
            state.track?.uri,
            state.queue.map { it.uri },
        )
        if (uris.isEmpty()) {
            _actionMessage.value = "Nothing to save — the current item and queue are empty or local files."
            return
        }
        val skipped = rawUris.count { it.isNotBlank() } - uris.size
        viewModelScope.launch {
            _queuePlaylistBusy.value = true
            try {
                val playlistName: String
                if (remoteActive()) {
                    val created = dashboardRepository.createAndAddPlaylist(
                        name = trimmed,
                        albumIds = emptySet(),
                        retentionUntil = retentionUntil,
                        uris = uris,
                    )
                    playlistName = created.playlist_name.ifBlank { trimmed }
                    runCatching {
                        _playlists.value = dashboardRepository.listPlaylists().first
                    }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                } else {
                    val created = playlistRepository.createPlaylistWithUris(
                        trimmed, uris, retentionUntil,
                    )
                    playlistName = created.name.ifBlank { trimmed }
                    runCatching {
                        _playlists.value = playlistRepository.getUserPlaylists()
                    }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                }
                val skippedText = if (skipped > 0) " (skipped $skipped unsupported item(s))" else ""
                val retentionText = if (retentionUntil != null) " — deletes automatically" else ""
                _actionMessage.value =
                    "Saved ${uris.size} item(s) to \"$playlistName\"$skippedText$retentionText"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Save queue as playlist failed", e)
                _actionMessage.value = playlistActionError("Save failed", e)
            } finally {
                _queuePlaylistBusy.value = false
            }
        }
    }

    // ---- Single persistent app queue playlist -------------------------------

    fun requestActiveQueuePlaylistCreation(albumIds: Set<String> = emptySet()) {
        if (_activeQueuePlaylist.value != null) {
            addAlbumsToActiveQueuePlaylist(albumIds)
            return
        }
        _pendingActiveQueueCreation.value = PendingActiveQueueCreation(albumIds)
    }

    fun requestActiveQueueReset() {
        if (_activeQueuePlaylist.value == null) {
            _actionMessage.value = "There is no queue playlist to reset."
            return
        }
        _pendingActiveQueueCreation.value = PendingActiveQueueCreation(reset = true)
    }

    /**
     * Merge mode: start a brand-new queue playlist from what is playing now,
     * even when an old active queue pointer still exists. Unlike Reset this
     * does not preserve the old playlist's unplayed tail.
     */
    fun requestNewActiveQueuePlaylist(albumIds: Set<String> = emptySet()) {
        _pendingActiveQueueCreation.value = PendingActiveQueueCreation(
            albumIds = albumIds,
            reset = false,
            newFromCurrent = true,
        )
    }

    fun cancelActiveQueueCreation() {
        _pendingActiveQueueCreation.value = null
    }

    fun confirmActiveQueueCreation() {
        val pending = _pendingActiveQueueCreation.value ?: return
        _pendingActiveQueueCreation.value = null
        if (pending.local) {
            createLocalQueuePlaylist(pending.albumIds, pending.reset)
        } else {
            createActiveQueuePlaylist(
                pending.albumIds,
                pending.reset,
                pending.newFromCurrent,
            )
        }
    }

    fun setQueueMode(mode: QueueMode) {
        if (_queueMode.value == mode) return
        _queueMode.value = mode
        playlistRepository.setQueueMode(mode)
        refreshPlayback()
    }

    fun requestLocalQueuePlaylistCreation(albumIds: Set<String> = emptySet()) {
        _pendingActiveQueueCreation.value =
            PendingActiveQueueCreation(albumIds = albumIds, reset = false, local = true)
    }

    fun requestLocalQueueReset() {
        if (_localQueuePlaylist.value == null) {
            _actionMessage.value = "There is no local queue playlist to reset."
            return
        }
        _pendingActiveQueueCreation.value =
            PendingActiveQueueCreation(reset = true, local = true)
    }

    fun requestLocalQueueResetWithSpotify() {
        mergeSpotifyQueueIntoLocalQueue()
    }

    /** Spotify MERGE reset: append Spotify's up-next queue after the current track. */
    private fun mergeSpotifyQueueIntoLocalQueue() {
        if (_localQueueBusy.value) return
        val active = _localQueuePlaylist.value ?: playlistRepository.localQueuePlaylist()
        if (active == null || active.playlistId.isBlank()) {
            _actionMessage.value = "Create a local queue playlist first."
            return
        }
        viewModelScope.launch {
            _localQueueBusy.value = true
            try {
                val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
                val activeUris = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse { active.uris }.ifEmpty { active.uris }
                val ownContext = current?.context?.uri == "spotify:playlist:${active.playlistId}"
                // Keep an own-context Spotify queue only to discover any extra
                // manual items that were added after the local tail.
                val currentUri = current?.item?.uri?.trim().orEmpty()
                val resolvedAnchor = if (currentUri.isNotEmpty()) {
                    resolveLocalQueueIndex(active.copy(uris = activeUris), currentUri)
                } else {
                    -1
                }
                val anchor = when {
                    resolvedAnchor >= 0 -> resolvedAnchor
                    localCursorPlaylistId == active.playlistId &&
                        localCursorIndex in activeUris.indices -> localCursorIndex
                    else -> -1
                }
                if (anchor < 0) {
                    _actionMessage.value =
                        "Start playback from the local queue first so the app knows the current position."
                    return@launch
                }
                val upcoming = activeUris.drop(anchor + 1)
                val spotifyQueue = runCatching { spotifyRepository.getQueue() }
                    .getOrDefault(emptyList())
                    .map { it.uri.trim() }
                    .filter { QueueSnapshot.isPlayable(it) }
                val toAdd = ArrayList<String>(spotifyQueue.size)
                val seen = upcoming.toMutableSet()
                for (uri in spotifyQueue) {
                    if (seen.add(uri)) toAdd.add(uri)
                }
                if (toAdd.isEmpty()) {
                    val capText = if (spotifyQueue.size >= 20) {
                        " (Spotify returns at most 20 queued tracks)"
                    } else {
                        ""
                    }
                    _actionMessage.value = "No new Spotify tracks to merge into the local queue.$capText"
                    return@launch
                }
                val insertAt = (anchor + 1).coerceIn(0, activeUris.size)
                playlistRepository.insertTrackUrisToPlaylist(active.playlistId, toAdd, insertAt)
                val updated = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse {
                    activeUris.toMutableList().apply { addAll(insertAt, toAdd) }
                }.ifEmpty { activeUris + toAdd }
                playlistRepository.setLocalQueuePlaylist(active.playlistId, active.name, updated)
                _localQueuePlaylist.value = QueuePlaylistRef(active.playlistId, active.name, updated)
                if (localCursorPlaylistId == active.playlistId) {
                    playlistRepository.setLocalQueueCursor(
                        active.playlistId,
                        anchor,
                        activeUris.getOrNull(anchor),
                    )
                }
                val queueCapNote = if (spotifyQueue.size >= 20) {
                    " (Spotify returns at most 20 queued tracks)"
                } else {
                    ""
                }
                _localQueueStatus.value =
                    "Local queue: ${updated.size} tracks · ${toAdd.size} merged from Spotify" +
                        queueCapNote
                val playback = runCatching {
                    val device = pickPlaybackDevice(spotifyRepository.getDevices())
                        ?: throw Exception(
                            "Open Spotify on a device first - no device can play right now."
                        )
                    spotifyRepository.playPlaylistAsContext(active.playlistId, device.id, anchor)
                    device.name.ifBlank { "the active device" }
                }
                if (playback.isSuccess) {
                    _actionMessage.value =
                        "Merged ${toAdd.size} track(s) after the current track in " +
                            "\"${active.name}\" and started it on ${playback.getOrNull()}." +
                            queueCapNote
                } else {
                    _actionMessage.value =
                        "Merged ${toAdd.size} track(s) after the current track in " +
                            "\"${active.name}\"; playback did not start: " +
                            (playback.exceptionOrNull()?.message ?: "unknown error") +
                            queueCapNote
                }
                refreshPlayback()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Spotify queue merge failed", e)
                _actionMessage.value = playlistActionError("Merge queue failed", e)
            } finally {
                _localQueueBusy.value = false
            }
        }
    }

    /**
     * Start a new, empty local queue. The previous queue playlist is left on
     * Spotify on purpose: clearing the app's pointer must not delete music the
     * user may still want. Nothing is played, because an empty playlist cannot
     * start playback.
     */
    fun requestLocalQueueClear() {
        if (_localQueueBusy.value) return
        viewModelScope.launch {
            _localQueueBusy.value = true
            try {
                val previous = _localQueuePlaylist.value ?: playlistRepository.localQueuePlaylist()
                val name = queuePlaylistName()
                val created = playlistRepository.createPlaylist(name)
                val playlistName = created.name.ifBlank { name }
                playlistRepository.setLocalQueuePlaylist(created.id, playlistName, emptyList())
                _localQueuePlaylist.value = QueuePlaylistRef(created.id, playlistName, emptyList())
                // A null URI means "no track has played yet". Reset then keeps
                // the whole playlist instead of dropping its first track.
                localCursorPlaylistId = created.id
                localCursorIndex = 0
                localCursorUri = null
                playlistRepository.setLocalQueueCursor(created.id, 0, null)
                _localQueueStatus.value = if (previous != null) {
                    "Local queue: \"$playlistName\" (0 tracks) · empty, previous queue kept"
                } else {
                    "Local queue: \"$playlistName\" (0 tracks) · empty"
                }
                _actionMessage.value = if (previous != null) {
                    "Created an empty local queue \"$playlistName\". " +
                        "The previous queue \"${previous.name}\" was kept on Spotify."
                } else {
                    "Created an empty local queue \"$playlistName\"."
                }
                runCatching {
                    _playlists.value = playlistRepository.getUserPlaylists()
                }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Local queue clear failed", e)
                _actionMessage.value = playlistActionError("Clear local queue failed", e)
            } finally {
                _localQueueBusy.value = false
            }
        }
    }

    /**
     * The album-sheet and multi-select entry point. If a queue playlist is
     * already active this appends to it; otherwise it asks for confirmation
     * before replacing playback with a new one.
     */
    fun queueAlbumsToActiveQueue(albumIds: Set<String>) {
        if (albumIds.isEmpty()) {
            _actionMessage.value = "Select at least one album first."
            return
        }
        val active = _activeQueuePlaylist.value ?: playlistRepository.lastQueuePlaylist()
        if (active == null) {
            requestActiveQueuePlaylistCreation(albumIds)
        } else {
            addAlbumsToActiveQueuePlaylist(albumIds)
        }
    }

    fun addSelectedAlbumsToActiveQueuePlaylist() {
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) {
            _actionMessage.value = "Select at least one album first."
            return
        }
        addAlbumsToActiveQueuePlaylist(ids)
    }

    fun addAlbumsToActiveQueuePlaylist(albumIds: Set<String> = emptySet()) {
        if (albumIds.isEmpty()) {
            _actionMessage.value = "Select at least one album first."
            return
        }
        val active = _activeQueuePlaylist.value ?: playlistRepository.lastQueuePlaylist()
        if (active == null || active.playlistId.isBlank()) {
            requestActiveQueuePlaylistCreation(albumIds)
            return
        }
        if (_activeQueueBusy.value) return
        viewModelScope.launch {
            _activeQueueBusy.value = true
            _queueToEndBusy.value = true
            try {
                ensureActiveQueuePersistent(active)
                val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
                val queue = runCatching { spotifyRepository.getQueue() }.getOrDefault(emptyList())
                val activeUris = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse { active.uris }.ifEmpty { active.uris }
                val ownContext = current?.context?.uri == "spotify:playlist:${active.playlistId}"
                // Only use the side queue when the active playlist is the
                // context that is playing. In that case it already contains
                // the active tail plus any manual items the user queued.
                val upcoming = if (ownContext) {
                    val activeIndex = resolveActiveQueueIndex(active, current?.item?.uri)
                    QueueSnapshot.upNextIncludingPrevious(
                        queueUris = queue.map { it.uri },
                        previousUris = activeUris,
                        currentUri = current?.item?.uri,
                        dropRepeats = current?.repeat_state == "context" ||
                            current?.repeat_state == "track",
                        currentIndex = activeIndex.takeIf { it >= 0 },
                    )
                } else {
                    emptyList()
                }
                val albumUris = resolveAlbumUris(albumIds)
                val toAppend = ActiveQueuePlanner.newAlbumUris(albumUris, upcoming)
                if (toAppend.isEmpty()) {
                    _actionMessage.value =
                        "Those album tracks are already in the queue playlist or Spotify queue."
                    return@launch
                }
                val added = if (remoteActive()) {
                    dashboardRepository.addTrackUrisToPlaylist(active.playlistId, toAppend)
                } else {
                    playlistRepository.addTrackUrisToPlaylist(active.playlistId, toAppend)
                }
                val updated = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse { activeUris + toAppend }.ifEmpty { activeUris + toAppend }
                playlistRepository.setLastQueuePlaylist(active.playlistId, active.name, updated)
                _activeQueuePlaylist.value = QueuePlaylistRef(active.playlistId, active.name, updated)
                _activeQueueStatus.value = if (ownContext) {
                    "Queue playlist: ${updated.size} tracks · ${toAppend.size} just added"
                } else {
                    "Queue playlist: ${updated.size} tracks · not the current playback context"
                }
                _actionMessage.value =
                    "Added ${toAppend.size} track(s) to the end of \"${active.name}\" " +
                        "(server accepted $added)."
                refreshPlayback()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Add albums to active queue failed", e)
                _actionMessage.value = playlistActionError("Add to queue failed", e)
            } finally {
                _activeQueueBusy.value = false
                _queueToEndBusy.value = false
            }
        }
    }

    // ---- Local-playlist queue mode -----------------------------------------

    fun addSelectedAlbumsToLocalQueuePlaylist() {
        modifyLocalQueueWithSelectedAlbums(insertNext = false)
    }

    /** Insert the selected albums immediately after the current local track. */
    fun queueSelectedAlbumsNextInLocalQueue() {
        modifyLocalQueueWithSelectedAlbums(insertNext = true)
    }

    private fun modifyLocalQueueWithSelectedAlbums(insertNext: Boolean) {
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) {
            _actionMessage.value = "Select at least one album first."
            return
        }
        modifyLocalQueueWithAlbums(ids, insertNext)
    }

    fun addAlbumsToLocalQueuePlaylist(albumIds: Set<String>) {
        modifyLocalQueueWithAlbums(albumIds, insertNext = false)
    }

    fun queueAlbumsNextInLocalQueue(albumIds: Set<String>) {
        modifyLocalQueueWithAlbums(albumIds, insertNext = true)
    }

    /**
     * Append selected albums, or insert them at the current local position + 1.
     * The cursor is only persisted by [resolveLocalQueueIndex]; inserting after
     * the current index does not shift it.
     */
    private fun modifyLocalQueueWithAlbums(albumIds: Set<String>, insertNext: Boolean) {
        if (albumIds.isEmpty()) {
            _actionMessage.value = "Select at least one album first."
            return
        }
        val active = _localQueuePlaylist.value ?: playlistRepository.localQueuePlaylist()
        if (active == null || active.playlistId.isBlank()) {
            if (insertNext) {
                _actionMessage.value =
                    "Create a local queue playlist first, then queue tracks next."
            } else {
                requestLocalQueuePlaylistCreation(albumIds)
            }
            return
        }
        if (_localQueueBusy.value) return
        viewModelScope.launch {
            _localQueueBusy.value = true
            try {
                val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
                val activeUris = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse { active.uris }.ifEmpty { active.uris }
                val ownContext = current?.context?.uri == "spotify:playlist:${active.playlistId}"
                val currentUri = current?.item?.uri
                val activeWithUris = active.copy(uris = activeUris)
                val liveIndex = if (ownContext) {
                    resolveLocalQueueIndex(activeWithUris, currentUri)
                } else {
                    -1
                }
                val anchorIndex = if (insertNext) {
                    when {
                        liveIndex >= 0 -> liveIndex
                        localCursorPlaylistId == active.playlistId &&
                            localCursorIndex in activeUris.indices -> localCursorIndex
                        activeUris.isEmpty() -> 0
                        else -> -1
                    }
                } else {
                    liveIndex
                }
                if (insertNext && anchorIndex < 0) {
                    _actionMessage.value =
                        "Start playback from the local queue first so the app knows the current position."
                    return@launch
                }
                val insertAt = if (insertNext) {
                    (anchorIndex + 1).coerceIn(0, activeUris.size)
                } else {
                    -1
                }
                val upcoming = if (insertNext) {
                    activeUris.drop(insertAt)
                } else if (liveIndex >= 0) {
                    activeUris.drop(liveIndex + 1)
                } else {
                    activeUris
                }
                val albumUris = resolveAlbumUris(albumIds)
                val toAdd = if (insertNext) {
                    ActiveQueuePlanner.nextAlbumUris(
                        albumUris = albumUris,
                        currentUri = if (ownContext) currentUri else null,
                        upcomingUris = upcoming,
                    )
                } else {
                    ActiveQueuePlanner.newAlbumUris(albumUris, upcoming)
                }
                if (toAdd.isEmpty()) {
                    _actionMessage.value = if (insertNext) {
                        "Those album tracks are already queued next."
                    } else {
                        "Those album tracks are already in the local queue playlist."
                    }
                    return@launch
                }
                val fallback = if (insertNext) {
                    activeUris.toMutableList().apply { addAll(insertAt, toAdd) }
                } else {
                    activeUris + toAdd
                }
                if (insertNext) {
                    playlistRepository.insertTrackUrisToPlaylist(
                        active.playlistId,
                        toAdd,
                        insertAt,
                    )
                } else {
                    playlistRepository.addTrackUrisToPlaylist(active.playlistId, toAdd)
                }
                val updated = runCatching {
                    playlistRepository.getPlaylistTrackUris(active.playlistId)
                }.getOrElse { fallback }.ifEmpty { fallback }
                playlistRepository.setLocalQueuePlaylist(active.playlistId, active.name, updated)
                _localQueuePlaylist.value = QueuePlaylistRef(active.playlistId, active.name, updated)
                _localQueueStatus.value = when {
                    insertNext && activeUris.isEmpty() ->
                        "Local queue: ${updated.size} tracks · ${toAdd.size} added"
                    insertNext && ownContext ->
                        "Local queue: ${updated.size} tracks · ${toAdd.size} queued next"
                    insertNext ->
                        "Local queue: ${updated.size} tracks · ${toAdd.size} queued after the saved position"
                    ownContext ->
                        "Local queue: ${updated.size} tracks · ${toAdd.size} just added"
                    else ->
                        "Local queue: ${updated.size} tracks · not the current playback context"
                }
                _actionMessage.value = if (insertNext) {
                    "Queued ${toAdd.size} track(s) next in \"${active.name}\"."
                } else {
                    "Added ${toAdd.size} track(s) to the end of \"${active.name}\"."
                }
                refreshPlayback()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Modify local queue failed", e)
                _actionMessage.value = playlistActionError(
                    if (insertNext) "Queue next failed" else "Add to local queue failed",
                    e,
                )
            } finally {
                _localQueueBusy.value = false
            }
        }
    }

    /**
     * Create the local-playlist queue. A normal create starts from what is
     * playing now (its up-next queue, or the current track if the queue is
     * empty). A reset starts from the unplayed tail of the active local queue.
     */
    private fun createLocalQueuePlaylist(albumIds: Set<String>, reset: Boolean) {
        if (_localQueueBusy.value) return
        viewModelScope.launch {
            _localQueueBusy.value = true
            try {
                val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
                val queue = runCatching { spotifyRepository.getQueue() }.getOrDefault(emptyList())
                val previous = _localQueuePlaylist.value ?: playlistRepository.localQueuePlaylist()
                val previousUris = if (reset && previous != null) {
                    runCatching {
                        playlistRepository.getPlaylistTrackUris(previous.playlistId)
                    }.getOrElse { previous.uris }.ifEmpty { previous.uris }
                } else {
                    emptyList()
                }
                val currentUri = current?.item?.uri
                val ownContext = previous != null &&
                    current?.context?.uri == "spotify:playlist:${previous.playlistId}"
                val content = if (reset && previous != null) {
                    val anchorIndex = if (ownContext) {
                        resolveLocalQueueIndex(previous.copy(uris = previousUris), currentUri)
                    } else if (localCursorPlaylistId == previous.playlistId && localCursorUri != null) {
                        localCursorIndex
                    } else {
                        -1
                    }
                    // A reset must be non-destructive: anchor at the current track
                    // and keep the current track plus the unplayed tail, rather
                    // than dropping the current track and restarting the tail.
                    if (anchorIndex in previousUris.indices) {
                        previousUris
                            .subList(anchorIndex, previousUris.size)
                            .filter { QueueSnapshot.isPlayable(it) }
                    } else if (previousUris.isNotEmpty()) {
                        previousUris.filter { QueueSnapshot.isPlayable(it) }
                    } else {
                        QueueSnapshot.newQueueContent(currentUri, emptyList())
                    }
                } else {
                    // A new queue starts from the currently playing track. It must
                    // not inherit Spotify's whole up-next list; selected albums are
                    // appended below.
                    if (QueueSnapshot.isPlayable(currentUri)) listOf(currentUri!!.trim()) else emptyList()
                }
                val albumUris = resolveAlbumUris(albumIds)
                val merged = ArrayList<String>(content)
                for (uri in albumUris) {
                    if (uri !in merged) merged.add(uri)
                }
                Log.d(
                    "MainViewModel",
                    "Local queue create: reset=$reset previous=${previous?.playlistId} " +
                        "previousUris=${previousUris.size} queue=${queue.size} " +
                        "content=${content.size} merged=${merged.size} current=$currentUri " +
                        "context=${current?.context?.uri}",
                )
                if (merged.isEmpty()) {
                    if (reset) {
                        _localQueuePlaylist.value = null
                        playlistRepository.clearLocalQueuePlaylist()
                        playlistRepository.clearLocalQueueCursor()
                        localCursorPlaylistId = null
                        localCursorIndex = -1
                        localCursorUri = null
                        previous?.let { deleteQueuePlaylist(it.playlistId) }
                        _localQueueStatus.value = "No local queue playlist yet"
                        _actionMessage.value = "Local queue playlist reset. Nothing was left."
                    } else {
                        _actionMessage.value =
                            "Nothing to queue - start Spotify playback or select an album."
                    }
                    return@launch
                }

                val name = queuePlaylistName()
                val created = playlistRepository.createPlaylistWithUris(
                    name = name,
                    uris = merged,
                    retentionUntil = null,
                )
                val actual = runCatching {
                    playlistRepository.getPlaylistTrackUris(created.id)
                }.getOrElse { merged }.ifEmpty { merged }
                val playlistName = created.name.ifBlank { name }
                playlistRepository.setLocalQueuePlaylist(created.id, playlistName, actual)
                _localQueuePlaylist.value = QueuePlaylistRef(created.id, playlistName, actual)
                localCursorPlaylistId = created.id
                localCursorIndex = 0
                localCursorUri = actual.firstOrNull()
                playlistRepository.setLocalQueueCursor(created.id, 0, localCursorUri)
                _localQueueStatus.value =
                    "Local queue ready: \"$playlistName\" (${actual.size} tracks)"

                val playback = runCatching {
                    val device = pickPlaybackDevice(spotifyRepository.getDevices())
                        ?: throw Exception(
                            "Open Spotify on a device first - no device can play right now."
                        )
                    spotifyRepository.playPlaylistAsContext(created.id, device.id, 0)
                    device.name.ifBlank { "the active device" }
                }
                if (playback.isSuccess) {
                    // A new queue playlist replaces the old one. Reset must stay
                    // non-destructive and keep the previous playlist as history.
                    if (!reset && previous != null && previous.playlistId != created.id) {
                        deleteQueuePlaylist(previous.playlistId)
                    }
                    _actionMessage.value =
                        "Playing local queue \"$playlistName\" on ${playback.getOrNull()}"
                } else {
                    _actionMessage.value =
                        "Created local queue \"$playlistName\" but playback did not start: " +
                            (playback.exceptionOrNull()?.message ?: "unknown error")
                }
                runCatching {
                    _playlists.value = playlistRepository.getUserPlaylists()
                }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                refreshPlayback()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Local queue playlist create failed", e)
                _actionMessage.value = playlistActionError("Local queue failed", e)
            } finally {
                _localQueueBusy.value = false
            }
        }
    }

    /**
     * Find the current occurrence in the local playlist. The cursor keeps
     * duplicate tracks unambiguous and is persisted for reset-after-restart.
     */
    private fun resolveLocalQueueIndex(active: QueuePlaylistRef, currentUri: String?): Int {
        val uri = currentUri?.trim().orEmpty()
        if (uri.isEmpty()) return -1
        if (localCursorPlaylistId != active.playlistId) {
            localCursorPlaylistId = active.playlistId
            localCursorIndex = -1
            localCursorUri = null
        }
        val uris = active.uris
        if (uris.isEmpty()) return -1
        fun found(i: Int): Int {
            if (localCursorIndex != i || localCursorUri != uri) {
                localCursorIndex = i
                localCursorUri = uri
                playlistRepository.setLocalQueueCursor(active.playlistId, i, uri)
            }
            return i
        }
        val sameAsLast = localCursorUri == uri
        val start = if (sameAsLast && localCursorIndex in uris.indices) localCursorIndex else 0
        for (i in start until uris.size) {
            if (uris[i] == uri) return found(i)
        }
        for (i in 0 until start) {
            if (uris[i] == uri) return found(i)
        }
        return -1
    }

    private fun updateLocalQueuePlaybackStatus(
        current: com.spotifydashboard.app.api.CurrentlyPlayingResponse?,
        @Suppress("UNUSED_PARAMETER") queue: List<com.spotifydashboard.app.api.SpotifyPlaybackTrack>,
    ) {
        val active = _localQueuePlaylist.value ?: return
        val ownContext = current?.context?.uri == "spotify:playlist:${active.playlistId}"
        if (!ownContext) {
            _localQueueStatus.value =
                "Local queue: \"${active.name}\" (${active.uris.size} tracks) · not the current context"
            return
        }
        val index = resolveLocalQueueIndex(active, current?.item?.uri)
        if (index >= 0) {
            val total = active.uris.size
            val remaining = (total - index - 1).coerceAtLeast(0)
            val trackName = current?.item?.name.orEmpty()
            _localQueueStatus.value =
                "Local queue: ${index + 1}/$total · $trackName · $remaining left"
        } else {
            _localQueueStatus.value =
                "Local queue: \"${active.name}\" (${active.uris.size} tracks) · " +
                    "current track not in the playlist"
        }
    }

    /**
     * Create one queue playlist, store it as the active queue and start it
     * immediately. This is used both for the first create and for Reset; reset
     * additionally removes the old queue playlist once the new one is playing.
     */
    private fun createActiveQueuePlaylist(
        albumIds: Set<String>,
        reset: Boolean,
        newFromCurrent: Boolean = false,
    ) {
        if (_activeQueueBusy.value) return
        viewModelScope.launch {
            _activeQueueBusy.value = true
            _queueToEndBusy.value = true
            try {
                val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
                val queue = runCatching { spotifyRepository.getQueue() }.getOrDefault(emptyList())
                val previous = _activeQueuePlaylist.value ?: playlistRepository.lastQueuePlaylist()
                val previousContext = previous != null &&
                    current?.context?.uri == "spotify:playlist:${previous.playlistId}"
                // Spotify returns 204 when playback is stopped. There is no
                // current item to anchor the tail then, so fall back to the
                // last position seen while this queue playlist was playing.
                val stoppedOnPrevious = current == null &&
                    previous != null &&
                    activeQueueCursorPlaylistId == previous.playlistId &&
                    activeQueueCursorIndex >= 0 &&
                    lastPlaybackContextUri == "spotify:playlist:${previous.playlistId}"
                val keepPreviousTail = !newFromCurrent &&
                    (previousContext || stoppedOnPrevious)
                val previousUris = if (keepPreviousTail && previous != null) {
                    runCatching {
                        playlistRepository.getPlaylistTrackUris(previous.playlistId)
                    }.getOrElse { previous.uris }.ifEmpty { previous.uris }
                } else {
                    emptyList()
                }
                val previousIndex = if (previousContext && previous != null) {
                    resolveActiveQueueIndex(previous, current?.item?.uri)
                } else {
                    -1
                }
                val anchorIndex = when {
                    previousContext -> previousIndex.takeIf { it >= 0 }
                    stoppedOnPrevious -> activeQueueCursorIndex
                    else -> null
                }
                val anchorUri = when {
                    previousContext -> current?.item?.uri
                    stoppedOnPrevious -> activeQueueCursorUri
                    else -> null
                }
                val upNext = if (keepPreviousTail && previousUris.isNotEmpty()) {
                    QueueSnapshot.upNextIncludingPrevious(
                        queueUris = queue.map { it.uri },
                        previousUris = previousUris,
                        currentUri = anchorUri,
                        dropRepeats = current?.repeat_state == "context" ||
                            current?.repeat_state == "track",
                        currentIndex = anchorIndex,
                    )
                } else if (reset) {
                    QueueSnapshot.queueUris(queue.map { it.uri })
                } else {
                    QueueSnapshot.newQueueContent(current?.item?.uri, queue.map { it.uri })
                }
                val albumUris = resolveAlbumUris(albumIds)
                val newAlbumUris = ActiveQueuePlanner.newAlbumUris(albumUris, upNext)
                val content = upNext + newAlbumUris
                Log.d(
                    "MainViewModel",
                    "Queue create: reset=$reset newFromCurrent=$newFromCurrent " +
                        "previous=${previous?.playlistId} " +
                        "previousContext=$previousContext stopped=$stoppedOnPrevious " +
                        "previousUris=${previousUris.size} " +
                        "queue=${queue.size} upNext=${upNext.size} content=${content.size} " +
                        "current=${current?.item?.uri} context=${current?.context?.uri}",
                )
                if (content.isEmpty()) {
                    if (reset) {
                        _activeQueuePlaylist.value = null
                        playlistRepository.clearLastQueuePlaylist()
                        previous?.let { deleteQueuePlaylist(it.playlistId) }
                        _activeQueueStatus.value = "No queue playlist yet"
                        _actionMessage.value = "Queue playlist reset. Nothing is queued."
                    } else {
                        _actionMessage.value =
                            "Nothing to queue - there is no current queue or selected album."
                    }
                    return@launch
                }

                val name = queuePlaylistName()
                val playlistId: String
                val playlistName: String
                if (remoteActive()) {
                    val created = dashboardRepository.createAndAddPlaylist(
                        name = name,
                        albumIds = emptySet(),
                        retentionUntil = null,
                        uris = content,
                    )
                    playlistId = created.playlist_id
                    playlistName = created.playlist_name.ifBlank { name }
                } else {
                    val created = playlistRepository.createPlaylistWithUris(
                        name = name,
                        uris = content,
                        retentionUntil = null,
                    )
                    playlistId = created.id
                    playlistName = created.name.ifBlank { name }
                }

                val actual = runCatching {
                    playlistRepository.getPlaylistTrackUris(playlistId)
                }.getOrElse { content }.ifEmpty { content }
                playlistRepository.setLastQueuePlaylist(playlistId, playlistName, actual)
                _activeQueuePlaylist.value = QueuePlaylistRef(playlistId, playlistName, actual)
                _activeQueueStatus.value =
                    "Queue playlist ready: \"$playlistName\" (${actual.size} tracks)"
                clearPendingSwitch()
                queueSwitchJob?.cancel()
                playlistRepository.cancelQueueSwitch()

                val playback = runCatching {
                    val device = pickPlaybackDevice(spotifyRepository.getDevices())
                        ?: throw Exception(
                            "Open Spotify on a device first - no device can play right now."
                        )
                    spotifyRepository.playPlaylistAsContext(playlistId, device.id, 0)
                    device.name.ifBlank { "the active device" }
                }
                if (playback.isSuccess) {
                    if ((reset || newFromCurrent) &&
                        previous != null &&
                        previous.playlistId != playlistId
                    ) {
                        deleteQueuePlaylist(previous.playlistId)
                    }
                    _actionMessage.value =
                        "Playing \"$playlistName\" on ${playback.getOrNull()}"
                } else {
                    _actionMessage.value =
                        "Created \"$playlistName\" but playback did not start: " +
                            (playback.exceptionOrNull()?.message ?: "unknown error")
                }
                runCatching {
                    _playlists.value = if (remoteActive()) {
                        dashboardRepository.listPlaylists().first
                    } else {
                        playlistRepository.getUserPlaylists()
                    }
                }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                refreshPlayback()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Queue playlist create failed", e)
                _actionMessage.value = playlistActionError("Queue playlist failed", e)
            } finally {
                _activeQueueBusy.value = false
                _queueToEndBusy.value = false
            }
        }
    }

    private suspend fun resolveAlbumUris(albumIds: Set<String>): List<String> {
        if (albumIds.isEmpty()) return emptyList()
        val uris = if (remoteActive()) {
            albumIds.flatMap { albumId ->
                dashboardRepository.albumTracks(albumId).map { it.uri }
            }
        } else {
            albumIds.flatMap { albumId ->
                playlistRepository.fetchAlbumTracks(albumId).map { it.uri }
            }
        }
        return uris.map { it.trim() }.filter { QueueSnapshot.isPlayable(it) }
    }

    private suspend fun ensureActiveQueuePersistent(active: QueuePlaylistRef) {
        runCatching {
            if (remoteActive()) {
                dashboardRepository.clearPlaylistRetention(active.playlistId)
            } else {
                playlistRepository.clearLocalRetention(active.playlistId)
            }
        }.onFailure {
            Log.w("MainViewModel", "Could not clear queue playlist retention", it)
        }
    }

    /**
     * Locate the current URI in the active playlist. The cursor advances past
     * an earlier occurrence of the same URI, so a deliberate duplicate later
     * in the playlist is not mistaken for the played prefix.
     */
    private fun resolveActiveQueueIndex(active: QueuePlaylistRef, currentUri: String?): Int {
        val uri = currentUri?.trim().orEmpty()
        if (uri.isEmpty()) return -1
        if (activeQueueCursorPlaylistId != active.playlistId) {
            activeQueueCursorPlaylistId = active.playlistId
            activeQueueCursorIndex = -1
            activeQueueCursorUri = null
        }
        val uris = active.uris
        val sameAsLast = activeQueueCursorUri == uri
        var index = if (sameAsLast) {
            activeQueueCursorIndex.coerceAtLeast(0)
        } else {
            (activeQueueCursorIndex + 1).coerceAtLeast(0)
        }
        var found = -1
        while (index in uris.indices) {
            if (uris[index].trim() == uri) {
                found = index
                break
            }
            index++
        }
        if (found < 0) {
            found = uris.indexOfFirst { it.trim() == uri }
        }
        if (found >= 0) {
            activeQueueCursorIndex = found
            activeQueueCursorUri = uri
        }
        return found
    }

    private fun updateActiveQueuePlaybackStatus(
        current: com.spotifydashboard.app.api.CurrentlyPlayingResponse?,
        queue: List<com.spotifydashboard.app.api.SpotifyPlaybackTrack>,
    ) {
        val active = _activeQueuePlaylist.value ?: return
        val ownContext = current?.context?.uri == "spotify:playlist:${active.playlistId}"
        if (!ownContext) {
            _activeQueueStatus.value =
                "Queue playlist ready: \"${active.name}\" (${active.uris.size} tracks) · " +
                    "not the current context"
            return
        }
        val currentUri = current?.item?.uri
        val index = resolveActiveQueueIndex(active, currentUri)
        if (index < 0) {
            _activeQueueStatus.value =
                "Queue playlist is the current context (${active.uris.size} tracks)"
            return
        }
        val tail = active.uris.drop(index + 1)
        val extras = ActiveQueuePlanner.extraUpcoming(queue.map { it.uri }, tail)
        val extraText = if (extras.isNotEmpty()) {
            " · ${extras.size} manual Spotify queue item(s) waiting"
        } else {
            ""
        }
        val trackName = current?.item?.name ?: "current track"
        val state = if (current?.is_playing == true) "Playing" else "Paused"
        _activeQueueStatus.value = "$state ${index + 1}/${active.uris.size}: $trackName$extraText"
    }

    /**
     * Append the up-next queue to the playlist that is currently playing.
     * Only allowed when the context is a playlist this account owns or
     * collaborates on; the current track is intentionally not duplicated.
     */
    fun appendQueueToCurrentPlaylist() {
        val state = _playbackState.value
        val playlistId = state.contextPlaylistId
        if (playlistId.isNullOrBlank()) {
            _actionMessage.value = "Playback is not from a playlist."
            return
        }
        if (!state.contextEditable) {
            _actionMessage.value = "This playlist is not editable from this account."
            return
        }
        val uris = QueueSnapshot.queueUris(state.queue.map { it.uri })
        if (uris.isEmpty()) {
            _actionMessage.value = "Nothing queued to append."
            return
        }
        val skipped = state.queue.count { it.uri.isNotBlank() } - uris.size
        viewModelScope.launch {
            _queuePlaylistBusy.value = true
            try {
                val added = if (remoteActive()) {
                    dashboardRepository.addTrackUrisToPlaylist(playlistId, uris)
                } else {
                    playlistRepository.addTrackUrisToPlaylist(playlistId, uris)
                }
                runCatching {
                    _playlists.value = if (remoteActive()) {
                        dashboardRepository.listPlaylists().first
                    } else {
                        playlistRepository.getUserPlaylists()
                    }
                }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                val skippedText = if (skipped > 0) " (skipped $skipped unsupported item(s))" else ""
                val target = state.contextName.ifBlank { "the current playlist" }
                _actionMessage.value = "Appended $added item(s) to \"$target\"$skippedText"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Append queue to playlist failed", e)
                _actionMessage.value = playlistActionError("Append failed", e)
            } finally {
                _queuePlaylistBusy.value = false
            }
        }
    }

    private fun playlistActionError(prefix: String, e: Exception): String {
        val raw = e.message.orEmpty()
        val denied = raw.contains("403") || raw.contains("Forbidden", ignoreCase = true) ||
            raw.contains("refused", ignoreCase = true) || raw.contains("permission", ignoreCase = true)
        return if (denied) {
            "$prefix: Spotify refused the edit. Only playlists you own or collaborate on can be changed."
        } else {
            "$prefix: ${raw.ifBlank { "unknown error" }}"
        }
    }

    // --- Queue playlist entry points -----------------------------------------

    /**
     * Compatibility entry point used by the album sheet and multi-select.
     * With no active playlist this asks for confirmation and creates one;
     * with an active playlist it appends to that same playlist.
     */
    fun queueToEnd(albumIds: Set<String> = emptySet()) {
        queueAlbumsToActiveQueue(albumIds)
    }

    /** Move playback over to the waiting queue playlist right now. */
    fun startPendingQueueSwitch() {
        val pending = _pendingQueueSwitch.value ?: return
        viewModelScope.launch {
            try {
                spotifyRepository.playPlaylistAsContext(pending.playlistId, pending.deviceId, 0)
                deleteQueuePlaylist(pending.previousPlaylistId)
                clearPendingSwitch()
                queueSwitchJob?.cancel()
                playlistRepository.cancelQueueSwitch()
                _actionMessage.value = "Playing \"${pending.name}\""
            } catch (e: Exception) {
                Log.e("MainViewModel", "Queue switch failed", e)
                _actionMessage.value = playlistActionError("Switch failed", e)
            }
        }
    }

    /** Name of the generated queue playlist: unique per reset, so leftovers are obvious. */
    private fun queuePlaylistName(): String {
        val stamp = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "Queue playlist \u00b7 $stamp"
    }

    /**
     * Wait for the current track to end, then hand playback over. The app
     * does it precisely; the worker is only the backstop for a locked phone.
     */
    private fun scheduleQueueSwitch(playlistId: String, deviceId: String?, remainingMs: Long) {
        queueSwitchJob?.cancel()
        queueSwitchJob = viewModelScope.launch {
            delay(maxOf(0L, remainingMs - SWITCH_LEAD_MS))
            runCatching { performPendingSwitch() }
                .onFailure { Log.w("MainViewModel", "Deferred queue switch failed", it) }
        }
        playlistRepository.enqueueQueueSwitch(remainingMs)
    }

    /**
     * The handover itself: only when the context is still the expected one.
     */
    private suspend fun performPendingSwitch() {
        val pending = _pendingQueueSwitch.value ?: return
        val current = spotifyRepository.getCurrentPlayback()
        val contextUri = current?.context?.uri.orEmpty()
        if (contextUri == "spotify:playlist:${pending.playlistId}") {
            clearPendingSwitch()          // already there
            return
        }
        if (pending.expectedContextUri.isNotBlank() && contextUri != pending.expectedContextUri) {
            // The user started something else - their choice wins.
            Log.d("MainViewModel", "Queue switch skipped, context changed")
            clearPendingSwitch()
            return
        }
        spotifyRepository.playPlaylistAsContext(
            pending.playlistId,
            switchDeviceId(pending.deviceId),
            0,
        )
        deleteQueuePlaylist(pending.previousPlaylistId)
        clearPendingSwitch()
        _actionMessage.value = "Playing \"${pending.name}\""
    }

    /**
     * The device that should get the handover: the one that is active *now*
     * (it can change while a track is playing), with the device that was
     * active when the request was made as a fallback.
     */
    private suspend fun switchDeviceId(fallback: String?): String? {
        val devices = runCatching { spotifyRepository.getDevices() }.getOrDefault(emptyList())
        val active = pickPlaybackDevice(devices)
        return active?.id ?: fallback
    }

    /**
     * Pick up a handover that was scheduled before the app was restarted
     * (the ViewModel is recreated on rotation, and the process can be killed
     * while the phone is locked).
     */
    private fun restorePendingQueueSwitch() {
        val pending = playlistRepository.pendingQueueSwitch() ?: return
        _pendingQueueSwitch.value = pending
        viewModelScope.launch {
            val current = runCatching { spotifyRepository.getCurrentPlayback() }.getOrNull()
            val remaining = currentRemainingMs(current)
            if (current == null || remaining <= SWITCH_NOW_THRESHOLD_MS) {
                runCatching { performPendingSwitch() }
            } else {
                scheduleQueueSwitch(pending.playlistId, pending.deviceId, remaining)
            }
        }
    }

    private fun clearPendingSwitch() {
        _pendingQueueSwitch.value = null
        playlistRepository.setPendingQueueSwitch(null)
    }

    /** Delete a queue playlist (Spotify has no hard delete: this is an unfollow). */
    private suspend fun deleteQueuePlaylist(playlistId: String?) {
        if (playlistId.isNullOrBlank()) return
        try {
            if (remoteActive()) {
                dashboardRepository.deletePlaylistNow(playlistId)
            } else {
                playlistRepository.unfollowPlaylist(playlistId)
                playlistRepository.clearLocalRetention(playlistId)
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not delete the previous queue playlist", e)
        }
    }

    /** How long the current track still has to play. */
    private fun currentRemainingMs(
        current: com.spotifydashboard.app.api.CurrentlyPlayingResponse?,
    ): Long {
        val duration = current?.item?.duration_ms ?: 0
        val progress = current?.progress_ms ?: 0
        if (duration <= 0) return 0L
        return (duration - progress).coerceAtLeast(0).toLong()
    }

    fun startPlaybackPolling() {
        stopPlaybackPolling()
        playbackJob = viewModelScope.launch {
            // Check this coroutine's own Job. Reading the playbackJob property
            // here raced with the assignment below: the loop could see the
            // previous (null/cancelled) job and exit before the first refresh.
            // Refresh sequentially so a slow request cannot stack up behind
            // the next tick.
            while (isActive) {
                refreshPlaybackNow()
                delay(5000)
            }
        }
    }

    fun stopPlaybackPolling() {
        playbackJob?.cancel()
        playbackJob = null
    }

    // ── Bulk removal from the open playlist ────────────────────────────────

    fun removeSelectedAlbumsFromPlaylist() {
        val playlist = _selectedPlaylist.value ?: return
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val removed = if (remoteActive()) {
                    dashboardRepository.removeAlbumsFromPlaylist(playlist.id, ids)
                } else {
                    playlistRepository.removeAlbumsFromPlaylist(playlist.id, ids)
                }
                clearSelection()
                _actionMessage.value =
                    "Removed $removed track(s) from \"${playlist.name}\""
                runPlaylistQuery()
                if (remoteActive()) {
                    _playlists.value = dashboardRepository.listPlaylists().first
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Bulk remove failed", e)
                _actionMessage.value = "Remove failed: ${e.message}"
            }
        }
    }

    /**
     * Delete the selected albums from the Spotify saved library and the local
     * board. The UI confirms first because this is irreversible.
     */
    fun removeSelectedAlbumsFromLibrary() {
        if (remoteActive()) {
            _actionMessage.value = "Deleting from the server library is available in local mode for now"
            return
        }
        val ids = _selectedAlbums.value
        if (ids.isEmpty() || _libraryDeleteBusy.value) return
        if (librarySyncJob?.isActive == true) {
            _actionMessage.value = "Stop the library sync before deleting albums"
            return
        }
        if (!spotifyRepository.hasLibraryModifyScope()) {
            _actionMessage.value =
                "Spotify library access is missing. Log out and sign in again, then retry."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _libraryDeleteBusy.value = true
            try {
                val removed = spotifyRepository.removeSavedAlbumsPermanentlyByIds(ids)
                clearSelection()
                _actionMessage.value =
                    "Deleted $removed album${if (removed == 1) "" else "s"} from your Spotify library"
                loadLibraryFilterOptions()
                runLibraryQuery()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Library delete failed", e)
                _actionMessage.value = "Could not delete albums: ${e.message}"
                // A partial chunk failure may already have removed some rows.
                loadLibraryFilterOptions()
                runLibraryQuery()
            } finally {
                _libraryDeleteBusy.value = false
            }
        }
    }

    // ── Shortlived playlists ────────────────────────────────────────────────

    /** `expiresAt` is an ISO-8601 UTC string, or null to keep forever. */
    fun setPlaylistRetention(expiresAt: String?) {
        val playlist = _selectedPlaylist.value ?: return
        viewModelScope.launch {
            _retentionBusy.value = true
            try {
                if (remoteActive()) {
                    if (expiresAt == null) {
                        dashboardRepository.clearPlaylistRetention(playlist.id)
                    } else {
                        dashboardRepository.setPlaylistRetention(playlist.id, expiresAt)
                    }
                } else {
                    if (expiresAt == null) {
                        playlistRepository.clearLocalRetention(playlist.id)
                    } else {
                        playlistRepository.setLocalRetention(
                            playlist.id, playlist.name, expiresAt,
                        )
                    }
                }
                _playlistExpiresAt.value = expiresAt
                _actionMessage.value = if (expiresAt == null)
                    "Playlist will be kept forever"
                else
                    "Playlist deletes at ${expiresAt.replace("T", " ").replace("Z", " UTC")}"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Retention update failed", e)
                _actionMessage.value = "Retention update failed: ${e.message}"
            } finally {
                _retentionBusy.value = false
            }
        }
    }

    fun deletePlaylistNow() {
        val playlist = _selectedPlaylist.value ?: return
        viewModelScope.launch {
            _retentionBusy.value = true
            try {
                if (remoteActive()) {
                    dashboardRepository.deletePlaylistNow(playlist.id)
                } else {
                    playlistRepository.unfollowPlaylist(playlist.id)
                    playlistRepository.clearLocalRetention(playlist.id)
                }
                _playlistExpiresAt.value = null
                _selectedPlaylist.value = null
                _playlistAlbumsList.value = emptyList()
                _playlistTotal.value = 0
                _playlistHasMore.value = false
                loadPlaylists()
                _actionMessage.value = "\"${playlist.name}\" removed from Spotify"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Delete playlist failed", e)
                _actionMessage.value = "Delete failed: ${e.message}"
            } finally {
                _retentionBusy.value = false
            }
        }
    }

    /**
     * Flip a playlist's public/private flag and refresh the list source of
     * truth. Mirrors the delete path: local via Spotify direct, remote via
     * the server's /playlists/{id}/visibility endpoint (additive).
     */
    fun setPlaylistPublic(playlist: com.spotifydashboard.app.api.SpotifyPlaylist, public: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val hadPublic = playlist.public
            // Optimistic update so the toggle feels instant.
            _playlists.value = _playlists.value.map { if (it.id == playlist.id) it.copy(public = public) else it }
            try {
                if (remoteActive()) {
                    dashboardRepository.setPlaylistPublic(playlist.id, public)
                } else {
                    playlistRepository.setPlaylistPublic(playlist.id, public)
                }
                _actionMessage.value = "is now " + (if (public) "public" else "private")
            } catch (e: Exception) {
                // Revert on failure.
                _playlists.value = _playlists.value.map { if (it.id == playlist.id) it.copy(public = hadPublic) else it }
                Log.e("MainViewModel", "setPlaylistPublic failed", e)
                _actionMessage.value = "Could not change visibility: ${e.message}"
            }
        }
    }

    /** Batch-unfollow the given playlists (local + remote). Mirrors deletePlaylistNow. */
    fun deletePlaylists(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ids.forEach { id ->
                    if (remoteActive()) {
                        dashboardRepository.deletePlaylistNow(id)
                    } else {
                        playlistRepository.unfollowPlaylist(id)
                        playlistRepository.clearLocalRetention(id)
                    }
                }
                loadPlaylists()
                _actionMessage.value = "Removed ${ids.size} playlist${if (ids.size == 1) "" else "s"}"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Batch delete playlists failed", e)
                _actionMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun toggleAlbumSelection(albumId: String) {
        _selectedAlbums.value = if (_selectedAlbums.value.contains(albumId)) {
            _selectedAlbums.value - albumId
        } else {
            _selectedAlbums.value + albumId
        }
    }

    /**
     * Select-all / deselect-all for the currently visible albums
     * (mirrors TS useSelection toggleAll).
     */
    fun toggleAllVisible(ids: List<String>) {
        _selectedAlbums.value = if (ids.isNotEmpty() && ids.all { it in _selectedAlbums.value }) {
            _selectedAlbums.value - ids.toSet()
        } else {
            _selectedAlbums.value + ids.toSet()
        }
    }

    fun clearSelection() {
        _selectedAlbums.value = emptySet()
    }

    // --- Queue selected albums (mirrors TS handleQueue toast logic) ---
    fun queueSelectedAlbums() {
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                // Remote: single server job (no cap), polled to completion.
                val result = if (remoteActive()) dashboardRepository.queueAlbums(ids)
                else spotifyRepository.queueAlbums(ids)
                if (result.success == 0) {
                    _actionMessage.value = result.error ?: "Open Spotify on a device first"
                } else {
                    clearSelection()
                    _actionMessage.value = "Added ${result.success} tracks to queue" +
                        (if (result.failed > 0) " (${result.failed} failed)" else "")
                }
                Log.d("MainViewModel", "Queue result: $result")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Queue failed", e)
                _actionMessage.value = e.message ?: "Queue failed"
            }
        }
    }

    /**
     * "Queue to end" for the albums the user selected: the current queue plus
     * the selection become one playlist, and playback continues there.
     */
    fun queueSelectedAlbumsToEnd() {
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) return
        queueAlbumsToActiveQueue(ids)
    }

    // --- Playlist picker ---
    fun requestAddToPlaylist() {
        if (_selectedAlbums.value.isEmpty()) return
        if (_playlists.value.isEmpty()) loadPlaylists()
        _showPlaylistPicker.value = true
    }

    fun addSelectedToPlaylist(playlistId: String) {
        val ids = _selectedAlbums.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                // Remote: server resolves album_ids (no device Spotify token needed).
                if (remoteActive()) dashboardRepository.addAlbumsToPlaylist(ids, playlistId)
                else playlistRepository.addAlbumsToPlaylist(ids, playlistId)
                _showPlaylistPicker.value = false
                clearSelection()
                _actionMessage.value = "Added ${ids.size} album(s) to playlist"
                Log.d("MainViewModel", "Added ${ids.size} albums to playlist $playlistId")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Add to playlist failed", e)
                _actionMessage.value = "Add to playlist failed: ${e.message}"
            }
        }
    }

    fun dismissPlaylistPicker() {
        _showPlaylistPicker.value = false
        clearSelection()
    }

    // --- Create new playlist ---

    fun requestCreatePlaylist() {
        _showCreatePlaylistDialog.value = true
    }

    fun dismissCreatePlaylistDialog() {
        _showCreatePlaylistDialog.value = false
    }

    fun createAndAddToPlaylist(name: String, retentionUntil: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _actionMessage.value = "Playlist name cannot be empty"
            return
        }
        val ids = _selectedAlbums.value
        viewModelScope.launch {
            _isCreatingPlaylist.value = true
            try {
                // Remote: create + add server-side in one call.
                if (remoteActive()) {
                    val created = dashboardRepository.createAndAddPlaylist(trimmed, ids, retentionUntil)
                    Log.d("MainViewModel", "Created remote playlist: ${created.playlist_id}")
                    _playlists.value = dashboardRepository.listPlaylists().first
                    _showCreatePlaylistDialog.value = false
                    _showPlaylistPicker.value = false
                    clearSelection()
                    _actionMessage.value = if (ids.isNotEmpty())
                        "Created \"${created.playlist_name}\" and added ${created.tracks_added} tracks" +
                            (if (retentionUntil != null) " · deletes ${created.expires_at ?: retentionUntil}" else "")
                    else
                        "Created playlist \"${created.playlist_name}\""
                    return@launch
                }
                val newPlaylist = playlistRepository.createPlaylist(trimmed)
                Log.d("MainViewModel", "Created playlist: ${newPlaylist.id} — ${newPlaylist.name}")

                if (ids.isNotEmpty()) {
                    playlistRepository.addAlbumsToPlaylist(ids, newPlaylist.id)
                    Log.d("MainViewModel", "Added ${ids.size} albums to new playlist ${newPlaylist.id}")
                }
                if (retentionUntil != null) {
                    playlistRepository.setLocalRetention(
                        newPlaylist.id, newPlaylist.name, retentionUntil,
                    )
                }

                _playlists.value = playlistRepository.getUserPlaylists()

                _showCreatePlaylistDialog.value = false
                _showPlaylistPicker.value = false
                clearSelection()
                _actionMessage.value = if (ids.isNotEmpty())
                    "Created \"${newPlaylist.name}\" and added ${ids.size} album(s)" +
                        (if (retentionUntil != null) " · deletes $retentionUntil" else "")
                else
                    "Created playlist \"${newPlaylist.name}\""
            } catch (e: Exception) {
                Log.e("MainViewModel", "Create playlist failed", e)
                _actionMessage.value = "Failed to create playlist: ${e.message}"
            } finally {
                _isCreatingPlaylist.value = false
            }
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    // --- Media server account -------------------------------------------------
    // Local mode ignores all of this; remote mode needs a server session before
    // any server call (the login screen gates on hasServerSession()).

    fun hasServerSession(): Boolean = dashboardRepository.hasSession()

    fun setServerMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dashboardRepository.setMode(mode)
            withContext(Dispatchers.Main) { resetForDataSourceSwitch() }
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dashboardRepository.setServerUrl(url)
            refreshDataSourceState()
            withContext(Dispatchers.Main) { resetForDataSourceSwitch() }
        }
    }

    /** Switching backends drops all loaded state — the user re-queries. */
    private fun resetForDataSourceSwitch() {
        clearSelection()
        // The remote API does not expose Spotify album_type, so do not leave a
        // hidden Albums/Singles filter active when the data source changes.
        if (dashboardRepository.isRemote()) {
            _libraryFilters.value = _libraryFilters.value.copy(albumType = "")
        }
        _selectedPlaylist.value = null
        _libraryAlbumsList.value = emptyList()
        _libraryTotal.value = 0
        _libraryHasMore.value = false
        _playlists.value = emptyList()
        _playlistAlbumsList.value = emptyList()
        _playlistTotal.value = 0
        _librarySyncState.value = SyncState.Idle
        _syncHistory.value = null
        refreshDataSourceState()
        loadLibraryFilterOptions()
        runLibraryQuery()
    }

    fun testServerUrl(url: String) {
        viewModelScope.launch {
            _serverBusy.value = true
            _serverTestResult.value = null
            try {
                val ok = dashboardRepository.testConnection(url)
                _serverTestResult.value = if (ok) "✓ Server reachable" else "✕ No healthy response"
            } catch (e: Exception) {
                _serverTestResult.value = "✕ " + (e.message ?: "Connection failed")
            } finally {
                _serverBusy.value = false
            }
        }
    }

    fun clearServerTestResult() {
        _serverTestResult.value = null
    }

    fun clearServerAuthError() {
        _serverAuthError.value = null
    }

    fun setServerAuthError(message: String?) {
        _serverAuthError.value = message
    }

    fun serverLogin(email: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _serverBusy.value = true
            _serverAuthError.value = null
            try {
                val who = dashboardRepository.login(email.trim(), password)
                _dataSource.value = _dataSource.value.copy(email = who, hasSession = true)
                refreshDataSourceState()
                resetForDataSourceSwitch()
                onDone(true)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Server login failed", e)
                _serverAuthError.value = e.message ?: "Login failed"
                onDone(false)
            } finally {
                _serverBusy.value = false
            }
        }
    }

    fun serverRegister(email: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _serverBusy.value = true
            _serverAuthError.value = null
            try {
                val who = dashboardRepository.register(email.trim(), password)
                _dataSource.value = _dataSource.value.copy(email = who, hasSession = true)
                refreshDataSourceState()
                resetForDataSourceSwitch()
                onDone(true)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Server register failed", e)
                _serverAuthError.value = e.message ?: "Registration failed"
                onDone(false)
            } finally {
                _serverBusy.value = false
            }
        }
    }

    fun serverLogout() {
        viewModelScope.launch {
            try {
                dashboardRepository.logout()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Server logout error", e)
            }
            refreshDataSourceState()
            resetForDataSourceSwitch()
        }
    }

    fun refreshSpotifyLink() {
        refreshDataSourceState()
    }

    /**
     * Phone-first sync: make the signed-in server account mirror this phone's
     * on-device library. The server needs no Spotify link for this — it just
     * receives the CSV. Its own server-side Spotify sync stays available
     * separately (e.g. from the web UI).
     */
    fun pushLocalLibraryToServer() {
        if (!dashboardRepository.hasSession()) {
            _actionMessage.value = "Sign in to the media server first"
            return
        }
        viewModelScope.launch {
            _pushBusy.value = true
            try {
                val csv = spotifyRepository.buildLibraryCsv()
                if (csv == null) {
                    _actionMessage.value =
                        "This phone has no local library yet — sync locally first"
                    return@launch
                }
                val result = dashboardRepository.importCsvText(csv, replace = true)
                val kept = result.imported + result.existing
                _actionMessage.value = "Pushed $kept albums" +
                    (if (result.removed > 0) ", removed ${result.removed}" else "") +
                    (if (result.errors.isNotEmpty()) " · ${result.errors.size} warning(s)" else "")
                if (remoteActive()) {
                    loadLibraryFilterOptions()
                    runLibraryQuery()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Push to server failed", e)
                _actionMessage.value = "Push failed: ${e.message}"
            } finally {
                _pushBusy.value = false
            }
        }
    }

    /** Fetches the server's Spotify login URL; the Activity opens it in a browser. */
    fun fetchSpotifyLinkUrl() {
        viewModelScope.launch {
            _serverBusy.value = true
            try {
                _pendingBrowserUrl.value = dashboardRepository.spotifyAuthUrl()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Spotify link URL failed", e)
                _actionMessage.value = e.message ?: "Could not start Spotify linking"
            } finally {
                _serverBusy.value = false
            }
        }
    }

    private val _pendingBrowserUrl = MutableStateFlow<String?>(null)
    val pendingBrowserUrl: StateFlow<String?> = _pendingBrowserUrl.asStateFlow()

    fun consumeBrowserUrl() {
        _pendingBrowserUrl.value = null
    }

    fun unlinkSpotify() {
        viewModelScope.launch {
            try {
                dashboardRepository.disconnectSpotify()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Spotify unlink error", e)
            }
            refreshDataSourceState()
        }
    }

    // --- CSV import (mirrors TS syncCSV) ---

    fun importCsvText(csvText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = if (remoteActive()) dashboardRepository.importCsvText(csvText)
                else spotifyRepository.importCsvText(csvText)
                _csvImportResult.value = result
                Log.d("MainViewModel", "CSV import: $result")
                loadLibraryFilterOptions()
                runLibraryQuery()
            } catch (e: Exception) {
                Log.e("MainViewModel", "CSV import failed", e)
                _csvImportResult.value = CsvImportResult(0, 0, listOf(e.message ?: "Import failed"))
            }
        }
    }

    fun clearCsvImportResult() {
        _csvImportResult.value = null
    }

    // --- Duplicate cleanup (card 269) ------------------------------------

    /**
     * Scan the local library for likely duplicate releases. Remote mode is
     * deliberately read-only for now: a server-side hidden store is needed
     * before deleting server albums would be durable.
     */
    fun findDuplicates() {
        if (remoteActive()) {
            _duplicateGroups.value = emptyList()
            _hiddenAlbums.value = emptyList()
            _actionMessage.value = "Duplicate cleanup is available in local mode for now"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _duplicatesLoading.value = true
            try {
                val albums = spotifyRepository.getAllAlbums()
                val groups = DuplicateFinder.find(albums)
                _duplicateGroups.value = groups
                _hiddenAlbums.value = spotifyRepository.getHiddenAlbums()
                Log.d("MainViewModel", "Duplicate scan: ${groups.size} groups in ${albums.size} albums")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Duplicate scan failed", e)
                _duplicateGroups.value = emptyList()
                _actionMessage.value = "Could not scan for duplicates: ${e.message}"
            } finally {
                _duplicatesLoading.value = false
            }
        }
    }

    /**
     * card 344 - Follow all artists found in the Spotify library.
     * Scans liked songs and/or saved albums, dedupes artist ids,
     * skips already-followed artists, then follows the rest.
     * Requires user-follow-read / user-follow-modify scopes (re-login after
     * this scope change before use).
     */
    fun followLibraryArtists(source: String = "both", dryRun: Boolean = false) {
        if (remoteActive()) {
            _actionMessage.value = "Follow library artists requires local Spotify auth"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _followArtistsBusy.value = true
            try {
                val result = spotifyRepository.followLibraryArtists(source, dryRun)
                _actionMessage.value = if (dryRun) {
                    "Dry run: ${result.notFollowing} of ${result.uniqueArtists} artists would be followed"
                } else {
                    "Followed ${result.followed} of ${result.uniqueArtists} artists (${result.notFollowing} were new)"
                }
                Log.d("MainViewModel", "Follow artists result=$result")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Follow library artists failed", e)
                _actionMessage.value = "Could not follow artists: ${e.message}"
            } finally {
                _followArtistsBusy.value = false
            }
        }
    }

    // --- Spotify catalogue playlists (Tools) ------------------------------

    fun resetSpotifySearchPlaylist() {
        spotifyAlbumSearchJob?.cancel()
        spotifyAlbumSearchJob = null
        catalogLibraryJob?.cancel()
        catalogLibraryJob = null
        _spotifySearchPlaylist.value = SpotifySearchPlaylistState()
        _catalogPlaylistProgress.value = null
        _catalogPlaylistOutcome.value = null
        _catalogLibraryProgress.value = null
        _catalogLibraryOutcome.value = null
    }

    fun resetArtistDiscography() {
        discographyArtistSearchJob?.cancel()
        discographyArtistSearchJob = null
        discographyAlbumsJob?.cancel()
        discographyAlbumsJob = null
        catalogLibraryJob?.cancel()
        catalogLibraryJob = null
        _artistDiscography.value = ArtistDiscographyState()
        _catalogPlaylistProgress.value = null
        _catalogPlaylistOutcome.value = null
        _catalogLibraryProgress.value = null
        _catalogLibraryOutcome.value = null
    }

    fun clearCatalogPlaylistOutcome() {
        _catalogPlaylistOutcome.value = null
        _catalogPlaylistProgress.value = null
    }

    fun clearCatalogLibraryOutcome() {
        _catalogLibraryOutcome.value = null
        _catalogLibraryProgress.value = null
    }

    fun searchSpotifyAlbums(query: String) {
        _catalogLibraryOutcome.value = null
        _catalogPlaylistOutcome.value = null
        val q = query.trim()
        if (q.isEmpty()) {
            _spotifySearchPlaylist.value = _spotifySearchPlaylist.value.copy(
                query = "",
                results = emptyList(),
                loading = false,
                error = "Type an album or artist name first",
                hasSearched = false,
            )
            return
        }
        if (remoteActive()) {
            _spotifySearchPlaylist.value = _spotifySearchPlaylist.value.copy(
                query = q,
                results = emptyList(),
                loading = false,
                error = "Spotify search requires local Spotify login",
                hasSearched = false,
            )
            return
        }
        spotifyAlbumSearchJob?.cancel()
        spotifyAlbumSearchJob = viewModelScope.launch {
            _spotifySearchPlaylist.value = SpotifySearchPlaylistState(query = q, loading = true)
            try {
                val results = spotifyRepository.searchCatalogAlbums(q)
                _spotifySearchPlaylist.value = SpotifySearchPlaylistState(
                    query = q,
                    results = results,
                    loading = false,
                    hasSearched = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Spotify album search failed", e)
                _spotifySearchPlaylist.value = SpotifySearchPlaylistState(
                    query = q,
                    loading = false,
                    error = e.message ?: "Spotify search failed",
                    hasSearched = true,
                )
            }
        }
    }

    fun searchDiscographyArtists(query: String) {
        _catalogLibraryOutcome.value = null
        _catalogPlaylistOutcome.value = null
        val q = query.trim()
        if (q.isEmpty()) {
            _artistDiscography.value = _artistDiscography.value.copy(
                query = "",
                artists = emptyList(),
                selectedArtist = null,
                albums = emptyList(),
                loadingArtists = false,
                error = "Type an artist name first",
                hasSearched = false,
            )
            return
        }
        if (remoteActive()) {
            _artistDiscography.value = _artistDiscography.value.copy(
                query = q,
                artists = emptyList(),
                selectedArtist = null,
                albums = emptyList(),
                loadingArtists = false,
                error = "Artist discography requires local Spotify login",
                hasSearched = false,
            )
            return
        }
        discographyAlbumsJob?.cancel()
        discographyArtistSearchJob?.cancel()
        discographyArtistSearchJob = viewModelScope.launch {
            _artistDiscography.value = ArtistDiscographyState(query = q, loadingArtists = true)
            try {
                val artists = spotifyRepository.searchCatalogArtists(q)
                _artistDiscography.value = ArtistDiscographyState(
                    query = q,
                    artists = artists,
                    loadingArtists = false,
                    hasSearched = true,
                    includeAlbums = _artistDiscography.value.includeAlbums,
                    includeSingles = _artistDiscography.value.includeSingles,
                    includeAppearsOn = _artistDiscography.value.includeAppearsOn,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Spotify artist search failed", e)
                _artistDiscography.value = ArtistDiscographyState(
                    query = q,
                    loadingArtists = false,
                    error = e.message ?: "Artist search failed",
                    hasSearched = true,
                    includeAlbums = _artistDiscography.value.includeAlbums,
                    includeSingles = _artistDiscography.value.includeSingles,
                    includeAppearsOn = _artistDiscography.value.includeAppearsOn,
                )
            }
        }
    }

    fun selectDiscographyArtist(artist: SpotifyCatalogArtist) {
        _catalogLibraryOutcome.value = null
        _catalogPlaylistOutcome.value = null
        _artistDiscography.value = _artistDiscography.value.copy(
            selectedArtist = artist,
            albums = emptyList(),
            error = null,
        )
        loadArtistDiscographyAlbums()
    }

    fun clearDiscographyArtist() {
        discographyAlbumsJob?.cancel()
        discographyAlbumsJob = null
        _artistDiscography.value = _artistDiscography.value.copy(
            selectedArtist = null,
            albums = emptyList(),
            loadingAlbums = false,
            error = null,
        )
    }

    fun setDiscographyGroup(group: String, enabled: Boolean) {
        val current = _artistDiscography.value
        val updated = when (group) {
            "album" -> current.copy(includeAlbums = enabled)
            "single" -> current.copy(includeSingles = enabled)
            "appears_on" -> current.copy(includeAppearsOn = enabled)
            else -> return
        }
        _artistDiscography.value = updated
        if (updated.selectedArtist == null) return
        if (updated.selectedGroups().isEmpty()) {
            discographyAlbumsJob?.cancel()
            discographyAlbumsJob = null
            _artistDiscography.value = updated.copy(
                albums = emptyList(),
                loadingAlbums = false,
                error = "Choose Albums, Singles or Appears on",
            )
            return
        }
        loadArtistDiscographyAlbums()
    }

    private fun loadArtistDiscographyAlbums() {
        val state = _artistDiscography.value
        val artist = state.selectedArtist ?: return
        val groups = state.selectedGroups()
        if (groups.isEmpty()) return
        discographyAlbumsJob?.cancel()
        discographyAlbumsJob = viewModelScope.launch {
            _artistDiscography.value = _artistDiscography.value.copy(
                albums = emptyList(),
                loadingAlbums = true,
                error = null,
            )
            try {
                val albums = spotifyRepository.getArtistCatalogAlbums(artist.id, groups)
                _artistDiscography.value = _artistDiscography.value.copy(
                    albums = albums,
                    loadingAlbums = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Artist discography load failed", e)
                _artistDiscography.value = _artistDiscography.value.copy(
                    albums = emptyList(),
                    loadingAlbums = false,
                    error = e.message ?: "Could not load the discography",
                )
            }
        }
    }

    /**
     * Save the selected catalogue releases to the user's Spotify library and
     * mirror them into the dashboard's local library. Duplicate IDs are
     * collapsed; Spotify treats the save as idempotent.
     */
    fun addCatalogAlbumsToLibrary(albums: List<SpotifyCatalogAlbum>) {
        if (_catalogPlaylistBusy.value || _catalogLibraryBusy.value) return
        val unique = albums.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (unique.isEmpty()) {
            _catalogLibraryOutcome.value = CatalogLibraryOutcome(error = "Select at least one release")
            return
        }
        if (remoteActive()) {
            _catalogLibraryOutcome.value = CatalogLibraryOutcome(
                error = "Adding to the Spotify library requires local Spotify login",
            )
            return
        }
        if (!spotifyRepository.hasLibraryModifyScope()) {
            _catalogLibraryOutcome.value = CatalogLibraryOutcome(
                error = "Spotify library access is missing. Log out and sign in again to grant it.",
            )
            return
        }
        _catalogLibraryBusy.value = true
        _catalogLibraryProgress.value = CatalogLibraryProgress(0, unique.size, "Saving to Spotify")
        _catalogLibraryOutcome.value = null
        _catalogPlaylistOutcome.value = null
        catalogLibraryJob = viewModelScope.launch {
            try {
                val result = spotifyRepository.addCatalogAlbumsToLibrary(unique) { done, total, stage ->
                    _catalogLibraryProgress.value = CatalogLibraryProgress(done, total, stage)
                }
                _catalogLibraryOutcome.value = CatalogLibraryOutcome(
                    albumsAdded = result.saved,
                    albumsImported = result.imported,
                )
                _actionMessage.value = "Added ${result.saved} release${
                    if (result.saved == 1) "" else "s"
                } to your Spotify library"
                loadLibraryFilterOptions()
                runLibraryQuery()
                Log.d("MainViewModel", "Saved ${result.saved} catalogue releases to the library")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Saving catalogue releases to the library failed", e)
                _catalogLibraryOutcome.value = CatalogLibraryOutcome(
                    error = e.message ?: "Could not add the releases to your library",
                )
                // A partial save may have imported some rows before Spotify
                // stopped; refresh the board so those are visible.
                loadLibraryFilterOptions()
                runLibraryQuery()
            } finally {
                _catalogLibraryBusy.value = false
                _catalogLibraryProgress.value = null
            }
        }
    }

    /**
     * Create a private playlist from catalogue albums. [albums] is already
     * filtered by the dialogs (including the user's dedupe decision).
     */
    fun createCatalogPlaylist(
        name: String,
        albums: List<SpotifyCatalogAlbum>,
        deduplicate: Boolean,
    ) {
        // Guard against a fast double confirm before Compose can disable the
        // button; a second job would create a duplicate playlist.
        if (_catalogPlaylistBusy.value || _catalogLibraryBusy.value) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _catalogPlaylistOutcome.value = CatalogPlaylistOutcome(error = "Enter a playlist name")
            return
        }
        if (albums.isEmpty()) {
            _catalogPlaylistOutcome.value = CatalogPlaylistOutcome(error = "Select at least one album")
            return
        }
        if (remoteActive()) {
            _catalogPlaylistOutcome.value = CatalogPlaylistOutcome(
                error = "Playlist creation requires local Spotify login",
            )
            return
        }
        val ids = albums.map { it.id }.distinct()
        _catalogPlaylistBusy.value = true
        _catalogPlaylistProgress.value = CatalogPlaylistProgress(0, ids.size, 0)
        _catalogPlaylistOutcome.value = null
        _catalogLibraryOutcome.value = null
        viewModelScope.launch {
            try {
                val result = playlistRepository.createPlaylistFromAlbumIds(
                    name = trimmed,
                    albumIds = ids,
                    deduplicate = deduplicate,
                    onProgress = { done, total, tracks ->
                        _catalogPlaylistProgress.value = CatalogPlaylistProgress(done, total, tracks)
                    },
                )
                _catalogPlaylistOutcome.value = CatalogPlaylistOutcome(
                    playlistId = result.playlistId,
                    playlistName = result.playlistName,
                    albumsAdded = result.albumsAdded,
                    tracksAdded = result.tracksAdded,
                    duplicatesSkipped = result.duplicatesSkipped,
                )
                val duplicateText = if (result.duplicatesSkipped > 0) {
                    ", ${result.duplicatesSkipped} duplicate track${if (result.duplicatesSkipped == 1) "" else "s"} skipped"
                } else {
                    ""
                }
                _actionMessage.value =
                    "Created private playlist \"${result.playlistName}\" with ${result.tracksAdded} tracks$duplicateText"
                runCatching {
                    // getUserPlaylists() returns an empty list on a transient
                    // failure, so never replace a populated grid with it.
                    val refreshed = playlistRepository.getUserPlaylists()
                    if (refreshed.isNotEmpty()) _playlists.value = refreshed
                }.onFailure { Log.w("MainViewModel", "Playlist refresh failed", it) }
                Log.d("MainViewModel", "Created catalogue playlist ${result.playlistId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Creating catalogue playlist failed", e)
                _catalogPlaylistOutcome.value = CatalogPlaylistOutcome(
                    error = e.message ?: "Could not create the playlist",
                )
            } finally {
                _catalogPlaylistBusy.value = false
                _catalogPlaylistProgress.value = null
            }
        }
    }

    /** Hide the selected copies, then rescan so the dialog shows the result. */
    fun removeDuplicateAlbums(albums: List<AlbumEntity>) {
        if (albums.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hidden = spotifyRepository.hideAlbums(albums)
                _actionMessage.value = "Removed $hidden duplicate album${if (hidden == 1) "" else "s"} from this device"
                loadLibraryFilterOptions()
                runLibraryQuery()
                findDuplicates()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Removing duplicates failed", e)
                _actionMessage.value = "Could not remove duplicates: ${e.message}"
            }
        }
    }

    /** Remove duplicate copies permanently from the Spotify online library. */
    fun removeDuplicateAlbumsPermanently(albums: List<AlbumEntity>) {
        if (remoteActive()) {
            _actionMessage.value = "Permanent removal is available in local mode for now"
            return
        }
        if (albums.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val removed = spotifyRepository.removeSavedAlbumsPermanently(albums)
                _actionMessage.value = "Permanently removed $removed album${
                    if (removed == 1) "" else "s"
                } from Spotify"
                loadLibraryFilterOptions()
                runLibraryQuery()
                findDuplicates()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Permanent duplicate removal failed", e)
                _actionMessage.value = "Could not remove from Spotify: ${e.message}"
            }
        }
    }

    fun restoreHiddenAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val restored = spotifyRepository.restoreHiddenAlbums()
                _actionMessage.value = "Restored $restored hidden album${if (restored == 1) "" else "s"}"
                loadLibraryFilterOptions()
                runLibraryQuery()
                findDuplicates()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Restoring duplicates failed", e)
                _actionMessage.value = "Could not restore albums: ${e.message}"
            }
        }
    }

    fun clearDuplicateScan() {
        _duplicateGroups.value = null
        _hiddenAlbums.value = emptyList()
    }

    // --- Add from site (card 270) ---------------------------------------

    /**
     * Resolve the given Spotify album IDs via the phone's Spotify token and
     * add them to the active library (local Room or server import). Returns
     * through [_actionMessage] and [_csvImportResult].
     */
    fun addAlbumsFromSite(ids: List<String>) {
        val unique = ids.distinct().filter { it.isNotBlank() }
        if (unique.isEmpty()) {
            _actionMessage.value = "No albums selected"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _addingFromSite.value = true
            try {
                spotifyRepository.unhideAlbums(unique)
                val entities = unique.mapNotNull { spotifyRepository.fetchAlbumEntity(it) }
                if (entities.isEmpty()) {
                    _actionMessage.value =
                        "Could not look up those albums. Log in to Spotify on this device and try again."
                    return@launch
                }
                val csv = CSVHelper.buildCsv(
                    listOf("id", "title", "artist", "year", "genre", "image_url", "added_at"),
                    entities.map { e ->
                        listOf(e.id, e.title, e.artist, e.year, e.genre, e.imageUrl, e.addedAt)
                    }
                )
                val result = if (remoteActive()) dashboardRepository.importCsvText(csv)
                else spotifyRepository.importCsvText(csv)
                _csvImportResult.value = result
                loadLibraryFilterOptions()
                runLibraryQuery()
                val added = result.imported + result.existing
                _actionMessage.value = "Added $added album${if (added == 1) "" else "s"} to the library" +
                    (if (result.errors.isNotEmpty()) " (${result.errors.size} skipped)" else "")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Add from site failed", e)
                _csvImportResult.value = CsvImportResult(0, 0, listOf(e.message ?: "Import failed"))
                _actionMessage.value = "Could not add albums: ${e.message}"
            } finally {
                _addingFromSite.value = false
            }
        }
    }

    // --- Backup (mirrors TS useBackupSettings + ExportService) ---

    fun toggleBackup() {
        viewModelScope.launch {
            val next = _backupSettings.value.copy(enabled = !_backupSettings.value.enabled)
            _backupSettings.value = next
            spotifyRepository.saveBackupSettings(next)
        }
    }

    fun setBackupSubdir(subdir: String) {
        viewModelScope.launch {
            val next = _backupSettings.value.copy(subdirName = subdir)
            _backupSettings.value = next
            spotifyRepository.saveBackupSettings(next)
        }
    }

    fun setGridSize(size: Int) {
        val coerced = size.coerceIn(80, 280)
        _gridSize.value = coerced
        viewModelScope.launch {
            spotifyRepository.saveGridSize(coerced)
        }
    }

    /** Delete the local library (destructive — UI must confirm first). */
    fun clearLibrary() {        viewModelScope.launch {
            try {
                if (remoteActive()) dashboardRepository.clearRemoteLibrary()
                else spotifyRepository.clearLibrary()
                clearSelection()
                _librarySyncState.value = SyncState.Idle
                loadLibraryFilterOptions()
                runLibraryQuery()
                _actionMessage.value = if (remoteActive()) "Server library cleared" else "Local library cleared"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Clear library failed", e)
                _actionMessage.value = "Clear failed: ${e.message}"
            }
        }
    }

    private suspend fun recordBackup(path: String) {
        val stamped = _backupSettings.value.copy(
            lastBackup = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date()),
            lastPath = path
        )
        _backupSettings.value = stamped
        spotifyRepository.saveBackupSettings(stamped)
    }

    /** Backup source follows the active data source: server export in remote mode. */
    private suspend fun exportLibraryBackupNow() =
        if (remoteActive()) dashboardRepository.exportLibraryCsv(_backupSettings.value.subdirName)
        else spotifyRepository.exportLibraryBackup(_backupSettings.value.subdirName)

    /** Manual "Export now" from the Backup tab. */
    fun exportLibraryNow() {
        viewModelScope.launch {
            try {
                val backup = exportLibraryBackupNow()
                if (backup != null) {
                    recordBackup(backup.path)
                    _actionMessage.value = "Backup saved: ${backup.filename}" +
                        (if (backup.bytes > 0) " (${backup.bytes / 1024} KB)" else "")
                } else {
                    _actionMessage.value = "Library is empty — nothing to back up"
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Backup failed", e)
                _actionMessage.value = "Backup failed: ${e.message}"
            }
        }
    }

    /** Auto-backup after a successful Spotify sync (mirrors TS handleSyncSpotify). */
    private suspend fun maybeAutoBackup(synced: Boolean) {
        if (!_backupSettings.value.enabled || !synced) return
        try {
            val backup = exportLibraryBackupNow()
            if (backup != null) {
                recordBackup(backup.path)
                _actionMessage.value = "Backup saved: ${backup.filename}"
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Auto-backup failed", e)
            _actionMessage.value = "Backup failed: ${e.message}"
        }
    }

    /** Playlist CSV export (track_count + tracks columns, no import path). */
    fun exportPlaylistNow() {
        val playlist = _selectedPlaylist.value ?: return
        viewModelScope.launch {
            try {
                // Remote: server-built CSV saved to app files.
                if (remoteActive()) {
                    val file = dashboardRepository.exportPlaylistCsv(playlist.id, playlist.name)
                    _actionMessage.value = if (file != null) "Saved: ${file.name}" else "Export failed"
                    return@launch
                }
                val backup = playlistRepository.exportPlaylistCsv(playlist.id, playlist.name)
                if (backup != null) {
                    _actionMessage.value = "Saved: ${backup.filename}"
                } else {
                    _actionMessage.value = "Playlist is empty — nothing to export"
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Playlist export failed", e)
                _actionMessage.value = "Export failed: ${e.message}"
            }
        }
    }
}