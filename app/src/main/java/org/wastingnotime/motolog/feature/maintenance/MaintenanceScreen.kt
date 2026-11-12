package org.wastingnotime.motolog.feature.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.maintenance.*
import java.time.LocalDate

/** Read-only due/pending surface. Completion/postponement forms belong to the next slice. */
@Composable
fun MaintenanceScreen(repository: MaintenanceRepository, bikeId: String, readingId: String?, back: () -> Unit) {
    var view by remember { mutableStateOf<MaintenanceView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bikeId, readingId) {
        try {
            view = withContext(Dispatchers.IO) {
                repository.refresh(bikeId, readingId, LocalDate.now(), System.currentTimeMillis())
            }
        } catch (review: MilestoneReviewRequired) {
            error = review.message
            view = withContext(Dispatchers.IO) { repository.existing(bikeId, readingId, LocalDate.now()) }
        }
        catch (_: Exception) { error = "Maintenance unavailable. Saved records are retained; return and try again." }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = back) { Text("Back to checklist") }
                Text("Maintenance due and pending", style = MaterialTheme.typography.headlineSmall)
                Text("Evaluated against the displayed last confirmed owner reading. Historical readings do not prove current mileage.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val saved = view
                if (saved == null && error == null) Text("Evaluating local records…")
                saved?.let { model ->
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
                                rule.pending.forEach { PendingTarget(it) }
                            }
                        }
                    }
                    val grouped = model.rules.map { it.item.baseline.id }.toSet()
                    model.pending.filter { it.groupId !in grouped }.groupBy { it.groupId }.forEach { (_, work) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${work.first().occurrence.kind} · ${work.first().occurrence.rule.component}")
                                work.forEach { PendingTarget(it) }
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
    if (row.originalKm != row.effectiveKm || row.originalDate != row.effectiveDate)
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
