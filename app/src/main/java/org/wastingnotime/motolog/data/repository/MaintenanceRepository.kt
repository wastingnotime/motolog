package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.maintenance.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate

/** Synchronous internal commands; call off the main thread. Selection and admission serialize in Room. */
class MaintenanceRepository(private val db: MotoLogDatabase) {
    private val store = LocalStore(db)
    private val registration = RegistrationRepository(db)

    fun recordCorrective(operationId: String, bikeId: String, details: PerformedDetails, today: LocalDate, now: Long): Boolean =
        MaintenanceCommands(db).corrective(operationId, bikeId, details, today, now)

    fun createProgrammedJob(operationId: String, bikeId: String, action: String, component: String,
        target: WorkTarget?, explicitlyUnscheduled: Boolean, now: Long): Boolean =
        MaintenanceCommands(db).createJob(operationId, bikeId, action, component, target, explicitlyUnscheduled, now)

    fun scheduleProgrammedJob(operationId: String, selected: ObligationSelection, target: WorkTarget, now: Long): Boolean =
        MaintenanceCommands(db).scheduleJob(operationId, selected, target, now)

    fun applyOutcome(operationId: String, outcome: ChecklistOutcome, readingId: String?, today: LocalDate, now: Long): Boolean =
        MaintenanceCommands(db).outcome(operationId, outcome, readingId, today, now)

    fun completedHistory(bikeId: String): List<MaintenanceHistory> = store.snapshot().histories
        .filter { it.bikeId == bikeId }
        .sortedWith(compareByDescending<MaintenanceHistory> { it.performedDate }.thenBy { it.id })

    fun operationApplied(operationId: String): Boolean = db.dao().operation(operationId) != null

    /** Only explicitly qualified history is used; import receipt order is never service order. */
    fun qualifyAnchor(operationId: String, baselineId: String, historyId: String,
        expectedRevision: Int, orderReconciled: Boolean, now: Long): Boolean = db.runInTransaction<Boolean> {
        val command = WriteBatch(anchors = listOf(QualifyingAnchor("anchor:$operationId", baselineId,
            historyId, expectedRevision + 1, orderReconciled)))
        if (db.dao().operation(operationId) != null) return@runInTransaction store.write(operationId, command, now)
        val snapshot = store.snapshot()
        val item = registration.items(snapshot).single { it.baseline.id == baselineId }
        val history = snapshot.histories.single { it.id == historyId }
        validateHistory(history, item.baseline.bikeId, item.effective)
        validateAnchorChoice(snapshot, baselineId, history, expectedRevision, orderReconciled)
        store.write(operationId, command, now)
    }

    /** Idempotent cycle selection. Uses a specifically confirmed owner reading, never GPS or a fabricated zero. */
    fun refresh(bikeId: String, readingId: String?, today: LocalDate, now: Long): MaintenanceView = db.runInTransaction<MaintenanceView> {
        val snapshot = store.snapshot()
        val bike = snapshot.bikes.single { it.id == bikeId }
        val km = reading(snapshot, bikeId, readingId)
        val items = registration.items(snapshot).filter { it.baseline.bikeId == bikeId }
        // Validate all fixed grids before any materialization or write. A rejected refresh is safe to repeat after restart.
        val selectedMarks = items.associate { item ->
            val support = DueEvaluation.support(item.effective)
            val step = when (support) { RuleSupport.OIL -> 5_000L; RuleSupport.OIL_FILTER -> 10_000L; else -> null }
            val retained = snapshot.obligations.filter { it.baselineId == item.baseline.id && it.branch == MILEAGE }
                .mapNotNull { link -> snapshot.occurrences.single { it.id == link.occurrenceId }.originalKm }
            item.baseline.id to if (step != null && (item.effective.sourceKind != SourceKind.MANUAL || bike.manualConfirmed))
                DueEvaluation.selectMarks(km, step, retained) else emptyList()
        }
        items.forEach { item ->
            if (item.effective.sourceKind == SourceKind.MANUAL && !bike.manualConfirmed) return@forEach
            val rule = item.effective
            val support = DueEvaluation.support(rule)
            val prior = snapshot.obligations.filter { it.baselineId == item.baseline.id }
            val currentAnchor = anchor(snapshot, item)
            fun add(key: String, branch: String, targets: DueTargets, kmAnchor: String? = null, dateAnchor: String? = null) {
                if (prior.any { it.cycleKey == key }) return
                val id = "due:${item.baseline.id.length}:${item.baseline.id}:$key"
                store.write("select:$id", WriteBatch(occurrences = listOf(MaintenanceOccurrence(id,
                    bikeId, null, rule, targets.km, targets.date?.toString(), targets.km, targets.date?.toString(),
                    kind = MaintenanceKind.PREVENTIVE)), obligations = listOf(PreventiveObligation(id,
                    item.baseline.id, key, branch, kmAnchor, dateAnchor))), now)
            }
            when (support) {
                RuleSupport.OIL, RuleSupport.OIL_FILTER -> {
                    selectedMarks.getValue(item.baseline.id).forEach { mark ->
                        add("mileage:$mark", MILEAGE, DueTargets(km = mark))
                    }
                    if (support == RuleSupport.OIL && currentAnchor?.performedDate != null) {
                        add("calendar:${currentAnchor.id}", CALENDAR,
                            DueTargets(date = LocalDate.parse(currentAnchor.performedDate).plusMonths(6)), dateAnchor = currentAnchor.id)
                    }
                }
                RuleSupport.INTERVAL, RuleSupport.CONDITIONAL_INTERVAL -> {
                    val targets = DueEvaluation.interval(rule, currentAnchor?.odometerKm,
                        currentAnchor?.performedDate?.let(LocalDate::parse))
                    if (targets.km != null || targets.date != null) {
                        add("interval:${item.revision}:${currentAnchor!!.id}", INTERVAL, targets,
                            currentAnchor.id.takeIf { targets.km != null }, currentAnchor.id.takeIf { targets.date != null })
                    }
                }
                RuleSupport.MANUAL_REVIEW -> Unit
            }
        }
        view(store.snapshot(), bikeId, km, today)
    }

