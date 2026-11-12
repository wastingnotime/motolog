package org.wastingnotime.motolog.feature.ride

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.wastingnotime.motolog.data.repository.RideRepository
import org.wastingnotime.motolog.domain.model.RideState
import org.wastingnotime.motolog.domain.ride.observationAt
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Composable
fun RideScreen(runtime: RideRuntime, bike: String, back: () -> Unit) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<RideRepository.View?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var fine by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    val error by runtime.error.collectAsState()
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        notifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(bike) {
        while (isActive) {
            fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            notifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            try { view = withContext(Dispatchers.IO) { runtime.repository.view(bike, LocalDate.now().toString()) } }
            catch (_: Exception) { localError = "Local records unavailable. Reopen to recover committed records." }
            delay(1_000)
        }
    }
    fun start(resume: String? = null) {
        localError = null
        if (!fine) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
            return
        }
        // Activity-only launch: promote in the service after its final permission check.
        // A revoked permission between these checks must not leave a pending FGS-start deadline.
        try {
            context.startService(Intent(context, RideCaptureService::class.java)
                .setAction(if (resume == null) RideCaptureService.START else RideCaptureService.RESUME)
                .putExtra("bike", bike).putExtra("operation", UUID.randomUUID().toString()).putExtra("ride", resume))
        } catch (_: RuntimeException) { localError = "Recording could not start. Keep MotoLog visible and check location permission." }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manual ride", style = MaterialTheme.typography.headlineLarge)
                Text("Use controls only while safely stopped. Automatic mode: off.")
                val active = view?.active
                Text(when (active?.state) {
                    RideState.ACTIVE -> if (view?.cursor?.gap != null) "Recording · GPS gap / waiting for usable fix" else "Recording · GPS estimate"
                    RideState.INTERRUPTED -> "Interrupted · saved ride needs recovery"
                    else -> "Not recording"
                }, modifier = Modifier.testTag("ride-state"), style = MaterialTheme.typography.titleLarge)
                Text(if (fine) "Precise location granted" else "Precise location denied or approximate only. Ride metrics unavailable; saved data is retained.")
                Text(if (notifications) "Notifications allowed" else "Notifications unavailable or denied. Recording status and Stop remain here; Android may show the service in active apps.")
                TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) }) { Text("Review recording permissions") }
                (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (view == null) Text("Opening saved ride…")
                else if (active == null) Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Start ride") }
                else if (active.state == RideState.INTERRUPTED) {
                    Text("No distance or time is added for the interruption. Resume uses the same ride, with a new GPS baseline. End uses the last persisted usable observation (or start if none).")
                    Button(onClick = { start(active.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Resume same ride") }
                    OutlinedButton(onClick = { runtime.execute { runtime.repository.stop(active.id, observationAt(System.currentTimeMillis()), true) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("End at last observation") }
                } else Button(onClick = {
                    context.startService(Intent(context, RideCaptureService::class.java).setAction(RideCaptureService.STOP).putExtra("ride", active.id))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Stop ride") }
                val saved = view
                if (saved != null) {
                    Text("Today · recorded local days", style = MaterialTheme.typography.titleLarge)
                    Text("GPS distance: ${if (saved.millis == 0L) "unavailable · no eligible pairs" else String.format(Locale.US, "%.3f km", saved.meters / 1000)}", modifier = Modifier.testTag("ride-distance"))
                    Text("Average speed: ${saved.averageKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "unavailable · no eligible time"}", modifier = Modifier.testTag("ride-average"))
                    Text("Partial coverage · ${saved.millis / 1000} eligible seconds, including measured stops. Gaps excluded. ${saved.days.sumOf { it.gaps }} gap starts; ${saved.days.sumOf { it.rejected }} rejected fixes.")
                    saved.cursor?.gap?.let { Text("Open gap: ${saved.cursor.gapReason} · since ${java.time.Instant.ofEpochMilli(it.epochMillis)} · rejected ${saved.cursor.rejected}") }
                    Text("Recent saved rides", style = MaterialTheme.typography.titleLarge)
                    saved.recent.forEach { Text("${it.state} · ${java.time.Instant.ofEpochMilli(it.started.epochMillis)} · ${it.started.zoneId}\nGPS estimate · ${it.algorithmVersion}") }
                    saved.gaps.forEach { Text("Gap: ${it.reason} · rejected ${it.rejectedCount}\n${java.time.Instant.ofEpochMilli(it.started.epochMillis)} → ${it.ended?.let { end -> java.time.Instant.ofEpochMilli(end.epochMillis) } ?: "open"}") }
                }
                TextButton(onClick = { scope.launch { back() } }) { Text("Back to checklist") }
            }
        }
    }
}

/** Remains visible in parked management flows; Stop never depends on a fix or motion. */
@Composable
fun RideStatusBar(runtime: RideRuntime) {
    var status by remember { mutableStateOf<Pair<org.wastingnotime.motolog.data.local.RideSession?, org.wastingnotime.motolog.data.local.RecordingCursor?>>(null to null) }
    val context = LocalContext.current
    LaunchedEffect(runtime) {
        while (isActive) {
            try { status = withContext(Dispatchers.IO) { runtime.repository.status() } } catch (_: Exception) { /* Screen shows read failure. */ }
            delay(1_000)
        }
    }
    val failure by runtime.error.collectAsState()
    failure?.let { MaterialTheme { Text(it, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) } }
    val active = status.first
    if (active != null) MaterialTheme {
        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (failure != null) "Recording stopped · reopen to recover"
                    else if (active.state == RideState.INTERRUPTED) "Ride interrupted · open Manual ride"
                    else if (status.second?.gap != null) "Recording · GPS gap" else "Recording · GPS estimate", modifier = Modifier.weight(1f).padding(top = 12.dp))
                if (active.state == RideState.ACTIVE) Button(onClick = {
                    context.startService(Intent(context, RideCaptureService::class.java).setAction(RideCaptureService.STOP).putExtra("ride", active.id))
                }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Stop ride") }
            }
        }
    }
}
