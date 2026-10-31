package com.spotifydashboard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.spotifydashboard.app.data.AlbumEntity
import com.spotifydashboard.app.ui.theme.SpotifyCardGray
import com.spotifydashboard.app.ui.theme.SpotifyGreen
import com.spotifydashboard.app.ui.theme.SpotifyLightGray
import com.spotifydashboard.app.ui.theme.SpotifyRed
import com.spotifydashboard.app.utils.DuplicateGroup

/**
 * Full-screen duplicate review (card 269).
 *
 * Every group starts with all copies except the oldest selected for removal.
 * The owner can uncheck the copy to keep; "Remove" is disabled while any group
 * would lose every copy. Removal is app-level: rows are hidden locally and
 * blocked from sync, and can be restored here.
 *
 * Note: keep the body free of early `return@` statements — returning from the
 * composable lambda mid-way leaves unbalanced groups and corrupted the Compose
 * slot table (ArrayIndexOutOfBoundsException at endRoot). Use if/else instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateAlbumsDialog(
    groups: List<DuplicateGroup>?,
    hiddenAlbums: List<AlbumEntity>,
    loading: Boolean,
    isRemote: Boolean,
    onDismiss: () -> Unit,
    onRemove: (List<AlbumEntity>) -> Unit,
    onRemovePermanently: (List<AlbumEntity>) -> Unit,
    onRestore: () -> Unit
) {
    var showPermanentConfirm by remember { mutableStateOf(false) }
    val selectedToRemove = remember(groups) {
        mutableStateMapOf<String, Boolean>().apply {
            groups?.forEach { group ->
                group.albums.forEachIndexed { index, album ->
                    put(album.id, index > 0)
                }
            }
        }
    }

    val selectedAlbums = groups.orEmpty()
        .flatMap { group -> group.albums.filter { selectedToRemove[it.id] == true } }
    val invalidGroups = groups.orEmpty().filter { group ->
        group.albums.all { selectedToRemove[it.id] == true }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Duplicate albums") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )

                if (showPermanentConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showPermanentConfirm = false },
                        title = { Text("Permanently remove from Spotify?") },
                        text = {
                            Text(
                                "This permanently removes the selected ${selectedAlbums.size} album(s) from your " +
                                    "Spotify library online. This cannot be undone."
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPermanentConfirm = false
                                onRemovePermanently(selectedAlbums)
                            }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyRed, contentColor = Color.White)) {
                                Text("Remove permanently")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showPermanentConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (isRemote) {
                    BoxMessage(
                        title = "Local mode only for now",
                        body = "Duplicate cleanup needs the on-device library. Switch the media server " +
                            "off in Settings (or use the phone's local library) and try again. " +
                            "Nothing on the server or in Spotify has been changed."
                    )
                } else if (loading || groups == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = SpotifyGreen)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Scanning the local library…", color = SpotifyLightGray)
                    }
                } else {
                    val allGroups = groups

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "Groups are matched by title and primary artist, ignoring deluxe, " +
                                    "remaster and regional tags. Uncheck the copy to keep. Removal is " +
                                    "local to this device and reversible — Spotify is untouched.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyLightGray
                            )
                        }

                        if (allGroups.isEmpty()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("No duplicate albums found", fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Nothing looks like the same release under two Spotify IDs.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SpotifyLightGray
                                        )
                                    }
                                }
                            }
                        }

                        items(allGroups, key = { it.key }) { group ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        group.title,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        group.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SpotifyLightGray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    group.albums.forEach { album ->
                                        DuplicateAlbumRow(
                                            album = album,
                                            remove = selectedToRemove[album.id] == true,
                                            onToggle = { selectedToRemove[album.id] = it }
                                        )
                                    }
                                    if (group.albums.all { selectedToRemove[it.id] == true }) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Keep at least one copy in this group.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SpotifyRed
                                        )
                                    }
                                }
                            }
                        }

                        if (hiddenAlbums.isNotEmpty()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SpotifyCardGray),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            "Hidden duplicates",
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${hiddenAlbums.size} album${if (hiddenAlbums.size == 1) "" else "s"} " +
                                                "hidden from this device. They will not come back on sync.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SpotifyLightGray,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        OutlinedButton(
                                            onClick = onRestore,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 12.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Restore,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Restore hidden albums")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (allGroups.isNotEmpty()) {
                        Surface(color = Color.Black, tonalElevation = 4.dp) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${selectedAlbums.size} selected",
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (invalidGroups.isNotEmpty()) {
                                        Text(
                                            "Every group needs at least one keeper.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SpotifyRed
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { showPermanentConfirm = true },
                                    enabled = selectedAlbums.isNotEmpty() && invalidGroups.isEmpty() && !isRemote,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyRed)
                                ) {
                                    Text("Remove permanently")
                                }
                                Button(
                                    onClick = { onRemove(selectedAlbums) },
                                    enabled = selectedAlbums.isNotEmpty() && invalidGroups.isEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SpotifyGreen,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Remove duplicates")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateAlbumRow(
    album: AlbumEntity,
    remove: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubcomposeAsyncImage(
            model = artworkModel(album.imageUrl),
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                album.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                buildString {
                    if (album.year > 0) append(album.year)
                    if (album.year > 0) append(" · ")
                    append(album.id)
                },
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("Remove", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        Checkbox(
            checked = remove,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun BoxMessage(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = SpotifyLightGray)
    }
}
