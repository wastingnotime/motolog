package org.wastingnotime.motolog.feature.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun MaintenanceField(label: String, value: String, busy: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, enabled = !busy, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
@Composable
internal fun MaintenanceChoice(label: String, checked: Boolean, busy: Boolean = false, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = !busy, role = Role.Checkbox, onValueChange = change)) {
        Checkbox(checked, null, enabled = !busy)
        Text(label, Modifier.padding(top = 12.dp))
    }
}
internal fun target(km: String, date: String): WorkTarget {
    require(km.isBlank() || km.matches(Regex("[0-9]{1,12}"))) { "Enter whole kilometers (up to 12 digits)." }
    return WorkTarget(km.takeIf { it.isNotBlank() }?.toLong(), date.takeIf { it.isNotBlank() }).also { it.validate() }
}

@Composable
internal fun ProgrammedForm(busy: Boolean, existing: MaintenanceOccurrence?,
    save: (String, String, WorkTarget?, Boolean) -> Unit) {
    var action by rememberSaveable { mutableStateOf("") }
    var component by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var unscheduled by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(if (existing == null) "New programmed job" else "Initial scheduling", style = MaterialTheme.typography.headlineSmall)
    if (existing == null) {
        MaintenanceField("Action", action, busy) { action = it }
        MaintenanceField("Component / job", component, busy) { component = it }
        MaintenanceChoice("Leave unscheduled intentionally", unscheduled, busy) { unscheduled = it }
    } else Text("${existing.rule.action} ${existing.rule.component}\nThis adds the first target; no performed work is recorded.")
    if (!unscheduled) {
        MaintenanceField("Absolute km target (optional)", km, busy) { km = it }
        MaintenanceField("Due date YYYY-MM-DD (optional)", date, busy) { date = it }
        Text("Enter a date, kilometers, or both. Either target makes the job due.")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, onClick = {
        try {
            require(existing != null || (action.isNotBlank() && component.isNotBlank())) { "Enter the action and component." }
            save(action, component, if (unscheduled) null else target(km, date), unscheduled)
        } catch (e: IllegalArgumentException) { error = e.message }
    }) { Text(if (existing == null) "Create programmed job" else "Save initial schedule") }
}

