package io.github.matthewdaluz.openhsr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matthewdaluz.openhsr.Boards
import io.github.matthewdaluz.openhsr.LocationStatus
import io.github.matthewdaluz.openhsr.RefreshStatus
import io.github.matthewdaluz.openhsr.StopSelection
import io.github.matthewdaluz.openhsr.data.Departure
import io.github.matthewdaluz.openhsr.data.HamiltonZone
import io.github.matthewdaluz.openhsr.data.NearbyStop
import io.github.matthewdaluz.openhsr.data.ScheduleInfo
import io.github.matthewdaluz.openhsr.data.Stop
import io.github.matthewdaluz.openhsr.data.firstDate
import io.github.matthewdaluz.openhsr.data.lastBoardDate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.CANADA)
private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA)

@Composable
fun DepartureSheet(
    height: Dp,
    stop: Stop?,
    selection: StopSelection,
    boards: Boards,
    nearby: List<NearbyStop>,
    location: LocationStatus,
    info: ScheduleInfo?,
    refresh: RefreshStatus,
    now: Instant,
    live: Boolean,
    query: String,
    results: List<Stop>,
    onQuery: (String) -> Unit,
    onSearchFocus: () -> Unit,
    onNearMe: () -> Unit,
    onStop: (Stop) -> Unit,
    onBack: () -> Unit,
    onDate: (LocalDate?) -> Unit,
    onRetry: () -> Unit,
) {
    val today = now.atZone(HamiltonZone).toLocalDate()
    var choosingDate by rememberSaveable(selection.stopId) { mutableStateOf(false) }
    val first = info?.firstDate?.let { maxOf(it, today) }
    val last = info?.lastBoardDate
    val canPickDate = first != null && last != null && first <= last
    Column(Modifier.fillMaxWidth().height(height)) {
        if (stop == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Nearby departures", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Button(onClick = onNearMe, enabled = location != LocationStatus.Finding) { Text("Near me") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search stops by name or number") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                trailingIcon = if (query.isNotEmpty()) {
                    { TextButton(onClick = { onQuery("") }) { Text("Clear") } }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)
                    .onFocusChanged { if (it.isFocused) onSearchFocus() },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(stop.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Stop ${stop.number}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(start = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selection.date == null,
                    onClick = { onDate(null) },
                    label = { Text("Upcoming") },
                )
                FilterChip(
                    selected = selection.date != null,
                    enabled = canPickDate,
                    onClick = { choosingDate = true },
                    label = { Text(selection.date?.format(dateFormat) ?: "Choose date") },
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                info == null -> item {
                    if (refresh == RefreshStatus.FAILED) {
                        SheetMessage("Couldn’t download HSR’s timetable. Connect to the internet and try again.", onRetry)
                    } else {
                        Loading("Downloading HSR’s timetable…")
                    }
                }

                stop != null -> {
                    val picked = selection.date
                    when {
                        !boards.hasSelectedBoard || boards.selection != selection -> item { Loading("Loading departures…") }
                        boards.selected.isEmpty() -> item {
                            val date = picked ?: today
                            val message = when {
                                date < info.firstDate || date > info.lastBoardDate -> "HSR’s published timetable doesn’t cover this date."
                                picked == null -> "No upcoming departures in the next 24 hours."
                                else -> "No scheduled departures on this date."
                            }
                            SheetMessage(message, if (refresh == RefreshStatus.FAILED) onRetry else null)
                        }
                        else -> {
                            if (picked == null && !live) item { SheetMessage("Live departure updates are unavailable.") }
                            items(boards.selected, key = { "${it.tripId}/${it.serviceDate}/${it.sequence}" }) { departure ->
                                DepartureRow(departure, now, countdown = picked == null)
                                HorizontalDivider()
                            }
                        }
                    }
                }

                query.isNotBlank() -> {
                    if (results.isEmpty()) item { SheetMessage("No HSR stops match your search.") }
                    items(results, key = Stop::id) { result ->
                        StopResult(result, null, null, now) { onStop(result) }
                    }
                }

                location == LocationStatus.Finding -> item { Loading("Finding your location…") }
                location == LocationStatus.Denied -> item {
                    SheetMessage("Location permission is off. Search for a stop or tap one on the map.")
                }
                location == LocationStatus.Unavailable -> item {
                    SheetMessage("Your location is unavailable. Use stop search or try Near me again.")
                }

                location is LocationStatus.Located -> {
                    if (nearby.isEmpty()) item { SheetMessage("No HSR stops within 800 m of your location.") }
                    items(nearby, key = { it.stop.id }) { nearbyStop ->
                        StopResult(nearbyStop.stop, nearbyStop.distanceMetres,
                            boards.nearby[nearbyStop.stop.id], now,
                            pending = nearbyStop.stop.id !in boards.nearby,
                        ) { onStop(nearbyStop.stop) }
                    }
                }

                else -> item { SheetMessage("Tap a stop on the map or find stops near you.") }
            }
        }
    }
    if (choosingDate && first != null && last != null && first <= last) {
        TimetableDateDialog(first, last, selection.date, { choosingDate = false }) { date ->
            choosingDate = false
            onDate(date)
        }
    }
}

@Composable
private fun Loading(message: String) {
    Row(
        Modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SheetMessage(message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(onClick = retry) { Text("Retry") }
    }
}

@Composable
private fun StopResult(
    stop: Stop,
    metres: Int?,
    departure: Departure?,
    now: Instant,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(stop.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val distance = metres?.let { " · $it m" }.orEmpty()
        Text("Stop ${stop.number}$distance", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (departure != null) {
            Text(
                "${departure.routeNumber}  ${departure.headsign} · ${formatDepartureTime(departure, now, true)} · ${countdown(departure, now)} · ${status(departure)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (metres != null) {
            Text(if (pending) "Loading departures…" else "No upcoming departures", style = MaterialTheme.typography.bodyMedium)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DepartureRow(departure: Departure, now: Instant, countdown: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(7.dp)) {
            Text(departure.routeNumber, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(departure.headsign, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val tint = if (departure.predictedAt != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Text(status(departure), style = MaterialTheme.typography.labelMedium, color = tint)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (countdown) {
                Text(countdown(departure, now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(formatDepartureTime(departure, now, countdown), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun status(departure: Departure): String = if (departure.predictedAt == null) "Scheduled" else "Live"

private fun formatDepartureTime(departure: Departure, now: Instant, includeDay: Boolean): String {
    val timestamp = departure.time.atZone(HamiltonZone)
    val formatted = timestamp.format(clockFormat)
    return if (includeDay && timestamp.toLocalDate() != now.atZone(HamiltonZone).toLocalDate()) {
        "${timestamp.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.CANADA)} $formatted"
    } else formatted
}

private fun countdown(departure: Departure, now: Instant): String {
    val seconds = Duration.between(now, departure.time).seconds
    if (seconds < 60) return "Due"
    val minutes = (seconds + 59) / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableDateDialog(
    first: LocalDate,
    last: LocalDate,
    selected: LocalDate?,
    onDismiss: () -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    val dates = remember(first, last) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.utcDate() in first..last
            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        }
    }
    val initial = selected?.coerceIn(first, last) ?: first
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = first.year..last.year,
        selectableDates = dates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onDate(it.utcDate()) } },
                enabled = picker.selectedDateMillis?.let(dates::isSelectableDate) == true) { Text("Show timetable") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = picker, showModeToggle = false)
    }
}

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
