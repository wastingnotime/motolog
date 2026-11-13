package org.wastingnotime.motolog.feature.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.maintenance.*
import org.wastingnotime.motolog.feature.common.observationText
import org.wastingnotime.motolog.feature.fuel.FuelScreen
import org.wastingnotime.motolog.feature.maintenance.MaintenanceScreen
import org.wastingnotime.motolog.feature.registration.RegistrationScreen
import org.wastingnotime.motolog.feature.ride.*
import java.time.LocalDate
import java.util.Locale

@Composable
fun MotoLogScreen(registration: RegistrationRepository, maintenance: MaintenanceRepository,
    fuel: FuelRepository, runtime: RideRuntime, clock: () -> Long = System::currentTimeMillis) {
    var armed by remember { mutableStateOf(false) }
    var route by rememberSaveable { mutableStateOf("dashboard") }
    var saved by remember { mutableStateOf<WriteBatch?>(null) }
    var ride by remember { mutableStateOf<RideRepository.View?>(null) }
    var due by remember { mutableStateOf<MaintenanceView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(route) {
        while (isActive) {
            try {
                val loaded = withContext(Dispatchers.IO) { fuel.snapshot() }
                saved = loaded; now = clock(); error = null
                loaded.bikes.singleOrNull()?.let { bike ->
                    val reading = loaded.readings.filter { it.certainty == org.wastingnotime.motolog.domain.model.Certainty.CONFIRMED }.maxByOrNull { it.observed!!.epochMillis }
                    ride = withContext(Dispatchers.IO) { runtime.repository.view(bike.id, LocalDate.now().toString()) }
                    due = withContext(Dispatchers.IO) {
                        try { maintenance.refresh(bike.id, reading?.id, LocalDate.now(), now) }
                        catch (_: MilestoneReviewRequired) { maintenance.existing(bike.id, reading?.id, LocalDate.now(), now) }
                    }
                }
            } catch (_: Exception) { error = "Local records unavailable. Saved records are retained. Reopen to try again." }
            if (route != "dashboard") break
            delay(5_000)
        }
    }
    LaunchedEffect(route) {
        if (route == "settings") while (isActive) {
            try { armed = withContext(Dispatchers.IO) { runtime.repository.automatic() != null } }
            catch (_: Exception) { error = "Automatic mode unavailable. Reopen to reload saved state." }
            delay(1_000)
        }
    }
    val bike = saved?.bikes?.singleOrNull()
    val reading = saved?.readings?.filter { it.certainty == org.wastingnotime.motolog.domain.model.Certainty.CONFIRMED }?.maxByOrNull { it.observed!!.epochMillis }
    val home = { route = "dashboard" }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF155E63), onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EEEA), onPrimaryContainer = Color(0xFF102F30),
        surfaceContainerHighest = Color(0xFFE3ECE8), onSurfaceVariant = Color(0xFF394B48), background = Color(0xFFF6F8F6), surface = Color(0xFFF6F8F6))) {
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
            activity?.let {
                WindowCompat.getInsetsController(it.window, view).apply {
                    isAppearanceLightStatusBars = lightSurface
                    isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                RideStatusBar(runtime)
                Box(Modifier.weight(1f)) {
                    BackHandler(enabled = route != "dashboard") { home() }
                    when {
                        route == "registration" || saved != null && bike == null -> RegistrationScreen(registration, maintenance, fuel, runtime, home)
                        route == "quick-refuel" && bike != null -> FuelScreen(fuel, bike.id, "refuel", true, home)
                        route == "fuel" && bike != null -> FuelScreen(fuel, bike.id, back = home)
                        route == "maintenance" && bike != null -> MaintenanceScreen(maintenance, bike.id, reading?.id, home)
                        route == "rides" && bike != null -> RideHistoryScreen(runtime.repository, bike.id, home)
                        route == "settings" -> Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = home) { Text("Back to dashboard") }
                            Text("Settings", style = MaterialTheme.typography.headlineMedium)
                            Text("Parked management")
                            Text(if (armed) "Automatic tracking: armed" else "Automatic tracking: off", style = MaterialTheme.typography.titleLarge)
                            Text("Use dashboard ride controls to arm optional automatic tracking or record manually. Precise location is required. Monitoring uses battery and must be re-armed after interruption.")
                            Text("Display: English (US) · km · liters · km/h · BRL")
                            Text("Records are stored on this device and work offline.")
                            OutlinedButton(onClick = { route = "registration" }) { Text("Motorcycle and checklist") }
                        }
                        else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spacer(Modifier.height(4.dp))
                            Text("MotoLog · Your Fazer", style = MaterialTheme.typography.headlineSmall)
                            Text("Use quick actions only while safely stopped.", style = MaterialTheme.typography.bodySmall)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (saved == null) Text("Opening local records…")
                            bike?.let {
                                RideScreen(runtime, it.id, embedded = true, back = home)
                                Button(onClick = { route = "quick-refuel" }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Quick refuel", style = MaterialTheme.typography.titleLarge) }
                                val status = FuelProjection.evaluate(it.id, saved!!.fuels, saved!!.readings, now)
                                DashboardMetrics(listOf(
                                    DashboardMetric("GPS distance today", ride?.takeIf { it.millis > 0 }?.let { String.format(Locale.US, "%.3f km", it.meters / 1000) }
                                        ?: "Unavailable · no eligible pairs", "dashboard-distance"),
                                    DashboardMetric("Average speed today", ride?.averageKmh?.let { String.format(Locale.US, "%.1f km/h", it) }
                                        ?: "Unavailable · no eligible time", "dashboard-average"),
                                    DashboardMetric("Kilometers since refuel", status.kilometersSince?.let { km -> "$km km" }
                                        ?: "Unavailable · ${status.unavailable!!.message}", "dashboard-fuel"),
                                    DashboardMetric("Last refuel odometer", status.refuelReading?.kilometers?.let { km -> "$km km" }
                                        ?: "Unavailable", "dashboard-last-refuel")
                                ))
                                Text("GPS today includes measured stops; gaps excluded. Coverage may be partial.", style = MaterialTheme.typography.bodySmall)
                                status.current?.let { current ->
                                    Text("Owner reading: ${current.kilometers} km\nObserved: ${observationText(current.observed!!)}\nAge: ${status.currentAgeMillis!! / 60_000} minutes · ${if (status.currentAgeMillis in 0..FuelProjection.FRESH_MILLIS) "fresh" else "not current"}", modifier = Modifier.testTag("dashboard-reading"))
                                } ?: Text("Current owner reading: unavailable", modifier = Modifier.testTag("dashboard-reading"))
                                Text("GPS never supplies the motorcycle odometer.", style = MaterialTheme.typography.bodySmall)
                                Text("Care and records", style = MaterialTheme.typography.titleLarge)
                                val pending = due?.pending.orEmpty()
                                Text(if (due == null) "Maintenance status unavailable" else "${pending.size} pending targets · ${pending.count { work -> work.effective.status == DueStatus.DUE }} reached\n${due!!.rules.count { rule -> rule.evaluation.status in listOf(DueStatus.UNKNOWN, DueStatus.MANUAL_REVIEW) }} checklist items need evidence or manual review", modifier = Modifier.testTag("dashboard-pending"))
                                Text("Open detailed management when parked. Unknown mileage or partial manual coverage never means all maintenance is current.")
                                OutlinedButton(onClick = { route = "maintenance" }, modifier = Modifier.fillMaxWidth()) { Text("Maintenance and pending work") }
                                OutlinedButton(onClick = { route = "rides" }, modifier = Modifier.fillMaxWidth()) { Text("Ride history") }
                                OutlinedButton(onClick = { route = "fuel" }, modifier = Modifier.fillMaxWidth()) { Text("Fuel history and owner reading") }
                                OutlinedButton(onClick = { route = "registration" }, modifier = Modifier.fillMaxWidth()) { Text("Motorcycle and checklist") }
                                OutlinedButton(onClick = { route = "settings" }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DashboardMetric(val label: String, val value: String, val tag: String)

@Composable
private fun DashboardMetrics(metrics: List<DashboardMetric>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 340.dp && fontScale < 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.chunked(2).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { Metric(it, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { Metric(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun Metric(metric: DashboardMetric, modifier: Modifier) {
    Card(modifier.heightIn(min = 88.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge)
            Text(metric.value, style = if (metric.value.startsWith("Unavailable")) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(metric.tag))
        }
    }
}
