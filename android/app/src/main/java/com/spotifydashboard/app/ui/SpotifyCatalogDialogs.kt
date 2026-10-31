package com.spotifydashboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.spotifydashboard.app.repository.SpotifyCatalogAlbum
import com.spotifydashboard.app.repository.SpotifyCatalogArtist
import com.spotifydashboard.app.ui.theme.SpotifyCardGray
import com.spotifydashboard.app.ui.theme.SpotifyLightGray
import com.spotifydashboard.app.ui.theme.SpotifyGreen

private val DialogShape = RoundedCornerShape(18.dp)

/**
 * Tools -> Playlist from Spotify search.
 *
 * The user searches the Spotify album catalogue, ticks the matches, and
 * reviews duplicate handling before a private playlist is created.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpotifySearchPlaylistDialog(
    state: SpotifySearchPlaylistState,
    busy: Boolean,
    progress: CatalogPlaylistProgress?,
    outcome: CatalogPlaylistOutcome?,
    libraryBusy: Boolean,
    libraryProgress: CatalogLibraryProgress?,
    libraryOutcome: CatalogLibraryOutcome?,
    onSearch: (String) -> Unit,
    onCreate: (String, List<SpotifyCatalogAlbum>, Boolean) -> Unit,
    onAddToLibrary: (List<SpotifyCatalogAlbum>) -> Unit,
    onDismiss: () -> Unit,
    onClearOutcome: () -> Unit,
    onClearLibraryOutcome: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf(state.query) }
    var playlistName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDedupePrompt by remember { mutableStateOf(false) }
    var showLibraryConfirm by remember { mutableStateOf(false) }
    val selectedAlbums = state.results.filter { it.id in selectedIds }
    val closeDialog: () -> Unit = {
        onClearOutcome()
        onClearLibraryOutcome()
        onDismiss()
    }

    LaunchedEffect(state.results) {
        selectedIds = state.results.map { it.id }.toSet()
        if (playlistName.isBlank() && state.query.isNotBlank()) {
            playlistName = "${state.query} playlist"
        }
    }

    when {
        showLibraryConfirm -> {
            AddToLibraryConfirmDialog(
                albumCount = selectedAlbums.size,
                onAdd = {
                    showLibraryConfirm = false
                    onAddToLibrary(selectedAlbums)
                },
                onCancel = { showLibraryConfirm = false },
            )
        }

        libraryOutcome?.isSuccess == true -> {
            CatalogLibrarySuccessDialog(outcome = libraryOutcome, onClose = closeDialog)
        }

        showDedupePrompt -> {
            DedupeBeforeCreateDialog(
                albumCount = selectedAlbums.size,
                duplicateAlbums = duplicateAlbumCount(selectedAlbums),
                onDeduplicate = {
                    showDedupePrompt = false
                    onCreate(playlistName, dedupeAlbums(selectedAlbums), true)
                },
                onCreateAsIs = {
                    showDedupePrompt = false
                    onCreate(playlistName, selectedAlbums, false)
                },
                onCancel = { showDedupePrompt = false },
            )
        }

        outcome?.isSuccess == true -> {
            CatalogPlaylistSuccessDialog(outcome = outcome, onClose = closeDialog)
        }

        else -> {
            Dialog(
                onDismissRequest = { if (!busy) closeDialog() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = !busy,
                    dismissOnClickOutside = !busy,
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.94f)
                        .padding(12.dp)
                        .imePadding(),
                    shape = DialogShape,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Playlist from Spotify search", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Search the Spotify catalogue, then create a private playlist or save the matches to your library.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                )
                            }
                            IconButton(onClick = closeDialog, enabled = !busy) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !state.loading,
                            singleLine = true,
                            label = { Text("Album or artist") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        keyboard?.hide()
                                        selectedIds = emptySet()
                                        onSearch(query)
                                    },
                                    enabled = !busy && !state.loading && query.isNotBlank(),
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search Spotify")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboard?.hide()
                                    selectedIds = emptySet()
                                    onSearch(query)
                                },
                            ),
                        )

                        val errorText = libraryOutcome?.error ?: outcome?.error ?: state.error
                        if (errorText != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (state.loading) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Searching Spotify...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (state.hasSearched && state.results.isEmpty() && !state.loading) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No albums found. Try a different spelling or a shorter search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray,
                            )
                        }

                        if (state.results.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${state.results.size} result${if (state.results.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SpotifyLightGray,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { selectedIds = state.results.map { it.id }.toSet() },
                                    enabled = !busy,
                                ) { Text("Select all") }
                                TextButton(
                                    onClick = { selectedIds = emptySet() },
                                    enabled = !busy,
                                ) { Text("Clear") }
                            }
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(state.results, key = { it.id }) { album ->
                                    CatalogAlbumRow(
                                        album = album,
                                        checked = album.id in selectedIds,
                                        enabled = !busy,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) {
                                                selectedIds + album.id
                                            } else {
                                                selectedIds - album.id
                                            }
                                        },
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Private playlist name") },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${selectedAlbums.size} album${if (selectedAlbums.size == 1) "" else "s"} selected " +
                                "\u00b7 private playlist",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showLibraryConfirm = true },
                            enabled = !busy && selectedAlbums.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.LibraryAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Add selected to library")
                        }
                        if (busy) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (libraryBusy) {
                                    libraryProgress?.let {
                                        "${it.stage} ${it.done}/${it.total}"
                                    } ?: "Updating your library..."
                                } else {
                                    progress?.let {
                                        "Fetching album ${it.albumsDone}/${it.albumsTotal} \u00b7 ${it.tracksFound} tracks"
                                    } ?: "Preparing the playlist..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = closeDialog, enabled = !busy) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { showDedupePrompt = true },
                                enabled = !busy && playlistName.isNotBlank() && selectedAlbums.isNotEmpty(),
                            ) { Text("Create private playlist") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tools -> Artist discography playlist.
 *
 * The artist search is the first step so the user can confirm the right
 * artist. Album, single and appearance filters match Spotify's
 * include_groups values.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistDiscographyPlaylistDialog(
    state: ArtistDiscographyState,
    busy: Boolean,
    progress: CatalogPlaylistProgress?,
    outcome: CatalogPlaylistOutcome?,
    libraryBusy: Boolean,
    libraryProgress: CatalogLibraryProgress?,
    libraryOutcome: CatalogLibraryOutcome?,
    onSearchArtists: (String) -> Unit,
    onSelectArtist: (SpotifyCatalogArtist) -> Unit,
    onClearArtist: () -> Unit,
    onGroupChange: (String, Boolean) -> Unit,
    onCreate: (String, List<SpotifyCatalogAlbum>, Boolean) -> Unit,
    onAddToLibrary: (List<SpotifyCatalogAlbum>) -> Unit,
    onDismiss: () -> Unit,
    onClearOutcome: () -> Unit,
    onClearLibraryOutcome: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf(state.query) }
    var playlistName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDedupePrompt by remember { mutableStateOf(false) }
    var showLibraryConfirm by remember { mutableStateOf(false) }
    val artist = state.selectedArtist
    val selectedAlbums = state.albums.filter { it.id in selectedIds }
    val closeDialog: () -> Unit = {
        onClearOutcome()
        onClearLibraryOutcome()
        onDismiss()
    }

    LaunchedEffect(artist?.id) {
        playlistName = if (artist != null) "${artist.name} discography" else ""
        selectedIds = emptySet()
    }
    LaunchedEffect(state.albums) {
        selectedIds = state.albums.map { it.id }.toSet()
    }

    when {
        showLibraryConfirm -> {
            AddToLibraryConfirmDialog(
                albumCount = selectedAlbums.size,
                onAdd = {
                    showLibraryConfirm = false
                    onAddToLibrary(selectedAlbums)
                },
                onCancel = { showLibraryConfirm = false },
            )
        }

        libraryOutcome?.isSuccess == true -> {
            CatalogLibrarySuccessDialog(outcome = libraryOutcome, onClose = closeDialog)
        }

        showDedupePrompt -> {
            DedupeBeforeCreateDialog(
                albumCount = selectedAlbums.size,
                duplicateAlbums = duplicateAlbumCount(selectedAlbums),
                onDeduplicate = {
                    showDedupePrompt = false
                    onCreate(playlistName, dedupeAlbums(selectedAlbums), true)
                },
                onCreateAsIs = {
                    showDedupePrompt = false
                    onCreate(playlistName, selectedAlbums, false)
                },
                onCancel = { showDedupePrompt = false },
            )
        }

        outcome?.isSuccess == true -> {
            CatalogPlaylistSuccessDialog(outcome = outcome, onClose = closeDialog)
        }

        else -> {
            Dialog(
                onDismissRequest = { if (!busy) closeDialog() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = !busy,
                    dismissOnClickOutside = !busy,
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.94f)
                        .padding(12.dp)
                        .imePadding(),
                    shape = DialogShape,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Artist discography", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Build a private playlist from albums, singles and appearances, or add releases to your library.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                )
                            }
                            IconButton(onClick = closeDialog, enabled = !busy) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        if (artist == null) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && !state.loadingArtists,
                                singleLine = true,
                                label = { Text("Artist name") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            keyboard?.hide()
                                            onSearchArtists(query)
                                        },
                                        enabled = !busy && !state.loadingArtists && query.isNotBlank(),
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = "Find artist")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        keyboard?.hide()
                                        onSearchArtists(query)
                                    },
                                ),
                            )

                            val errorText = libraryOutcome?.error ?: outcome?.error ?: state.error
                            if (errorText != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (state.loadingArtists) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Searching Spotify artists...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (state.hasSearched && state.artists.isEmpty() && !state.loadingArtists) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "No artists found. Try a shorter name.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                )
                            }
                            if (state.artists.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Pick the artist",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SpotifyLightGray,
                                )
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(state.artists, key = { it.id }) { result ->
                                        CatalogArtistRow(
                                            artist = result,
                                            enabled = !busy,
                                            onClick = { onSelectArtist(result) },
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = closeDialog, enabled = !busy) { Text("Cancel") }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CatalogArtwork(artist.imageUrl, 54.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        artist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        artist.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SpotifyLightGray,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = onClearArtist, enabled = !busy) {
                                    Text("Change")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Include",
                                style = MaterialTheme.typography.labelLarge,
                                color = SpotifyLightGray,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.includeAlbums,
                                    onClick = { onGroupChange("album", !state.includeAlbums) },
                                    enabled = !busy && !state.loadingAlbums,
                                    label = { Text("Albums") },
                                )
                                FilterChip(
                                    selected = state.includeSingles,
                                    onClick = { onGroupChange("single", !state.includeSingles) },
                                    enabled = !busy && !state.loadingAlbums,
                                    label = { Text("Singles") },
                                )
                                FilterChip(
                                    selected = state.includeAppearsOn,
                                    onClick = { onGroupChange("appears_on", !state.includeAppearsOn) },
                                    enabled = !busy && !state.loadingAlbums,
                                    label = { Text("Appears on") },
                                )
                            }

                            val errorText = libraryOutcome?.error ?: outcome?.error ?: state.error
                            if (errorText != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (state.loadingAlbums) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text("Loading discography...", style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.albums.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${state.albums.size} release${if (state.albums.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SpotifyLightGray,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        onClick = { selectedIds = state.albums.map { it.id }.toSet() },
                                        enabled = !busy,
                                    ) { Text("Select all") }
                                    TextButton(
                                        onClick = { selectedIds = emptySet() },
                                        enabled = !busy,
                                    ) { Text("Clear") }
                                }
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(state.albums, key = { it.id }) { album ->
                                        CatalogAlbumRow(
                                            album = album,
                                            checked = album.id in selectedIds,
                                            enabled = !busy,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) {
                                                    selectedIds + album.id
                                                } else {
                                                    selectedIds - album.id
                                                }
                                            },
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = playlistName,
                                onValueChange = { playlistName = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                singleLine = true,
                                label = { Text("Private playlist name") },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${selectedAlbums.size} of ${state.albums.size} releases selected " +
                                    "\u00b7 private playlist",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showLibraryConfirm = true },
                                enabled = !busy && selectedAlbums.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.Default.LibraryAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Add selected to library")
                            }
                            if (busy) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (libraryBusy) {
                                        libraryProgress?.let {
                                            "${it.stage} ${it.done}/${it.total}"
                                        } ?: "Updating your library..."
                                    } else {
                                        progress?.let {
                                            "Fetching album ${it.albumsDone}/${it.albumsTotal} \u00b7 ${it.tracksFound} tracks"
                                        } ?: "Preparing the playlist..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = closeDialog, enabled = !busy) { Text("Cancel") }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { showDedupePrompt = true },
                                    enabled = !busy && playlistName.isNotBlank() && selectedAlbums.isNotEmpty(),
                                ) { Text("Create private playlist") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToLibraryConfirmDialog(
    albumCount: Int,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add to library?") },
        text = {
            Text(
                "Save $albumCount release${if (albumCount == 1) "" else "s"} to your Spotify library? " +
                    "They will also be added to this dashboard.",
            )
        },
        confirmButton = {
            Button(onClick = onAdd) { Text("Add to library") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun CatalogLibrarySuccessDialog(
    outcome: CatalogLibraryOutcome,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(36.dp),
            )
        },
        title = { Text("Added to library") },
        text = {
            Column {
                Text(
                    "${outcome.albumsAdded} release${if (outcome.albumsAdded == 1) "" else "s"} " +
                        "saved to your Spotify library.",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (outcome.albumsImported < outcome.albumsAdded) {
                        "${outcome.albumsImported} appeared in this dashboard immediately; " +
                            "the rest will arrive with the next sync."
                    } else {
                        "The dashboard library was updated too."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                )
            }
        },
        confirmButton = {
            Button(onClick = onClose) { Text("Close") }
        },
    )
}

@Composable
private fun CatalogPlaylistSuccessDialog(
    outcome: CatalogPlaylistOutcome,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(36.dp),
            )
        },
        title = { Text("Playlist created") },
        text = {
            Column {
                Text(outcome.playlistName, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${outcome.tracksAdded} track${if (outcome.tracksAdded == 1) "" else "s"} " +
                        "from ${outcome.albumsAdded} album${if (outcome.albumsAdded == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (outcome.duplicatesSkipped > 0) {
                    Text(
                        "${outcome.duplicatesSkipped} duplicate track" +
                            "${if (outcome.duplicatesSkipped == 1) "" else "s"} skipped",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The playlist is private in Spotify by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                )
            }
        },
        confirmButton = {
            Button(onClick = onClose) { Text("Close") }
        },
    )
}

@Composable
private fun DedupeBeforeCreateDialog(
    albumCount: Int,
    duplicateAlbums: Int,
    onDeduplicate: () -> Unit,
    onCreateAsIs: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Deduplicate before creating?") },
        text = {
            Column {
                Text("$albumCount album${if (albumCount == 1) "" else "s"} selected.")
                Spacer(Modifier.height(6.dp))
                Text(
                    if (duplicateAlbums > 0) {
                        "$duplicateAlbums duplicate album title${if (duplicateAlbums == 1) "" else "s"} found."
                    } else {
                        "No duplicate album titles were found."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Deduplicate skips duplicate album titles and repeated tracks that appear on multiple releases. " +
                        "Create as-is keeps everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDeduplicate) { Text("Deduplicate & create") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCreateAsIs) { Text("Create as-is") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CatalogAlbumRow(
    album: SpotifyCatalogAlbum,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        CatalogArtwork(album.imageUrl, 48.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(album.artist)
                    if (album.year > 0) append(" \u00b7 ${album.year}")
                    append(" \u00b7 ${album.kindLabel}")
                    if (album.totalTracks > 0) append(" \u00b7 ${album.totalTracks} tracks")
                },
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CatalogArtistRow(
    artist: SpotifyCatalogArtist,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogArtwork(artist.imageUrl, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CatalogArtwork(url: String, size: Dp) {
    val shape = RoundedCornerShape(6.dp)
    if (url.isBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(SpotifyCardGray),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Album, contentDescription = null, tint = SpotifyLightGray)
        }
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SpotifyCardGray),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SpotifyCardGray),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Album, contentDescription = null, tint = SpotifyLightGray)
                }
            },
        )
    }
}

private fun duplicateAlbumCount(albums: List<SpotifyCatalogAlbum>): Int {
    val keys = albums.map { normalizedAlbumKey(it) }
    return keys.size - keys.distinct().size
}

private fun dedupeAlbums(albums: List<SpotifyCatalogAlbum>): List<SpotifyCatalogAlbum> {
    val seen = mutableSetOf<String>()
    return albums.filter { seen.add(normalizedAlbumKey(it)) }
}

private fun normalizedAlbumKey(album: SpotifyCatalogAlbum): String =
    "${album.name}|${album.artist}"
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
