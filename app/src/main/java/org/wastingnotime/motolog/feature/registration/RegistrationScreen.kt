package org.wastingnotime.motolog.feature.registration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.maintenance.FazerInventory
import org.wastingnotime.motolog.feature.maintenance.MaintenanceScreen
import org.wastingnotime.motolog.domain.model.*
import java.time.OffsetDateTime
import java.util.UUID

@Composable
fun RegistrationScreen(repository: RegistrationRepository, maintenance: MaintenanceRepository? = null) {
    var snapshot by remember { mutableStateOf<WriteBatch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        try { snapshot = withContext(Dispatchers.IO) { repository.snapshot() } }
        catch (_: Exception) { error = "Records unavailable. Restart to try again; saved records are retained." }
    }
    fun runCommand(command: () -> Unit) {
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { command() }
                screen = "home"; selected = null; reload++
            } catch (exception: Exception) {
                error = if (exception is IllegalArgumentException) exception.message ?: "Check entered values."
                    else "Could not save. Reload and check for an existing entry before retrying."
            } finally { busy = false }
        }
    }
    if (screen == "maintenance" && maintenance != null && snapshot?.bikes?.isNotEmpty() == true) {
        val saved = snapshot!!
        val reading = saved.readings.filter { it.certainty == Certainty.CONFIRMED }.maxByOrNull { it.observed!!.epochMillis }
        MaintenanceScreen(maintenance, saved.bikes.single().id, reading?.id) {
            screen = "home"; reload++
        }
        return
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(8.dp))
                Text("MotoLog", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("One bike. Your records, offline.", style = MaterialTheme.typography.bodyLarge)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
                val saved = snapshot
                if (saved == null) Text("Opening local records…")
                else if (saved.bikes.isEmpty()) {
                    RegisterForm(busy) { id, year, matched, reading -> runCommand {
                        repository.register(id, year, matched, reading, System.currentTimeMillis())
                    } }
                } else {
                    val bike = saved.bikes.single()
                    if (screen != "home") TextButton(enabled = !busy, onClick = { screen = "home"; error = null }) { Text("Back to checklist") }
                    when (screen) {
                        "edit" -> {
                            val item = repository.items(saved).find { it.baseline.id == selected }
                            RuleForm(item, busy) { id, values -> runCommand {
                                if (item == null) repository.addItem(id, bike.id, values, System.currentTimeMillis())
                                else repository.editItem(item.baseline.id, item.revision, values, System.currentTimeMillis())
                            } }
                        }
                        "entry" -> EntryForm(bike.id, repository.items(saved), repository, busy) { preview, duplicates ->
                            runCommand { repository.confirm(preview, duplicates, System.currentTimeMillis()) }
                        }
                        else -> {
                            Text("Fazer YS250 · BlueFlex · BR · model 2017", style = MaterialTheme.typography.titleLarge)
                            Text("Manufacture year: ${bike.manufactureYear ?: "unknown"}")
                            if (!bike.manualConfirmed) {
                                Text("Manual applicability unconfirmed. No manual schedules seeded.")
                                MatchForm(bike.manufactureYear, busy) { year -> runCommand {
                                    repository.confirmManual(bike.id, year, System.currentTimeMillis())
                                } }
                            } else Text("Manual match confirmed on this device", color = MaterialTheme.colorScheme.primary)
                            val reading = saved.readings.filter { it.certainty == Certainty.CONFIRMED }
                                .maxByOrNull { it.observed!!.epochMillis }
                            Text(if (reading == null) "Owner odometer: unknown. Add a reading when available."
                                else "Last confirmed owner reading: ${reading.kilometers} km\nObserved: ${java.time.Instant.ofEpochMilli(reading.observed!!.epochMillis)}\nHistorical readings are not automatically current.")
                            Text("Prior service: ${saved.histories.size} reviewed records. Missing history and anchors remain unknown.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy, onClick = { screen = "entry" }) { Text("Local setup entry") }
                                OutlinedButton(enabled = !busy, onClick = { selected = null; screen = "edit" }) { Text("Add item") }
                            }
                            TextButton(enabled = !busy, onClick = { error = null; reload++ }) { Text("Reload local records") }
                            Text("Checklist", style = MaterialTheme.typography.headlineSmall)
                            Text(FazerInventory.gaps, modifier = Modifier.testTag("coverage-gaps"))
                            if (maintenance != null) OutlinedButton(onClick = { screen = "maintenance" }) { Text("Maintenance due and pending") }
                            Text("Review due targets separately; missing anchors and partial coverage remain visible.")
                            repository.items(saved).forEach { item ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${item.label} · ${item.effective.action} ${item.effective.component}", style = MaterialTheme.typography.titleMedium)
                                        Text(scheduleText(item.effective))
                                        item.effective.coverageNote?.let { Text(it) }
                                        Text("Source: ${if (item.override != null) "Owner override; original source retained\n" else ""}${item.source}", style = MaterialTheme.typography.bodySmall)
                                        if (item.override != null) Text("Original baseline: ${item.baseline.rule.action} ${item.baseline.rule.component}\n${scheduleText(item.baseline.rule)}\n${item.baseline.rule.coverageNote.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                        TextButton(enabled = !busy, onClick = { selected = item.baseline.id; screen = "edit" }) { Text("Edit ${item.baseline.rule.component}") }
                                    }
                                }
                            }
                            val programmed = saved.occurrences.filter { it.kind == MaintenanceKind.PROGRAMMED &&
                                it.state != OccurrenceState.COMPLETED && saved.occurrences.none { child -> child.parentId == it.id } }
                            if (programmed.isNotEmpty()) Text("Open programmed work", style = MaterialTheme.typography.titleLarge)
                            programmed.forEach {
                                Text("${it.rule.action}: ${it.rule.component}\nOpen · ${it.effectiveKm?.let { km -> "$km km" } ?: "km unknown"} / ${it.effectiveDate ?: "date unknown"} · source: Owner")
                            }
                            if (saved.importRows.isNotEmpty()) Text("Local setup notes", style = MaterialTheme.typography.titleLarge)
                            saved.importRows.forEach { Text("${it.certainty}: ${it.sourceNote}") }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
@Composable
private fun Choice(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}
private const val matchText = "I confirm YS250 / BlueFlex / BR / model year 2017 and the Yamaha U44CWDW0 second edition May 2017 manual match my bike. Manufacture year is entered separately."

@Composable
private fun RegisterForm(busy: Boolean, save: (String, Int?, Boolean, OdometerReading?) -> Unit) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var year by rememberSaveable { mutableStateOf("") }
    var match by rememberSaveable { mutableStateOf(false) }
    var km by rememberSaveable { mutableStateOf("") }
    var observed by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf<String?>(null) }
    Text("Register your Fazer", style = MaterialTheme.typography.headlineSmall)
    Text("Supported preset: YS250 / BlueFlex / BR / model 2017. Leave the manual match unconfirmed if any detail is unknown.")
    Field("Manufacture year (optional)", year, !busy) { year = it }
    Choice(matchText, match, !busy) { match = it }
    Field("Owner odometer km (optional)", km, !busy) { km = it }
    Field("Observed at (ISO timestamp with offset)", observed, !busy) { observed = it }
    TextButton(enabled = !busy, onClick = { observed = OffsetDateTime.now().toString() }) { Text("I observed this reading now") }
    Text("Skip both reading fields if unknown. A past timestamp stays historical. No prior service is assumed.")
    invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, onClick = {
        try {
            val y = optionalYear(year)
            require(!match || y != null) { "Enter manufacture year before confirming the manual" }
            require(km.isNotBlank() || observed.isBlank()) { "Enter kilometers or clear the timestamp" }
            val reading = if (km.isBlank()) null else OdometerReading("$id:initial", id, kilometers(km),
                Certainty.CONFIRMED, ReadingSource.OWNER, observation(observed), System.currentTimeMillis())
            save(id, y, match, reading)
        } catch (e: Exception) { invalid = e.message ?: "Check reading and timestamp" }
    }, modifier = Modifier.fillMaxWidth()) { Text("Register offline") }
}

