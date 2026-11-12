package org.wastingnotime.motolog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MaintenanceOutcomeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "maintenance-outcome.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var store: LocalStore
    private lateinit var repo: MaintenanceRepository
    private val today = LocalDate.of(2026, 10, 1)
    private val now = 1_800_000_000_000L
    @Before fun setup() {
        context.deleteDatabase(name); open()
        RegistrationRepository(db).register("bike", 2017, true, OdometerReading("reading", "bike", 20_800,
            Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now, "UTC", 0), now), now)
        repo.refresh("bike", "reading", today, now)
    }
    private fun open() { db = MotoLogDatabase.open(context, name); store = LocalStore(db); repo = MaintenanceRepository(db) }
    private fun reopen() { db.close(); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun selection(rows: List<MaintenanceOccurrence>) = rows.map { ObligationSelection(it.id, it.revision) }
    private fun marks() = store.snapshot().occurrences.filter { it.originalKm == 20_000L }
    private fun details(row: MaintenanceOccurrence, date: String = today.toString()) = PerformedDetails(date, "UTC", 20_800,
        "${row.rule.action} ${row.rule.component}", "Synthetic confirmed physical action")
    private fun finish(rows: List<MaintenanceOccurrence>, done: List<MaintenanceOccurrence>, target: WorkTarget? = null,
        anchors: Boolean = false): ChecklistOutcome = ChecklistOutcome(selection(rows), done.map { row ->
            PerformedAction(listOf(row.id), details(row), if (anchors) AnchorChoice(
                store.snapshot().obligations.single { it.occurrenceId == row.id }.baselineId, 0) else null)
        }, target, if (target == null) "" else "Synthetic postponement reason")
    private fun job(id: String, target: WorkTarget? = null): MaintenanceOccurrence {
        repo.createProgrammedJob(id, "bike", "Replace", "Synthetic $id", target, target == null, now)
        return store.snapshot().occurrences.single { it.id == "job:$id" }
    }
    private fun apply(id: String, command: ChecklistOutcome) = repo.applyOutcome(id, command, "reading", today, now)
    private fun visible() = repo.existing("bike", "reading", today).pending.map { it.occurrence }
    private fun failReceipt(id: String) {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_outcome BEFORE INSERT ON AppliedOperation WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'Synthetic disk failure'); END")
    }

    @Test fun allPerformedCommitsMultipleActionsAndAnchorsWithIndependentFutureTargetsAndImmutableHistory() {
        val rows = marks(); assertEquals(2, rows.size)
        val command = finish(rows, rows, anchors = true)
        assertTrue(apply("all", command))
        val saved = store.snapshot()
        assertEquals(2, saved.histories.size); assertEquals(2, saved.satisfactions.size); assertEquals(2, saved.anchors.size)
        assertTrue(rows.all { row -> visible().none { it.id == row.id } })
        assertEquals(setOf(25_000L, 30_000L), visible().mapNotNull { it.originalKm }.toSet())
        assertTrue(saved.histories.all { history -> history.rule == rows.single { it.id == history.occurrenceId }.rule &&
            history.originalKm == 20_000L && history.effectiveKm == 20_000L })
        reopen(); assertFalse(apply("all", command)); assertEquals(saved, store.snapshot())
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("UPDATE MaintenanceHistory SET description = 'changed'") }
        assertThrows(IllegalArgumentException::class.java) { apply("another-completion", command) }
        assertThrows(OperationConflict::class.java) { apply("all", command.copy(reason = "changed intent")) }
    }

    @Test fun nonePerformedPostponesWholeChecklistWithoutHistoryAndCanRepeatWithAudit() {
        val rows = marks()
        val command = finish(rows, emptyList(), WorkTarget(26_000, "2026-12-01"))
        assertTrue(apply("none", command)); reopen(); assertFalse(apply("none", command))
        assertTrue(store.snapshot().histories.isEmpty()); assertTrue(store.snapshot().satisfactions.isEmpty())
        assertTrue(store.snapshot().anchors.isEmpty())
        rows.forEach { old ->
            val row = visible().single { it.id == old.id }
            assertEquals(old.rule, row.rule); assertEquals(20_000L, row.originalKm); assertEquals(26_000L, row.effectiveKm)
            assertEquals(OccurrenceState.POSTPONED, row.state)
        }
        val nextRows = visible().filter { it.id in rows.map { it.id } }
        val second = finish(nextRows, emptyList(), WorkTarget(27_000, "2027-01-01"))
        apply("none-again", second)
        // Replay the first request after later changes still uses its original command receipt.
        assertFalse(repo.applyOutcome("none", command, "reading", today.plusDays(1), now + 1))
        assertEquals(4, store.occurrenceRevisions().size)
        assertEquals(setOf(20_000L, 26_000L), store.occurrenceRevisions().map { it.effectiveKm }.toSet())
        assertTrue(store.snapshot().occurrences.none { it.parentId != null })
    }

    @Test fun somePerformedCarriesEachRemainingItemOnceThroughEditsLaterMarksAndCompletion() {
        val rows = marks(); val done = rows.take(1); val remaining = rows.last()
        val command = finish(rows, done, WorkTarget(26_000, "2026-12-01"), anchors = true)
        apply("partial", command)
        val child = visible().single { it.parentId == remaining.id }
        assertEquals(remaining.rule, child.rule); assertEquals(remaining.originalKm, child.originalKm)
        assertEquals(26_000L, child.effectiveKm)
        assertTrue(visible().none { it.id in rows.map { row -> row.id } })
        val baseline = store.snapshot().obligations.single { it.occurrenceId == remaining.id }.baselineId
        RegistrationRepository(db).editItem(baseline, 0, remaining.rule.copy(scheduleKind = ScheduleKind.INTERVAL, intervalKm = 500), now)
        store.write("later-reading", WriteBatch(readings = listOf(OdometerReading("later", "bike", 30_000,
            Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now + 100, "UTC", 0), now))), now)
        repo.refresh("bike", "later", today, now)
        reopen(); assertFalse(apply("partial", command))
        val retained = visible().single { it.id == child.id }
        assertEquals(child, retained)
        val complete = finish(listOf(retained), listOf(retained))
        apply("finish-remainder", complete); reopen()
        assertTrue(visible().none { it.id == child.id || it.id == remaining.id })
        val snapshot = store.snapshot()
        assertEquals(2, snapshot.histories.size)
        assertEquals(remaining.rule, snapshot.histories.single { it.occurrenceId == child.id }.rule)
        assertEquals(26_000L, snapshot.satisfactions.single { it.occurrenceId == child.id }.effectiveKm)
        assertEquals(1, snapshot.occurrences.count { it.parentId == remaining.id })
    }

    @Test fun repeatPartialCarriesAllRemainingProgrammedItemsExactlyOnceAndDoesNotTouchRide() {
        val rows = listOf(job("one"), job("two"), job("three"))
        store.write("ride", WriteBatch(rides = listOf(RideSession("ride", "bike", RideState.ACTIVE, 1, RideOwnership.MANUAL,
            Observation(now, "UTC", 0), null, "synthetic", now))), now)
        val ride = store.snapshot().rides.single()
        val first = finish(rows, rows.take(1), WorkTarget(22_000, "2026-12-01"))
        apply("first", first)
        val children = visible().filter { it.parentId in rows.map { row -> row.id } }
        assertEquals(2, children.size)
        val second = finish(children, children.take(1), WorkTarget(23_000, "2027-01-01"))
        apply("second", second); reopen(); assertFalse(apply("first", first)); assertFalse(apply("second", second))
        val leaf = visible().single { it.kind == MaintenanceKind.PROGRAMMED }
        assertEquals(children.last().id, leaf.parentId)
        assertEquals(1, store.snapshot().occurrences.count { it.parentId == children.last().id })
        assertEquals(ride, store.snapshot().rides.single())
        apply("final", finish(listOf(leaf), listOf(leaf)))
        assertTrue(visible().none { it.kind == MaintenanceKind.PROGRAMMED })
        assertEquals(3, store.snapshot().histories.size)
    }

    @Test fun failedLateWritesRollBackAllNoneAndSomeIncludingAuditsReceiptsAndRemaindersThenRetry() {
        val rows = marks()
        listOf(0, 1, 2).forEach { count ->
            val id = "fail$count"
            val command = finish(rows, rows.take(count), if (count == rows.size) null else WorkTarget(26_000), anchors = count > 0)
            val before = store.snapshot(); val audits = store.occurrenceRevisions()
            failReceipt(id)
            assertThrows(Exception::class.java) { apply(id, command) }
            assertEquals(before, store.snapshot()); assertEquals(audits, store.occurrenceRevisions()); assertFalse(repo.operationApplied(id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_outcome")
        }
        val retry = finish(rows, rows.take(1), WorkTarget(26_000))
        failReceipt("retry")
        assertThrows(Exception::class.java) { apply("retry", retry) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_outcome")
        reopen(); assertTrue(apply("retry", retry)); reopen(); assertFalse(apply("retry", retry))
        assertEquals(1, store.snapshot().histories.size)
        assertEquals(1, store.snapshot().occurrences.count { it.parentId != null })
    }

    @Test fun scheduledByDateKmBothAndUnscheduledJobsRemainOpenAndFirstSchedulingHasNoWorkHistory() {
        val cases = listOf(WorkTarget(date = "2026-10-01"), WorkTarget(km = 20_000), WorkTarget(25_000, "2026-10-01"), null)
        val jobs = cases.mapIndexed { i, target -> job("case$i", target) }
        val view = repo.existing("bike", "reading", today)
        jobs.take(3).forEach { job -> assertEquals(org.wastingnotime.motolog.domain.maintenance.DueStatus.DUE,
            view.pending.single { it.occurrence.id == job.id }.effective.status) }
        assertEquals(org.wastingnotime.motolog.domain.maintenance.DueStatus.UNSCHEDULED,
            view.pending.single { it.occurrence.id == jobs.last().id }.effective.status)
        val unscheduled = jobs.last()
        val target = WorkTarget(24_000, "2026-11-01")
        repo.scheduleProgrammedJob("initial", selection(listOf(unscheduled)).single(), target, now)
        reopen(); assertFalse(repo.scheduleProgrammedJob("initial", selection(listOf(unscheduled)).single(), target, now))
        val scheduled = visible().single { it.id == unscheduled.id }
        assertNull(scheduled.originalKm); assertNull(scheduled.originalDate); assertEquals(target.km, scheduled.effectiveKm)
        assertEquals(OccurrenceState.PENDING, scheduled.state); assertTrue(scheduled.changeReason!!.contains("Initial scheduling"))
        assertTrue(store.snapshot().histories.isEmpty()); assertEquals(4, visible().count { it.kind == MaintenanceKind.PROGRAMMED })
        apply("scheduled-done", finish(listOf(scheduled), listOf(scheduled)))
        assertEquals(1, store.snapshot().histories.size)
        assertTrue(visible().any { it.id == jobs.first().id })
    }

    @Test fun importedUnscheduledJobCanReceiveFirstTargetAndRideCanStartWhileItIsPostponed() {
        val registration = RegistrationRepository(db)
        val preview = registration.preview(SetupEntry("imported-job", "bike", EntryKind.PROGRAMMED,
            "Synthetic imported outstanding work", action = "Repair", component = "Synthetic imported accessory"), now)
        registration.confirm(preview, false, now)
        val imported = preview.batch.occurrences.single()
        repo.scheduleProgrammedJob("import-first-schedule", selection(listOf(imported)).single(), WorkTarget(date = "2026-11-01"), now)
        val scheduled = visible().single { it.id == imported.id }
        assertNull(scheduled.originalDate); assertEquals("2026-11-01", scheduled.effectiveDate)
        assertTrue(store.snapshot().histories.isEmpty())
        apply("import-postpone", finish(listOf(scheduled), emptyList(), WorkTarget(date = "2026-12-01")))
        store.write("start-with-postponed-job", WriteBatch(rides = listOf(RideSession("new-ride", "bike", RideState.ACTIVE, 1,
            RideOwnership.MANUAL, Observation(now, "UTC", 0), null, "synthetic", now))), now)
        reopen()
        assertEquals(RideState.ACTIVE, store.snapshot().rides.single().state)
        assertTrue(visible().any { it.id == imported.id })
        assertTrue(store.snapshot().histories.isEmpty())
    }

    @Test fun targetValidationRejectsNonAdvancingPastOrUnconfirmedDroppedThresholdsWithoutWrites() {
        val row = job("dual", WorkTarget(22_000, "2026-11-01"))
        val before = store.snapshot()
        listOf(WorkTarget(22_000, "2026-12-01"), WorkTarget(20_800, "2026-12-01"),
            WorkTarget(23_000, "2026-10-01"), WorkTarget(23_000, "2026-11-01"), WorkTarget(23_000)).forEachIndexed { i, target ->
            assertThrows(IllegalArgumentException::class.java) { apply("invalid$i", finish(listOf(row), emptyList(), target)) }
            assertEquals(before, store.snapshot())
        }
        val drop = finish(listOf(row), emptyList(), WorkTarget(23_000)).copy(confirmDroppedThreshold = true)
        apply("drop-confirmed", drop)
        assertNull(visible().single { it.id == row.id }.effectiveDate)
        assertEquals("2026-11-01", visible().single { it.id == row.id }.originalDate)
    }

    @Test fun staleOrForeignActionsAndInvalidAnchorsCannotPartiallyCommit() {
        val rows = marks(); val before = store.snapshot()
        val valid = finish(rows, rows)
        val invalid = listOf(valid.copy(selected = valid.selected.mapIndexed { i, s -> if (i == 1) s.copy(revision = 99) else s }),
            valid.copy(performed = valid.performed.mapIndexed { i, a -> if (i == 1) a.copy(anchorChoice = AnchorChoice("wrong", 0)) else a }),
            valid.copy(performed = listOf(valid.performed.first().copy(obligationIds = rows.map { it.id }))))
        invalid.forEachIndexed { i, command ->
            assertThrows(IllegalArgumentException::class.java) { apply("bad$i", command) }; assertEquals(before, store.snapshot())
        }
    }

    @Test fun mileageWinningOilOrThresholdClearsOldCalendarAndAdvancesAnchorAtomically() = oilOrThreshold(false)
    @Test fun producerReconcileAlsoAcceptsExplicitEarlyOilCalendarWithReachedMileage() = oilOrThreshold(true)
    private fun oilOrThreshold(usePrimitive: Boolean) {
        val oil = marks().single { it.rule.component == "Engine oil" }
        val prior = MaintenanceHistory("oil-origin", "bike", null, oil.rule, null, null, null, null,
            "2025-12-15", "UTC", 15_000, Certainty.CONFIRMED, null, null, "Synthetic prior oil replacement",
            "Replace Engine oil", null, "Confirmed synthetic oil action", now)
        store.write("origin", WriteBatch(histories = listOf(prior)), now)
        val baseline = store.snapshot().obligations.single { it.occurrenceId == oil.id }.baselineId
        repo.qualifyAnchor("origin-anchor", baseline, prior.id, 0, false, now)
        repo.refresh("bike", "reading", LocalDate.of(2026, 3, 16), now)
        val calendar = visible().single { it.rule == oil.rule && it.originalDate != null }
        assertEquals("2026-06-15", calendar.originalDate)
        val rows = listOf(oil, calendar)
        val physical = PerformedDetails("2026-03-15", "UTC", 20_800, "Replace Engine oil", "Confirmed same physical oil replacement")
        val choice = AnchorChoice(baseline, 1)
        val outcome = ChecklistOutcome(selection(rows), listOf(PerformedAction(rows.map { it.id }, physical, choice)))
        val history = MaintenanceHistory("or-physical", "bike", oil.id, oil.rule, oil.originalKm, oil.originalDate,
            oil.effectiveKm, oil.effectiveDate, physical.date, physical.zone, physical.odometerKm, Certainty.CONFIRMED,
            null, null, physical.work, physical.work, null, physical.evidence, now, MaintenanceKind.PREVENTIVE)
        val before = store.snapshot()
        if (usePrimitive) {
            assertThrows(IllegalArgumentException::class.java) { repo.reconcile("no-anchor", history, selection(rows), now) }
            assertThrows(IllegalArgumentException::class.java) { repo.reconcile("before-origin", history.copy(performedDate = "2025-12-14"), selection(rows), now, choice) }
        } else {
            assertThrows(IllegalArgumentException::class.java) { apply("no-anchor", outcome.copy(performed = outcome.performed.map { it.copy(anchorChoice = null) })) }
            assertThrows(IllegalArgumentException::class.java) { apply("before-origin", outcome.copy(performed = outcome.performed.map {
                it.copy(details = physical.copy(date = "2025-12-14")) })) }
        }
        assertEquals(before, store.snapshot())
        val future = visible().single { it.rule == oil.rule && it.originalKm == 25_000L }
        if (usePrimitive) {
            assertThrows(IllegalArgumentException::class.java) { repo.reconcile("future-mark", history, selection(rows + future), now, choice) }
            assertTrue(repo.reconcile("or-complete", history, selection(rows), now, choice))
        } else {
            assertThrows(IllegalArgumentException::class.java) { apply("future-mark", outcome.copy(selected = selection(rows + future),
                performed = listOf(PerformedAction((rows + future).map { it.id }, physical, choice)))) }
            assertTrue(apply("or-complete", outcome))
        }
        reopen()
        if (usePrimitive) assertFalse(repo.reconcile("or-complete", history, selection(rows), now, choice))
        else assertFalse(apply("or-complete", outcome))
        val next = repo.refresh("bike", "reading", LocalDate.of(2026, 3, 16), now).pending.filter { it.groupId == baseline }
        assertEquals(listOf("2026-09-15"), next.mapNotNull { it.occurrence.originalDate })
        assertEquals(listOf(25_000L), next.mapNotNull { it.occurrence.originalKm })
        val satisfaction = store.snapshot().satisfactions.single { it.occurrenceId == calendar.id }
        assertEquals("2026-06-15", satisfaction.originalDate)
        assertEquals("2026-06-15", satisfaction.effectiveDate)
        assertEquals("2026-03-15", store.snapshot().histories.single { it.id == satisfaction.historyId }.performedDate)
        assertEquals(2, store.snapshot().anchors.maxOf { it.revision })
    }

    @Test fun concurrentSameOperationAndChronologicalBackdatedHistoryAreStable() {
        val rows = marks(); val command = finish(rows, rows)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val results = executor.invokeAll((1..6).map { Callable { apply("concurrent", command) } }).map { it.get() }
            assertEquals(1, results.count { it }); assertEquals(2, store.snapshot().histories.size)
        } finally { executor.shutdownNow() }
        val later = job("backdated")
        val historical = finish(listOf(later), listOf(later)).let { outcome -> outcome.copy(performed = outcome.performed.map {
            it.copy(details = it.details.copy(date = "2026-01-01")) }) }
        apply("perform-backdated", historical)
        assertEquals(listOf("2026-10-01", "2026-10-01", "2026-01-01"), repo.completedHistory("bike").map { it.performedDate })
    }
}
