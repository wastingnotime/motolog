package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate
import java.time.ZoneId

/** One explicit checklist selection, with zero, some or all actions performed. */
data class ChecklistOutcome(
    val selected: List<ObligationSelection>,
    val performed: List<PerformedAction>,
    val remainingTarget: WorkTarget? = null,
    val reason: String = "",
    val confirmDroppedThreshold: Boolean = false,
)
data class WorkTarget(val km: Long? = null, val date: String? = null) {
    internal fun fields(): List<Any?> = listOf(km, date)
    fun validate() {
        require(km != null || date != null) { "Enter an absolute date, whole-km target, or both." }
        require(km == null || km in 0..999_999_999_999L) { "Target must be nonnegative whole kilometers (up to 12 digits)." }
        date?.let { value -> require(runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)) {
            "Use a valid date in YYYY-MM-DD." } }
    }
}
data class PerformedDetails(val date: String, val zone: String, val odometerKm: Long?,
    val work: String, val evidence: String, val description: String = work,
    val costBrl: String? = null, val location: String? = null, val replacements: String? = null) {
    internal fun fields(): List<Any?> = listOf(date, zone, odometerKm, work, evidence, description, costBrl, location, replacements)
}
data class PerformedAction(val obligationIds: List<String>, val details: PerformedDetails,
    val anchorChoice: AnchorChoice? = null)

/** All generated history, satisfaction links, remainders, revision audits and the receipt share one transaction. */
internal class MaintenanceCommands(private val db: MotoLogDatabase) {
    private val store = LocalStore(db)
    private val due = MaintenanceRepository(db)

    /** Standalone performed work never satisfies a checklist or establishes an anchor. */
    fun corrective(operationId: String, bikeId: String, details: PerformedDetails, today: LocalDate, now: Long): Boolean =
        store.command(operationId, listOf("corrective", bikeId, details.fields()), now) {
            val date = runCatching { LocalDate.parse(details.date) }.getOrNull()
            require(date != null && date.toString() == details.date && date <= today) {
                "Service date must be today or earlier (YYYY-MM-DD)." }
            require(runCatching { ZoneId.of(details.zone) }.isSuccess) { "Choose a valid service time zone." }
            require(details.description.isNotBlank()) { "Describe the issue or reason for service." }
            require(details.work.isNotBlank()) { "Describe the work performed." }
            require(details.odometerKm == null || details.odometerKm in 0..999_999_999_999L) {
                "Enter nonnegative whole owner kilometers (up to 12 digits), or leave unknown." }
            val cost = details.costBrl?.let { value ->
                require(runCatching { DecimalValues.normalize(value, 2, false) }.isSuccess) {
                    "Cost must be nonnegative BRL with up to 12 whole digits and two decimal places, or blank for unknown." }
                DecimalValues.normalize(value, 2, false)
            }
            val snapshot = store.snapshot(includeRideEvidence = false)
            require(snapshot.bikes.any { it.id == bikeId }) { "Register the motorcycle first." }
            details.odometerKm?.let { km ->
                // Date-only services cannot be ordered within a day. Compare only strictly earlier/later days.
                val neighbors = snapshot.histories.filter { it.bikeId == bikeId && it.certainty == Certainty.CONFIRMED }
                    .mapNotNull { row -> row.performedDate?.let { day -> row.odometerKm?.let { day to it } } } +
                    snapshot.readings.filter { it.bikeId == bikeId && it.certainty == Certainty.CONFIRMED }
                        .mapNotNull { row -> row.observed?.let { observed -> row.kilometers?.let {
                            java.time.Instant.ofEpochMilli(observed.epochMillis).atZone(ZoneId.of(observed.zoneId)).toLocalDate().toString() to it
                        } } }
                require(neighbors.none { (day, reading) -> (day < details.date && reading > km) || (day > details.date && reading < km) }) {
                    "Service odometer conflicts with earlier or later confirmed owner readings. Review the date or kilometers; leave unknown if uncertain." }
            }
            WriteBatch(histories = listOf(MaintenanceHistory("corrective:$operationId", bikeId, null,
                RuleValues("Corrective service", "Owner motorcycle", SourceKind.CUSTOM, sourceIdentity = "Owner corrective entry"),
                null, null, null, null, details.date, details.zone, details.odometerKm, Certainty.CONFIRMED,
                cost, details.location?.trim()?.takeIf { it.isNotEmpty() }, details.description.trim(), details.work.trim(),
                details.replacements?.trim()?.takeIf { it.isNotEmpty() }, null, now, MaintenanceKind.CORRECTIVE)))
        }

