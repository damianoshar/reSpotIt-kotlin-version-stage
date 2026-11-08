package com.spotifydashboard.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.data.ConcertEntity
import com.spotifydashboard.app.api.ConcertScanDto

@Composable
fun ConcertsDialog(
    concerts: List<ConcertEntity>,
    scanRows: List<ConcertScanRow>,
    scan: ConcertScanDto?,
    loading: Boolean,
    message: String?,
    onScan: (String?, String?) -> Unit,
    onRefreshScan: () -> Unit,
    onToggleScanRow: (Int) -> Unit,
    onSaveScanRows: (Boolean) -> Unit,
    onClearScanRows: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    // Default to correlating results with the user's followed Spotify artists.
    var followedOnly by remember { mutableStateOf(true) }

    val followedCount = scanRows.count { it.followed }
    val visibleRows = scanRows.mapIndexed { index, row -> index to row }
        .filter { (_, row) -> !followedOnly || row.followed }
    val selectedVisible = scanRows.count { it.selected && (!followedOnly || it.followed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Concerts calendar") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Concerts page URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("...or paste page text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onScan(urlText.takeIf { it.isNotBlank() }, pasteText.takeIf { it.isNotBlank() }) },
                    enabled = !loading && (urlText.isNotBlank() || pasteText.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Scan with AI")
                }

                if (scan != null) {
                    Spacer(Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                when (scan.status) {
                                    "running" -> "Scanning in the background (${scan.stage.ifBlank { "queued" }})..."
                                    "ready" -> "Scan ready: ${scan.items.size} concert(s) to review."
                                    "error" -> "Scan failed: ${scan.error.ifBlank { "unknown error" }}"
                                    else -> "Scan ${scan.status}"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (scan.source_url.isNotBlank()) {
                                Text(
                                    scan.source_url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onRefreshScan, enabled = !loading) {
                                    Text("Refresh")
                                }
                                TextButton(onClick = onClearScanRows, enabled = !loading) {
                                    Text("Dismiss scan")
                                }
                            }
                        }
                    }
                }

                if (scanRows.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { followedOnly = true }) {
                            Text(
                                "Followed ($followedCount)",
                                fontWeight = if (followedOnly) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        TextButton(onClick = { followedOnly = false }) {
                            Text(
                                "All (${scanRows.size})",
                                fontWeight = if (followedOnly) FontWeight.Normal else FontWeight.Bold
                            )
                        }
                    }
                    if (followedOnly && followedCount == 0) {
                        Text(
                            "No concerts by artists you follow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { followedOnly = false }) {
                            Text("Show all ${scanRows.size}")
                        }
                    }
                    visibleRows.forEach { (index, row) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = row.selected,
                                onCheckedChange = { onToggleScanRow(index) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.artist, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append(row.date.ifBlank { "date unknown" })
                                        if (row.venue.isNotBlank()) append(" \u00b7 ${row.venue}")
                                        if (row.followed) append(" \u00b7 \u2605 followed")
                                        if (row.spotifyId.isBlank()) append(" \u00b7 not on Spotify")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSaveScanRows(followedOnly) },
                            enabled = !loading && selectedVisible > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (loading) "Saving..." else "Save $selectedVisible concert(s)")
                        }
                        TextButton(onClick = onClearScanRows, enabled = !loading) {
                            Text("Clear")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (concerts.isEmpty()) {
                    Text("No concerts saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(concerts, key = { it.id }) { c ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.artist, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        (c.date.ifBlank { "date TBD" }) + (if (c.venue.isNotBlank()) " \u00b7 ${c.venue}" else "") + (if (c.followed) " \u00b7 \u2605" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDelete(c.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear all")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}