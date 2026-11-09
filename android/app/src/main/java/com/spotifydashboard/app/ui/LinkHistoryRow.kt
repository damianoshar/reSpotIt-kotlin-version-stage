package com.spotifydashboard.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.utils.LinkHistoryBus
import com.spotifydashboard.app.utils.LinkHistoryStore
import com.spotifydashboard.app.utils.LinkTarget

/**
 * Recent + saved chips for a page/URL input. Picking a chip fills the dialog's
 * field; the save toggle pins the current value. Everything is device-local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkHistoryRow(
    kind: String,
    value: String,
    target: LinkTarget,
    onPick: (String, LinkTarget) -> Unit,
    enabled: Boolean = true,
    accept: List<LinkTarget> = listOf(LinkTarget.URL, LinkTarget.TEXT),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { LinkHistoryStore(context) }
    // Any write anywhere bumps the bus, so this row re-reads the store.
    val version = LinkHistoryBus.version

    val recent = remember(kind, version) { store.recent(kind).filter { it.target in accept } }
    val saved = remember(kind, version) { store.saved(kind).filter { it.target in accept } }
    val trimmed = value.trim()
    val alreadySaved = trimmed.isNotEmpty() && saved.any { it.value.trim() == trimmed }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recent.isEmpty()) {
                Text(
                    "none yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            recent.forEach { entry ->
                AssistChip(
                    onClick = {
                        store.recordRecent(kind, entry.value, entry.target)
                        onPick(entry.value, entry.target)
                    },
                    enabled = enabled,
                    label = {
                        Text(
                            (if (entry.target == LinkTarget.TEXT) "¶ " else "") + entry.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Saved",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (saved.isEmpty()) {
                Text(
                    "none yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            saved.forEach { entry ->
                InputChip(
                    selected = false,
                    onClick = {
                        store.recordRecent(kind, entry.value, entry.target)
                        onPick(entry.value, entry.target)
                    },
                    enabled = enabled,
                    label = {
                        Text(
                            (if (entry.target == LinkTarget.TEXT) "¶ " else "") + entry.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    },
                )
                IconButton(
                    onClick = {
                        entry.id?.let { store.removeSaved(kind, it) }
                    },
                    enabled = enabled,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove saved link",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            TextButton(
                onClick = {
                    if (trimmed.isNotEmpty()) {
                        store.toggleSaved(kind, value, target)
                    }
                },
                enabled = enabled && trimmed.isNotEmpty(),
            ) {
                Text(
                    if (alreadySaved) "Unsave" else "Save link",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