    fun createJob(operationId: String, bikeId: String, action: String, component: String,
        target: WorkTarget?, explicitlyUnscheduled: Boolean, now: Long): Boolean =
        store.command(operationId, listOf("create-job", bikeId, action, component, target?.fields(), explicitlyUnscheduled), now) {
            require(action.isNotBlank() && component.isNotBlank()) { "Enter the action and component." }
            require((target == null) == explicitlyUnscheduled) { "Enter a target or explicitly choose unscheduled." }
            target?.validate()
            require(store.snapshot(includeRideEvidence = false).bikes.any { it.id == bikeId })
            WriteBatch(occurrences = listOf(MaintenanceOccurrence("job:$operationId", bikeId, null,
                RuleValues(action.trim(), component.trim(), SourceKind.CUSTOM, sourceIdentity = "Owner programmed job"),
                target?.km, target?.date, target?.km, target?.date, kind = MaintenanceKind.PROGRAMMED)))
        }

    /** First scheduling is separate from postponement and creates no performed history. */
    fun scheduleJob(operationId: String, selected: ObligationSelection, target: WorkTarget, now: Long): Boolean =
        store.command(operationId, listOf("initial-schedule", selected.id, selected.revision, target.fields()), now) {
            target.validate()
            val snapshot = store.snapshot(includeRideEvidence = false)
            val row = selected(snapshot, selected)
            require(row.kind == MaintenanceKind.PROGRAMMED && row.effectiveKm == null && row.effectiveDate == null &&
                row.state == OccurrenceState.PENDING && row.parentId == null) { "Only an unscheduled programmed job can receive its first target." }
            WriteBatch(occurrenceChanges = listOf(row.copy(effectiveKm = target.km, effectiveDate = target.date,
                revision = row.revision + 1, changeReason = "Initial scheduling of previously unscheduled work")))
        }