@Composable
private fun MatchForm(initialYear: Int?, busy: Boolean, save: (Int) -> Unit) {
    var year by rememberSaveable { mutableStateOf(initialYear?.toString().orEmpty()) }
    var match by rememberSaveable { mutableStateOf(false) }
    var invalid by remember { mutableStateOf<String?>(null) }
    Field("Manufacture year", year, !busy) { year = it }
    Choice(matchText, match, !busy) { match = it }
    invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(enabled = match && !busy, onClick = {
        try { save(requireNotNull(optionalYear(year)) { "Enter manufacture year" }) }
        catch (e: Exception) { invalid = e.message }
    }) { Text("Confirm match and admit verified facts") }
}

@Composable
private fun RuleForm(item: ChecklistItem?, busy: Boolean, save: (String, RuleValues) -> Unit) {
    val original = item?.effective
    val id = rememberSaveable(item?.baseline?.id) { UUID.randomUUID().toString() }
    var action by rememberSaveable(id) { mutableStateOf(original?.action.orEmpty()) }
    var component by rememberSaveable(id) { mutableStateOf(original?.component.orEmpty()) }
    var km by rememberSaveable(id) { mutableStateOf(original?.intervalKm?.toString().orEmpty()) }
    var months by rememberSaveable(id) { mutableStateOf(original?.intervalMonths?.toString().orEmpty()) }
    var note by rememberSaveable(id) { mutableStateOf(original?.coverageNote.orEmpty()) }
    var invalid by remember { mutableStateOf<String?>(null) }
    Text(if (item == null) "Add Custom item" else "Edit checklist item", style = MaterialTheme.typography.headlineSmall)
    Text("Owner changes display Custom. The original baseline and source remain available. Enter a simple interval, or leave both thresholds unknown.")
    Field("Action", action, !busy) { action = it }
    Field("Component / scope", component, !busy) { component = it }
    Field("Interval km (optional)", km, !busy) { km = it }
    Field("Interval months (optional)", months, !busy) { months = it }
    Field("Coverage / owner note", note, !busy) { note = it }
    invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, onClick = {
        try {
            require(action.isNotBlank() && component.isNotBlank()) { "Enter action and component" }
            val distance = km.takeIf { it.isNotBlank() }?.let(::kilometers)
            val time = months.takeIf { it.isNotBlank() }?.toInt()
            require(distance == null || distance > 0) { "Interval must be positive" }
            require(time == null || time > 0) { "Interval must be positive" }
            val unchangedSchedule = item != null && distance == original?.intervalKm && time == original?.intervalMonths
            save(id, RuleValues(action, component, SourceKind.CUSTOM,
                scheduleKind = if (unchangedSchedule) original!!.scheduleKind else if (distance != null || time != null) ScheduleKind.INTERVAL else ScheduleKind.UNKNOWN,
                intervalKm = distance, intervalMonths = time, coverageNote = note.takeIf { it.isNotBlank() }))
        } catch (e: Exception) { invalid = e.message ?: "Check interval values" }
    }) { Text("Save Custom item") }
}