    /** Atomic reconciliation primitive for TASK-16. History must describe precisely one performed action.
     * New history and all selected satisfaction snapshots commit together, or none do.
     */
    fun reconcile(operationId: String, history: MaintenanceHistory, selected: List<ObligationSelection>, now: Long, anchorChoice: AnchorChoice? = null): Boolean =
        db.runInTransaction<Boolean> {
            require(selected.isNotEmpty() && selected.map { it.id }.distinct().size == selected.size)
            val snapshot = store.snapshot()
            val rows = selected.map { selection -> snapshot.occurrences.single { it.id == selection.id } }
            val links = rows.map { row -> ObligationSatisfaction(row.id, history.id, row.originalKm,
                row.originalDate, row.effectiveKm, row.effectiveDate, selected.single { it.id == row.id }.revision) }
            val existingHistory = snapshot.histories.find { it.id == history.id }
            require(existingHistory == null || existingHistory == history) { "History identity conflict" }
            val batch = WriteBatch(histories = listOf(history), satisfactions = links,
                anchors = listOfNotNull(anchorChoice?.let { choice -> QualifyingAnchor("anchor:$operationId",
                    choice.baselineId, history.id, choice.expectedRevision + 1, choice.orderReconciled) }),
                occurrenceChanges = rows.map { row -> row.copy(state = OccurrenceState.COMPLETED,
                    revision = selected.single { it.id == row.id }.revision + 1, changeReason = "Explicit obligation reconciliation") })
            // Completed rows retain exactly the targets used by the original receipt.
            if (db.dao().operation(operationId) != null) {
                return@runInTransaction store.write(operationId, batch, now)
            }
            require(existingHistory == null) { "Use a new performed-action snapshot for reconciliation" }
            rows.zip(selected).forEach { (row, selection) ->
                require(row.revision == selection.revision && row.state != OccurrenceState.COMPLETED) { "Obligation changed; reload" }
                require(snapshot.occurrences.none { it.parentId == row.id }) { "Select the carried remainder, not its parent" }
                validateHistory(history, row.bikeId, row.rule)
            }
            validateSelectedAction(snapshot, history, rows, anchorChoice)
            anchorChoice?.let { choice ->
                val item = registration.items(snapshot).single { it.baseline.id == choice.baselineId }
                require(rows.all { obligation(snapshot, it)?.baselineId == choice.baselineId }) {
                    "Anchor choice must belong to the selected work group" }
                validateHistory(history, item.baseline.bikeId, item.effective)
                validateAnchorChoice(snapshot, choice.baselineId, history, choice.expectedRevision, choice.orderReconciled)
            }
            history.occurrenceId?.let { require(it in selected.map { s -> s.id }) }
            store.write(operationId, batch, now)
        }

    /** Read retained work without adopting any new marks, including after a review-required jump. */
    fun existing(bikeId: String, readingId: String?, today: LocalDate): MaintenanceView = db.runInTransaction<MaintenanceView> {
        val snapshot = store.snapshot()
        view(snapshot, bikeId, reading(snapshot, bikeId, readingId), today)
    }