    fun outcome(operationId: String, command: ChecklistOutcome, readingId: String?, today: LocalDate, now: Long): Boolean {
        val selections = command.selected.sortedBy { it.id }
        val performed = command.performed.sortedBy { it.obligationIds.sorted().joinToString("\u0000") }
        val fields = listOf("checklist-outcome", selections.map { listOf(it.id, it.revision) },
            performed.map { listOf(it.obligationIds.sorted(), it.details.fields(), it.anchorChoice?.let { anchor ->
                listOf(anchor.baselineId, anchor.expectedRevision, anchor.orderReconciled) }) },
            command.remainingTarget?.fields(), command.reason, command.confirmDroppedThreshold, readingId)
        return store.command(operationId, fields, now) {
            require(selections.isNotEmpty() && selections.map { it.id }.distinct().size == selections.size) { "Choose a nonempty checklist without duplicate items." }
            val snapshot = store.snapshot(includeRideEvidence = false)
            val rows = selections.map { selected(snapshot, it) }
            require(rows.map { it.bikeId }.distinct().size == 1) { "Checklist must belong to one motorcycle." }
            val performedIds = performed.flatMap { it.obligationIds }
            require(performedIds.distinct().size == performedIds.size && performedIds.all { id -> rows.any { it.id == id } }) {
                "Every performed item must belong to the checklist exactly once." }
            val remaining = rows.filter { it.id !in performedIds }
            require((command.remainingTarget != null) == remaining.isNotEmpty()) { "Remaining work needs a new target; full completion has no remainder." }
            val histories = mutableListOf<MaintenanceHistory>()
            val satisfactions = mutableListOf<ObligationSatisfaction>()
            val anchors = mutableListOf<QualifyingAnchor>()
            val changes = mutableListOf<MaintenanceOccurrence>()
            performed.forEachIndexed { index, action ->
                require(action.obligationIds.isNotEmpty())
                val actionRows = action.obligationIds.sorted().map { id -> rows.single { it.id == id } }
                val first = actionRows.first()
                require(actionRows.all { it.rule == first.rule && it.kind == first.kind }) { "One performed action must match every selected source snapshot and maintenance kind." }
                val details = action.details
                val date = try { LocalDate.parse(details.date) }
                    catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Use a valid performed date in YYYY-MM-DD.") }
                require(date.toString() == details.date && date <= today) { "Performed date must be today or earlier (YYYY-MM-DD)." }
                ZoneId.of(details.zone)
                val history = MaintenanceHistory("work:${operationId.length}:$operationId:$index", first.bikeId, first.id,
                    first.rule, first.originalKm, first.originalDate, first.effectiveKm, first.effectiveDate,
                    details.date, details.zone, details.odometerKm, Certainty.CONFIRMED, details.costBrl,
                    details.location, details.description, details.work, details.replacements, details.evidence, now, first.kind)
                due.validateHistory(history, first.bikeId, first.rule)
                actionRows.forEach { row ->
                    satisfactions += ObligationSatisfaction(row.id, history.id, row.originalKm, row.originalDate,
                        row.effectiveKm, row.effectiveDate, row.revision)
                    changes += row.copy(state = OccurrenceState.COMPLETED, revision = row.revision + 1,
                        changeReason = "Explicit checklist completion")
                }
                due.validateSelectedAction(snapshot, history, actionRows, action.anchorChoice)
                action.anchorChoice?.let { choice ->
                    require(actionRows.all { due.obligation(snapshot, it)?.baselineId == choice.baselineId }) { "Anchor must match the selected work group." }
                    require(anchors.none { it.baselineId == choice.baselineId }) { "Choose one physical completion per anchor." }
                    val item = RegistrationRepository(db).items(snapshot).single { it.baseline.id == choice.baselineId }
                    due.validateHistory(history, item.baseline.bikeId, item.effective)
                    due.validateAnchorChoice(snapshot, choice.baselineId, history, choice.expectedRevision, choice.orderReconciled)
                    anchors += QualifyingAnchor("anchor:${operationId.length}:$operationId:$index", choice.baselineId,
                        history.id, choice.expectedRevision + 1, choice.orderReconciled)
                }
                histories += history
            }
            val remainders = mutableListOf<MaintenanceOccurrence>()
            if (remaining.isNotEmpty()) {
                require(command.reason.isNotBlank()) { "Enter a reason for postponement." }
                val target = requireNotNull(command.remainingTarget)
                target.validate()
                val km = readingId?.let { id -> snapshot.readings.single { it.id == id }.also {
                    require(it.bikeId == rows.first().bikeId && it.certainty == Certainty.CONFIRMED && it.observed != null)
                }.kilometers }
                remaining.forEach { row ->
                    validatePostponement(row, target, km, today, command.confirmDroppedThreshold)
                    changes += row.copy(state = OccurrenceState.POSTPONED, revision = row.revision + 1,
                        effectiveKm = if (performed.isEmpty()) target.km else row.effectiveKm,
                        effectiveDate = if (performed.isEmpty()) target.date else row.effectiveDate,
                        changeReason = command.reason)
                    if (performed.isNotEmpty()) remainders += row.copy(
                        id = "remainder:${operationId.length}:$operationId:${row.id}", parentId = row.id,
                        effectiveKm = target.km, effectiveDate = target.date, state = OccurrenceState.POSTPONED,
                        revision = 0, changeReason = command.reason)
                }
            }
            WriteBatch(histories = histories, satisfactions = satisfactions, anchors = anchors,
                occurrences = remainders, occurrenceChanges = changes)
        }
    }

    private fun selected(snapshot: WriteBatch, selection: ObligationSelection): MaintenanceOccurrence {
        val row = snapshot.occurrences.single { it.id == selection.id }
        require(row.revision == selection.revision && row.state != OccurrenceState.COMPLETED &&
            snapshot.occurrences.none { it.parentId == row.id }) { "Checklist changed; reload and select the outstanding work." }
        require(row.kind in listOf(MaintenanceKind.PREVENTIVE, MaintenanceKind.PROGRAMMED)) { "Select preventive or programmed work." }
        return row
    }
}

internal fun validatePostponement(row: MaintenanceOccurrence, target: WorkTarget, currentKm: Long?,
    today: LocalDate, confirmDropped: Boolean) {
    target.km?.let { km ->
        require((row.effectiveKm == null || km > row.effectiveKm) && (currentKm == null || km > currentKm)) {
            "New km target must advance the previous target and the confirmed owner reading." }
    }
    target.date?.let { date ->
        require(date > today.toString() && (row.effectiveDate == null || date > row.effectiveDate)) {
            "New date must advance the previous target and be after today." }
    }
    require(confirmDropped || !((row.effectiveKm != null && target.km == null) || (row.effectiveDate != null && target.date == null))) {
        "Confirm the dropped threshold: only the remaining dimension will trigger this work." }
}