@Composable
private fun EntryForm(bikeId: String, items: List<ChecklistItem>, repository: RegistrationRepository,
    busy: Boolean, save: (EntryPreview, Boolean) -> Unit) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var kind by rememberSaveable { mutableStateOf(EntryKind.NOTE.name) }
    var certainty by rememberSaveable { mutableStateOf(Certainty.UNKNOWN.name) }
    var note by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("") }
    var component by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var observed by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var baseline by rememberSaveable { mutableStateOf<String?>(null) }
    var performed by rememberSaveable { mutableStateOf(false) }
    var duplicateReviewed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<EntryPreview?>(null) }
    var invalid by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("Private local setup", style = MaterialTheme.typography.headlineSmall)
    Text("Enter one source row at a time. Preview before saving. No planning history is bundled. Notes, readings, performed work and open jobs have different effects.")
    val shown = preview
    if (shown != null) {
        Text("Review entry", style = MaterialTheme.typography.titleLarge)
        val history = shown.batch.histories.firstOrNull()
        val reading = shown.batch.readings.firstOrNull()
        Text("Original note: ${shown.batch.importRows.single().sourceNote}\nType: $kind · certainty: $certainty\nDate: ${history?.performedDate ?: "unknown"}\nOdometer: ${history?.odometerKm ?: reading?.kilometers ?: "unknown"}\nObserved: ${reading?.observed?.let { java.time.Instant.ofEpochMilli(it.epochMillis) } ?: "unknown"}\nCost: ${history?.costBrl ?: "unknown"}")
        val rule = shown.batch.histories.firstOrNull()?.rule ?: shown.batch.occurrences.firstOrNull()?.rule
        Text("Action mapping: ${rule?.let { "${it.action}: ${it.component}; ${it.sourceKind}; ${it.locator.orEmpty()}" } ?: "None"}")
        Text("Will save ${shown.batch.histories.size} performed record(s), ${shown.batch.occurrences.size} open job(s), ${shown.batch.readings.size} reading(s). Unknown prior work creates no history. Uncertain work supplies no qualifying anchor.")
        Text("Duplicate candidates: ${shown.duplicateCandidates.size}")
        shown.duplicateCandidates.forEach { Text(it.substringAfter(": ")) }
        Choice("I reviewed duplicate candidates and intend a separate entry", duplicateReviewed, !busy) { duplicateReviewed = it }
        Button(enabled = !busy && (shown.duplicateCandidates.isEmpty() || duplicateReviewed), onClick = { save(shown, duplicateReviewed) }) { Text("Confirm local entry") }
        TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel preview") }
    } else {
        Text("Entry type")
        EntryKind.entries.forEach { value ->
            Choice(when (value) { EntryKind.NOTE -> "Unmapped / uncertain source note"; EntryKind.READING -> "Owner odometer reading"; EntryKind.PERFORMED -> "Actually performed service"; EntryKind.PROGRAMMED -> "Open programmed work" }, kind == value.name, !preparing) { kind = value.name }
        }
        Field("Original source note", note, !preparing) { note = it }
        Text("Certainty")
        Certainty.entries.forEach { value -> Choice(value.name, certainty == value.name, !preparing) { certainty = value.name } }
        if (kind == EntryKind.PERFORMED.name || kind == EntryKind.PROGRAMMED.name) {
            Field("Performed / planned action", action, !preparing) { action = it }
            Field("Component / actual scope", component, !preparing) { component = it }
        }
        if (kind == EntryKind.PERFORMED.name) {
            Field("Service date yyyy-MM-dd (optional)", date, !preparing) { date = it }
            Field("Cost BRL (optional; unknown is not zero)", cost, !preparing) { cost = it }
            Text("Optional checklist mapping. Select only evidence of this exact action, including component scope. Purchases, promises, diagnosis and generic brake work do not establish replacements.")
            TextButton(onClick = { baseline = null }) { Text(if (baseline == null) "✓ Owner source / no qualifying checklist mapping" else "Use owner source / no checklist mapping") }
            items.forEach { item -> TextButton(onClick = { baseline = item.baseline.id }) {
                Text("${if (baseline == item.baseline.id) "✓ " else ""}${item.effective.action}: ${item.effective.component}")
            } }
            Choice("I confirm this action was actually performed with the stated scope; it was not merely planned, purchased or diagnosed", performed, !preparing) { performed = it }
        }
        if (kind == EntryKind.PERFORMED.name || kind == EntryKind.READING.name) Field("Recorded odometer km (optional)", km, !preparing) { km = it }
        if (kind == EntryKind.READING.name) {
            Field("Observed at (ISO timestamp with offset; optional if unknown)", observed, !preparing) { observed = it }
            TextButton(onClick = { observed = OffsetDateTime.now().toString() }) { Text("I observed this reading now") }
        }
        if (kind == EntryKind.PROGRAMMED.name) Text("Imported as open work with unknown date, odometer target and cost. It creates no completed history.")
        invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !preparing, onClick = {
            preparing = true; invalid = null
            scope.launch {
                try {
                    val entryKind = EntryKind.valueOf(kind)
                    require(entryKind != EntryKind.PERFORMED || performed) { "Confirm actual performed work, or use an unmapped note" }
                    val draft = SetupEntry(id, bikeId, entryKind, note, Certainty.valueOf(certainty),
                        date = if (entryKind == EntryKind.PERFORMED) date.takeIf { it.isNotBlank() } else null,
                        km = if (entryKind == EntryKind.PERFORMED || entryKind == EntryKind.READING) km.takeIf { it.isNotBlank() }?.let(::kilometers) else null,
                        observed = if (entryKind == EntryKind.READING && observed.isNotBlank()) observation(observed) else null,
                        cost = if (entryKind == EntryKind.PERFORMED) cost.takeIf { it.isNotBlank() } else null,
                        action = action.ifBlank { "Review" }, component = component.ifBlank { "Owner note" },
                        baselineId = if (entryKind == EntryKind.PERFORMED) baseline else null)
                    preview = withContext(Dispatchers.IO) { repository.preview(draft, System.currentTimeMillis()) }
                } catch (e: Exception) { invalid = e.message ?: "Check entered values" }
                finally { preparing = false }
            }
        }) { Text("Preview local entry") }
    }
}

