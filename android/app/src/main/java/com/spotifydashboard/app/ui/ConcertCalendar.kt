package com.spotifydashboard.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.data.ConcertEntity
import com.spotifydashboard.app.utils.ConcertCalendarDates
import com.spotifydashboard.app.utils.ConcertCalendarDates.Month

/**
 * The saved-concert calendar: a month grid with markers on the days that have
 * concerts, and the concerts themselves as cards.
 *
 * The month and the selected day live in the caller (the dashboard screen),
 * so they survive closing and reopening the dialog - the same behaviour as
 * the React client.
 */
@Composable
fun ConcertCalendar(
    concerts: List<ConcertEntity>,
    month: Month,
    selectedDate: String?,
    onMonthChange: (Month) -> Unit,
    onSelectDate: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { ConcertCalendarDates.todayIso() }
    val grid = remember(month) { ConcertCalendarDates.monthGrid(month) }

    // Concerts grouped by their YYYY-MM-DD key; undated ones are listed apart.
    val byDate: Map<String, List<ConcertEntity>> = remember(concerts) {
        val map = LinkedHashMap<String, List<ConcertEntity>>()
        for (concert in concerts) {
            val key = ConcertCalendarDates.dateKey(concert.date) ?: continue
            map[key] = (map[key] ?: emptyList()) + concert
        }
        map
    }
    val dated = remember(concerts) {
        concerts.filter { ConcertCalendarDates.dateKey(it.date) != null }
            .sortedBy { ConcertCalendarDates.dateKey(it.date) }
    }
    val upcoming = dated.filter { (ConcertCalendarDates.dateKey(it.date) ?: "") >= today }
    val past = dated.filter { (ConcertCalendarDates.dateKey(it.date) ?: "") < today }
        .sortedByDescending { ConcertCalendarDates.dateKey(it.date) }
    val undated = concerts.filter { ConcertCalendarDates.dateKey(it.date) == null }
        .sortedBy { it.artist.lowercase() }

    val selectedConcerts = selectedDate?.let { byDate[it] } ?: emptyList()
    val listed = if (selectedDate != null) selectedConcerts else upcoming

    Column(modifier = modifier.fillMaxWidth()) {
        // Month header ------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(month.shifted(-1)) }) {
                Text("\u2039", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                month.label(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                onMonthChange(Month.current())
                onSelectDate(today)
            }) { Text("Today") }
            IconButton(onClick = { onMonthChange(month.shifted(1)) }) {
                Text("\u203a", style = MaterialTheme.typography.titleLarge)
            }
        }

        // Weekday header ----------------------------------------------------
        Row {
            ConcertCalendarDates.WEEKDAY_NAMES.forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Month grid --------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (row in 0 until 6) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (column in 0 until 7) {
                        val day = grid[row * 7 + column]
                        DayCell(
                            day = day,
                            count = byDate[day.iso]?.size ?: 0,
                            selected = day.iso == selectedDate,
                            today = day.iso == today,
                            onClick = { onSelectDate(if (selectedDate == day.iso) null else day.iso) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Concerts ----------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selectedDate != null) "Concerts on $selectedDate" else "Upcoming",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (selectedDate != null) {
                TextButton(onClick = { onSelectDate(null) }) { Text("Show all upcoming") }
            }
        }

        if (listed.isEmpty()) {
            Text(
                if (selectedDate != null) "No concerts on this day." else "No upcoming concerts saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            listed.forEach { concert ->
                Spacer(Modifier.height(4.dp))
                ConcertCard(concert = concert, onDelete = onDelete)
            }
        }

        if (undated.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Without a date",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            undated.forEach { concert ->
                Spacer(Modifier.height(4.dp))
                ConcertCard(concert = concert, onDelete = onDelete)
            }
        }

        if (past.isNotEmpty() && selectedDate == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Past",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            past.forEach { concert ->
                Spacer(Modifier.height(4.dp))
                ConcertCard(concert = concert, onDelete = onDelete, dimmed = true)
            }
        }

        if (concerts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear all") }
        }
    }
}

/**
 * One day of the grid. The day number and the "has concerts" marker are
 * separate elements, so a day with concerts stays readable at any font size.
 */
@Composable
private fun RowScope.DayCell(
    day: ConcertCalendarDates.Day,
    count: Int,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = when {
        selected || count > 0 -> scheme.primary
        else -> scheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primary else Color.Transparent)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.day.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (count > 0 || today) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> scheme.onPrimary
                !day.inMonth -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
                else -> scheme.onSurface
            },
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (selected) scheme.onPrimary else scheme.primary),
            )
        }
    }
}

/**
 * One concert as a card: the artist and the follow-up line are separate
 * blocks, so they can never run into each other.
 */
@Composable
private fun ConcertCard(
    concert: ConcertEntity,
    onDelete: (String) -> Unit,
    dimmed: Boolean = false,
) {
    val meta = listOf(concert.date.ifBlank { "date TBD" }, concert.venue)
        .filter { it.isNotBlank() }
        .joinToString(" \u00b7 ")
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    concert.artist.ifBlank { "(unknown artist)" },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (concert.followed) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        "\u2605 followed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = { onDelete(concert.id) }) {
                Icon(Icons.Default.Close, contentDescription = "Delete ${concert.artist}")
            }
        }
    }
}
