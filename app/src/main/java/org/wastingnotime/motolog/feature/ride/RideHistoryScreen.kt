package org.wastingnotime.motolog.feature.ride

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.RideRepository
import org.wastingnotime.motolog.feature.common.observationText
import java.util.Locale

@Composable
fun RideHistoryScreen(repository: RideRepository, bike: String, back: () -> Unit) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var eventAfter by rememberSaveable(selected) { mutableLongStateOf(-1) }
    var gapOffset by rememberSaveable(selected) { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<RideSession>>(emptyList()) }
    var detail by remember { mutableStateOf<RideRepository.Detail?>(null) }
    var events by remember { mutableStateOf<List<RideSample>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<RideGap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceDetails by remember(selected) { mutableStateOf(false) }
    BackHandler { if (selected != null) selected = null else back() }
    LaunchedEffect(offset, selected, eventAfter, gapOffset) {
        error = null; detail = null
        try {
            withContext(Dispatchers.IO) {
                val id = selected
                if (id == null) rows = repository.history(bike, offset)
                else { detail = repository.detail(id); events = repository.events(id, eventAfter); gaps = repository.gaps(id, gapOffset) }
            }
        } catch (_: Exception) { error = "Ride history unavailable. Saved records are retained; return and try again." }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { if (selected != null) selected = null else back() }) { Text(if (selected == null) "Back to dashboard" else "Back to rides") }
        Text(if (selected == null) "Ride history" else "Ride details", style = MaterialTheme.typography.headlineMedium)
        Text("Parked management · read only")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (selected == null) {
            if (rows.isEmpty()) Text("No rides on this page.")
            rows.forEach { ride ->
                OutlinedButton(onClick = { selected = ride.id }, modifier = Modifier.fillMaxWidth()) {
                    Text("${observationText(ride.started)} · ${ride.state.name.lowercase().replaceFirstChar { it.uppercase() }}")
                }
            }
            if (offset > 0) TextButton(onClick = { offset = (offset - 20).coerceAtLeast(0) }) { Text("Newer rides") }
            if (rows.size == 20) TextButton(onClick = { offset += 20 }) { Text("Older rides") }
        } else detail?.let { saved ->
            Text("Started: ${observationText(saved.ride.started)}")
            Text("Ended: ${saved.ride.ended?.let(::observationText) ?: "Still open"}")
            val total = saved.total?.takeIf { it.eligibleMillis > 0 }
            Text("GPS distance: ${total?.let { String.format(Locale.US, "%.3f km", it.meters / 1000) } ?: "unavailable · no eligible pairs"}")
            Text("Average speed: ${saved.averageKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "unavailable · no eligible time"}")
            Text("Eligible duration: ${total?.let { "${it.eligibleMillis / 1000} seconds" } ?: "unavailable"}")
            Text("Partial GPS coverage · measured stops included; gaps excluded. ${saved.gapCount} saved gaps; ${saved.rejected} rejected fixes.")
            saved.cursor?.gap?.let { Text("Open coverage gap since ${observationText(it)}") }
            Text("Speed-change events", style = MaterialTheme.typography.titleLarge)
            Text("Native GPS speed · first usable speed and changes of at least 5 km/h")
            if (events.isEmpty()) Text("No events on this page.")
            events.forEach { Text("${observationText(it.observed)} · ${String.format(Locale.US, "%.1f km/h", it.speedMps * 3.6)}") }
            if (events.size == 20) TextButton(onClick = { eventAfter = events.last().sequence }) { Text("Next speed events") }
            if (eventAfter >= 0) TextButton(onClick = { eventAfter = -1 }) { Text("First speed events") }
            Text("Coverage gaps", style = MaterialTheme.typography.titleLarge)
            if (gaps.isEmpty()) Text("No saved gaps on this page.")
            gaps.forEach { Text("${it.reason.lowercase().replace('_', ' ')} · ${observationText(it.started)} → ${it.ended?.let(::observationText) ?: "open"} · ${it.rejectedCount} rejected fixes") }
            if (gaps.size == 20) TextButton(onClick = { gapOffset += 20 }) { Text("Next gaps") }
            if (gapOffset > 0) TextButton(onClick = { gapOffset = 0 }) { Text("First gaps") }
            TextButton(onClick = { sourceDetails = !sourceDetails }) { Text("Recording source details") }
            if (sourceDetails) Text("GPS evidence · ${saved.ride.algorithmVersion}\nProjection: ${saved.total?.algorithmVersion ?: "unavailable for this historical recording"}")
        }
    }
}
