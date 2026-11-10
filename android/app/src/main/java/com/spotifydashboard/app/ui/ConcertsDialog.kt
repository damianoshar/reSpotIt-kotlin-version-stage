package com.spotifydashboard.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.data.ConcertEntity
import com.spotifydashboard.app.api.ConcertScanDto
import com.spotifydashboard.app.utils.ConcertCalendarDates
import com.spotifydashboard.app.utils.LinkHistoryStore
import com.spotifydashboard.app.utils.LinkTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two sheets of one dialog: the saved-concert calendar and the page scan. */
private enum class ConcertsTab { CALENDAR, SCAN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertsDialog(
    concerts: List<ConcertEntity>,
    scanRows: List<ConcertScanRow>,
    scan: ConcertScanDto?,
    loading: Boolean,
    message: String?,
    onScan: (String?, String?) -> Unit,
    onUploadFile: (String, ByteArray, String) -> Unit,
    // Open the in-app browser so a JS-rendered page can be snapshotted.
    onOpenBrowser: (String?) -> Unit,
    onRefreshScan: () -> Unit,
    onToggleScanRow: (Int) -> Unit,
    onSaveScanRows: (Boolean) -> Unit,
    onClearScanRows: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    // Calendar view state lives in the caller so it survives closing the dialog.
    calendarMonth: ConcertCalendarDates.Month,
    calendarSelectedDate: String?,
    onCalendarMonthChange: (ConcertCalendarDates.Month) -> Unit,
    onCalendarDateChange: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    // Default to correlating results with the user's followed Spotify artists.
    var followedOnly by remember { mutableStateOf(true) }
    // Calendar and scan are two tabs of one sheet: the React client has two
    // separate dialogs, this keeps both one tap away.
    var tab by remember { mutableStateOf(ConcertsTab.CALENDAR) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linkStore = remember { LinkHistoryStore(context) }

    // Read the chosen document's name, MIME and bytes, then hand it to the
    // ViewModel which uploads it as the scan source (click-and-forget).
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }?.takeIf { it.isNotBlank() } ?: "upload.txt"
            val mime = resolver.getType(uri) ?: "text/plain"
            val bytes = try {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (bytes != null) onUploadFile(name, bytes, mime)
            }
        }
    }

    val followedCount = scanRows.count { it.followed }
    val visibleRows = scanRows.mapIndexed { index, row -> index to row }
        .filter { (_, row) -> !followedOnly || row.followed }
    val selectedVisible = scanRows.count { it.selected && (!followedOnly || it.followed) }

    // If the scan matched no followed artist, an empty "Followed" view looks
    // like the scan found nothing. Show all results in that case.
    LaunchedEffect(followedCount, scanRows.size) {
        if (followedCount == 0 && scanRows.isNotEmpty()) followedOnly = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (tab == ConcertsTab.CALENDAR) "Concerts calendar" else "Scan concerts (AI)")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == ConcertsTab.CALENDAR,
                        onClick = { tab = ConcertsTab.CALENDAR },
                        label = { Text("Calendar") },
                    )
                    FilterChip(
                        selected = tab == ConcertsTab.SCAN,
                        onClick = { tab = ConcertsTab.SCAN },
                        label = { Text("Scan page") },
                    )
                }

                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }

                // A scan that is running or ready is announced on both tabs,
                // so the user does not have to hunt for the review list.
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
                                if (scan.status == "ready") {
                                    TextButton(onClick = { tab = ConcertsTab.SCAN }) {
                                        Text("Review")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (tab == ConcertsTab.CALENDAR) {
                    ConcertCalendar(
                        concerts = concerts,
                        month = calendarMonth,
                        selectedDate = calendarSelectedDate,
                        onMonthChange = onCalendarMonthChange,
                        onSelectDate = onCalendarDateChange,
                        onDelete = onDelete,
                        onClear = onClear,
                    )
                } else {
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
                        // Bounded height: without maxLines the field grows to fit the
                        // whole paste and pushes the dialog off-screen. With maxLines
                        // set, Compose scrolls the text inside the field instead.
                        minLines = 3,
                        maxLines = 6
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkHistoryRow(
                        kind = "concerts",
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
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val url = urlText.takeIf { it.isNotBlank() }
                            val paste = pasteText.takeIf { it.isNotBlank() }
                            if (url != null) linkStore.recordRecent("concerts", url, LinkTarget.URL)
                            else if (paste != null) linkStore.recordRecent("concerts", paste, LinkTarget.TEXT)
                            onScan(url, paste)
                        },
                        enabled = !loading && (urlText.isNotBlank() || pasteText.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Scan with AI")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(arrayOf("text/*", "application/octet-stream"))
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Attach file (.txt / .md / .html)")
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onOpenBrowser(urlText.takeIf { it.isNotBlank() }) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open in browser and scan the page")
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
                            ConcertScanCard(
                                row = row,
                                checked = row.selected,
                                enabled = !loading,
                                onToggle = { onToggleScanRow(index) },
                            )
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * One extracted concert in the review list. The artist and the follow-up line
 * are separate blocks, so they can never run into each other.
 */
@Composable
private fun ConcertScanCard(
    row: ConcertScanRow,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
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
}
