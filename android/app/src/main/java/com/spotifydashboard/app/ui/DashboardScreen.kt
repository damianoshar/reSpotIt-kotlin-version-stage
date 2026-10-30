package com.spotifydashboard.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import com.spotifydashboard.app.BuildConfig
import com.spotifydashboard.app.playback.LocalPlaybackState
import com.spotifydashboard.app.data.AlbumEntity
import com.spotifydashboard.app.data.ViewMode
import com.spotifydashboard.app.utils.PlaylistListOptions
import com.spotifydashboard.app.data.FilterState
import com.spotifydashboard.app.data.PlaylistAlbumEntity
import com.spotifydashboard.app.repository.BackupSettings
import com.spotifydashboard.app.repository.SyncState
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.data.LibraryStats
import com.spotifydashboard.app.data.StatsBucket
import com.spotifydashboard.app.repository.PlaylistSyncState
import com.spotifydashboard.app.ui.theme.SpotifyCardGray
import com.spotifydashboard.app.ui.theme.SpotifyChipGray
import com.spotifydashboard.app.ui.theme.SpotifyGreen
import com.spotifydashboard.app.ui.theme.SpotifyLightGray
import com.spotifydashboard.app.ui.theme.SpotifyRed
import com.spotifydashboard.app.utils.CsvImportResult
import com.spotifydashboard.app.api.JobDto
import com.spotifydashboard.app.utils.SyncHistory
import com.spotifydashboard.app.utils.openSpotifyUrl
import com.spotifydashboard.app.utils.spotifyAlbumUrl
import com.spotifydashboard.app.utils.spotifyPlaylistUrl
import com.spotifydashboard.app.utils.PlaylistRetentionStore
import com.spotifydashboard.app.utils.QueueMode
import com.spotifydashboard.app.api.RemoteAlbumTrack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun SpotifyDashboardApp(
    viewModel: MainViewModel,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onServerLogin: () -> Unit,
    onConfigureCredentials: () -> Unit = {},
    isAuthenticated: Boolean
) {
    // Card 276: one provider at the root covers the board, dialogs and sheets.
    val syncPhotos by viewModel.syncPhotos.collectAsState()
    CompositionLocalProvider(LocalArtworkEnabled provides syncPhotos) {
        SpotifyDashboardContent(
            viewModel = viewModel,
            onLogin = onLogin,
            onLogout = onLogout,
            onServerLogin = onServerLogin,
            onConfigureCredentials = onConfigureCredentials,
            isAuthenticated = isAuthenticated
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyDashboardContent(
    viewModel: MainViewModel,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onServerLogin: () -> Unit,
    onConfigureCredentials: () -> Unit = {},
    isAuthenticated: Boolean
) {
    if (!isAuthenticated) {
        LoginScreen(onLogin = onLogin, onConfigure = onConfigureCredentials)
        return
    }

    var selectedTab by remember { mutableStateOf(0) }
    var showSyncModal by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showPlaylistFilters by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    var showFollowConfirm by remember { mutableStateOf(false) }
    var showAddFromSite by remember { mutableStateOf(false) }
    var showSpotifySearchPlaylist by remember { mutableStateOf(false) }
    var showArtistDiscographyPlaylist by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showPlaylistRemoveConfirm by remember { mutableStateOf(false) }
    var showLibraryDeleteConfirm by remember { mutableStateOf(false) }
    var navVisible by remember { mutableStateOf(true) }
    // The board bar (search + sort + view options) hides on its own toggle.
    var boardBarVisible by remember { mutableStateOf(true) }

    val gridSize by viewModel.gridSize.collectAsState()

    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val libraryTotal by viewModel.libraryTotal.collectAsState()
    val libraryLoading by viewModel.libraryLoading.collectAsState()
    val libraryLoadingMore by viewModel.libraryLoadingMore.collectAsState()
    val libraryHasMore by viewModel.libraryHasMore.collectAsState()
    val librarySyncState by viewModel.librarySyncState.collectAsState()
    val libraryStats by viewModel.libraryStats.collectAsState()
    val libraryFilters by viewModel.libraryFilters.collectAsState()
    val libraryGenres by viewModel.libraryGenres.collectAsState()
    val libraryArtists by viewModel.libraryArtists.collectAsState()
    val libraryYears by viewModel.libraryYears.collectAsState()

    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val playlistSyncState by viewModel.playlistSyncState.collectAsState()
    val playlistAlbums by viewModel.playlistAlbums.collectAsState()
    val playlistTotal by viewModel.playlistTotal.collectAsState()
    val playlistLoading by viewModel.playlistLoading.collectAsState()
    val playlistLoadingMore by viewModel.playlistLoadingMore.collectAsState()
    val playlistHasMore by viewModel.playlistHasMore.collectAsState()
    val playlistFilters by viewModel.playlistFilters.collectAsState()
    val playlistGenres by viewModel.playlistGenres.collectAsState()
    val playlistArtists by viewModel.playlistArtists.collectAsState()
    val playlistYears by viewModel.playlistYears.collectAsState()

    val selectedAlbums by viewModel.selectedAlbums.collectAsState()
    val libraryDeleteBusy by viewModel.libraryDeleteBusy.collectAsState()
    val pendingActiveQueueCreation by viewModel.pendingActiveQueueCreation.collectAsState()
    val activeQueueBusy by viewModel.activeQueueBusy.collectAsState()
    val localQueueBusy by viewModel.localQueueBusy.collectAsState()
    val localQueuePlaylist by viewModel.localQueuePlaylist.collectAsState()
    val queuePlaylistBusy by viewModel.queuePlaylistBusy.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val showPlaylistPicker by viewModel.showPlaylistPicker.collectAsState()
    val showCreatePlaylistDialog by viewModel.showCreatePlaylistDialog.collectAsState()
    val isCreatingPlaylist by viewModel.isCreatingPlaylist.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val csvImportResult by viewModel.csvImportResult.collectAsState()
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()
    val duplicatesLoading by viewModel.duplicatesLoading.collectAsState()
    val followArtistsBusy by viewModel.followArtistsBusy.collectAsState()
    val addingFromSite by viewModel.addingFromSite.collectAsState()
    val siteAlbumLabels by viewModel.siteAlbumLabels.collectAsState()
    val spotifySearchPlaylist by viewModel.spotifySearchPlaylist.collectAsState()
    val artistDiscography by viewModel.artistDiscography.collectAsState()
    val catalogPlaylistBusy by viewModel.catalogPlaylistBusy.collectAsState()
    val catalogPlaylistProgress by viewModel.catalogPlaylistProgress.collectAsState()
    val catalogPlaylistOutcome by viewModel.catalogPlaylistOutcome.collectAsState()
    val catalogLibraryBusy by viewModel.catalogLibraryBusy.collectAsState()
    val catalogLibraryProgress by viewModel.catalogLibraryProgress.collectAsState()
    val catalogLibraryOutcome by viewModel.catalogLibraryOutcome.collectAsState()
    val catalogBusy = catalogPlaylistBusy || catalogLibraryBusy
    val backupSettings by viewModel.backupSettings.collectAsState()
    val dataSource by viewModel.dataSource.collectAsState()
    val serverBusy by viewModel.serverBusy.collectAsState()
    val pushBusy by viewModel.pushBusy.collectAsState()
    val serverTestResult by viewModel.serverTestResult.collectAsState()
    val syncHistory by viewModel.syncHistory.collectAsState()
    val isRemote = dataSource.mode == "remote"

    val autohideNav by viewModel.autohideNav.collectAsState()
    val autohideBoardBar by viewModel.autohideBoardBar.collectAsState()
    val syncPhotos by viewModel.syncPhotos.collectAsState()
    val libraryViewMode by viewModel.libraryViewMode.collectAsState()
    val playlistViewMode by viewModel.playlistViewMode.collectAsState()
    val albumSheet by viewModel.albumSheet.collectAsState()
    val playlistExpiresAt by viewModel.playlistExpiresAt.collectAsState()
    val retentionBusy by viewModel.retentionBusy.collectAsState()
    val localPlayback by viewModel.localPlaybackState.collectAsState()

    // Turning the feature back on should always start from a visible bar.
    LaunchedEffect(autohideNav) { navVisible = true }
    LaunchedEffect(autohideBoardBar) { boardBarVisible = true }

    // Scroll-direction tracking for the autohiding bottom bar. The connection
    // only consumes nothing — it just observes the LazyGrid's pre-scroll delta.
    // Two independent autohide targets: the bottom nav and the board bar
    // (search + sort + view options). Each has its own toggle in Settings.
    val navScrollConnection = remember(autohideNav, autohideBoardBar) {
        object : NestedScrollConnection {
            private var navAccumulated = 0f
            private var barAccumulated = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (autohideNav) {
                    navAccumulated = (navAccumulated + available.y).coerceIn(-160f, 160f)
                    if (navAccumulated > 56f) {
                        if (navVisible) navVisible = false
                        navAccumulated = 0f
                    } else if (navAccumulated < -40f) {
                        if (!navVisible) navVisible = true
                        navAccumulated = 0f
                    }
                }
                if (autohideBoardBar) {
                    barAccumulated = (barAccumulated + available.y).coerceIn(-160f, 160f)
                    if (barAccumulated > 56f) {
                        if (boardBarVisible) boardBarVisible = false
                        barAccumulated = 0f
                    } else if (barAccumulated < -40f) {
                        if (!boardBarVisible) boardBarVisible = true
                        barAccumulated = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    // Active-filter badge for the toolbar icon (library / playlist tabs only)
    val activeFilterCount = activeFilterCount(if (selectedTab == 1) playlistFilters else libraryFilters)

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify Dashboard") },
                actions = {
                    if (selectedTab == 0 || selectedTab == 1) {
                        IconButton(onClick = {
                            if (selectedTab == 0) showFilters = !showFilters
                            else showPlaylistFilters = !showPlaylistFilters
                        }) {
                            BadgedBox(
                                badge = {
                                    if (activeFilterCount > 0) {
                                        Badge(containerColor = SpotifyGreen, contentColor = Color.Black) {
                                            Text("$activeFilterCount")
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter")
                            }
                        }
                    }
                    if (selectedTab == 0) {
                        // Sync sits next to the filter button, so it is always
                        // reachable without scrolling the board.
                        if (librarySyncState is SyncState.Progress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = SpotifyGreen,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            TextButton(
                                onClick = {
                                    showSyncModal = true
                                    viewModel.loadSyncHistory()
                                },
                                enabled = librarySyncState !is SyncState.Progress,
                            ) { Text("Sync") }
                        }
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = !autohideNav || navVisible) {
                Column {
                    if (localPlayback.enabled &&
                        (localPlayback.connected || localPlayback.connecting) &&
                        (localPlayback.title.isNotBlank() || localPlayback.connecting)
                    ) {
                        LocalMiniPlayer(
                            state = localPlayback,
                            onToggle = viewModel::toggleLocalPlayback,
                            onStop = viewModel::stopLocalPlayback,
                            onOpen = { selectedTab = 4 },
                        )
                    }
                    NavigationBar(containerColor = Color.Black) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                    label = { Text("Library") },
                    colors = spotifyNavItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.loadPlaylists()
                    },
                    icon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                    label = { Text("Playlists") },
                    colors = spotifyNavItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    label = { Text("Tools") },
                    colors = spotifyNavItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    colors = spotifyNavItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("Player") },
                    colors = spotifyNavItemColors()
                )
                }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .nestedScroll(navScrollConnection)
        ) {
            when (selectedTab) {
                0 -> LibraryTab(
                    albums = libraryAlbums,
                    total = libraryTotal,
                    loading = libraryLoading,
                    loadingMore = libraryLoadingMore,
                    hasMore = libraryHasMore,
                    syncState = librarySyncState,
                    filters = libraryFilters,
                    genres = libraryGenres,
                    artists = libraryArtists,
                    years = libraryYears,
                    gridSize = gridSize,
                    viewMode = libraryViewMode,
                    boardBarVisible = boardBarVisible,
                    showFilters = showFilters,
                    selectedAlbums = selectedAlbums,
                    allowReleaseType = !isRemote,
                    onFilterChange = viewModel::updateLibraryFilter,
                    onResetFilters = viewModel::resetLibraryFilters,
                    onGridSizeChange = viewModel::setGridSize,
                    onViewModeChange = viewModel::setLibraryViewMode,
                    onLoadMore = viewModel::loadLibraryMore,
                    onSyncClick = { showSyncModal = true; viewModel.loadSyncHistory() },
                    onCancelSync = viewModel::cancelLibrarySync,
                    onDismissSyncState = viewModel::clearLibrarySyncState,
                    onToggleSelect = viewModel::toggleAlbumSelection,
                    onToggleAllVisible = { viewModel.toggleAllVisible(libraryAlbums.map { it.id }) },
                    onClearSelection = viewModel::clearSelection,
                    onAddToQueue = viewModel::queueSelectedAlbums,
                    onAddToPlaylist = viewModel::requestAddToPlaylist,
                    onRemoveFromPlaylist = null,
                    onDeleteFromLibrary = if (isRemote) null else {
                        { showLibraryDeleteConfirm = true }
                    },
                    deleteBusy = libraryDeleteBusy,
                    hasLocalQueue = localQueuePlaylist != null,
                    localQueueBusy = localQueueBusy,
                    activeQueueBusy = activeQueueBusy,
                    queuePlaylistBusy = queuePlaylistBusy,
                    onLocalQueueCreate = {
                        viewModel.requestLocalQueuePlaylistCreation(selectedAlbums)
                    },
                    onLocalQueueAdd = viewModel::addSelectedAlbumsToLocalQueuePlaylist,
                    onLocalQueueNext = viewModel::queueSelectedAlbumsNextInLocalQueue,
                    onLocalQueueReset = viewModel::requestLocalQueueReset,
                    onOpenAlbum = { album ->
                        viewModel.openAlbumSheet(
                            albumId = album.id,
                            title = album.title,
                            artist = album.artist,
                            imageUrl = album.imageUrl,
                            year = album.year,
                            playlistId = null,
                        )
                    }
                )
                1 -> PlaylistsTab(
                    playlists = playlists,
                    selectedPlaylist = selectedPlaylist,
                    playlistAlbums = playlistAlbums,
                    playlistTotal = playlistTotal,
                    playlistLoading = playlistLoading,
                    playlistLoadingMore = playlistLoadingMore,
                    playlistHasMore = playlistHasMore,
                    filters = playlistFilters,
                    genres = playlistGenres,
                    artists = playlistArtists,
                    years = playlistYears,
                    gridSize = gridSize,
                    viewMode = playlistViewMode,
                    boardBarVisible = boardBarVisible,
                    showFilters = showPlaylistFilters,
                    syncState = playlistSyncState,
                    selectedAlbums = selectedAlbums,
                    onSelectPlaylist = viewModel::selectPlaylist,
                    onSyncPlaylist = viewModel::syncPlaylist,
                    onCancelPlaylistSync = viewModel::cancelPlaylistSync,
                    onDismissPlaylistSync = viewModel::clearPlaylistSyncState,
                    onFilterChange = viewModel::updatePlaylistFilter,
                    onResetFilters = viewModel::resetPlaylistFilters,
                    onGridSizeChange = viewModel::setGridSize,
                    onViewModeChange = viewModel::setPlaylistViewMode,
                    onLoadMore = viewModel::loadPlaylistMore,
                    onExportPlaylist = viewModel::exportPlaylistNow,
                    onLoadPlaylists = viewModel::loadPlaylists,
                    onToggleSelect = viewModel::toggleAlbumSelection,
                    onToggleAllVisible = { viewModel.toggleAllVisible(playlistAlbums.map { it.albumId }) },
                    onClearSelection = viewModel::clearSelection,
                    onAddToQueue = viewModel::queueSelectedAlbums,
                    onAddToPlaylist = viewModel::requestAddToPlaylist,
                    onRemoveFromPlaylist = { showPlaylistRemoveConfirm = true },
                    onDeleteSelectedPlaylists = viewModel::deletePlaylists,
                    onTogglePlaylistVisibility = viewModel::setPlaylistPublic,
                    hasLocalQueue = localQueuePlaylist != null,
                    localQueueBusy = localQueueBusy,
                    activeQueueBusy = activeQueueBusy,
                    queuePlaylistBusy = queuePlaylistBusy,
                    onLocalQueueCreate = {
                        viewModel.requestLocalQueuePlaylistCreation(selectedAlbums)
                    },
                    onLocalQueueAdd = viewModel::addSelectedAlbumsToLocalQueuePlaylist,
                    onLocalQueueNext = viewModel::queueSelectedAlbumsNextInLocalQueue,
                    onLocalQueueReset = viewModel::requestLocalQueueReset,
                    onOpenAlbum = { album ->
                        viewModel.openAlbumSheet(
                            albumId = album.albumId,
                            title = album.title,
                            artist = album.artist,
                            imageUrl = album.imageUrl,
                            year = album.year,
                            playlistId = album.playlistId,
                        )
                    },
                    playlistExpiresAt = playlistExpiresAt,
                    onOpenRetention = { showRetentionDialog = true }
                )
                2 -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    var showStats by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { showStats = !showStats }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (showStats) "Hide stats" else "Stats")
                    }
                    if (showStats) {
                        Spacer(modifier = Modifier.height(12.dp))
                        StatsScreen(stats = libraryStats)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showAddFromSite = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add from site")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.resetSpotifySearchPlaylist()
                            showSpotifySearchPlaylist = true
                        },
                        enabled = !isRemote && !catalogBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Playlist from Spotify search")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.resetArtistDiscography()
                            showArtistDiscographyPlaylist = true
                        },
                        enabled = !isRemote && !catalogBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Artist discography playlist")
                    }
                    Text(
                        if (isRemote)
                            "Spotify search and discography need local Spotify login."
                        else
                            "Search Spotify or pick an artist. Create a private playlist with dedupe, " +
                                "or add the selected releases to your Spotify library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            showDuplicates = true
                            viewModel.findDuplicates()
                        },
                        enabled = dataSource.mode != "remote",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Find duplicate albums")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showFollowConfirm = true },
                        enabled = !followArtistsBusy && dataSource.mode != "remote",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (followArtistsBusy) "Following library artists…" else "Follow library artists")
                    }
                    Text(
                        if (dataSource.mode == "remote")
                            "Following requires local Spotify auth/logins."
                        else
                            "Collects artist IDs from Liked Songs + saved albums, skips ones already followed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (showFollowConfirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showFollowConfirm = false },
                            title = { Text("Follow library artists?") },
                            text = { Text("Follow all artists found in your Liked Songs and saved albums? This modifies your Spotify account and may take a while.") },
                            confirmButton = {
                                Button(onClick = {
                                    showFollowConfirm = false
                                    viewModel.followLibraryArtists("both", false)
                                }) { Text("Follow") }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showFollowConfirm = false }) { Text("Cancel") }
                            }
                        )
                    }
                }

                3 -> SettingsScreen(
                    stats = libraryStats,
                    gridSize = gridSize,
                    backupSettings = backupSettings,
                    onGridSizeChange = viewModel::setGridSize,
                    onToggleBackup = viewModel::toggleBackup,
                    onSetBackupSubdir = viewModel::setBackupSubdir,
                    onExportNow = viewModel::exportLibraryNow,
                    onClearLibrary = { showClearConfirm = true },
                    onFindDuplicates = {
                        showDuplicates = true
                        viewModel.findDuplicates()
                    },
                    onAddFromSite = { showAddFromSite = true },
                    onLogout = onLogout,
                    dataSource = dataSource,
                    serverBusy = serverBusy,
                    pushBusy = pushBusy,
                    serverTestResult = serverTestResult,
                    onSetServerMode = viewModel::setServerMode,
                    onSetServerUrl = viewModel::setServerUrl,
                    onTestServerUrl = viewModel::testServerUrl,
                    onClearServerTestResult = viewModel::clearServerTestResult,
                    onServerLogin = onServerLogin,
                    onPushToServer = viewModel::pushLocalLibraryToServer,
                    onLogoutServer = onLogout,
                    autohideNav = autohideNav,
                    onSetAutohideNav = viewModel::setAutohideNav,
                    autohideBoardBar = autohideBoardBar,
                    onSetAutohideBoardBar = viewModel::setAutohideBoardBar,
                    syncPhotos = syncPhotos,
                    onSetSyncPhotos = viewModel::setSyncPhotos
                )
                4 -> PlayerScreen(viewModel = viewModel)
            }
        }
    }

    // Playlist picker dialog
    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPlaylistPicker,
            title = { Text("Add to Playlist") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item {
                        TextButton(
                            onClick = viewModel::requestCreatePlaylist,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Create new playlist",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (playlists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else {
                        items(playlists) { playlist ->
                            TextButton(
                                onClick = { viewModel.addSelectedToPlaylist(playlist.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = playlist.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissPlaylistPicker) { Text("Cancel") }
            }
        )
    }

    // Create new playlist dialog
    if (showCreatePlaylistDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        var retentionUntil by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = {
                if (!isCreatingPlaylist) viewModel.dismissCreatePlaylistDialog()
            },
            icon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
            title = { Text("New Playlist") },
            text = {
                Column {
                    Text(
                        text = "Enter a name for your new playlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist name") },
                        placeholder = { Text("My playlist") },
                        singleLine = true,
                        enabled = !isCreatingPlaylist,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Delete automatically",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    RetentionPicker(
                        value = retentionUntil,
                        onChange = { retentionUntil = it },
                        enabled = !isCreatingPlaylist,
                    )
                    if (isCreatingPlaylist) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Creating playlist...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.createAndAddToPlaylist(newPlaylistName, retentionUntil) },
                    enabled = newPlaylistName.isNotBlank() && !isCreatingPlaylist
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissCreatePlaylistDialog,
                    enabled = !isCreatingPlaylist
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    when {
        showSyncModal -> {
            SyncModal(
                onDismiss = {
                    showSyncModal = false
                    viewModel.clearCsvImportResult()
                },
                onConfirm = { clean, includeTracks ->
                    showSyncModal = false
                    viewModel.clearCsvImportResult()
                    viewModel.startLibrarySync(clean, includeTracks)
                },
                csvResult = csvImportResult,
                backupSettings = backupSettings,
                syncHistory = syncHistory,
                isRemote = isRemote,
                onImportCsv = viewModel::importCsvText,
                onClearCsvResult = viewModel::clearCsvImportResult,
                onExportNow = viewModel::exportLibraryNow,
                onToggleBackup = viewModel::toggleBackup,
                onSetBackupSubdir = viewModel::setBackupSubdir
            )
        }
    }

    albumSheet?.let { sheet ->
        AlbumTrackSheet(
            sheet = sheet,
            onDismiss = viewModel::closeAlbumSheet,
            onRemoveTrack = viewModel::removeTrackFromOpenPlaylist,
            onRemoveAlbum = viewModel::removeAlbumFromOpenPlaylist,
            onQueue = viewModel::queueAlbumSheetTracks,
            onQueueTrack = viewModel::queueTrackUri,
            onAddToPlaylist = { viewModel.requestAddAlbumToPlaylist(sheet.albumId) },
            localPlaybackEnabled = localPlayback.enabled,
            onPlayAlbum = { viewModel.playAlbumLocally() },
            onPlayTrack = { uri -> viewModel.playTrackLocally(uri) },
            onLocalQueueNextTrack = { uri -> viewModel.enqueueTrackLocally(uri, next = true) },
            onLocalQueueEndTrack = { uri -> viewModel.enqueueTrackLocally(uri, next = false) },
            onLocalQueueAlbumNext = viewModel::enqueueAlbumLocallyNext,
            hasLocalQueue = localQueuePlaylist != null,
            localQueueBusy = localQueueBusy,
            activeQueueBusy = activeQueueBusy,
            queuePlaylistBusy = queuePlaylistBusy,
            onLocalQueueCreate = {
                viewModel.requestLocalQueuePlaylistCreation(setOf(sheet.albumId))
            },
            onLocalQueueAdd = {
                viewModel.addAlbumsToLocalQueuePlaylist(setOf(sheet.albumId))
            },
            onLocalQueueNext = {
                viewModel.queueAlbumsNextInLocalQueue(setOf(sheet.albumId))
            },
            onLocalQueueReset = viewModel::requestLocalQueueReset,

        )
    }

    if (showRetentionDialog && selectedPlaylist != null) {
        RetentionDialog(
            playlist = selectedPlaylist!!,
            currentExpiry = playlistExpiresAt,
            busy = retentionBusy,
            onDismiss = { showRetentionDialog = false },
            onSet = { expiry ->
                showRetentionDialog = false
                viewModel.setPlaylistRetention(expiry)
            },
            onDeleteNow = {
                showRetentionDialog = false
                viewModel.deletePlaylistNow()
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(if (isRemote) "Clear server library?" else "Clear local library?") },
            text = { Text(if (isRemote) "This deletes all ${libraryTotal} albums stored on the server. Your Spotify saved library is untouched — you can re-sync anytime." else "This deletes all ${libraryTotal} locally stored albums. Your Spotify saved library is untouched — you can re-sync anytime.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearLibrary()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showPlaylistRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showPlaylistRemoveConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Remove from playlist?") },
            text = { Text("Remove ${selectedAlbums.size} selected album${if (selectedAlbums.size == 1) "" else "s"} from this playlist? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPlaylistRemoveConfirm = false
                        viewModel.removeSelectedAlbumsFromPlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showLibraryDeleteConfirm) {
        val count = selectedAlbums.size
        AlertDialog(
            onDismissRequest = { showLibraryDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Delete from Spotify library?") },
            text = {
                Text(
                    "Delete $count selected album${if (count == 1) "" else "s"} from " +
                        "your Spotify saved library and this device? " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLibraryDeleteConfirm = false
                        viewModel.removeSelectedAlbumsFromLibrary()
                    },
                    enabled = !libraryDeleteBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLibraryDeleteConfirm = false },
                    enabled = !libraryDeleteBusy,
                ) { Text("Cancel") }
            }
        )
    }

    if (showDuplicates) {
        DuplicateAlbumsDialog(
            groups = duplicateGroups,
            hiddenAlbums = hiddenAlbums,
            loading = duplicatesLoading,
            isRemote = isRemote,
            onDismiss = {
                showDuplicates = false
                viewModel.clearDuplicateScan()
            },
            onRemove = viewModel::removeDuplicateAlbums,
            onRemovePermanently = viewModel::removeDuplicateAlbumsPermanently,
            onRestore = viewModel::restoreHiddenAlbums
        )
    }

    if (showAddFromSite) {
        AddFromSiteDialog(
            adding = addingFromSite,
            albumLabels = siteAlbumLabels,
            onPrefetch = viewModel::prefetchSiteAlbums,
            onDismiss = { showAddFromSite = false },
            onAdd = viewModel::addAlbumsFromSite
        )
    }

    if (showSpotifySearchPlaylist) {
        SpotifySearchPlaylistDialog(
            state = spotifySearchPlaylist,
            busy = catalogBusy,
            progress = catalogPlaylistProgress,
            outcome = catalogPlaylistOutcome,
            libraryBusy = catalogLibraryBusy,
            libraryProgress = catalogLibraryProgress,
            libraryOutcome = catalogLibraryOutcome,
            onSearch = viewModel::searchSpotifyAlbums,
            onCreate = viewModel::createCatalogPlaylist,
            onAddToLibrary = viewModel::addCatalogAlbumsToLibrary,
            onDismiss = {
                showSpotifySearchPlaylist = false
                viewModel.resetSpotifySearchPlaylist()
            },
            onClearOutcome = viewModel::clearCatalogPlaylistOutcome,
            onClearLibraryOutcome = viewModel::clearCatalogLibraryOutcome,
        )
    }

    if (showArtistDiscographyPlaylist) {
        ArtistDiscographyPlaylistDialog(
            state = artistDiscography,
            busy = catalogBusy,
            progress = catalogPlaylistProgress,
            outcome = catalogPlaylistOutcome,
            libraryBusy = catalogLibraryBusy,
            libraryProgress = catalogLibraryProgress,
            libraryOutcome = catalogLibraryOutcome,
            onSearchArtists = viewModel::searchDiscographyArtists,
            onSelectArtist = viewModel::selectDiscographyArtist,
            onClearArtist = viewModel::clearDiscographyArtist,
            onGroupChange = viewModel::setDiscographyGroup,
            onCreate = viewModel::createCatalogPlaylist,
            onAddToLibrary = viewModel::addCatalogAlbumsToLibrary,
            onDismiss = {
                showArtistDiscographyPlaylist = false
                viewModel.resetArtistDiscography()
            },
            onClearOutcome = viewModel::clearCatalogPlaylistOutcome,
            onClearLibraryOutcome = viewModel::clearCatalogLibraryOutcome,
        )
    }

    // Queue playlist create/reset warning. The new playlist starts immediately,
    // so the user must explicitly accept that the current track is cut off.
    // Kept last so it also sits above the album bottom sheet.
    pendingActiveQueueCreation?.let { pending ->
        val currentTrack = playbackState.track?.name
        AlertDialog(
            onDismissRequest = viewModel::cancelActiveQueueCreation,
            title = {
                Text(
                    when {
                        pending.local && pending.reset -> "Reset local queue playlist?"
                        pending.local -> "Create local queue playlist?"
                        pending.reset -> "Reset queue playlist?"
                        pending.newFromCurrent -> "New queue playlist?"
                        else -> "Create queue playlist?"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        when {
                            pending.local && pending.reset -> append(
                                "A fresh private local queue playlist replaces the current one " +
                                    "and starts immediately. It keeps only the unplayed tracks " +
                                    "after the current one. The current track is cut off and the " +
                                    "old local queue playlist is removed."
                            )
                            pending.local -> append(
                                "One private local queue playlist is created from what is " +
                                    "playing now (or the current track if Spotify has nothing " +
                                    "queued) plus any selected albums, and it starts immediately. " +
                                    "The current track is cut off and the old local queue " +
                                    "playlist is removed once the new one plays."
                            )
                            pending.reset -> append(
                                "A fresh private queue playlist replaces the current one and " +
                                    "starts immediately. The current track is cut off and the old " +
                                    "queue playlist is removed."
                            )
                            pending.newFromCurrent -> append(
                                "A brand-new private queue playlist is created from what is " +
                                    "playing now plus any selected albums, and it starts " +
                                    "immediately. The old queue playlist is removed once the new " +
                                    "one plays. The current track is cut off."
                            )
                            else -> append(
                                "One private queue playlist is created from the current Spotify " +
                                    "queue plus any selected albums, and it starts immediately. " +
                                    "The current track is cut off."
                            )
                        }
                        if (!currentTrack.isNullOrBlank()) {
                            append("\n\nNow playing: \u201c$currentTrack\u201d")
                        } else {
                            append("\n\nPlayback will start on the active Spotify device.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmActiveQueueCreation,
                    enabled = !activeQueueBusy && !localQueueBusy
                ) {
                    Text(
                        if (pending.reset) "Reset and play" else "Create and play"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelActiveQueueCreation) { Text("Cancel") }
            }
        )
    }
}

/** Section card wrapper for the Settings screen. */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SettingsScreen(
    stats: LibraryStats?,
    gridSize: Int,
    backupSettings: BackupSettings,
    onGridSizeChange: (Int) -> Unit,
    onToggleBackup: () -> Unit,
    onSetBackupSubdir: (String) -> Unit,
    onExportNow: () -> Unit,
    onClearLibrary: () -> Unit,
    onFindDuplicates: () -> Unit,
    onAddFromSite: () -> Unit,
    onLogout: () -> Unit,
    dataSource: MainViewModel.DataSourceState,
    serverBusy: Boolean,
    pushBusy: Boolean,
    serverTestResult: String?,
    onSetServerMode: (String) -> Unit,
    onSetServerUrl: (String) -> Unit,
    onTestServerUrl: (String) -> Unit,
    onClearServerTestResult: () -> Unit,
    onServerLogin: () -> Unit,
    onPushToServer: () -> Unit,
    onLogoutServer: () -> Unit,
    autohideNav: Boolean,
    onSetAutohideNav: (Boolean) -> Unit,
    autohideBoardBar: Boolean,
    onSetAutohideBoardBar: (Boolean) -> Unit,
    syncPhotos: Boolean,
    onSetSyncPhotos: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            SettingsSection("Media server") {
                MediaServerSection(
                    mode = dataSource.mode,
                    serverUrl = dataSource.serverUrl,
                    email = dataSource.email,
                    busy = serverBusy,
                    pushBusy = pushBusy,
                    testResult = serverTestResult,
                    hasSession = dataSource.hasSession,
                    onSetMode = { onClearServerTestResult(); onSetServerMode(it) },
                    onSetServerUrl = onSetServerUrl,
                    onTestConnection = onTestServerUrl,
                    onRequestServerLogin = onServerLogin,
                    onPushToServer = onPushToServer,
                    onLogoutServer = onLogoutServer
                )
            }
        }
        item {
            SettingsSection("Display") {
                Text("Album grid size", fontWeight = FontWeight.Medium)
                Text(
                    "How large album cards appear in the grids.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                GridSizeRow(gridSize = gridSize, onGridSizeChange = onGridSizeChange)
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sync photos", fontWeight = FontWeight.Medium)
                        Text(
                            "Load album covers and artwork. Turn off on low-end devices to save memory and data — covers become placeholders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray
                        )
                    }
                    Switch(
                        checked = syncPhotos,
                        onCheckedChange = onSetSyncPhotos
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Autohide bottom navigation", fontWeight = FontWeight.Medium)
                        Text(
                            "Hides while scrolling down and returns on scroll up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray
                        )
                    }
                    Switch(
                        checked = autohideNav,
                        onCheckedChange = onSetAutohideNav
                    )
                }
            }
        }
        item {
            SettingsSection("Display") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Autohide search & sort bar", fontWeight = FontWeight.Medium)
                        Text(
                            "Hides the search, sort and view options while scrolling down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray
                        )
                    }
                    Switch(
                        checked = autohideBoardBar,
                        onCheckedChange = onSetAutohideBoardBar
                    )
                }
            }
        }
        item {
            SettingsSection("Backup") {
                BackupSettingsSection(
                    backupSettings = backupSettings,
                    onToggleBackup = onToggleBackup,
                    onSetBackupSubdir = onSetBackupSubdir,
                    onExportNow = onExportNow
                )
            }
        }
        item {
            SettingsSection("Library") {
                Text(
                    if (stats != null)
                        "${stats.totalAlbums} albums · ${stats.totalArtists} artists · ${stats.totalGenres} genres"
                    else if (dataSource.mode == "remote") "Media server library"
                    else "Local library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray
                )
                Button(
                    onClick = onClearLibrary,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (dataSource.mode == "remote") "Clear server library" else "Clear local library")
                }
                Text(
                    if (dataSource.mode == "remote")
                        "Deletes albums stored on the media server only — your Spotify library is untouched."
                    else
                        "Deletes locally stored albums only — your Spotify library is untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item {
            SettingsSection("Account") {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (dataSource.mode == "remote") "Sign out" else "Log out of Spotify")
                }
            }
        }
        item {
            SettingsSection("About") {
                Text(
                    "Spotify Dashboard for Android",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Browse your saved Spotify albums offline. Syncs via the Spotify Web API (PKCE) into an on-device database.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** Spotify bottom-nav: gray icons, white when selected, no pill indicator. */
@Composable
private fun spotifyNavItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White,
    selectedTextColor = Color.White,
    unselectedIconColor = SpotifyLightGray,
    unselectedTextColor = SpotifyLightGray,
    indicatorColor = Color.Transparent
)

/** Auto-loads the next page when the user scrolls near the end — the Compose
 * twin of the TS IntersectionObserver infinite scroll. Guards mirror the
 * ViewModel's (hasMore + not already loading), so double-fires are harmless.
 * The trailing Load More button stays as a fallback. */
/**
 * Infinite scroll for the list view (the grid variant uses AutoLoadMore).
 */
@Composable
private fun AutoLoadMoreList(
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    listState: LazyListState,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, hasMore, loading, loadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) ->
                hasMore && !loading && !loadingMore && total > 0 && last >= total - 6
            }
            .collect { onLoadMore() }
    }
}

/** The trailing "Load More" fallback button, shared by the grid and the list. */
@Composable
private fun LoadMoreFooter(loadingMore: Boolean, onLoadMore: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (loadingMore) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Button(onClick = onLoadMore) {
                Text("Load More")
            }
        }
    }
}

