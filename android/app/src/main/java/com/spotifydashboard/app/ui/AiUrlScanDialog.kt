package com.spotifydashboard.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.api.ResolvedAlbumDto
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.api.TopTrackDto
import com.spotifydashboard.app.utils.LinkHistoryStore
import com.spotifydashboard.app.utils.LinkTarget

@Composable
fun AiUrlScanDialog(
    loading: Boolean,
    message: String?,
    onScan: (String?, String?) -> Unit,
    onOpenBrowser: (String) -> Unit = {},
    initialUrl: String = "",
    onUrlChange: (String) -> Unit = {},
    /**
     * Albums found by the last scan. They are kept so the user can also add
     * them to a playlist (a new one they name, or one they already have).
     */
    scannedAlbums: List<ResolvedAlbumDto> = emptyList(),
    playlists: List<SpotifyPlaylist> = emptyList(),
    playlistBusy: Boolean = false,
    onAddToPlaylist: (albumIds: List<String>, playlistId: String?, newName: String?) -> Unit = { _, _, _ -> },
    // "Also add the most popular track of each album": the ranking is
    // resolved read-only, the write happens only behind the button.
    topTracks: List<TopTrackDto> = emptyList(),
    topTracksResolving: Boolean = false,
    topTracksAddBusy: Boolean = false,
    onResolveTopTracks: (albumIds: List<String>) -> Unit = {},
    onAddTopTracksToPlaylist: (uris: List<String>, playlistId: String?, newName: String?) -> Unit = { _, _, _ -> },
    /**
     * Add the found albums to the user's Spotify library and to the dashboard
     * index. Only runs when the user presses "Add to library": a scan never
     * saves anything on its own (UI policy P9).
     */
    onAddToLibrary: () -> Unit = {},
    libraryBusy: Boolean = false,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var pasteText by remember { mutableStateOf("") }
    // Playlist target for the found albums: a new playlist the user names, or
    // one of the playlists they already have.
    var playlistName by remember { mutableStateOf("") }
    var playlistTarget by remember { mutableStateOf("") }
    var playlistMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val linkStore = remember { LinkHistoryStore(context) }
    val scannedIds = remember(scannedAlbums) {
        scannedAlbums.mapNotNull { it.spotify_id }.filter { it.isNotBlank() }.distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan page with AI (albums)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Paste a URL (or text) without Spotify links. AI extracts albums, then searches Spotify.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it; onUrlChange(it) },
                    label = { Text("Page URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                // Keep the browser entry point next to the URL it acts on: the
                // dialog body scrolls, so an action at the bottom can be hidden
                // below the fold on a phone.
                OutlinedButton(
                    onClick = {
                        val url = urlText.trim()
                        if (url.isNotEmpty()) {
                            linkStore.recordRecent("albums", url, LinkTarget.URL)
                            onOpenBrowser(url)
                        }
                    },
                    enabled = !loading && urlText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open in browser and scan")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("...or paste page text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(Modifier.height(8.dp))
                LinkHistoryRow(
                    kind = "albums",
                    value = urlText.trim().ifEmpty { pasteText },
                    target = if (urlText.isNotBlank()) LinkTarget.URL else LinkTarget.TEXT,
                    onPick = { picked, pickedTarget ->
                        if (pickedTarget == LinkTarget.URL) {
                            urlText = picked; pasteText = ""
                        } else {
                            pasteText = picked; urlText = ""
                        }
                    },
                    enabled = !loading,
                )
                if (scannedIds.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Found ${scannedIds.size} album${if (scannedIds.size == 1) "" else "s"}" +
                            " on Spotify — nothing was saved yet. Choose below where they go.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    // The library save is an explicit action: the scan itself
                    // never writes to Spotify or to the dashboard index.
                    Button(
                        onClick = { onAddToLibrary() },
                        enabled = !libraryBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (libraryBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Adding…")
                        } else {
                            Text("Add to library (${scannedIds.size})")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it; playlistTarget = "" },
                        label = { Text("New playlist name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !playlistBusy
                    )
                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(
                            onClick = { playlistMenuOpen = true },
                            enabled = !playlistBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                playlists.firstOrNull { it.id == playlistTarget }?.name
                                    ?: "Pick one of your playlists"
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = playlistMenuOpen,
                            onDismissRequest = { playlistMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Create a new playlist") },
                                onClick = {
                                    playlistTarget = ""
                                    playlistMenuOpen = false
                                }
                            )
                            playlists.forEach { playlist ->
                                DropdownMenuItem(
                                    text = { Text(playlist.name) },
                                    onClick = {
                                        playlistTarget = playlist.id
                                        playlistMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            onAddToPlaylist(
                                scannedIds,
                                playlistTarget.ifBlank { null },
                                playlistName.trim().ifBlank { null }
                            )
                        },
                        enabled = !playlistBusy &&
                            (playlistTarget.isNotBlank() || playlistName.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (playlistBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Adding…")
                        } else {
                            Text("Add to playlist (${scannedIds.size})")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    AlbumTopTracksSection(
                        albumIds = scannedIds,
                        topTracks = topTracks,
                        resolving = topTracksResolving,
                        busy = topTracksAddBusy,
                        playlists = playlists,
                        onResolve = onResolveTopTracks,
                        onAdd = onAddTopTracksToPlaylist,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = urlText.takeIf { it.isNotBlank() }
                    val paste = pasteText.takeIf { it.isNotBlank() }
                    if (url != null) linkStore.recordRecent("albums", url, LinkTarget.URL)
                    else if (paste != null) linkStore.recordRecent("albums", paste, LinkTarget.TEXT)
                    onScan(url, paste)
                },
                enabled = !loading && (urlText.isNotBlank() || pasteText.isNotBlank())
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Scan with AI")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}