@Composable
internal fun OutcomeForm(rows: List<MaintenanceOccurrence>, model: MaintenanceView, busy: Boolean,
    save: (List<MaintenanceOccurrence>, List<MaintenanceOccurrence>, PerformedDetails?, WorkTarget?, String, Boolean, List<String>, Boolean) -> Unit) {
    var performed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var km by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf("") }
    var evidence by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var replacements by rememberSaveable { mutableStateOf("") }
    var targetKm by rememberSaveable { mutableStateOf("") }
    var targetDate by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var dropped by rememberSaveable { mutableStateOf(false) }
    var anchors by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var reconciled by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Record checklist outcome", style = MaterialTheme.typography.headlineSmall)
    Text("Mark only work actually performed. Unmarked items remain open. All changes save together.")
    rows.forEach { row ->
        MaintenanceChoice("Performed: ${row.rule.action} ${row.rule.component} (${row.originalKm?.let { "$it km" } ?: row.originalDate ?: "unscheduled"})",
            row.id in performed, busy) { checked -> performed = ArrayList(if (checked) performed + row.id else performed - row.id) }
    }
    val done = rows.filter { it.id in performed }
    val remaining = rows.filter { it.id !in performed }
    if (done.isNotEmpty()) {
        Text("Performed work — ${done.size} selected targets")
        Text("Multiple selected targets for the same action and source snapshot record one physical action. Notes, evidence and optional cost below apply to each performed action.")
        MaintenanceField("Performed date YYYY-MM-DD", date, busy) { date = it }
        MaintenanceField("Performed owner odometer km (optional)", km, busy) { km = it }
        MaintenanceField("Performed work / notes", work, busy) { work = it }
        MaintenanceField("Confirmed action evidence", evidence, busy) { evidence = it }
        MaintenanceField("Cost BRL per physical action (optional)", cost, busy) { cost = it }
        MaintenanceField("Location (optional)", location, busy) { location = it }
        MaintenanceField("Replacements (optional)", replacements, busy) { replacements = it }
        model.rules.filter { rule -> done.any { row -> row.rule == rule.item.effective &&
            model.pending.any { it.occurrence.id == row.id && it.groupId == rule.item.baseline.id } } }.forEach { rule ->
            val id = rule.item.baseline.id
            MaintenanceChoice("Use ${rule.item.effective.component} completion as next interval/calendar anchor", id in anchors, busy) {
                anchors = ArrayList(if (it) anchors + id else anchors - id)
            }
        }
        if (anchors.isNotEmpty()) MaintenanceChoice("I reconciled physical service order if dates are equal or missing", reconciled, busy) { reconciled = it }
    }
    if (remaining.isNotEmpty()) {
        Text(if (done.isEmpty()) "Full postponement — no performed history" else "Partial completion — ${remaining.size} linked outstanding targets")
        MaintenanceField("New absolute km target (optional)", targetKm, busy) { targetKm = it }
        MaintenanceField("New due date YYYY-MM-DD (optional)", targetDate, busy) { targetDate = it }
        TextButton(enabled = !busy, onClick = { targetDate = LocalDate.now().plusDays(1).toString() }) { Text("Use tomorrow (editable)") }
        model.currentKm?.takeIf { it <= 999_999_999_899L }?.let { current ->
            TextButton(enabled = !busy, onClick = { targetKm = (current + 100).toString() }) { Text("Use owner reading + 100 km (editable)") }
        }
        Text("Convenience values are not maintenance recommendations. New targets must advance previous targets and known current evidence.")
        MaintenanceField("Postponement reason", reason, busy) { reason = it }
        MaintenanceChoice("I confirm any dropped date/km threshold; only the remaining dimension will trigger this work", dropped, busy) { dropped = it }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, modifier = Modifier.testTag("save-outcome"), onClick = {
        try {
            require(done.isEmpty() || (work.isNotBlank() && evidence.isNotBlank())) { "Describe the performed work and confirmed action evidence." }
            require(km.isBlank() || km.matches(Regex("[0-9]{1,12}"))) { "Enter whole owner kilometers." }
            val details = if (done.isEmpty()) null else PerformedDetails(date, ZoneId.systemDefault().id,
                km.takeIf { it.isNotBlank() }?.toLong(), work, evidence, costBrl = cost.takeIf { it.isNotBlank() }?.let {
                    org.wastingnotime.motolog.domain.model.DecimalValues.normalize(it, 2, false) },
                location = location.takeIf { it.isNotBlank() }, replacements = replacements.takeIf { it.isNotBlank() })
            save(rows, done, details, if (remaining.isEmpty()) null else target(targetKm, targetDate), reason, dropped, anchors, reconciled)
        } catch (e: IllegalArgumentException) { error = e.message }
    }) { Text(if (remaining.isEmpty()) "Save full completion" else if (done.isEmpty()) "Save full postponement" else "Save partial completion and remainder") }
}

@Composable
internal fun CorrectiveForm(busy: Boolean, saveError: String?, save: (PerformedDetails) -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var zone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var issue by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var replacements by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Record corrective service", style = MaterialTheme.typography.headlineSmall)
    Text("Record work actually performed. Pending checklist work stays open. Blank optional fields remain unknown; enter 0 for a known zero cost.")
    MaintenanceField("Service date YYYY-MM-DD", date, busy) { date = it }
    MaintenanceField("Service time zone", zone, busy) { zone = it }
    MaintenanceField("Issue / description", issue, busy) { issue = it }
    MaintenanceField("Work performed", work, busy) { work = it }
    MaintenanceField("Motorcycle owner odometer km (optional)", km, busy) { km = it }
    MaintenanceField("Service cost BRL (optional)", cost, busy) { cost = it }
    MaintenanceField("Service location (optional)", location, busy) { location = it }
    MaintenanceField("Service replacements (optional)", replacements, busy) { replacements = it }
    (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("maintenance-error")) }
    Button(enabled = !busy, onClick = {
        try {
            error = null
            require(km.isBlank() || km.matches(Regex("[0-9]{1,12}"))) { "Enter nonnegative whole owner kilometers (up to 12 digits), or leave unknown." }
            save(PerformedDetails(date, zone, km.takeIf { it.isNotBlank() }?.toLong(), work, "",
                issue, cost.takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() }, replacements.takeIf { it.isNotBlank() }))
        } catch (e: IllegalArgumentException) { error = e.message }
    }) { Text("Save corrective service") }
}
