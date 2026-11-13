package org.wastingnotime.motolog.feature.fuel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*
import java.time.Instant
import java.util.UUID
import org.wastingnotime.motolog.feature.common.ObservationInput
import org.wastingnotime.motolog.feature.common.observationText
import org.wastingnotime.motolog.domain.ride.observationAt

@Composable
fun FuelScreen(repository: FuelRepository, bikeId: String, initialScreen: String = "home", quick: Boolean = false, back: () -> Unit) {
    var saved by remember { mutableStateOf<WriteBatch?>(null) }
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(reload) {
        try { saved = withContext(Dispatchers.IO) { repository.snapshot() } }
        catch (_: Exception) { error = "Local records unavailable. Return and reload to try again." }
    }
    fun save(command: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { command() }
                saved = withContext(Dispatchers.IO) { repository.snapshot() }
                now = System.currentTimeMillis()
                if (quick) back() else screen = "home"
            } catch (e: Exception) {
                error = if (e is IllegalArgumentException) e.message ?: "Review entered values."
                    else "Could not confirm save. Retry the same entry; its operation ID prevents duplicate records."
            } finally { busy = false }
        }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(8.dp))
                Text("Fuel and owner odometer", style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !busy, onClick = {
                    if (quick || screen == "home") back() else { screen = "home"; error = null }
                }) { Text(if (quick || screen == "home") "Back to dashboard" else "Back to fuel") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("fuel-error")) }
                if (saved == null) Text("Opening local records…")
                else when (screen) {
                    "refuel" -> EntryForm(true, busy) { id, mode, total, quantity, km, observed ->
                        save { repository.refuel(RefuelCommand(id, bikeId, mode, total, quantity, km, observed), System.currentTimeMillis()) }
                    }
                    "reading" -> EntryForm(false, busy) { id, _, _, _, km, observed ->
                        save { repository.updateOdometer(ReadingCommand(id, bikeId, km, observed), System.currentTimeMillis()) }
                    }
                    "history" -> {
                        Text("Refuel history · read only", style = MaterialTheme.typography.titleLarge)
                        val entries = saved!!.fuels.filter { it.bikeId == bikeId }
                            .sortedWith(compareByDescending<org.wastingnotime.motolog.data.local.FuelEvent> { it.observed.epochMillis }.thenByDescending { it.id })
                        if (entries.isEmpty()) Text("No refuel recorded.")
                        entries.forEach { entry ->
                            val reading = saved!!.readings.find { it.id == entry.readingId && it.bikeId == bikeId }
                            val values = runCatching { FuelValues.parse(entry.mode, entry.totalBrl, entry.enteredQuantity) }.getOrNull()
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Refueled: ${observationText(entry.observed)}")
                                    Text("Recorded odometer: ${reading?.kilometers?.let { "$it km" } ?: "unavailable"}")
                                    Text("Reading: ${reading?.certainty ?: "unknown"} · ${reading?.source ?: "unknown"}")
                                    Text("Total paid: BRL ${values?.totalDisplay ?: entry.totalBrl}")
                                    Text("Liters: ${values?.litersDisplay ?: "unavailable"} L")
                                    Text("Price per liter: BRL ${values?.unitPriceDisplay ?: "unavailable"}/L")
                                    Text("Entered mode: ${if (entry.mode == FuelMode.LITERS) "Liters + total paid" else "Total paid + price per liter"}")
                                    Text("Exact entered total paid: BRL ${entry.totalBrl}\nExact entered ${if (entry.mode == FuelMode.LITERS) "liters" else "price per liter BRL"}: ${entry.enteredQuantity}")
                                    Text("Derived ${if (entry.mode == FuelMode.LITERS) "price per liter" else "liters"}: ${values?.let { if (entry.mode == FuelMode.LITERS) it.unitPrice else it.liters } ?: "unavailable"}\nDivision: scale 12, HALF_UP; display: 3 decimal places.")
                                    Text("Recorded at: ${Instant.ofEpochMilli(entry.recordedAt)}")
                                }
                            }
                        }
                    }
                    else -> {
                        val status = FuelProjection.evaluate(bikeId, saved!!.fuels, saved!!.readings, now)
                        Text(status.kilometersSince?.let { "Kilometers since refuel: $it km" }
                            ?: "Kilometers since refuel: unavailable. ${status.unavailable!!.message}",
                            modifier = Modifier.testTag("fuel-distance"))
                        Text("Last refuel odometer: ${status.refuelReading?.kilometers?.let { "$it km" } ?: "unavailable"}")
                        status.latestRefuel?.let { Text("Last refuel: ${observationText(it.observed)}") }
                        status.current?.let { reading ->
                            Text("Latest confirmed owner reading: ${reading.kilometers} km\nObserved: ${observationText(reading.observed!!)}\nAge: ${status.currentAgeMillis!! / 60_000} minutes · ${if (status.currentAgeMillis!! in 0..FuelProjection.FRESH_MILLIS) "fresh" else "not current"}")
                        } ?: Text("Current owner reading: unavailable.")
                        Text("Use while safely stopped. GPS never supplies the motorcycle odometer.")
                        Button(enabled = !busy, onClick = { screen = "refuel"; error = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("New refuel") }
                        OutlinedButton(enabled = !busy, onClick = { screen = "reading"; error = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Update odometer") }
                        TextButton(onClick = { screen = "history" }) { Text("Refuel history") }
                        TextButton(onClick = { reload++ }) { Text("Reload fuel records") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, busy: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, enabled = !busy, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (label.contains("odometer")) KeyboardType.Number else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun EntryForm(refuel: Boolean, busy: Boolean,
    save: (String, FuelMode, String, String, String, Observation) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var modeName by rememberSaveable { mutableStateOf(FuelMode.LITERS.name) }
    val mode = FuelMode.valueOf(modeName)
    var total by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var selectedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewed by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val inputs = listOf(modeName, total, quantity, km, selectedTime.toString())
    val isReviewed = reviewed == inputs
    Text(if (refuel) "New refuel" else "Update odometer", style = MaterialTheme.typography.titleLarge)
    if (refuel) {
        Text("Entry mode: ${if (mode == FuelMode.LITERS) "Liters + total paid" else "Total paid + price per liter"}")
        OutlinedButton(enabled = !busy, onClick = {
            modeName = if (mode == FuelMode.LITERS) FuelMode.UNIT_PRICE.name else FuelMode.LITERS.name
            quantity = ""; reviewed = null; error = null
        }) { Text(if (mode == FuelMode.LITERS) "Enter price per liter instead" else "Enter liters instead") }
        Field("Total paid BRL", total, busy) { total = it }
        Field(if (mode == FuelMode.LITERS) "Liters" else "Price per liter BRL/L", quantity, busy) { quantity = it }
    }
    Field("Recorded owner odometer km", km, busy) { km = it }
    Text("Read the odometer from your motorcycle display.")
    ObservationInput(selectedTime, !busy) { selectedTime = it; reviewed = null }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("fuel-input-error")) }
    Button(enabled = !busy, onClick = {
        try {
            OwnerObservation.kilometers(km)
            val observed = selectedTime ?: System.currentTimeMillis()
            OwnerObservation.validate(observationAt(observed), System.currentTimeMillis())
            reviewedTime = observed
            if (refuel) FuelValues.parse(mode, total, quantity)
            focus.clearFocus(); keyboard?.hide()
            reviewed = inputs; error = null
        } catch (e: IllegalArgumentException) { reviewed = null; error = e.message }
    }) { Text(if (refuel) "Review refuel" else "Review owner reading") }
    if (isReviewed) {
        if (refuel) {
            val values = FuelValues.parse(mode, total, quantity)
            Text("Total paid: BRL ${values.totalDisplay}\nLiters: ${values.litersDisplay} L\nPrice per liter: BRL ${values.unitPriceDisplay}/L", modifier = Modifier.testTag("fuel-review"))
            Text("Check the motorcycle reading and amounts before saving.")
        }
        Text("Owner odometer: $km km\nObserved: ${observationText(observationAt(requireNotNull(reviewedTime)))}")
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
            save(id, mode, total, quantity, km, observationAt(requireNotNull(reviewedTime)))
        }) { Text(if (refuel) "Save refuel" else "Save owner reading") }
    }
}