    fun view(snapshot: WriteBatch, bikeId: String, currentKm: Long?, today: LocalDate): MaintenanceView {
        val bike = snapshot.bikes.single { it.id == bikeId }
        val open = snapshot.occurrences.filter { it.bikeId == bikeId && it.state != OccurrenceState.COMPLETED &&
            snapshot.occurrences.none { child -> child.parentId == it.id } }
        val work = open.map { row ->
            val link = obligation(snapshot, row)
            val support = DueEvaluation.support(row.rule)
            val review = row.kind == MaintenanceKind.PREVENTIVE &&
                support in listOf(RuleSupport.CONDITIONAL_INTERVAL, RuleSupport.MANUAL_REVIEW)
            fun result(km: Long?, date: String?, original: Boolean): DueResult {
                val rescheduled = row.state == OccurrenceState.POSTPONED || row.parentId != null ||
                    row.originalKm != row.effectiveKm || row.originalDate != row.effectiveDate
                val expectsKm = if (row.kind != MaintenanceKind.PREVENTIVE || (!original && rescheduled)) km != null
                    else link?.branch == MILEAGE || (link?.branch == INTERVAL && row.rule.intervalKm != null)
                val expectsDate = if (row.kind != MaintenanceKind.PREVENTIVE || (!original && rescheduled)) date != null
                    else link?.branch == CALENDAR || (link?.branch == INTERVAL && row.rule.intervalMonths != null)
                return DueEvaluation.evaluate(DueTargets(km, date?.let(LocalDate::parse), expectsKm, expectsDate), currentKm, today, review)
            }
            PendingWork(row, link?.baselineId ?: root(snapshot, row).id,
                result(row.originalKm, row.originalDate, true), result(row.effectiveKm, row.effectiveDate, false),
                link?.takeIf { it.branch != MILEAGE }?.let { obligation ->
                    val current = snapshot.anchors.filter { it.baselineId == obligation.baselineId }.maxByOrNull { it.revision }?.historyId
                    current != null && current !in listOfNotNull(obligation.anchorKmId, obligation.anchorDateId)
                } ?: false)
        }
        val rules = registration.items(snapshot).filter { it.baseline.bikeId == bikeId }.map { item ->
            val support = if (item.effective.sourceKind == SourceKind.MANUAL && !bike.manualConfirmed)
                RuleSupport.MANUAL_REVIEW else DueEvaluation.support(item.effective)
            val currentAnchor = anchor(snapshot, item)
            val pending = work.filter { it.groupId == item.baseline.id }
            val mileageTargets = pending.filter { obligation(snapshot, it.occurrence)?.branch == MILEAGE }
                .mapNotNull { it.occurrence.effectiveKm }
            val retainedMarks = snapshot.obligations.filter { it.baselineId == item.baseline.id && it.branch == MILEAGE }
                .mapNotNull { link -> snapshot.occurrences.single { it.id == link.occurrenceId }.originalKm }
            fun fixedTarget(step: Long): Long = mileageTargets.minOrNull()
                ?: retainedMarks.maxOrNull()?.let { DueEvaluation.nextMark(it, step) }
                ?: DueEvaluation.firstMark(currentKm, step)
            val targets = when (support) {
                RuleSupport.OIL -> DueTargets(km = fixedTarget(5_000),
                    date = currentAnchor?.performedDate?.let { LocalDate.parse(it).plusMonths(6) }, expectsDate = true)
                RuleSupport.OIL_FILTER -> DueTargets(km = fixedTarget(10_000))
                RuleSupport.INTERVAL, RuleSupport.CONDITIONAL_INTERVAL -> DueEvaluation.interval(item.effective,
                    currentAnchor?.odometerKm, currentAnchor?.performedDate?.let(LocalDate::parse))
                RuleSupport.MANUAL_REVIEW -> DueTargets()
            }
            val review = support in listOf(RuleSupport.CONDITIONAL_INTERVAL, RuleSupport.MANUAL_REVIEW)
            val base = DueEvaluation.evaluate(targets, currentKm, today, review)
            // Current recurrence uses one physical completion. Older adopted work is shown separately.
            RuleDue(item, support, base, currentAnchor?.id, pending,
                snapshot.anchors.filter { it.baselineId == item.baseline.id }.maxOfOrNull { it.revision } ?: 0)
        }
        return MaintenanceView(rules, work, FazerInventory.gaps, currentKm)
    }

