package com.spotifydashboard.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.spotifydashboard.app.api.ResolvedTrackDto
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.utils.LinkHistoryStore
import com.spotifydashboard.app.utils.LinkTarget

/**
 * The AI **track** scan: the AI reads a page, extracts the song titles,
 * Spotify resolves them, and the user picks the playlist they go into.
 *
 * UI policy (P9): the scan only finds tracks. The single write happens when
 * the user presses "Add to playlist". There is deliberately no "Add to
 * library" button: Spotify has no saved-tracks shelf the user would expect.
 */
@Composable
fun AiTrackScanDialog(
    loading: Boolean,
    message: String?,
    onScan: (String?, String?) -> Unit,
    onOpenBrowser: (String) -> Unit = {},
    initialUrl: String = "",
    onUrlChange: (String) -> Unit = {},
    /**
     * Tracks found by the last scan. They are kept so the user can add them
     * to a playlist (a new one they name, or one they already have).
     */
    scannedTracks: List<ResolvedTrackDto> = emptyList(),
    playlists: List<SpotifyPlaylist> = emptyList(),
    playlistBusy: Boolean = false,
    onAddToPlaylist: (playlistId: String?, newName: String?) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var pasteText by remember { mutableStateOf("") }
    // Playlist target: a new playlist the user names, or one they already have.
    var playlistName by remember { mutableStateOf("") }
    var playlistTarget by remember { mutableStateOf("") }
    var playlistMenuOpen by remember { mutableStateOf(false) }
    // The tracks the user unticked; everything else is selected.
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current
    val linkStore = remember { LinkHistoryStore(context) }
    val selected = scannedTracks.filter { it.spotify_id !in skipped }
    val selectedUris = selected.mapNotNull { it.uri }.filter { it.isNotBlank() }.distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan page with AI (tracks)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Paste a URL (or text). AI extracts the song titles, then Spotify finds them. " +
                        "Nothing is saved until you press \"Add to playlist\".",
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
                            linkStore.recordRecent("tracks", url, LinkTarget.URL)
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
                    label = { Text("...or paste the tracklist") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(Modifier.height(8.dp))
                LinkHistoryRow(
                    kind = "tracks",
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
                if (scannedTracks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Found ${selectedUris.size} track${if (selectedUris.size == 1) "" else "s"} " +
                            "on Spotify — nothing was saved yet. Choose below where they go.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Untick a track to leave it out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    scannedTracks.forEach { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = track.spotify_id !in skipped,
                                onCheckedChange = { checked ->
                                    skipped = if (checked) skipped - track.spotify_id
                                    else skipped + track.spotify_id
                                }
                            )
                            if (!track.image_url.isNullOrBlank()) {
                                SubcomposeAsyncImage(
                                    model = artworkModel(track.image_url),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.name.ifBlank { track.title },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOf(track.artist_name, track.album_name)
                                        .filter { it.isNotBlank() }.joinToString(" — "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                                playlistTarget.ifBlank { null },
                                playlistName.trim().ifBlank { null }
                            )
                        },
                        enabled = !playlistBusy &&
                            selectedUris.isNotEmpty() &&
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
                            Text("Add to playlist (${selectedUris.size})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = urlText.takeIf { it.isNotBlank() }
                    val paste = pasteText.takeIf { it.isNotBlank() }
                    if (url != null) linkStore.recordRecent("tracks", url, LinkTarget.URL)
                    else if (paste != null) linkStore.recordRecent("tracks", paste, LinkTarget.TEXT)
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
