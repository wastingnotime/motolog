package org.wastingnotime.motolog.feature.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.model.MaintenanceKind
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.maintenance.*
import java.time.LocalDate
import java.util.UUID

/** Offline checklist selection, atomic outcomes and ordinary programmed work. */
@Composable
fun MaintenanceScreen(repository: MaintenanceRepository, bikeId: String, readingId: String?, back: () -> Unit) {
    var view by remember { mutableStateOf<MaintenanceView?>(null) }
    var histories by remember { mutableStateOf<List<MaintenanceHistory>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf("pending") }
    var chosen by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var expected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var expectedAnchors by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var operationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun command(action: () -> Unit) {
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                screen = "pending"; chosen = arrayListOf(); reload++
                notice = "Saved on this device."
            } catch (e: Exception) {
                error = if (e is IllegalArgumentException) e.message else "Could not save. Saved records are retained. Retry this same form."
            } finally { busy = false }
        }
    }
    LaunchedEffect(bikeId, readingId, reload) {
        try {
            val applied = withContext(Dispatchers.IO) { repository.operationApplied(operationId) }
            if (applied) { screen = "pending"; chosen = arrayListOf(); notice = "Saved action recovered on this device." }
            view = withContext(Dispatchers.IO) {
                repository.refresh(bikeId, readingId, LocalDate.now(), System.currentTimeMillis())
            }
        } catch (review: MilestoneReviewRequired) {
            error = review.message
            view = withContext(Dispatchers.IO) { repository.existing(bikeId, readingId, LocalDate.now()) }
        } catch (_: Exception) { error = "Maintenance unavailable. Saved records are retained; return and try again." }
        try { histories = withContext(Dispatchers.IO) { repository.completedHistory(bikeId) } }
        catch (_: Exception) { error = "History unavailable. Saved records are retained; return and try again." }
    }
    fun open(next: String) {
        expected = ArrayList(view?.pending.orEmpty().filter { it.occurrence.id in chosen }
            .map { "${it.occurrence.revision}:${it.occurrence.id}" })
        expectedAnchors = ArrayList(view?.rules.orEmpty().map { "${it.anchorRevision}:${it.item.baseline.id}" })
        operationId = UUID.randomUUID().toString(); screen = next; error = null; notice = null
    }
    fun expectedRevision(id: String, values: List<String>): Int = values.single {
        it.substringAfter(':') == id }.substringBefore(':').toInt()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !busy, onClick = {
                    if (screen == "pending") back() else { screen = "pending"; error = null }
                }) { Text(if (screen == "pending") "Back to checklist" else "Back to pending work") }
                Text("Maintenance due and pending", style = MaterialTheme.typography.headlineSmall)
                Text("Evaluated against the displayed last confirmed owner reading. Historical readings do not prove current mileage.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("maintenance-error")) }
                notice?.let { Text(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val saved = view
                if (saved == null && error == null) Text("Evaluating local records…")
                saved?.let { model ->
                    when (screen) {
                        "create", "schedule" -> {
                            val existing = model.pending.find { it.occurrence.id == chosen.singleOrNull() }?.occurrence
                            ProgrammedForm(busy, if (screen == "schedule") existing else null) { action, component, target, unscheduled ->
                                command {
                                    if (screen == "schedule") {
                                        val row = requireNotNull(existing)
                                        repository.scheduleProgrammedJob(operationId, ObligationSelection(row.id, expectedRevision(row.id, expected)), requireNotNull(target), System.currentTimeMillis())
                                    } else repository.createProgrammedJob(operationId, bikeId, action, component, target, unscheduled, System.currentTimeMillis())
                                }
                            }
                        }
                        "outcome" -> {
                            val rows = model.pending.filter { it.occurrence.id in chosen }.map { it.occurrence }
                            if (rows.size != chosen.size) Text("Selection changed. Return to pending work and review history before selecting again.")
                            else OutcomeForm(rows, model, busy) { all, done, details, target, reason, dropped, anchors, reconciled ->
                                val selected = all.map { ObligationSelection(it.id, expectedRevision(it.id, expected)) }
                                val groups = done.groupBy { row -> Pair(row.rule, model.pending.single { it.occurrence.id == row.id }.groupId) }
                                // Capture intent on the UI thread. All reads and the enclosing write run off it.
                                command {
                                    val actions = groups.map { (key, actionRows) ->
                                        PerformedAction(actionRows.map { it.id }, requireNotNull(details),
                                            key.second.takeIf { it in anchors }?.let { id -> AnchorChoice(id, expectedRevision(id, expectedAnchors), reconciled) })
                                    }
                                    repository.applyOutcome(operationId, ChecklistOutcome(selected, actions, target, reason, dropped),
                                        readingId, LocalDate.now(), System.currentTimeMillis())
                                }
                            }
                        }
                        "history" -> {
                            Text("Completed maintenance history", style = MaterialTheme.typography.titleLarge)
                            Text("Read-only · newest performed date first · unknown dates last")
                            if (histories.isEmpty()) Text("No performed work recorded.")
                            histories.forEach { row ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("${row.performedDate ?: "Date unknown"} · ${row.rule.action} ${row.rule.component}")
                                        Text("${row.performedWork}\nOwner odometer: ${row.odometerKm ?: "unknown"} km\nEvidence: ${row.anchorEvidence ?: "unknown"}")
                                        Text("Original: ${row.originalKm ?: "unknown"} km / ${row.originalDate ?: "unknown"}\nEffective: ${row.effectiveKm ?: "unknown"} km / ${row.effectiveDate ?: "unknown"}")
                                        row.costBrl?.let { Text("Cost BRL: $it") }
                                        row.location?.let { Text("Location: $it") }
                                        row.replacements?.let { Text("Replacements: $it") }
                                        Text("Source: ${row.rule.sourceKind} · ${row.rule.sourceIdentity ?: "Owner"} · ${row.rule.locator.orEmpty()} · baseline ${row.rule.baselineRevision ?: "unknown"} / override ${row.rule.overrideRevision ?: "none"}")
                                    }
                                }
                            }
                        }
                        else -> {
                            Button(enabled = !busy, onClick = { open("create") }) { Text("New programmed job") }
                            TextButton(onClick = { open("history") }) { Text("Completed maintenance history") }
                            Text("Select targets to form a checklist. Only explicitly selected targets can be completed or postponed.")
                            Button(enabled = chosen.isNotEmpty() && !busy, onClick = { open("outcome") }) { Text("Record selected checklist (${chosen.size})") }
                            Text(model.coverage, modifier = Modifier.testTag("due-coverage"))
                            Text("Earlier service history remains unknown unless explicitly recorded. A reached target does not prove a missed service.")
                            model.rules.forEach { rule ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${rule.item.label} · ${rule.item.effective.action} ${rule.item.effective.component}", style = MaterialTheme.typography.titleMedium)
                                        Text("Current rule: ${label(rule.evaluation)}")
                                        if (rule.anchorHistoryId == null) Text("Qualifying prior completion: unknown")
                                        if (rule.support == RuleSupport.OIL && rule.evaluation.calendar == BranchStatus.UNKNOWN)
                                            Text("Oil calendar status unknown; mileage is evaluated independently.")
                                        if (readingId == null && rule.support in listOf(RuleSupport.OIL, RuleSupport.OIL_FILTER))
                                            Text("1,000 km first target · informational, unadopted until the first confirmed owner reading.")
                                        rule.item.effective.coverageNote?.let { Text(it) }
                                        Text("Source: ${rule.item.source}", style = MaterialTheme.typography.bodySmall)
                                        if (rule.pending.isEmpty()) Text("No adopted pending targets. Missing anchors and review needs remain visible above.")
                                        rule.pending.forEach { work ->
                                            MaintenanceChoice("Select ${work.occurrence.rule.component} target ${work.occurrence.originalKm ?: work.occurrence.originalDate ?: "unscheduled"}", work.occurrence.id in chosen, busy) {
                                                chosen = ArrayList(if (it) chosen + work.occurrence.id else chosen - work.occurrence.id)
                                            }
                                            PendingTarget(work)
                                        }
                                    }
                                }
                            }
                            val grouped = model.rules.map { it.item.baseline.id }.toSet()
                            model.pending.filter { it.groupId !in grouped }.groupBy { it.groupId }.forEach { (_, work) ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${work.first().occurrence.kind} · ${work.first().occurrence.rule.component}")
                                        work.forEach { pending ->
                                            MaintenanceChoice("Select ${pending.occurrence.rule.component}", pending.occurrence.id in chosen, busy) {
                                                chosen = ArrayList(if (it) chosen + pending.occurrence.id else chosen - pending.occurrence.id)
                                            }
                                            PendingTarget(pending)
                                            val row = pending.occurrence
                                            if (row.kind == MaintenanceKind.PROGRAMMED && row.effectiveKm == null && row.effectiveDate == null)
                                                TextButton(onClick = { chosen = arrayListOf(row.id); open("schedule") }) { Text("Schedule ${row.rule.component}") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingTarget(work: PendingWork) {
    val row = work.occurrence
    if (work.priorAnchorNeedsReconciliation)
        Text("Retained obligation from a prior completion anchor. Reconcile explicitly with newer work; this is not the current recurrence calculation.")
    Text("${row.rule.action} ${row.rule.component} · ${label(work.effective)}")
    Text("Original target: ${row.originalKm?.let { "$it km" } ?: "km unknown"} / ${row.originalDate ?: "date unknown"}\n" +
        "Effective target: ${row.effectiveKm?.let { "$it km" } ?: "km unknown"} / ${row.effectiveDate ?: "date unknown"}")
    if (row.changeReason?.startsWith("Initial scheduling") == true)
        Text("Initial scheduling · originally unscheduled; no performance recorded.")
    else if (row.originalKm != row.effectiveKm || row.originalDate != row.effectiveDate)
        Text("Originally: ${label(work.originally)} · postponed until the effective target; no performance recorded.")
    Text("Source: ${row.rule.sourceIdentity ?: "Owner"}; ${row.rule.locator.orEmpty()}", style = MaterialTheme.typography.bodySmall)
}

private fun label(result: DueResult): String = when (result.status) {
    DueStatus.DUE -> "Due"
    DueStatus.NOT_DUE -> "Below known targets"
    DueStatus.UNKNOWN -> "Unknown — missing evidence"
    DueStatus.MANUAL_REVIEW -> "Manual review required"
    DueStatus.UNSCHEDULED -> "Open — unscheduled"
} + if (result.manualReview && result.status == DueStatus.DUE) " · condition/event review still required" else ""