    /** Oil's OR branches may share one physical action. A future fixed mileage mark never can. */
    internal fun validateSelectedAction(snapshot: WriteBatch, history: MaintenanceHistory,
        rows: List<MaintenanceOccurrence>, anchorChoice: AnchorChoice?) {
        rows.forEach { row ->
            val link = obligation(snapshot, row)
            if (link?.branch == MILEAGE) require(history.odometerKm != null && history.odometerKm >= requireNotNull(row.originalKm)) {
                "A future mileage mark cannot be satisfied early" }
            if (link?.branch == CALENDAR) {
                val date = requireNotNull(history.performedDate) { "Calendar satisfaction needs a performed date." }
                if (date < requireNotNull(row.originalDate)) {
                    val origin = snapshot.histories.single { it.id == link.anchorDateId }
                    require(DueEvaluation.support(row.rule) == RuleSupport.OIL &&
                        anchorChoice?.baselineId == link.baselineId && origin.performedDate != null && date >= origin.performedDate &&
                        rows.any { mileage ->
                            val other = obligation(snapshot, mileage)
                            other?.baselineId == link.baselineId && other.branch == MILEAGE &&
                                history.odometerKm != null && history.odometerKm >= requireNotNull(mileage.originalKm)
                        }) { "Early oil calendar satisfaction needs the corresponding reached mileage selection and an atomic calendar anchor choice after the prior service." }
                }
            }
        }
    }

    internal fun validateAnchorChoice(snapshot: WriteBatch, baselineId: String, history: MaintenanceHistory,
        expectedRevision: Int, orderReconciled: Boolean) {
        val historyId = history.id
        val previous = snapshot.anchors.filter { it.baselineId == baselineId }.maxByOrNull { it.revision }
        require((previous?.revision ?: 0) == expectedRevision) { "Anchor changed; reload" }
        if (previous != null) {
            require(previous.historyId != historyId) { "Already the current anchor" }
            val old = snapshot.histories.single { it.id == previous.historyId }
            val oldDate = old.performedDate
            val newDate = history.performedDate
            if (oldDate != null && newDate != null && newDate != oldDate)
                require(newDate > oldDate) { "Backdated service cannot replace a later anchor" }
            else require(orderReconciled) { "Service order is uncertain; explicit owner reconciliation required" }
        }
    }

    private fun reading(snapshot: WriteBatch, bikeId: String, id: String?): Long? = id?.let {
        snapshot.readings.single { row -> row.id == it }.also { row ->
            require(row.bikeId == bikeId && row.certainty == Certainty.CONFIRMED && row.observed != null)
        }.kilometers
    }
    private fun anchor(snapshot: WriteBatch, item: ChecklistItem): MaintenanceHistory? = snapshot.anchors
        .filter { it.baselineId == item.baseline.id }.maxByOrNull { it.revision }?.let { selected ->
            snapshot.histories.single { it.id == selected.historyId }.takeIf { it.rule == item.effective }
        }
    internal fun validateHistory(history: MaintenanceHistory, bikeId: String, rule: RuleValues) {
        require(history.bikeId == bikeId && history.rule == rule) { "Performed action/component/source revision must match exactly" }
        require(history.certainty == Certainty.CONFIRMED && !history.anchorEvidence.isNullOrBlank()) {
            "Explicit confirmed qualifying action evidence required" }
    }
    private fun root(snapshot: WriteBatch, row: MaintenanceOccurrence): MaintenanceOccurrence =
        row.parentId?.let { id -> root(snapshot, snapshot.occurrences.single { it.id == id }) } ?: row
    internal fun obligation(snapshot: WriteBatch, row: MaintenanceOccurrence): PreventiveObligation? =
        snapshot.obligations.find { it.occurrenceId == root(snapshot, row).id }

    companion object {
        const val MILEAGE = "mileage"
        const val CALENDAR = "calendar"
        const val INTERVAL = "interval"
    }
}

data class AnchorChoice(val baselineId: String, val expectedRevision: Int, val orderReconciled: Boolean = false)
data class ObligationSelection(val id: String, val revision: Int)
data class PendingWork(val occurrence: MaintenanceOccurrence, val groupId: String,
    val originally: DueResult, val effective: DueResult, val priorAnchorNeedsReconciliation: Boolean = false)
data class RuleDue(val item: ChecklistItem, val support: RuleSupport, val evaluation: DueResult,
    val anchorHistoryId: String?, val pending: List<PendingWork>, val anchorRevision: Int = 0)
data class MaintenanceView(val rules: List<RuleDue>, val pending: List<PendingWork>, val coverage: String, val currentKm: Long? = null)
