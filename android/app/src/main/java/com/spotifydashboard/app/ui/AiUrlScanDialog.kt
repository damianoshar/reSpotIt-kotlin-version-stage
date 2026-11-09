package com.spotifydashboard.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.utils.LinkHistoryStore
import com.spotifydashboard.app.utils.LinkTarget

@Composable
fun AiUrlScanDialog(
    loading: Boolean,
    message: String?,
    onScan: (String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val linkStore = remember { LinkHistoryStore(context) }

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
                    onValueChange = { urlText = it },
                    label = { Text("Page URL") },
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
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val url = urlText.takeIf { it.isNotBlank() }
                        val paste = pasteText.takeIf { it.isNotBlank() }
                        if (url != null) linkStore.recordRecent("albums", url, LinkTarget.URL)
                        else if (paste != null) linkStore.recordRecent("albums", paste, LinkTarget.TEXT)
                        onScan(url, paste)
                    },
                    enabled = !loading && (urlText.isNotBlank() || pasteText.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Scan with AI")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}