package com.spotifydashboard.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.spotifydashboard.app.api.SpotifyPlaylist
import com.spotifydashboard.app.api.TopTrackDto

/**
 * "Also add the most popular track of each album".
 *
 * An option of the album flows ("Add from site", "Scan page with AI"). The
 * most popular track of every selected album is resolved read-only, shown to
 * the user, and added to a playlist the user names or picks — only after the
 * user pressed the button (UI policy P9).
 *
 * @param albumIds the albums the top tracks are resolved for.
 * @param topTracks the tracks resolved for those albums.
 * @param resolving true while the ranking is being read.
 * @param busy true while the tracks are being written to the playlist.
 * @param onResolve reads the ranking (read-only, never writes).
 * @param onAdd writes the chosen track URIs to the chosen playlist.
 */
@Composable
fun AlbumTopTracksSection(
    albumIds: List<String>,
    topTracks: List<TopTrackDto>,
    resolving: Boolean,
    busy: Boolean,
    playlists: List<SpotifyPlaylist>,
    onResolve: (List<String>) -> Unit,
    onAdd: (List<String>, String?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember { mutableStateOf(false) }
    // The tracks the user unticked; everything else is selected.
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var playlistName by remember { mutableStateOf("") }
    var playlistTarget by remember { mutableStateOf("") }
    var playlistMenuOpen by remember { mutableStateOf(false) }

    // Resolve the ranking when the option is switched on, and again when the
    // selected albums change.
    LaunchedEffect(enabled, albumIds) {
        if (enabled && albumIds.isNotEmpty()) onResolve(albumIds)
    }

    val chosen = topTracks.filter { it.spotify_id !in skipped }
    val chosenUris = chosen.mapNotNull { it.uri }.filter { it.isNotBlank() }.distinct()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { enabled = it },
                enabled = !busy && albumIds.isNotEmpty()
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Also add the most popular track of each album",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Every selected album contributes its single most popular track, " +
                        "to a playlist you choose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (enabled) {
            if (resolving) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Looking for the most popular tracks…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            topTracks.forEach { track ->
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
                    if (track.album_image_url.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = artworkModel(track.album_image_url),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(track.artist_names, track.album_name, "popularity ${track.popularity}")
                                .filter { it.isNotBlank() }.joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!resolving && topTracks.isEmpty()) {
                Text(
                    "No tracks were found for the selected albums.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it; playlistTarget = "" },
                label = { Text("New playlist name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy
            )
            Spacer(Modifier.height(6.dp))
            Box {
                OutlinedButton(
                    onClick = { playlistMenuOpen = true },
                    enabled = !busy,
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
                    onAdd(
                        chosenUris,
                        playlistTarget.ifBlank { null },
                        playlistName.trim().ifBlank { null }
                    )
                },
                enabled = !busy &&
                    chosenUris.isNotEmpty() &&
                    (playlistTarget.isNotBlank() || playlistName.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Adding…")
                } else {
                    Text("Add top tracks (${chosenUris.size})")
                }
            }
        }
    }
}