@Composable
private fun AutoLoadMore(
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    gridState: LazyGridState,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(gridState, hasMore, loading, loadingMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) ->
                hasMore && !loading && !loadingMore && total > 0 && last >= total - 6
            }
            .collect { onLoadMore() }
    }
}

/**
 * Stats tab — totals plus the breakdown views (top genres / top artists /
 * albums by year), the same content the web frontends keep under their Stats
 * tab. Remote mode reads the server's V9 extras; local mode fills them from
 * Room.
 */
@Composable
fun StatsScreen(stats: LibraryStats?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        if (stats == null) {
            Text(
                "No stats yet — sync your library to see the breakdown.",
                color = SpotifyLightGray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Albums", stats.totalAlbums)
                StatItem("Artists", stats.totalArtists)
                StatItem("Genres", stats.totalGenres)
            }
            if (stats.oldestYear > 0 && stats.newestYear > 0) {
                Text(
                    "Span ${stats.oldestYear}–${stats.newestYear}",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            StatsBucketSection("Top genres", stats.topGenres)
            StatsBucketSection("Top artists", stats.topArtists)
            StatsBucketSection("Albums by year", stats.albumsByYear)
        }
    }
}

@Composable
private fun StatsBucketSection(title: String, buckets: List<StatsBucket>) {
    if (buckets.isEmpty()) return
    val max = buckets.maxOf { it.count }.coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = SpotifyLightGray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        buckets.forEach { bucket ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    bucket.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = bucket.count.toFloat() / max,
                    modifier = Modifier.width(96.dp).padding(horizontal = 4.dp),
                    color = SpotifyGreen,
                    trackColor = Color(0xFF282828)
                )
                Text(
                    "${bucket.count}",
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun LibraryTab(
    albums: List<AlbumEntity>,
    total: Int,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    syncState: SyncState,
    filters: FilterState,
    genres: List<String>,
    artists: List<String>,
    years: List<Int>,
    gridSize: Int,
    viewMode: ViewMode,
    boardBarVisible: Boolean,
    showFilters: Boolean,
    selectedAlbums: Set<String>,
    allowReleaseType: Boolean = true,
    onFilterChange: (FilterState.() -> FilterState) -> Unit,
    onResetFilters: () -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onLoadMore: () -> Unit,
    onSyncClick: () -> Unit,
    onCancelSync: () -> Unit,
    onDismissSyncState: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDeleteFromLibrary: (() -> Unit)? = null,
    deleteBusy: Boolean = false,
    hasLocalQueue: Boolean = false,
    localQueueBusy: Boolean = false,
    activeQueueBusy: Boolean = false,
    queuePlaylistBusy: Boolean = false,
    onLocalQueueCreate: (() -> Unit)? = null,
    onLocalQueueAdd: (() -> Unit)? = null,
    onLocalQueueNext: (() -> Unit)? = null,
    onLocalQueueReset: (() -> Unit)? = null,
    onOpenAlbum: (AlbumEntity) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search edits filters directly — the actual DB query is debounced 250ms
        // in the ViewModel (mirrors TS queryFilters).
        // The board bar (search + sort + view options) can slide away while
        // scrolling down - the autohide setting in Settings.
        AnimatedVisibility(visible = boardBarVisible) {
            Column {
                TextField(
                    value = filters.search,
                    onValueChange = { onFilterChange { copy(search = it) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search albums or artists...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filters.search.isNotEmpty()) {
                            IconButton(onClick = { onFilterChange { copy(search = "") } }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    }
                )

                SortChipsRow(
                    filters = filters,
                    options = LIBRARY_SORTS,
                    onFilterChange = onFilterChange
                )

                ViewOptionsRow(
                    mode = viewMode,
                    gridSize = gridSize,
                    onModeChange = onViewModeChange,
                    onGridSizeChange = onGridSizeChange,
                )
            }
        }

        when (val state = syncState) {
            is SyncState.Progress -> {
                val pct = if (state.total > 0) (state.fetched.toFloat() / state.total * 100).toInt() else 0
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${syncStageLabel(state.stage)}: ${state.fetched}/${state.total}", fontWeight = FontWeight.Medium)
                        Text("$pct%", fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = pct / 100f, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onCancelSync, modifier = Modifier.padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)) {
                        Text("Stop")
                    }
                }
            }
            is SyncState.Completed -> {
                SyncResultBanner(
                    title = "✓ ${state.result.imported} added · ${state.result.existing} already up to date",
                    subtitle = if (state.result.skipped > 0) "${state.result.skipped} skipped" else null,
                    onDismiss = onDismissSyncState
                )
            }
            is SyncState.Cancelled -> {
                SyncResultBanner(
                    title = "Sync stopped — kept ${state.result.imported} added so far",
                    subtitle = null,
                    onDismiss = onDismissSyncState
                )
            }
            is SyncState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyRed.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = SpotifyRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Error: ${state.message}",
                            color = SpotifyRed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissSyncState) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SpotifyRed)
                        }
                    }
                }
            }
            else -> {}
        }

        if (showFilters) {
            FilterPanel(
                filters = filters,
                genres = genres,
                artists = artists,
                years = years,
                onFilterChange = onFilterChange,
                onReset = onResetFilters,
                allowReleaseType = allowReleaseType,
            )
        }

        // Result count (mirrors TS filtered label)
        val activeCount = activeFilterCount(filters)
        Text(
            text = "$total album${if (total != 1) "s" else ""}" +
                (if (activeCount > 0) " (filtered)" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (selectedAlbums.isNotEmpty()) {
            SelectionToolbar(
                count = selectedAlbums.size,
                onClear = onClearSelection,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDeleteFromLibrary = onDeleteFromLibrary,
                deleteBusy = deleteBusy,
                hasLocalQueue = hasLocalQueue,
                localQueueBusy = localQueueBusy,
                activeQueueBusy = activeQueueBusy,
                queuePlaylistBusy = queuePlaylistBusy,
                onLocalQueueCreate = onLocalQueueCreate,
                onLocalQueueAdd = onLocalQueueAdd,
                onLocalQueueNext = onLocalQueueNext,
                onLocalQueueReset = onLocalQueueReset,
            )
        }

        // Select-all bar (mirrors TS Select all / Deselect all)
        if (albums.isNotEmpty()) {
            val allVisibleSelected = albums.all { selectedAlbums.contains(it.id) }
            TextButton(
                onClick = onToggleAllVisible,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(if (allVisibleSelected) "Deselect visible (${albums.size})" else "Select all visible (${albums.size})")
            }
        }

        // Give the grid the remaining height and keep the Sync footer pinned:
        // an unweighted LazyVerticalGrid consumes the whole column, which
        // pushed the footer below the viewport once the library was populated.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (loading && albums.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (albums.isEmpty() && syncState is SyncState.Idle) {
                EmptyState(
                    message = "Your library is empty. Sync with Spotify to load your saved albums.",
                    onSyncClick = onSyncClick
                )
            } else {
                when (viewMode) {
                    ViewMode.List -> {
                        val listState = rememberLazyListState()
                        AutoLoadMoreList(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            listState = listState,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(albums) { album ->
                                AlbumListRow(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.id),
                                    onToggle = { onToggleSelect(album.id) },
                                    onOpen = { onOpenAlbum(album) },
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                    ViewMode.Compact -> {
                        val gridState = rememberLazyGridState()
                        AutoLoadMore(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            gridState = gridState,
                            onLoadMore = onLoadMore
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(albums) { album ->
                                SelectableAlbumCompactCard(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.id),
                                    onToggle = { onToggleSelect(album.id) },
                                    onOpen = { onOpenAlbum(album) }
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                    ViewMode.Grid -> {
                        val gridState = rememberLazyGridState()
                        AutoLoadMore(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            gridState = gridState,
                            onLoadMore = onLoadMore
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = gridSize.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(albums) { album ->
                                SelectableAlbumCard(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.id),
                                    onToggle = { onToggleSelect(album.id) },
                                    onOpen = { onOpenAlbum(album) }
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$total albums", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PlaylistsTab(
    playlists: List<SpotifyPlaylist>,
    selectedPlaylist: SpotifyPlaylist?,
    playlistAlbums: List<com.spotifydashboard.app.data.PlaylistAlbumEntity>,
    playlistTotal: Int,
    playlistLoading: Boolean,
    playlistLoadingMore: Boolean,
    playlistHasMore: Boolean,
    filters: FilterState,
    genres: List<String>,
    artists: List<String>,
    years: List<Int>,
    gridSize: Int,
    viewMode: ViewMode,
    boardBarVisible: Boolean,
    showFilters: Boolean,
    syncState: PlaylistSyncState,
    selectedAlbums: Set<String>,
    onSelectPlaylist: (SpotifyPlaylist?) -> Unit,
    onSyncPlaylist: (String) -> Unit,
    onCancelPlaylistSync: () -> Unit,
    onDismissPlaylistSync: () -> Unit,
    onFilterChange: (FilterState.() -> FilterState) -> Unit,
    onResetFilters: () -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onLoadMore: () -> Unit,
    onExportPlaylist: () -> Unit,
    onLoadPlaylists: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    hasLocalQueue: Boolean = false,
    localQueueBusy: Boolean = false,
    activeQueueBusy: Boolean = false,
    queuePlaylistBusy: Boolean = false,
    onLocalQueueCreate: (() -> Unit)? = null,
    onLocalQueueAdd: (() -> Unit)? = null,
    onLocalQueueNext: (() -> Unit)? = null,
    onLocalQueueReset: (() -> Unit)? = null,
    onOpenAlbum: (PlaylistAlbumEntity) -> Unit = {},
    playlistExpiresAt: String? = null,
    onOpenRetention: () -> Unit = {},
    onDeleteSelectedPlaylists: (List<String>) -> Unit = {},
    onTogglePlaylistVisibility: (com.spotifydashboard.app.api.SpotifyPlaylist, Boolean) -> Unit = { _, _ -> },
) {
    // filters the rendered list and never feeds back into the loading branch.
    var nameSearch by remember { mutableStateOf("") }
    var listSortField by remember { mutableStateOf(PlaylistListOptions.SORT_NAME) }
    var listSortOrder by remember { mutableStateOf("asc") }
    val visiblePlaylists = remember(playlists, nameSearch, listSortField, listSortOrder) {
        PlaylistListOptions.apply(playlists, nameSearch, listSortField, listSortOrder)
    }

    // Batch selection state for the playlist list (SPOTI-24).
    var selectedPlaylists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // If a playlist is selected, show its album grid
    if (selectedPlaylist != null) {
        PlaylistAlbumScreen(
            playlist = selectedPlaylist,
            albums = playlistAlbums,
            total = playlistTotal,
            loading = playlistLoading,
            loadingMore = playlistLoadingMore,
            hasMore = playlistHasMore,
            filters = filters,
            genres = genres,
            artists = artists,
            years = years,
            gridSize = gridSize,
            viewMode = viewMode,
            boardBarVisible = boardBarVisible,
            showFilters = showFilters,
            syncState = syncState,
            selectedAlbums = selectedAlbums,
            onBack = { onSelectPlaylist(null) },
            onResync = { onSyncPlaylist(selectedPlaylist.id) },
            onCancelSync = onCancelPlaylistSync,
            onDismissSyncState = onDismissPlaylistSync,
            onFilterChange = onFilterChange,
            onResetFilters = onResetFilters,
            onGridSizeChange = onGridSizeChange,
            onViewModeChange = onViewModeChange,
            onLoadMore = onLoadMore,
            onExportPlaylist = onExportPlaylist,
            onToggleSelect = onToggleSelect,
            onToggleAllVisible = onToggleAllVisible,
            onClearSelection = onClearSelection,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            hasLocalQueue = hasLocalQueue,
            localQueueBusy = localQueueBusy,
            activeQueueBusy = activeQueueBusy,
            queuePlaylistBusy = queuePlaylistBusy,
            onLocalQueueCreate = onLocalQueueCreate,
            onLocalQueueAdd = onLocalQueueAdd,
            onLocalQueueNext = onLocalQueueNext,
            onLocalQueueReset = onLocalQueueReset,
            onOpenAlbum = onOpenAlbum,
            playlistExpiresAt = playlistExpiresAt,
            onOpenRetention = onOpenRetention
        )
        return
    }

    // Otherwise show the playlist list
    Column(modifier = Modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading playlists...")
                    LaunchedEffect(Unit) {
                        onLoadPlaylists()
                    }
                }
            }
        } else {
            // Board bar: search the playlists by name, sort them, refresh.
            AnimatedVisibility(visible = boardBarVisible) {
                Column {
                    TextField(
                        value = nameSearch,
                        onValueChange = { nameSearch = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search playlists...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (nameSearch.isNotEmpty()) {
                                IconButton(onClick = { nameSearch = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Three sort chips can exceed the row on narrow phones, so
                        // they scroll; the view toggle and refresh stay pinned at
                        // the right and are always reachable (SPOTI-24).
                        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            PlaylistListSortRow(
                                sortField = listSortField,
                                sortOrder = listSortOrder,
                                onSortChange = { field, order ->
                                    listSortField = field
                                    listSortOrder = order
                                },
                            )
                        }
                        ViewModeToggle(
                            mode = viewMode,
                            onModeChange = onViewModeChange,
                        )
                        IconButton(onClick = onLoadPlaylists) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh playlists",
                                tint = SpotifyLightGray,
                            )
                        }
                    }
                }
            }

            if (selectedPlaylists.isNotEmpty()) {
                PlaylistSelectionToolbar(
                    count = selectedPlaylists.size,
                    onClear = { selectedPlaylists = emptySet() },
                    onSelectAll = { selectedPlaylists = visiblePlaylists.map { it.id }.toSet() },
                    onDelete = { showDeleteConfirm = true }
                )
            }
            if (visiblePlaylists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No playlists match \"$nameSearch\"",
                        color = SpotifyLightGray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (viewMode == ViewMode.List) {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visiblePlaylists) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            isSelected = selectedPlaylists.contains(playlist.id),
                            onSelect = { onSelectPlaylist(playlist) },
                            onToggle = {
                                selectedPlaylists = if (selectedPlaylists.contains(playlist.id))
                                    selectedPlaylists - playlist.id else selectedPlaylists + playlist.id
                            },
                            onSync = { onSyncPlaylist(playlist.id) },
                            onTogglePublic = { newPublic -> onTogglePlaylistVisibility(playlist, newPublic) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (viewMode == ViewMode.Compact) 110.dp else 170.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visiblePlaylists) { playlist ->
                        PlaylistGridCard(
                            playlist = playlist,
                            compact = viewMode == ViewMode.Compact,
                            isSelected = selectedPlaylists.contains(playlist.id),
                            onSelect = { onSelectPlaylist(playlist) },
                            onToggle = {
                                selectedPlaylists = if (selectedPlaylists.contains(playlist.id))
                                    selectedPlaylists - playlist.id else selectedPlaylists + playlist.id
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        PlaylistDeleteConfirmDialog(
            count = selectedPlaylists.size,
            onConfirm = {
                showDeleteConfirm = false
                onDeleteSelectedPlaylists(selectedPlaylists.toList())
                selectedPlaylists = emptySet()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
    }

@Composable
fun PlaylistSelectionToolbar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$count selected", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onSelectAll) { Text("Select all") }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = SpotifyRed)
                }
            }
        }
    }
}

@Composable
fun PlaylistDeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("Delete playlists?") },
        text = { Text("Delete $count selected playlist${if (count == 1) "" else "s"}? They will be unfollowed from Spotify and removed from this device. This cannot be undone.") },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Full-screen album grid shown when a playlist is selected.
 * Mirrors the Library tab layout: header with back + resync, then the album grid.
 */
@Composable
fun PlaylistAlbumScreen(
    playlist: SpotifyPlaylist,
    albums: List<PlaylistAlbumEntity>,
    total: Int,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    filters: FilterState,
    genres: List<String>,
    artists: List<String>,
    years: List<Int>,
    gridSize: Int,
    viewMode: ViewMode,
    boardBarVisible: Boolean,
    showFilters: Boolean,
    syncState: PlaylistSyncState,
    selectedAlbums: Set<String>,
    onBack: () -> Unit,
    onResync: () -> Unit,
    onCancelSync: () -> Unit,
    onDismissSyncState: () -> Unit,
    onFilterChange: (FilterState.() -> FilterState) -> Unit,
    onResetFilters: () -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onLoadMore: () -> Unit,
    onExportPlaylist: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    hasLocalQueue: Boolean = false,
    localQueueBusy: Boolean = false,
    activeQueueBusy: Boolean = false,
    queuePlaylistBusy: Boolean = false,
    onLocalQueueCreate: (() -> Unit)? = null,
    onLocalQueueAdd: (() -> Unit)? = null,
    onLocalQueueNext: (() -> Unit)? = null,
    onLocalQueueReset: (() -> Unit)? = null,
    onOpenAlbum: (PlaylistAlbumEntity) -> Unit = {},
    playlistExpiresAt: String? = null,
    onOpenRetention: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header row: back button, playlist name, export + resync buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to playlists")
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "$total albums · ${playlist.tracks.total} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray
                )
                Text(
                    text = if (playlistExpiresAt != null)
                        "Deletes ${formatRetentionDate(playlistExpiresAt)}"
                    else "Kept forever",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playlistExpiresAt != null) SpotifyRed else SpotifyLightGray
                )
            }
            IconButton(onClick = onOpenRetention) {
                Icon(Icons.Default.Timer, contentDescription = "Playlist retention")
            }
            IconButton(onClick = onExportPlaylist) {
                Icon(Icons.Default.Share, contentDescription = "Export playlist CSV")
            }
            IconButton(
                onClick = onResync,
                enabled = syncState !is PlaylistSyncState.Progress
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Re-sync playlist")
            }
        }

        // Search + sort + view options (mirrors the library tab). The bar
        // can slide away while scrolling down - the autohide setting.
        AnimatedVisibility(visible = boardBarVisible) {
            Column {
                TextField(
                    value = filters.search,
                    onValueChange = { onFilterChange { copy(search = it) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search albums or artists...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filters.search.isNotEmpty()) {
                            IconButton(onClick = { onFilterChange { copy(search = "") } }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    }
                )

                SortChipsRow(
                    filters = filters,
                    options = PLAYLIST_SORTS,
                    onFilterChange = onFilterChange
                )

                ViewOptionsRow(
                    mode = viewMode,
                    gridSize = gridSize,
                    onModeChange = onViewModeChange,
                    onGridSizeChange = onGridSizeChange,
                )
            }
        }

        // Sync progress / error
        when (val state = syncState) {
            is PlaylistSyncState.Progress -> {
                val pct = if (state.total > 0) (state.fetched.toFloat() / state.total * 100).toInt() else 0
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${syncStageLabel(state.stage)}: ${state.fetched}/${state.total}", fontWeight = FontWeight.Medium)
                        Text("$pct%", fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = pct / 100f, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = onCancelSync,
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)
                    ) {
                        Text("Stop")
                    }
                }
            }
            is PlaylistSyncState.Completed -> {
                SyncResultBanner(
                    title = "✓ Synced ${state.albumCount} albums",
                    subtitle = null,
                    onDismiss = onDismissSyncState
                )
            }
            is PlaylistSyncState.Cancelled -> {
                SyncResultBanner(
                    title = "Sync stopped — partial progress saved",
                    subtitle = null,
                    onDismiss = onDismissSyncState
                )
            }
            is PlaylistSyncState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyRed.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = SpotifyRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Sync error: ${state.message}",
                            color = SpotifyRed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissSyncState) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SpotifyRed)
                        }
                    }
                }
            }
            else -> {}
        }

        // Selection toolbar
        if (selectedAlbums.isNotEmpty()) {
            SelectionToolbar(
                count = selectedAlbums.size,
                onClear = onClearSelection,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                hasLocalQueue = hasLocalQueue,
                localQueueBusy = localQueueBusy,
                activeQueueBusy = activeQueueBusy,
                queuePlaylistBusy = queuePlaylistBusy,
                onLocalQueueCreate = onLocalQueueCreate,
                onLocalQueueAdd = onLocalQueueAdd,
                onLocalQueueNext = onLocalQueueNext,
                onLocalQueueReset = onLocalQueueReset,
            )
        }

        if (showFilters) {
            FilterPanel(
                filters = filters,
                genres = genres,
                artists = artists,
                years = years,
                onFilterChange = onFilterChange,
                onReset = onResetFilters
            )
        }

        val plActiveCount = activeFilterCount(filters)
        Text(
            text = "$total album${if (total != 1) "s" else ""}" +
                (if (plActiveCount > 0) " (filtered)" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Select-all bar
        if (albums.isNotEmpty()) {
            val allVisibleSelected = albums.all { selectedAlbums.contains(it.albumId) }
            TextButton(
                onClick = onToggleAllVisible,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(if (allVisibleSelected) "Deselect visible (${albums.size})" else "Select all visible (${albums.size})")
            }
        }

        // Album grid
        when {
            loading && albums.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading albums...")
                    }
                }
            }
            albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = SpotifyLightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (plActiveCount > 0) "No albums match these filters."
                                else "No albums found. Tap \u21BB to sync this playlist.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SpotifyLightGray
                        )
                    }
                }
            }
            else -> {
                when (viewMode) {
                    ViewMode.List -> {
                        val listState = rememberLazyListState()
                        AutoLoadMoreList(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            listState = listState,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(albums, key = { it.albumId }) { album ->
                                PlaylistAlbumListRow(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.albumId),
                                    onToggle = { onToggleSelect(album.albumId) },
                                    onOpen = { onOpenAlbum(album) },
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                    ViewMode.Compact -> {
                        val gridState = rememberLazyGridState()
                        AutoLoadMore(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            gridState = gridState,
                            onLoadMore = onLoadMore
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            state = gridState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(albums, key = { it.albumId }) { album ->
                                PlaylistAlbumCompactCard(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.albumId),
                                    onToggle = { onToggleSelect(album.albumId) },
                                    onOpen = { onOpenAlbum(album) }
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                    ViewMode.Grid -> {
                        val gridState = rememberLazyGridState()
                        AutoLoadMore(
                            hasMore = hasMore,
                            loading = loading,
                            loadingMore = loadingMore,
                            gridState = gridState,
                            onLoadMore = onLoadMore
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = gridSize.dp),
                            state = gridState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(albums, key = { it.albumId }) { album ->
                                PlaylistAlbumCard(
                                    album = album,
                                    isSelected = selectedAlbums.contains(album.albumId),
                                    onToggle = { onToggleSelect(album.albumId) },
                                    onOpen = { onOpenAlbum(album) }
                                )
                            }
                            if (hasMore) {
                                item { LoadMoreFooter(loadingMore = loadingMore, onLoadMore = onLoadMore) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Album card for PlaylistAlbumEntity — mirrors SelectableAlbumCard. */
@Composable
fun PlaylistAlbumCard(
    album: PlaylistAlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Column {
                SubcomposeAsyncImage(
                    model = artworkModel(album.imageUrl),
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                )
                Text(
                    text = album.title.ifBlank { "Unknown Album" },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    text = if (album.artist.isNotBlank()) album.artist else "${album.trackCount} tracks",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    maxLines = 1
                )
            }
            SpotifyLinkChip(spotifyAlbumUrl(album.albumId), Modifier.align(Alignment.TopStart))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            )
            IconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Album tracks",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Sort chips for the playlist list: by name or by track count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistListSortRow(
    sortField: String,
    sortOrder: String = "asc",
    onSortChange: (String, String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = sortField == PlaylistListOptions.SORT_NAME,
            onClick = {
                if (sortField == PlaylistListOptions.SORT_NAME) {
                    onSortChange(PlaylistListOptions.SORT_NAME, if (sortOrder == "asc") "desc" else "asc")
                } else {
                    onSortChange(PlaylistListOptions.SORT_NAME, "asc")
                }
            },
            label = { Text("Name" + if (sortField == PlaylistListOptions.SORT_NAME) (if (sortOrder == "asc") " ↑" else " ↓") else "") },
            colors = spotifyChipColors(),
        )
        FilterChip(
            selected = sortField == PlaylistListOptions.SORT_TRACKS,
            onClick = {
                if (sortField == PlaylistListOptions.SORT_TRACKS) {
                    onSortChange(PlaylistListOptions.SORT_TRACKS, if (sortOrder == "asc") "desc" else "asc")
                } else {
                    onSortChange(PlaylistListOptions.SORT_TRACKS, "desc")
                }
            },
            label = { Text("Tracks" + if (sortField == PlaylistListOptions.SORT_TRACKS) (if (sortOrder == "asc") " ↑" else " ↓") else "") },
            colors = spotifyChipColors(),
        )
        FilterChip(
            selected = sortField == PlaylistListOptions.SORT_ADDED,
            onClick = {
                if (sortField == PlaylistListOptions.SORT_ADDED) {
                    onSortChange(PlaylistListOptions.SORT_ADDED, if (sortOrder == "asc") "desc" else "asc")
                } else {
                    // Newest first matches Spotify's native /me/playlists return order.
                    onSortChange(PlaylistListOptions.SORT_ADDED, "desc")
                }
            },
            label = { Text("Date Added" + if (sortField == PlaylistListOptions.SORT_ADDED) (if (sortOrder == "asc") " ↑" else " ↓") else "") },
            colors = spotifyChipColors(),
        )
    }
}

/** Playlist card for the grid view of the Playlists tab. */
@Composable
fun PlaylistGridCard(
    playlist: SpotifyPlaylist,
    compact: Boolean,
    isSelected: Boolean = false,
    onSelect: () -> Unit,
    onToggle: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(containerColor = SpotifyCardGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box {
            Column {
                SubcomposeAsyncImage(
                    model = artworkModel(playlist.images?.firstOrNull()?.url),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    },
                )
                Text(
                    text = playlist.name,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (!compact) {
                    Text(
                        text = "${playlist.tracks.total} tracks",
                        modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        maxLines = 1,
                    )
                }
            }
            // Selection checkbox overlay
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
            )
        }
    }
}

@Composable
fun PlaylistCard(
    playlist: SpotifyPlaylist,
    isSelected: Boolean = false,
    onSelect: () -> Unit,
    onToggle: () -> Unit = {},
    onSync: () -> Unit,
    onTogglePublic: ((Boolean) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${playlist.tracks.total} tracks", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
            }
            SpotifyLinkChip(spotifyPlaylistUrl(playlist.id))
            onTogglePublic?.let { cb ->
                val isPublic = playlist.public ?: false
                IconButton(onClick = { cb(!isPublic) }) {
                    Icon(
                        if (isPublic) Icons.Default.Public else Icons.Default.Lock,
                        contentDescription = if (isPublic) "Make private" else "Make public",
                        tint = if (isPublic) SpotifyGreen else SpotifyLightGray,
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open playlist",
                tint = SpotifyLightGray
            )
        }
    }
}

/**
 * Small ↗ chip that opens a Spotify page. Renders nothing for ids that are
 * not 22-char Spotify ids (CSV-imported rows), so there is never a dead link.
 */
@Composable
fun SpotifyLinkChip(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    val context = LocalContext.current
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { openSpotifyUrl(context, url) }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            "↗",
            color = SpotifyGreen,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SelectableAlbumCard(
    album: AlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Column {
                SubcomposeAsyncImage(
                    model = artworkModel(album.imageUrl),
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                )
                Text(
                    text = album.title,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    text = album.artist,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    maxLines = 1
                )
            }
            SpotifyLinkChip(spotifyAlbumUrl(album.id), Modifier.align(Alignment.TopStart))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            )
            IconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Album tracks",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Dense album card for the compact view: cover + title only, like the web
 * frontends' compact grid (no artist, year or genre line).
 */
@Composable
fun SelectableAlbumCompactCard(
    album: AlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Box {
            SubcomposeAsyncImage(
                model = artworkModel(album.imageUrl),
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)),
                loading = {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White.copy(alpha = 0.5f),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp),
            )
            IconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(28.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Album tracks",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
            }
            Text(
                text = album.title,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * One playlist album as a row - the list view of a playlist.
 */
@Composable
fun PlaylistAlbumListRow(
    album: PlaylistAlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        SubcomposeAsyncImage(
            model = artworkModel(album.imageUrl),
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            loading = {
                Box(
                    modifier = Modifier.size(44.dp).background(Color(0xFF282828)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 2.dp,
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier.size(44.dp).background(Color(0xFF282828)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        ) {
            Text(
                album.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                album.artist + (if (album.year > 0) " · ${album.year}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Album tracks",
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** Dense card for the compact view of a playlist. */
@Composable
fun PlaylistAlbumCompactCard(
    album: PlaylistAlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Box {
            SubcomposeAsyncImage(
                model = artworkModel(album.imageUrl),
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)),
                loading = {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White.copy(alpha = 0.5f),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF282828)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp),
            )
            IconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(28.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Album tracks",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
            }
            Text(
                text = album.title,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SelectionToolbar(
    count: Int,
    onClear: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDeleteFromLibrary: (() -> Unit)? = null,
    deleteBusy: Boolean = false,
    hasLocalQueue: Boolean = false,
    localQueueBusy: Boolean = false,
    activeQueueBusy: Boolean = false,
    queuePlaylistBusy: Boolean = false,
    onLocalQueueCreate: (() -> Unit)? = null,
    onLocalQueueAdd: (() -> Unit)? = null,
    onLocalQueueNext: (() -> Unit)? = null,
    onLocalQueueReset: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$count selected", fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onClear) { Text("Clear") }
                    TextButton(onClick = onAddToQueue) { Text("Queue") }
                    TextButton(onClick = onAddToPlaylist) { Text("Add to Playlist") }
                    if (onRemoveFromPlaylist != null) {
                        TextButton(onClick = onRemoveFromPlaylist) {
                            Text("Remove", color = SpotifyRed)
                        }
                    }
                    if (onDeleteFromLibrary != null) {
                        TextButton(
                            onClick = onDeleteFromLibrary,
                            enabled = !deleteBusy,
                        ) {
                            Text(
                                if (deleteBusy) "Deleting…" else "Delete",
                                color = if (deleteBusy) SpotifyLightGray else SpotifyRed,
                            )
                        }
                    }
                }
            }

            if (onLocalQueueCreate != null && onLocalQueueAdd != null &&
                onLocalQueueNext != null && onLocalQueueReset != null
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Local queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpotifyLightGray,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onLocalQueueCreate,
                            enabled = !localQueueBusy && !activeQueueBusy && !queuePlaylistBusy
                        ) {
                            Text(
                                when {
                                    localQueueBusy -> "Working\u2026"
                                    hasLocalQueue -> "New queue playlist"
                                    else -> "Queue playlist"
                                }
                            )
                        }
                        TextButton(
                            onClick = onLocalQueueAdd,
                            enabled = hasLocalQueue && !localQueueBusy &&
                                !activeQueueBusy && !queuePlaylistBusy
                        ) { Text("Add selected to end") }
                        TextButton(
                            onClick = onLocalQueueNext,
                            enabled = hasLocalQueue && !localQueueBusy &&
                                !activeQueueBusy && !queuePlaylistBusy
                        ) { Text("Queue next") }
                        TextButton(
                            onClick = onLocalQueueReset,
                            enabled = hasLocalQueue && !localQueueBusy &&
                                !activeQueueBusy && !queuePlaylistBusy
                         ) { Text("Reset (local only)") }
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
    }
}

@Composable
fun EmptyState(message: String, onSyncClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = SpotifyLightGray)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyLarge, color = SpotifyLightGray)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSyncClick) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync Now")
            }
        }
    }
}

@Composable
fun LoginScreen(onLogin: () -> Unit, onConfigure: () -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 400.dp)) {
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(SpotifyGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "Spotify Dashboard", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Sign in with your Spotify account to load and browse your saved album library.", style = MaterialTheme.typography.bodyLarge, color = SpotifyLightGray, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)) {
                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Connect with Spotify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onConfigure) {
                Text(text = "Configure Spotify credentials", color = SpotifyLightGray)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Requires a Spotify account. Only read access to your saved library is requested.", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * Server login — remote (media-server) mode only. Email/password app account
 * on any conformant /api/v1 backend; Spotify itself gets linked afterwards
 * in Settings → Media server.
 */
@Composable
fun ServerLoginScreen(
    serverUrl: String,
    busy: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onBackToDevice: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !busy
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Top-left back arrow so it stays reachable on small screens.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onBackToDevice, enabled = !busy) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Use on-device library instead", tint = SpotifyLightGray)
                }
            }
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(SpotifyGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "Media server", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sign in to $serverUrl — sync and the library database run on the server.",
                style = MaterialTheme.typography.bodyLarge, color = SpotifyLightGray, modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") },
                singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                // Do not ask the IME for TYPE_TEXT_VARIATION_PASSWORD on this
                // TCL build: it tries to switch to the missing OEM secure
                // input service and crashes the app. Masking still hides the
                // value, and auto-correct is off.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrect = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSubmit) onLogin(email.trim(), password)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = SpotifyRed)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onLogin(email.trim(), password) },
                enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)
            ) {
                Text(text = if (busy) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { onRegister(email.trim(), password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Create account")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onBackToDevice, enabled = !busy) {
                Text(text = "Use on-device library instead", color = SpotifyLightGray)
            }
        }
    }
}

/**
 * Media-server section for the Settings screen: local vs remote toggle,
 * server URL + connection test, server account, and Spotify link state.
 */
@Composable
fun MediaServerSection(
    mode: String,
    serverUrl: String,
    email: String?,
    busy: Boolean,
    pushBusy: Boolean,
    testResult: String?,
    hasSession: Boolean,
    onSetMode: (String) -> Unit,
    onSetServerUrl: (String) -> Unit,
    onTestConnection: (String) -> Unit,
    onRequestServerLogin: () -> Unit,
    onPushToServer: () -> Unit,
    onLogoutServer: () -> Unit
) {
    val isRemote = mode == "remote"
    var urlDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var showPushConfirm by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Media server", fontWeight = FontWeight.Medium)
                Text(
                    if (isRemote) "Sync + database run on your server."
                    else "Everything stays in this app (works offline).",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray
                )
            }
            Switch(
                checked = isRemote,
                onCheckedChange = { checked ->
                    onSetMode(if (checked) "remote" else "local")
                    // Turning the server on must not silently browse a
                    // backend that expects a JWT. Prompt immediately.
                    if (checked && !hasSession) onRequestServerLogin()
                }
            )
        }
        if (isRemote) {
            if (hasSession) {
                Text(
                    text = if (email.isNullOrBlank()) "Signed in to media server"
                    else "Signed in as $email",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyGreen,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://10.0.2.2:4000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSetServerUrl(urlDraft.trim()) },
                    enabled = urlDraft.trim().isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save URL")
                }
                OutlinedButton(
                    onClick = { onTestConnection(urlDraft.trim()) },
                    enabled = urlDraft.trim().isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (busy) "Testing…" else "Test connection")
                }
            }
            if (testResult != null) {
                Text(
                    text = testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testResult.startsWith("✓")) SpotifyGreen else SpotifyRed,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (hasSession) {
                OutlinedButton(
                    onClick = { showPushConfirm = true },
                    enabled = !pushBusy && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (pushBusy) "Pushing library…" else "Push this phone's library")
                }
                Text(
                    "Sends the library synced on this phone to your server account. " +
                        "The server copy mirrors this phone, so albums that are not here " +
                        "are removed there. Playlists, tracks and retention are kept, and " +
                        "the server does not need a Spotify link for this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (showPushConfirm) {
                    AlertDialog(
                        onDismissRequest = { showPushConfirm = false },
                        title = { Text("Push this phone's library?") },
                        text = {
                            Text(
                                "Your server account's albums will be replaced with the " +
                                    "library on this phone. Albums on the server that are " +
                                    "not on this phone will be removed. Playlists and " +
                                    "tracks are not touched."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPushConfirm = false
                                onPushToServer()
                            }) { Text("Push & mirror") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPushConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                OutlinedButton(
                    onClick = onLogoutServer,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign out of media server")
                }
            } else {
                Button(
                    onClick = onRequestServerLogin,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)
                ) {
                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in to media server")
                }
            }
        }
    }
}

/** Auto-backup controls shared by the Sync dialog Backup tab and the Settings screen. */@Composable
fun BackupSettingsSection(
    backupSettings: BackupSettings,
    onToggleBackup: () -> Unit,
    onSetBackupSubdir: (String) -> Unit,
    onExportNow: () -> Unit
) {
    var subdirDraft by remember(backupSettings.subdirName) {
        mutableStateOf(backupSettings.subdirName)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Auto-backup after sync", fontWeight = FontWeight.Medium)
            Text(
                "Saves a CSV copy of your library on every Spotify sync.",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray
            )
        }
        Switch(checked = backupSettings.enabled, onCheckedChange = { onToggleBackup() })
    }
    OutlinedTextField(
        value = subdirDraft,
        onValueChange = {
            subdirDraft = it
            onSetBackupSubdir(it)
        },
        label = { Text("Backup folder") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    )
    if (backupSettings.lastBackup.isNotBlank()) {
        Text(
            "Last backup: ${backupSettings.lastBackup}",
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (backupSettings.lastPath.isNotBlank()) {
            Text(
                backupSettings.lastPath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray
            )
        }
    }
    Button(
        onClick = onExportNow,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Export now")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncModal(
    onDismiss: () -> Unit,
    onConfirm: (clean: Boolean, includeTracks: Boolean) -> Unit,
    csvResult: CsvImportResult?,
    backupSettings: BackupSettings,
    syncHistory: List<JobDto>?,
    isRemote: Boolean,
    onImportCsv: (String) -> Unit,
    onClearCsvResult: () -> Unit,
    onExportNow: () -> Unit,
    onToggleBackup: () -> Unit,
    onSetBackupSubdir: (String) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var cleanSync by remember { mutableStateOf(false) }
    var includeTracks by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickingFile by remember { mutableStateOf(false) }
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pickingFile = false
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            withContext(Dispatchers.Main) {
                pickingFile = false
                onImportCsv(text)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Library") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Spotify") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("CSV") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Backup") })
                }
                Spacer(modifier = Modifier.height(12.dp))
                when (tab) {
                    0 -> {
                        Text("Fetches your saved albums from Spotify. Albums already in your library are skipped, so repeat syncs are fast.")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .clickable { cleanSync = !cleanSync },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = cleanSync, onCheckedChange = { cleanSync = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Clean sync (start from zero)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Deletes your local library first. Use this if your data looks wrong.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clickable { includeTracks = !includeTracks },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = includeTracks,
                                onCheckedChange = { includeTracks = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Include track lists",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Off by default. Tracks load lazily when you open an album; enable this to cache every album's track list during sync.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray
                                )
                            }
                        }

                        // Server-side history: what ran before this device was
                        // even open. Local mode syncs on-device, so there is
                        // nothing to list.
                        if (isRemote) {
                            val history = syncHistory
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Recent syncs",
                                style = MaterialTheme.typography.labelMedium,
                                color = SpotifyLightGray
                            )
                            when {
                                history == null -> Text(
                                    "Loading…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                history.isEmpty() -> Text(
                                    "No syncs yet — start one with Start Sync.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                else -> history.forEach { job ->
                                    Text(
                                        SyncHistory.rowText(job),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (job.status == "error") SpotifyRed else SpotifyLightGray,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        Text(
                            "Import albums from a CSV file. Columns: id, title, artist, year, genre, image_url, added_at.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                pickingFile = true
                                onClearCsvResult()
                                csvPicker.launch(arrayOf("text/csv", "text/plain", "text/comma-separated-values"))
                            },
                            enabled = !pickingFile,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            if (pickingFile) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reading file…")
                            } else {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose CSV file")
                            }
                        }
                        csvResult?.let { r ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "✓ ${r.imported} imported · ${r.skipped} skipped",
                                fontWeight = FontWeight.Medium,
                                color = SpotifyGreen
                            )
                            r.errors.take(5).forEach { e ->
                                Text(
                                    e,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    2 -> {
                        BackupSettingsSection(
                            backupSettings = backupSettings,
                            onToggleBackup = onToggleBackup,
                            onSetBackupSubdir = onSetBackupSubdir,
                            onExportNow = onExportNow
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (tab == 0) {
                Button(onClick = { onConfirm(cleanSync, includeTracks) }) { Text("Start Sync") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Human-readable stage names, mirroring the TS STAGE_LABELS. */
fun syncStageLabel(stage: com.spotifydashboard.app.repository.SyncStage): String = when (stage) {
    com.spotifydashboard.app.repository.SyncStage.ALBUMS -> "Fetching albums"
    com.spotifydashboard.app.repository.SyncStage.GENRES -> "Enriching genres"
    com.spotifydashboard.app.repository.SyncStage.SAVING -> "Saving to library"
}

/** Dismissible green result banner for completed / cancelled syncs. */
@Composable
fun SyncResultBanner(title: String, subtitle: String?, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SpotifyGreen.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SpotifyGreen)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = SpotifyGreen, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SpotifyGreen)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared filter/sort components (mirror TS FilterBar)
// ---------------------------------------------------------------------------

data class SortOption(val value: String, val label: String)

val LIBRARY_SORTS = listOf(
    SortOption("artist", "Artist"),
    SortOption("title", "Title"),
    SortOption("year", "Year"),
    SortOption("genre", "Genre"),
    SortOption("added_at", "Date Added")
)

val PLAYLIST_SORTS = listOf(
    SortOption("artist", "Artist"),
    SortOption("title", "Title"),
    SortOption("year", "Year"),
    SortOption("genre", "Genre"),
    SortOption("track_count", "Tracks"),
    SortOption("added_at", "Date Added")
)

/** Number of active filters — drives the toolbar badge (mirrors TS activeCount). */
fun activeFilterCount(filters: FilterState): Int = listOf(
    filters.search.isNotEmpty(),
    filters.genre.isNotEmpty(),
    filters.artist.isNotEmpty(),
    filters.albumType.isNotEmpty(),
    filters.yearFrom != null,
    filters.yearTo != null
).count { it }

/** Sort chips row — tapping the active chip toggles asc/desc (mirrors TS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortChipsRow(
    filters: FilterState,
    options: List<SortOption>,
    onFilterChange: (FilterState.() -> FilterState) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { opt ->
            val active = filters.sortField == opt.value
            FilterChip(
                selected = active,
                onClick = {
                    if (active) {
                        onFilterChange { copy(sortOrder = if (sortOrder == "asc") "desc" else "asc") }
                    } else {
                        onFilterChange { copy(sortField = opt.value, sortOrder = "asc") }
                    }
                },
                label = {
                    Text(opt.label + if (active) (if (filters.sortOrder == "asc") " ↑" else " ↓") else "")
                },
                colors = spotifyChipColors()
            )
        }
    }
}

/**
 * View-mode switch (mirrors the TS `view-mode-group`): grid, list, compact.
 * The compact grid is the dense one without the metadata, like the web apps.
 */
@Composable
fun ViewModeToggle(
    mode: ViewMode,
    onModeChange: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            ViewMode.Grid to Icons.Default.GridView,
            ViewMode.List to Icons.Default.ViewList,
            ViewMode.Compact to Icons.Default.ViewCompact,
        ).forEach { (candidate, icon) ->
            IconButton(
                onClick = { onModeChange(candidate) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = when (candidate) {
                        ViewMode.Grid -> "Grid view"
                        ViewMode.List -> "List view"
                        ViewMode.Compact -> "Compact grid"
                    },
                    tint = if (mode == candidate) SpotifyGreen else SpotifyLightGray,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** View-mode switch + grid density slider, the row the TS filter bar carries. */
@Composable
fun ViewOptionsRow(
    mode: ViewMode,
    gridSize: Int,
    onModeChange: (ViewMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewModeToggle(mode = mode, onModeChange = onModeChange)
        if (mode == ViewMode.Grid) {
            Slider(
                value = gridSize.toFloat(),
                onValueChange = { onGridSizeChange(it.toInt()) },
                valueRange = 80f..280f,
                steps = 19,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** One album as a row - the list view of the album boards. */
@Composable
fun AlbumListRow(
    album: AlbumEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        SubcomposeAsyncImage(
            model = artworkModel(album.imageUrl),
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            loading = {
                Box(
                    modifier = Modifier.size(44.dp).background(Color(0xFF282828)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 2.dp,
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier.size(44.dp).background(Color(0xFF282828)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        ) {
            Text(
                album.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                album.artist + (if (album.year > 0) " · ${album.year}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Album tracks",
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** Grid density slider (mirrors TS grid-size slider, 80–280px, default 160). */
@Composable
fun GridSizeRow(gridSize: Int, onGridSizeChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp), tint = SpotifyLightGray)
        Slider(
            value = gridSize.toFloat(),
            onValueChange = { onGridSizeChange(it.toInt()) },
            valueRange = 80f..280f,
            steps = 19,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(20.dp), tint = SpotifyLightGray)
    }
}

/** Spotify filter chips: dark when off, green with black text when on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun spotifyChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = SpotifyChipGray,
    labelColor = Color.White,
    selectedContainerColor = SpotifyGreen,
    selectedLabelColor = Color.Black
)

/** Single row in the inline dropdown list. Plain clickable row (not a menu
 * item) so taps never move focus away from the search field. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Searchable dropdown: a single field that shows the selection and filters
 * the options as you type (no separate search bar needed).
 * Rows are capped so libraries with thousands of artists stay snappy —
 * the footer tells the user to keep typing to narrow further.
 *
 * Focus handling: options render INLINE (not in a popup menu) because popup
 * windows float above the soft keyboard and swallow key taps. Typed text is
 * only ever reverted on explicit dismiss (outside tap / back / DONE) —
 * never on tap or focus.
 */
private const val DROPDOWN_MAX_ITEMS = 100

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchableDropdown(
    label: String,
    value: String,
    placeholder: String,
    options: List<String>,
    allLabel: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Editing text; resyncs only when the selection changes externally
    // (e.g. Clear filters) — tapping or refocusing never clears it.
    var text by remember(value) { mutableStateOf(value) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // While the field shows the untouched selection, list everything;
    // once the user types, narrow to matches.
    val query = if (text == value) "" else text
    val matches = remember(options, query) {
        if (query.isBlank()) options
        else options.filter { it.contains(query, ignoreCase = true) }
    }
    val shown = matches.take(DROPDOWN_MAX_ITEMS)

    /** Collapse the menu; optionally revert un-picked free text. */
    fun collapse(revert: Boolean) {
        expanded = false
        if (revert) text = value
        keyboard?.hide()
        focusManager.clearFocus()
    }

    /** DONE picks the exact match if there is one, else reverts (free text
     * can't be applied — the filter matches stored values exactly). */
    fun applyDone() {
        val exact = options.firstOrNull { it.equals(text.trim(), ignoreCase = true) }
        if (exact != null) {
            text = exact
            onSelect(exact)
        } else {
            text = value
        }
        expanded = false
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Back dismisses the menu without losing the selection.
    BackHandler(enabled = expanded) { collapse(revert = true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { applyDone() }),
            trailingIcon = {
                Row {
                    if (text.isNotEmpty()) {
                        IconButton(
                            onClick = { text = ""; onSelect(""); collapse(revert = false) },
                            modifier = Modifier.focusProperties { canFocus = false }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = {
                            if (expanded) collapse(revert = false)
                            else {
                                expanded = true
                                focusRequester.requestFocus()
                            }
                        },
                        modifier = Modifier.focusProperties { canFocus = false }
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        expanded = true
                    } else if (expanded) {
                        // Focus left while open (outside tap): revert + collapse.
                        // Skipped when already collapsed — avoids clobbering a
                        // just-applied selection on its state round-trip.
                        text = value
                        expanded = false
                    }
                }
        )
        // Inline options list instead of a popup menu: popup windows render
        // above the soft keyboard and swallow key taps, which made typing
        // impossible. Inline content pans with the IME like the rest of the form.
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 2.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OptionRow(
                        label = allLabel,
                        selected = value.isEmpty(),
                        onClick = { text = ""; onSelect(""); collapse(revert = false) }
                    )
                    if (shown.isEmpty()) {
                        Text(
                            "No matches",
                            color = SpotifyLightGray,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        shown.forEach { opt ->
                            OptionRow(
                                label = opt,
                                selected = opt == value,
                                onClick = { text = opt; onSelect(opt); collapse(revert = false) }
                            )
                        }
                        if (matches.size > shown.size) {
                            Text(
                                "…and ${matches.size - shown.size} more — keep typing to narrow",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Decade quick-pick chips derived from the library year range (mirrors TS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecadeChipsRow(
    years: List<Int>,
    filters: FilterState,
    onFilterChange: (FilterState.() -> FilterState) -> Unit
) {
    if (years.isEmpty()) return
    val minYear = years.minOrNull() ?: return
    val maxYear = years.maxOrNull() ?: return
    val start = (minYear / 10) * 10
    val end = (maxYear / 10) * 10
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var d = start
        while (d <= end) {
            val from = d
            val to = d + 9
            val active = filters.yearFrom == from && filters.yearTo == to
            FilterChip(
                selected = active,
                onClick = {
                    if (active) onFilterChange { copy(yearFrom = null, yearTo = null) }
                    else onFilterChange { copy(yearFrom = from, yearTo = to) }
                },
                label = { Text("${from}s") },
                colors = spotifyChipColors()
            )
            d += 10
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPanel(
    filters: FilterState,
    genres: List<String>,
    artists: List<String>,
    years: List<Int>,
    onFilterChange: (FilterState.() -> FilterState) -> Unit,
    onReset: () -> Unit,
    allowReleaseType: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Artist + genre share one searchable-dropdown pattern:
            // tap to browse everything, type to narrow.
            SearchableDropdown(
                label = "Artist",
                value = filters.artist,
                placeholder = if (artists.isEmpty()) "No artists yet" else "${artists.size} artists — tap or type to search",
                options = artists,
                allLabel = "All Artists",
                onSelect = { onFilterChange { copy(artist = it) } }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SearchableDropdown(
                label = "Genre",
                value = filters.genre,
                placeholder = if (genres.isEmpty()) "No genres yet" else "${genres.size} genres — tap or type to search",
                options = genres,
                allLabel = "All Genres",
                onSelect = { onFilterChange { copy(genre = it) } }
            )

            if (allowReleaseType) {
                Text(
                    "Release Type",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "" to "All",
                        "album" to "Albums",
                        "single" to "Singles",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = filters.albumType == value,
                            onClick = { onFilterChange { copy(albumType = value) } },
                            label = { Text(label) },
                            colors = spotifyChipColors(),
                        )
                    }
                }
            }

            Text("Release Year", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = filters.yearFrom?.toString() ?: "", onValueChange = { onFilterChange { copy(yearFrom = it.toIntOrNull()) } }, modifier = Modifier.weight(1f), label = { Text("From") }, singleLine = true)
                OutlinedTextField(value = filters.yearTo?.toString() ?: "", onValueChange = { onFilterChange { copy(yearTo = it.toIntOrNull()) } }, modifier = Modifier.weight(1f), label = { Text("To") }, singleLine = true)
            }
            DecadeChipsRow(years = years, filters = filters, onFilterChange = onFilterChange)

            val count = activeFilterCount(filters)
            if (count > 0) {
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyLightGray)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear $count filter${if (count > 1) "s" else ""}")
                }
            }
        }
    }
}

// ── Shortlived playlists ────────────────────────────────────────────────────

/** Human-friendly local rendering of a backend ISO-8601 expiry. */
fun formatRetentionDate(iso: String): String {
    val cleaned = iso.trim()
        .replace(Regex("\\.\\d+"), "")
        .replace("Z", "+00:00")
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )
    for (pattern in patterns) {
        try {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            if (!pattern.contains("XXX")) fmt.timeZone = TimeZone.getTimeZone("UTC")
            val date = fmt.parse(cleaned) ?: continue
            return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            // try next
        }
    }
    return iso
}

private fun showCustomDateTimePicker(
    context: android.content.Context,
    onPicked: (Long) -> Unit
) {
    val now = Calendar.getInstance()
    val dateDialog = android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val timeDialog = android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    val cal = Calendar.getInstance()
                    cal.set(year, month, day, hour, minute, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    onPicked(cal.timeInMillis)
                },
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                true
            )
            timeDialog.show()
        },
        now.get(Calendar.YEAR),
        now.get(Calendar.MONTH),
        now.get(Calendar.DAY_OF_MONTH)
    )
    dateDialog.datePicker.minDate = System.currentTimeMillis()
    dateDialog.show()
}

/**
 * Preset chips + custom date/time for a shortlived playlist.
 * `value == null` means keep forever.
 */
@Composable
fun RetentionPicker(
    value: String?,
    onChange: (String?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val presets = listOf(
        "Keep" to null,
        "1h" to 1,
        "6h" to 6,
        "1d" to 24,
        "1w" to 168,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (label, hours) ->
                AssistChip(
                    onClick = {
                        if (!enabled) return@AssistChip
                        if (hours == null) {
                            onChange(null)
                        } else {
                            onChange(
                                PlaylistRetentionStore.isoUtc(
                                    System.currentTimeMillis() + hours * 3_600_000L
                                )
                            )
                        }
                    },
                    label = { Text(label) },
                    enabled = enabled
                )
            }
            AssistChip(
                onClick = {
                    if (!enabled) return@AssistChip
                    showCustomDateTimePicker(context) { millis ->
                        onChange(PlaylistRetentionStore.isoUtc(millis))
                    }
                },
                label = { Text("Custom…") },
                enabled = enabled,
                leadingIcon = {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        Text(
            text = value?.let { "Deletes ${formatRetentionDate(it)}" } ?: "Kept forever",
            style = MaterialTheme.typography.bodySmall,
            color = if (value != null) SpotifyRed else SpotifyLightGray,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun RetentionDialog(
    playlist: SpotifyPlaylist,
    currentExpiry: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
    onDeleteNow: () -> Unit
) {
    var expiry by remember(playlist.id, currentExpiry) { mutableStateOf(currentExpiry) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Timer, contentDescription = null) },
        title = { Text("Shortlived playlist") },
        text = {
            Column {
                Text(
                    playlist.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    "When the timer ends the playlist is removed from your Spotify library. Your saved-albums library is never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                )
                RetentionPicker(
                    value = expiry,
                    onChange = { expiry = it },
                    enabled = !busy
                )
                if (confirmDelete) {
                    Text(
                        "Delete \"${playlist.name}\" now? This cannot be undone.",
                        color = SpotifyRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                Button(
                    onClick = onDeleteNow,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed)
                ) { Text("Delete now") }
            } else {
                Button(
                    onClick = { onSet(expiry) },
                    enabled = !busy
                ) { Text("Save") }
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = { confirmDelete = false }, enabled = !busy) {
                    Text("Cancel")
                }
            } else {
                Row {
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy
                    ) { Text("Delete now", color = SpotifyRed) }
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("Close")
                    }
                }
            }
        }
    )
}

// ── Album track sheet ───────────────────────────────────────────────────────

private fun formatTrackDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumTrackSheet(
    sheet: MainViewModel.AlbumSheetState,
    onDismiss: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRemoveAlbum: () -> Unit,
    onQueue: () -> Unit,
    onQueueTrack: (String) -> Unit,
    onQueueToEnd: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    localPlaybackEnabled: Boolean = false,
    onPlayAlbum: () -> Unit = {},
    onPlayTrack: (String) -> Unit = {},
    onLocalQueueNextTrack: (String) -> Unit = {},
    onLocalQueueEndTrack: (String) -> Unit = {},
    onLocalQueueAlbumNext: () -> Unit = {},
    hasLocalQueue: Boolean,
    localQueueBusy: Boolean,
    activeQueueBusy: Boolean,
    queuePlaylistBusy: Boolean,
    onLocalQueueCreate: () -> Unit,
    onLocalQueueAdd: () -> Unit,
    onLocalQueueNext: () -> Unit,
    onLocalQueueReset: () -> Unit

) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF181818)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(
                    model = artworkModel(sheet.imageUrl),
                    contentDescription = sheet.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    error = {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF282828))
                        )
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sheet.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Text(
                        sheet.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        maxLines = 1
                    )
                    Text(
                        listOfNotNull(
                            sheet.year.takeIf { it > 0 }?.toString(),
                            "${sheet.tracks.size} tracks"
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = SpotifyLightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (localPlaybackEnabled) {
                    Button(
                        onClick = onPlayAlbum,
                        enabled = sheet.tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play album")
                    }
                }
                if (localPlaybackEnabled) {
                    OutlinedButton(
                        onClick = onLocalQueueAlbumNext,
                        enabled = sheet.tracks.isNotEmpty()
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Queue next")
                    }
                }
                OutlinedButton(
                    onClick = onQueue,
                    enabled = sheet.tracks.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Queue")
                }
                OutlinedButton(onClick = onAddToPlaylist) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add to playlist")
                }
                if (sheet.playlistId != null) {
                    OutlinedButton(
                        onClick = onRemoveAlbum,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyRed)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Remove from playlist")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Local queue",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onLocalQueueCreate,
                    enabled = sheet.tracks.isNotEmpty() && !localQueueBusy &&
                        !activeQueueBusy && !queuePlaylistBusy
                ) {
                    Text(
                        when {
                            localQueueBusy -> "Working\u2026"
                            hasLocalQueue -> "New queue playlist"
                            else -> "Queue playlist"
                        }
                    )
                }
                OutlinedButton(
                    onClick = onLocalQueueAdd,
                    enabled = sheet.tracks.isNotEmpty() && hasLocalQueue &&
                        !localQueueBusy && !activeQueueBusy && !queuePlaylistBusy
                ) { Text("Add album to end") }
                OutlinedButton(
                    onClick = onLocalQueueNext,
                    enabled = sheet.tracks.isNotEmpty() && hasLocalQueue &&
                        !localQueueBusy && !activeQueueBusy && !queuePlaylistBusy
                ) { Text("Queue next") }
                OutlinedButton(
                    onClick = onLocalQueueReset,
                    enabled = hasLocalQueue && !localQueueBusy &&
                        !activeQueueBusy && !queuePlaylistBusy
                ) { Text("Reset Queue Playlist") }
            }

            Spacer(modifier = Modifier.height(12.dp))
            when {
                sheet.loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                sheet.error != null -> Text(
                    "Could not load tracks: ${sheet.error}",
                    color = SpotifyRed,
                    style = MaterialTheme.typography.bodySmall
                )
                sheet.tracks.isEmpty() -> Text(
                    "No tracks found.",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(
                        sheet.tracks,
                        key = { track -> track.uri.ifBlank { track.spotify_id } }
                    ) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${track.track_number ?: ""}",
                                modifier = Modifier.width(24.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    listOfNotNull(
                                        track.artist_names.takeIf { it.isNotBlank() },
                                        formatTrackDuration(track.duration_ms)
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    maxLines = 1
                                )
                            }
                            if (localPlaybackEnabled && track.uri.isNotBlank()) {
                                IconButton(onClick = { onPlayTrack(track.uri) }) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play this track",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onLocalQueueNextTrack(track.uri) }) {
                                    Icon(
                                        Icons.Default.QueueMusic,
                                        contentDescription = "Queue next locally",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onLocalQueueEndTrack(track.uri) }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add to end locally",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (track.uri.isNotBlank()) {
                                IconButton(onClick = { onQueueTrack(track.uri) }) {
                                    Icon(
                                        Icons.Default.QueueMusic,
                                        contentDescription = "Queue this track",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (sheet.playlistId != null) {
                                IconButton(onClick = { onRemoveTrack(track.uri) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove track",
                                        tint = SpotifyRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// -- Read-only Spotify playback state ---------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: MainViewModel) {
    val state by viewModel.playbackState.collectAsState()
    val localPlayback by viewModel.localPlaybackState.collectAsState()
    val localPlaybackDeviceName by viewModel.localPlaybackDeviceName.collectAsState()
    val localPlaybackAudioFocusMode by viewModel.localPlaybackAudioFocusMode.collectAsState()
    val localPlaybackBufferSeconds by viewModel.localPlaybackBufferSeconds.collectAsState()
    val queuePlaylistBusy by viewModel.queuePlaylistBusy.collectAsState()
    val pendingSwitch by viewModel.pendingQueueSwitch.collectAsState()
    val queueMode by viewModel.queueMode.collectAsState()
    val localQueue by viewModel.localQueuePlaylist.collectAsState()
    val localQueueBusy by viewModel.localQueueBusy.collectAsState()
    val localQueueStatus by viewModel.localQueueStatus.collectAsState()
    val dataSource by viewModel.dataSource.collectAsState()
    val isRemote = dataSource.mode == "remote"
    var showSaveQueueDialog by remember { mutableStateOf(false) }
    var displayedProgress by remember { mutableStateOf(state.progressMs) }

    LaunchedEffect(state.progressMs, state.progressAt, state.isPlaying) {
        displayedProgress = state.progressMs
        if (state.isPlaying) {
            while (true) {
                val elapsed = (System.currentTimeMillis() - state.progressAt).toInt()
                val duration = state.track?.duration_ms ?: 0
                displayedProgress = if (duration > 0)
                    (state.progressMs + elapsed).coerceAtMost(duration)
                else state.progressMs + elapsed
                delay(1000)
            }
        }
    }

    // Polling refreshes immediately on start, so the screen does not need a
    // separate initial refresh (which used to duplicate the first request).
    LaunchedEffect(Unit) { viewModel.startPlaybackPolling() }
    DisposableEffect(Unit) { onDispose { viewModel.stopPlaybackPolling() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (BuildConfig.LIBRESPOT_ENABLED) {
            LocalPlaybackCard(
                state = localPlayback,
                deviceName = localPlaybackDeviceName,
                audioFocusMode = localPlaybackAudioFocusMode,
                bufferSeconds = localPlaybackBufferSeconds,
                onDeviceNameChange = viewModel::setLocalPlaybackDeviceName,
                onAudioFocusModeChange = viewModel::setLocalPlaybackAudioFocusMode,
                onBufferSecondsChange = viewModel::setLocalPlaybackBufferSeconds,
                onToggleEnabled = viewModel::setLocalPlaybackEnabled,
                onToggleKeepConnected = viewModel::setLocalPlaybackKeepConnected,
                onPlayPause = viewModel::toggleLocalPlayback,
                onStop = viewModel::stopLocalPlayback,
                onSeek = viewModel::seekLocalPlayback,
                onNext = viewModel::nextLocalTrack,
                onPrevious = viewModel::previousLocalTrack,
                onPlayQueueIndex = viewModel::playLocalQueueIndex,
                onClearQueue = viewModel::clearLocalQueuedTracks,
                onSendQueueToSpotify = viewModel::sendLocalQueueToSpotify,
                onRemoveQueueItem = viewModel::removeLocalQueuedTrack,
                onMoveQueueItem = viewModel::moveLocalQueuedTrack,
                onForget = viewModel::forgetLocalAccount,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Now playing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = viewModel::refreshPlayback) { Text("Refresh") }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (state.loading && state.track == null) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            state.error?.let {
                Text(it, color = SpotifyRed, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }
            val track = state.track
            if (track == null) {
                Text(
                    "No active Spotify playback. Open Spotify on a device, or switch to local mode.",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubcomposeAsyncImage(
                        model = artworkModel(track.album?.images?.firstOrNull()?.url),
                        contentDescription = track.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        error = {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(Color(0xFF282828)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.4f))
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(
                            track.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                            maxLines = 1
                        )
                        Text(
                            track.album?.name ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = if (track.duration_ms > 0)
                        (displayedProgress.toFloat() / track.duration_ms.toFloat()).coerceIn(0f, 1f)
                    else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatMs(displayedProgress), style = MaterialTheme.typography.labelSmall, color = SpotifyLightGray)
                    Text(formatMs(track.duration_ms), style = MaterialTheme.typography.labelSmall, color = SpotifyLightGray)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    (if (state.isPlaying) "Playing" else "Paused") +
                        (if (state.deviceName.isNotBlank()) " - ${state.deviceName}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray
                )
            }
        }

        val activeQueue by viewModel.activeQueuePlaylist.collectAsState()
        val activeQueueBusy by viewModel.activeQueueBusy.collectAsState()
        val activeQueueStatus by viewModel.activeQueueStatus.collectAsState()
        val activeRef = activeQueue

        Spacer(modifier = Modifier.height(12.dp))
        Text("Queue playlist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = queueMode == QueueMode.LOCAL,
                onClick = { viewModel.setQueueMode(QueueMode.LOCAL) },
                label = { Text("Local queue") },
                colors = spotifyChipColors(),
            )
            FilterChip(
                selected = queueMode == QueueMode.MERGE,
                onClick = { viewModel.setQueueMode(QueueMode.MERGE) },
                label = { Text("Spotify merge") },
                colors = spotifyChipColors(),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Spotify merge no longer keeps its own queue playlist: it reuses the
        // local queue as the queue, with Spotify's items merged in on Reset.
        if (queueMode == QueueMode.LOCAL || queueMode == QueueMode.MERGE) {
            Text(
                localQueueStatus,
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray,
                maxLines = 3
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                localQueue?.let { "\"${it.name}\" \u00b7 ${it.uris.size} tracks" }
                    ?: "The playlist itself is the queue. Spotify's manual queue is ignored.",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.requestLocalQueuePlaylistCreation(emptySet()) },
                enabled = !localQueueBusy && !activeQueueBusy && !queuePlaylistBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        localQueueBusy -> "Working\u2026"
                        localQueue == null -> "Queue playlist"
                        else -> "New queue playlist"
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (queueMode == QueueMode.LOCAL) {
                    OutlinedButton(
                        onClick = viewModel::requestLocalQueueReset,
                        enabled = localQueue != null && !localQueueBusy &&
                            !activeQueueBusy && !queuePlaylistBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset (local only)") }
                } else {
                    OutlinedButton(
                        onClick = viewModel::requestLocalQueueResetWithSpotify,
                        enabled = localQueue != null && !localQueueBusy &&
                            !activeQueueBusy && !queuePlaylistBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (localQueueBusy) "Working..." else "Reset + Spotify queue") }
                }
                OutlinedButton(
                    onClick = viewModel::requestLocalQueueClear,
                    enabled = !localQueueBusy && !activeQueueBusy && !queuePlaylistBusy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (localQueueBusy) "Working..." else "Clear local queue") }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Add selected to end and Queue next are on the album pages. " +
                    "The playlist ID is the queue, and Spotify's manual queue is ignored. " +
                    "Clear starts an empty queue and keeps the old playlist on Spotify.",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray
            )
        } else {
            Text(
                activeQueueStatus,
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray,
                maxLines = 3
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (activeRef == null) {
                OutlinedButton(
                    onClick = { viewModel.requestActiveQueuePlaylistCreation(emptySet()) },
                    enabled = !activeQueueBusy && !queuePlaylistBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (activeQueueBusy) "Working\u2026" else "Create queue playlist") }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This creates one private playlist and starts it immediately; the current track is cut off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray
                )
            } else {
                Text(
                    "\"${activeRef.name}\" \u00b7 ${activeRef.uris.size} tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.requestNewActiveQueuePlaylist(emptySet()) },
                    enabled = !activeQueueBusy && !queuePlaylistBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("New queue playlist") }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::addSelectedAlbumsToActiveQueuePlaylist,
                        enabled = !activeQueueBusy && !queuePlaylistBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Add selected to end") }
                    OutlinedButton(
                        onClick = viewModel::requestActiveQueueReset,
                        enabled = !activeQueueBusy && !queuePlaylistBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset Queue Playlist") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Albums are appended after the existing tail. Manual Spotify queue items stay in place, so they do not play twice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showSaveQueueDialog = true },
                enabled = !queuePlaylistBusy && !activeQueueBusy &&
                    (state.track != null || state.queue.isNotEmpty()),
                modifier = Modifier.weight(1f)
            ) { Text("Save queue") }
            OutlinedButton(
                onClick = viewModel::appendQueueToCurrentPlaylist,
                enabled = !queuePlaylistBusy && !activeQueueBusy &&
                    state.contextPlaylistId != null &&
                    state.contextEditable && state.queue.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Append queue") }
        }
        pendingSwitch?.let { pending ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "\"${pending.name}\" starts when this track ends",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::startPendingQueueSwitch) { Text("Start now") }
            }
        }

        if (state.contextType == "playlist") {            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (state.contextName.isNotBlank()) "Playing from ${state.contextName}"
                else "Playing from a playlist",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray,
                maxLines = 1
            )
            if (!state.contextEditable) {
                Text(
                    "Append is disabled: this playlist is not editable from this account.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray
                )
            }
        } else if (state.track != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Start playback from one of your playlists to append the queue.",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Up next (${state.queue.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        if (state.queue.isEmpty()) {
            Text("Nothing queued.", color = SpotifyLightGray, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(state.queue) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                track.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray,
                                maxLines = 1
                            )
                        }
                        Text(
                            formatMs(track.duration_ms),
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Queue snapshot copies the current track + up-next once. Append only works on playlists you own or collaborate on. " +
                "Play as queue playlist hands the queue over to a new playlist (deleted again after a day). " +
                "Spotify Premium and an active device are required.",
            style = MaterialTheme.typography.labelSmall,
            color = SpotifyLightGray
        )
    }

    if (showSaveQueueDialog) {
        var snapshotName by remember {
            mutableStateOf(
                "Queue snapshot · " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(Calendar.getInstance().time)
            )
        }
        var retentionUntil by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!queuePlaylistBusy) showSaveQueueDialog = false },
            title = { Text("Save queue as playlist") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Creates a private playlist with the current track and ${state.queue.size} queued item(s). This is a one-time copy; later queue changes are not synced.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = snapshotName,
                        onValueChange = { snapshotName = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        enabled = !queuePlaylistBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Delete automatically (optional)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    RetentionPicker(
                        value = retentionUntil,
                        onChange = { retentionUntil = it },
                        enabled = !queuePlaylistBusy
                    )
                    if (isRemote) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Saves through the media server's Spotify account. The queue itself is read from this phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyLightGray
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveQueueAsPlaylist(snapshotName, retentionUntil)
                        showSaveQueueDialog = false
                    },
                    enabled = snapshotName.isNotBlank() && !queuePlaylistBusy
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveQueueDialog = false },
                    enabled = !queuePlaylistBusy
                ) { Text("Cancel") }
            }
        )
    }
}

// ── Experimental local playback UI ─────────────────────────────────────────

@Composable
private fun LocalMiniPlayer(
    state: LocalPlaybackState,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val progress = if (state.durationMs > 0) {
        state.positionMs.toFloat() / state.durationMs.toFloat()
    } else {
        0f
    }
    Surface(color = Color(0xFF282828), modifier = Modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = SpotifyGreen,
                trackColor = Color(0xFF404040),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubcomposeAsyncImage(
                    model = artworkModel(state.artUrl),
                    contentDescription = state.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    error = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF181818)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.title.ifBlank { "Connecting…" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        state.artist.ifBlank { "Experimental local playback" },
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause local playback" else "Play local playback",
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Stop local playback",
                        tint = SpotifyLightGray,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPlaybackCard(
    state: LocalPlaybackState,
    deviceName: String = "",
    audioFocusMode: String = "pause",
    bufferSeconds: Int = 8,
    onDeviceNameChange: (String) -> Unit = {},
    onAudioFocusModeChange: (String) -> Unit = {},
    onBufferSecondsChange: (Int) -> Unit = {},
    onToggleEnabled: (Boolean) -> Unit,
    onToggleKeepConnected: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Int) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit = {},
    onClearQueue: () -> Unit = {},
    onSendQueueToSpotify: () -> Unit = {},
    onRemoveQueueItem: (Int) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    var showForgetConfirm by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(state.positionMs, seeking) {
        if (!seeking) seekValue = state.positionMs.toFloat()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Local playback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Experimental · full tracks on this device (librespot)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpotifyLightGray,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        onToggleEnabled(enabled)
                    },
                )
            }

            if (state.enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Keep available for Connect (idle)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Stays reachable by other Spotify apps even when nothing is playing.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyLightGray
                        )
                    }
                    Switch(checked = state.keepConnected, onCheckedChange = onToggleKeepConnected)
                }
            }

            if (!state.enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Enable to play albums on this phone. Spotify Premium is required. " +
                        "This uses its own Spotify authorization and does not change the dashboard login.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                when {
                    state.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Waiting for Spotify authorization in the browser…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.needsAuth -> Text(
                        "Authorization needed. Play an album to open the Spotify login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                    )
                    state.error != null -> Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyRed,
                    )
                    state.title.isBlank() -> Text(
                        "Ready. Play an album from Library or Playlists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                    )
                    else -> {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Text(
                            state.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = seekValue,
                            onValueChange = {
                                seeking = true
                                seekValue = it
                            },
                            onValueChangeFinished = {
                                seeking = false
                                onSeek(seekValue.toInt())
                            },
                            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatMs(seekValue.toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotifyLightGray,
                            )
                            Text(
                                formatMs(state.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotifyLightGray,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPrevious) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track")
                            }
                            FilledIconButton(
                                onClick = onPlayPause,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = SpotifyGreen,
                                    contentColor = Color.Black,
                                ),
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                )
                            }
                            IconButton(onClick = onNext) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next track")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onStop) { Text("Stop") }
                        }
                        if (state.queue.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Local queue: ${state.queueIndex + 1}/${state.queue.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SpotifyLightGray,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(onClick = onClearQueue) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = onSendQueueToSpotify) { Text("To Spotify", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp),
                            ) {
                                itemsIndexed(state.queue) { i, track ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPlayQueueIndex(i) }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (i == state.queueIndex) "▶" else "${i + 1}",
                                            modifier = Modifier.width(24.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (i == state.queueIndex) SpotifyGreen else SpotifyLightGray,
                                        )
                                        Text(
                                            track.title.ifBlank { "Track ${i + 1}" },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (i > 0) {
                                            IconButton(onClick = { onMoveQueueItem(i, i - 1) }) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (i < state.queue.lastIndex) {
                                            IconButton(onClick = { onMoveQueueItem(i, i + 1) }) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        IconButton(onClick = { onRemoveQueueItem(i) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = SpotifyRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                var showSettings by remember { mutableStateOf(false) }
                TextButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "Hide Connect settings" else "Connect settings")
                }
                if (showSettings) {
                    Column {
                        var nameField by remember(deviceName) { mutableStateOf(deviceName) }
                        OutlinedTextField(
                            value = nameField,
                            onValueChange = { nameField = it },
                            label = { Text("Connect device name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { onDeviceNameChange(nameField) },
                            modifier = Modifier.padding(top = 6.dp),
                            enabled = nameField.isNotBlank() && nameField.trim() != deviceName,
                        ) { Text("Apply device name") }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Audio focus behavior", style = MaterialTheme.typography.labelSmall, color = SpotifyLightGray)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("pause", "duck", "never").forEach { value ->
                                FilterChip(
                                    selected = audioFocusMode == value,
                                    onClick = { onAudioFocusModeChange(value) },
                                    label = { Text(value.replaceFirstChar { it.uppercase() }) },
                                    colors = spotifyChipColors(),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                          Text("PCM buffer / latency", style = MaterialTheme.typography.labelSmall, color = SpotifyLightGray)
                          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                              listOf(2, 4, 6, 8, 10, 12, 15).forEach { value ->
                                  FilterChip(
                                      selected = bufferSeconds == value,
                                      onClick = { onBufferSecondsChange(value) },
                                      label = { Text("${value}s") },
                                      colors = spotifyChipColors(),
                                  )
                              }
                          }
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              "Streaming quality/bitrate and cache size are not user-configurable; Spotify and " +
                                  "librespot negotiate the delivered stream.",
                              style = MaterialTheme.typography.labelSmall,
                              color = SpotifyLightGray,
                          )
                    }
                }

                if (state.connected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showForgetConfirm = true }) {
                            Text("Forget account", color = SpotifyLightGray)
                        }
                    }
                }
            }
        }
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("Forget local playback account?") },
            text = {
                Text(
                    "The stored librespot credentials are deleted. The next play opens the Spotify login again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgetConfirm = false
                    onForget()
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatMs(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