private fun optionalYear(value: String): Int? = if (value.isBlank()) null else {
    require(value.matches(Regex("[0-9]{4}"))) { "Enter a four-digit manufacture year" }; value.toInt()
}
private fun kilometers(value: String): Long {
    require(value.matches(Regex("[0-9]{1,12}"))) { "Use whole nonnegative kilometers" }; return value.toLong()
}
private fun observation(value: String): Observation {
    val time = try { OffsetDateTime.parse(value) } catch (_: Exception) {
        throw IllegalArgumentException("Enter an ISO timestamp with offset, such as 2025-01-02T10:30:00-03:00")
    }
    return Observation(time.toInstant().toEpochMilli(), time.offset.id, time.offset.totalSeconds)
}
private fun scheduleText(rule: RuleValues) = when (rule.scheduleKind) {
    ScheduleKind.INTERVAL -> "Interval: ${rule.intervalKm?.let { "$it km" } ?: "distance unknown / not specified"}; ${rule.intervalMonths?.let { "$it months" } ?: "time unknown / not specified"}"
    ScheduleKind.MILESTONE -> "Fixed mileage reference · evaluation pending"
    ScheduleKind.CONDITION -> "Manual review · ${rule.intervalKm?.let { "$it km; " }.orEmpty()}${rule.intervalMonths?.let { "$it months; " }.orEmpty()}condition/event triggers also apply"
    ScheduleKind.UNKNOWN -> "Schedule unknown / unseeded"
}
