package org.wastingnotime.motolog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.maintenance.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class MaintenanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "maintenance-test.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var store: LocalStore
    private lateinit var repo: MaintenanceRepository
    private lateinit var registration: RegistrationRepository
    private val today = LocalDate.of(2026, 10, 1)
    private val now = 1_800_000_000_000L
    @Before fun setup() { context.deleteDatabase(name); open() }
    private fun open() {
        db = MotoLogDatabase.open(context, name); store = LocalStore(db)
        repo = MaintenanceRepository(db); registration = RegistrationRepository(db)
    }
    private fun reopen() { db.close(); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun register(km: Long? = null, matched: Boolean = true) {
        registration.register("bike", 2017, matched, km?.let { reading("initial", it, now - 10_000) }, now)
    }
    private fun reading(id: String, km: Long, time: Long) = OdometerReading(id, "bike", km,
        Certainty.CONFIRMED, ReadingSource.OWNER, Observation(time, "UTC", 0), time)
    private fun key(name: String) = "bike:manual:$name:1"
    private fun rule(name: String) = FazerInventory.rules.getValue(name)
    private fun history(id: String, values: RuleValues, km: Long?, date: String?) = MaintenanceHistory(id,
        "bike", null, values, null, null, null, null, date, date?.let { "UTC" }, km, Certainty.CONFIRMED,
        null, null, "Synthetic performed action", "${values.action}: ${values.component}", null,
        "Explicit synthetic action and component evidence", now)
    private fun due(readingId: String? = "initial", date: LocalDate = today) = repo.refresh("bike", readingId, date, maxOf(now, readingId?.let { id -> store.snapshot().readings.single { it.id == id }.observed!!.epochMillis } ?: now))
    private fun marks(name: String) = store.snapshot().obligations.filter { it.baselineId == key(name) && it.branch == "mileage" }
        .map { link -> store.snapshot().occurrences.single { it.id == link.occurrenceId } }.sortedBy { it.originalKm }
    private fun putReading(id: String, km: Long, time: Long = now) {
        store.write("read:$id", WriteBatch(readings = listOf(reading(id, km, time))), now)
    }
    private fun select(vararg rows: MaintenanceOccurrence) = rows.map { ObligationSelection(it.id, it.revision) }

    @Test fun persistedOilAndFilterThresholdsIncludeBeforeAtAfterFirstAndReplayMarks() {
        register(999)
        val points = listOf(999L, 1_000L, 1_001L, 4_999L, 5_000L, 5_001L, 9_999L, 10_000L, 10_001L,
            14_999L, 15_000L, 15_001L, 19_999L, 20_000L, 20_001L, 29_999L, 30_000L, 30_001L)
        points.forEachIndexed { index, km ->
            putReading("point:$index", km, now + index)
            val model = due("point:$index")
            listOf("oil", "oil-filter").forEach { name ->
                val expectedMarks = if (name == "oil") listOf(1_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L)
                    else listOf(1_000L, 10_000L, 20_000L, 30_000L)
                expectedMarks.filter { kotlin.math.abs(it - km) <= 1 }.forEach { mark ->
                    val target = model.rules.single { it.item.baseline.id == key(name) }.pending.single { it.occurrence.originalKm == mark }
                    assertEquals("$name at $km", if (km < mark) DueStatus.NOT_DUE else DueStatus.DUE, target.effective.status)
                    assertEquals(rule(name).locator, target.occurrence.rule.locator)
                }
            }
        }
        assertFalse(marks("oil-filter").any { it.originalKm == 15_000L })
        assertFalse(marks("oil").any { it.originalKm == 2_000L })
    }

    @Test fun verifiedNumericConditionalBranchesPersistAndRemainReviewBelowThreshold() {
        register(100)
        val names = listOf("brake-hoses", "air-filter", "chain-care")
        val histories = names.map { history("prior:$it", rule(it), 100, "2022-10-01") }
        store.write("conditional-priors", WriteBatch(histories = histories), now)
        names.forEach { repo.qualifyAnchor("anchor:$it", key(it), "prior:$it", 0, false, now) }
        val below = due(date = LocalDate.of(2026, 9, 30))
        names.forEach { name ->
            val evaluation = below.rules.single { it.item.baseline.id == key(name) }.evaluation
            assertEquals(DueStatus.MANUAL_REVIEW, evaluation.status); assertTrue(evaluation.manualReview)
        }
        listOf(599L, 600L, 601L, 40_099L, 40_100L, 40_101L).forEachIndexed { index, km ->
            putReading("numeric:$index", km, now + index)
            val model = due("numeric:$index")
            listOf("chain-care" to 600L, "air-filter" to 40_100L).forEach { (name, threshold) ->
                val value = model.rules.single { it.item.baseline.id == key(name) }
                assertEquals(if (km >= threshold) DueStatus.DUE else DueStatus.MANUAL_REVIEW, value.evaluation.status)
                assertTrue(value.evaluation.manualReview)
                assertEquals(threshold, value.pending.single().occurrence.originalKm)
            }
        }
        listOf(-1L, 0L, 1L).forEach { days ->
            val value = due("numeric:5", today.plusDays(days)).rules.single { it.item.baseline.id == key("brake-hoses") }
            assertEquals(if (days < 0) DueStatus.MANUAL_REVIEW else DueStatus.DUE, value.evaluation.status)
            assertTrue(value.evaluation.manualReview)
            assertEquals("2026-10-01", value.pending.single().occurrence.originalDate)
        }
        reopen()
        assertEquals(3, store.snapshot().anchors.size)
        assertTrue(store.snapshot().histories.all { it.performedWork.contains(it.rule.action) })
    }

    @Test(timeout = 10_000) fun largeStorageValidJumpFailsBeforeWritesAndFailsSafelyAfterRestart() {
        register(999); due()
        putReading("large-jump", 999_999_000_000)
        val saved = store.snapshot()
        val failure = assertThrows(MilestoneReviewRequired::class.java) { due("large-jump") }
        assertTrue(failure.message!!.contains("requires review"))
        assertEquals(saved, store.snapshot())
        reopen()
        assertThrows(MilestoneReviewRequired::class.java) { due("large-jump") }
        assertEquals(saved, store.snapshot())
        assertEquals(listOf(1_000L), marks("oil").map { it.originalKm })
    }

    @Test fun noReadingPlaceholderIsUnadoptedThenUsedBikeAdoptsOnlyCurrentFrontier() {
        register()
        val initial = due(null)
        assertTrue(store.snapshot().obligations.isEmpty())
        assertEquals(BranchStatus.UNKNOWN, initial.rules.single { it.item.baseline.id == key("oil") }.evaluation.mileage)
        reopen()
        putReading("first-real", 61_234)
        due("first-real")
        assertEquals(listOf(60_000L, 65_000L), marks("oil").map { it.originalKm })
        assertEquals(listOf(60_000L, 70_000L), marks("oil-filter").map { it.originalKm })
        assertTrue(store.snapshot().histories.isEmpty())
        assertTrue(store.snapshot().satisfactions.isEmpty())
        val saved = store.snapshot(); reopen(); due("first-real")
        assertEquals(saved, store.snapshot())
    }

    @Test fun genuinelyAdoptedFirstServiceRemainsAndReplayDoesNotInventFilterAtFifteenThousand() {
        register(999); due()
        assertEquals(listOf(1_000L), marks("oil").map { it.originalKm })
        putReading("later", 15_001); val model = due("later")
        assertEquals(listOf(1_000L, 5_000L, 10_000L, 15_000L, 20_000L), marks("oil").map { it.originalKm })
        assertEquals(listOf(1_000L, 10_000L, 20_000L), marks("oil-filter").map { it.originalKm })
        assertTrue(model.pending.filter { it.occurrence.originalKm == 1_000L }.all { it.effective.status == DueStatus.DUE })
        assertTrue(marks("oil").all { it.rule.locator!!.contains("PDF 57") })
        assertTrue(marks("oil-filter").all { it.rule.locator!!.contains("PDF 52") })
        assertTrue(store.snapshot().histories.isEmpty())
    }

    @Test fun lateOilAndDistinctFilterCompletionPreserveAbsoluteTargetsAndReplay() {
        register(20_800); due()
        val oil = marks("oil").first()
        val filter = marks("oil-filter").first()
        val performed = history("oil-work", rule("oil"), 20_800, "2026-09-30")
        assertThrows(IllegalArgumentException::class.java) {
            repo.reconcile("wrong-action", performed, select(oil, filter), now)
        }
        assertTrue(store.snapshot().histories.isEmpty())
        val before = store.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            repo.reconcile("stale-anchor", performed, select(oil), now, AnchorChoice(key("oil"), 2))
        }
        assertEquals(before, store.snapshot())
        assertTrue(repo.reconcile("oil-done", performed, select(oil), now, AnchorChoice(key("oil"), 0)))
        reopen()
        assertFalse(repo.reconcile("oil-done", performed, select(oil), now, AnchorChoice(key("oil"), 0)))
        assertEquals(OccurrenceState.PENDING, store.snapshot().occurrences.single { it.id == filter.id }.state)
        assertEquals(performed.id, store.snapshot().anchors.single().historyId)
        val filterWork = history("filter-work", rule("oil-filter"), 20_800, "2026-09-30")
        repo.reconcile("filter-done", filterWork, select(filter), now)
        due()
        assertEquals(listOf(25_000L), due().rules.single { it.item.baseline.id == key("oil") }.pending
            .mapNotNull { it.occurrence.originalKm })
        assertEquals(listOf(30_000L), due().rules.single { it.item.baseline.id == key("oil-filter") }.pending
            .mapNotNull { it.occurrence.originalKm })
        assertTrue(store.snapshot().obligations.none { it.baselineId == key("oil-filter") && it.branch == "calendar" })
        assertThrows(IllegalArgumentException::class.java) { repo.reconcile("duplicate", performed.copy(id = "other"), select(oil), now) }
        assertThrows(IllegalArgumentException::class.java) { repo.reconcile("future", performed.copy(id = "early"), select(marks("oil").last()), now) }
    }

    @Test fun postponedOlderMarkAndLaterMarkStayLinkedUntilExplicitlyReconciledTogether() {
        register(15_000); due()
        val old = marks("oil").first()
        val postponed = old.copy(effectiveKm = 26_000, state = OccurrenceState.POSTPONED, revision = 1,
            changeReason = "Synthetic owner postponement")
        store.write("postpone", WriteBatch(occurrenceChanges = listOf(postponed)), now)
        putReading("later", 25_800); val model = due("later")
        val oil = model.rules.single { it.item.baseline.id == key("oil") }
        val older = oil.pending.single { it.occurrence.id == old.id }
        assertEquals(DueStatus.DUE, older.originally.status)
        assertEquals(DueStatus.NOT_DUE, older.effective.status)
        assertEquals(3, oil.pending.count { it.occurrence.originalKm!! <= 25_000 })
        val later = marks("oil").filter { it.originalKm!! <= 25_000 }
        val performed = history("overlap-work", rule("oil"), 25_800, "2026-09-30")
        repo.reconcile("overlap", performed, later.map { ObligationSelection(it.id, it.revision) }, now)
        val saved = store.snapshot(); reopen()
        assertEquals(saved, store.snapshot())
        assertEquals(3, saved.satisfactions.size)
        assertEquals(15_000L, saved.satisfactions.single { it.occurrenceId == old.id }.originalKm)
        assertEquals(26_000L, saved.satisfactions.single { it.occurrenceId == old.id }.effectiveKm)
        assertEquals(15_000L, store.occurrenceRevisions().first { it.occurrenceId == old.id }.effectiveKm)
        assertFalse(repo.reconcile("overlap", performed, later.map { ObligationSelection(it.id, it.revision) }, now))
        assertEquals(listOf(30_000L), due("later").rules.single { it.item.baseline.id == key("oil") }.pending.map { it.occurrence.originalKm })
    }

    @Test fun newerDateOnlyReplacementUsesOneAnchorAndNeverBorrowsOlderMileage() {
        register(50_000)
        val custom = RuleValues("Replace", "Synthetic dual interval", SourceKind.CUSTOM,
            sourceIdentity = "Owner", baselineRevision = 1, scheduleKind = ScheduleKind.INTERVAL,
            intervalKm = 2_000, intervalMonths = 3)
        registration.addItem("dual", "bike", custom, now)
        val values = store.snapshot().baselines.single { it.id == "dual" }.rule
        val old = history("older", values, 20_000, "2026-01-31")
        val newer = history("newer", values, null, "2026-08-31")
        store.write("prior", WriteBatch(histories = listOf(old, newer)), now)
        repo.qualifyAnchor("older-anchor", "dual", old.id, 0, false, now)
        due() // An adopted prior obligation remains explicit, separate from current recurrence.
        repo.qualifyAnchor("newer-anchor", "dual", newer.id, 1, false, now)
        val current = due().rules.single { it.item.baseline.id == "dual" }
        assertEquals("newer", current.anchorHistoryId)
        assertEquals(DueStatus.UNKNOWN, current.evaluation.status)
        assertEquals(BranchStatus.UNKNOWN, current.evaluation.mileage)
        assertEquals(BranchStatus.BELOW, current.evaluation.calendar)
        val currentPending = current.pending.single { !it.priorAnchorNeedsReconciliation }
        assertNull(currentPending.occurrence.originalKm)
        assertTrue(current.pending.any { it.priorAnchorNeedsReconciliation })
        assertEquals("newer", store.snapshot().obligations.single { it.baselineId == "dual" && it.anchorDateId == "newer" }.anchorDateId)
        assertThrows(IllegalArgumentException::class.java) { repo.qualifyAnchor("backdate", "dual", old.id, 2, false, now) }
        reopen()
        assertEquals(DueStatus.UNKNOWN, due().rules.single { it.item.baseline.id == "dual" }.evaluation.status)
        val uncertainOrder = history("missing-date", values, 50_000, null)
        store.write("unknown-order", WriteBatch(histories = listOf(uncertainOrder)), now)
        assertThrows(IllegalArgumentException::class.java) { repo.qualifyAnchor("choose", "dual", uncertainOrder.id, 2, false, now) }
        repo.qualifyAnchor("choose-reviewed", "dual", uncertainOrder.id, 2, true, now)
        assertEquals(BranchStatus.UNKNOWN, due().rules.single { it.item.baseline.id == "dual" }.evaluation.calendar)
    }

    @Test fun oilCalendarAndMileageOverlapReconcileExplicitlyWithoutShiftingGrid() {
        register(999)
        val prior = history("calendar-origin", rule("oil"), null, "2024-08-31")
        store.write("prior-oil", WriteBatch(histories = listOf(prior)), now)
        repo.qualifyAnchor("calendar-anchor", key("oil"), prior.id, 0, false, now)
        val before = due(date = LocalDate.of(2025, 2, 27)).rules.single { it.item.baseline.id == key("oil") }
        assertEquals(BranchStatus.BELOW, before.evaluation.calendar)
        val at = due(date = LocalDate.of(2025, 2, 28)).rules.single { it.item.baseline.id == key("oil") }
        assertEquals(DueStatus.DUE, at.evaluation.status)
        putReading("first-service", 1_001)
        val pending = due("first-service", LocalDate.of(2025, 3, 1)).rules.single { it.item.baseline.id == key("oil") }.pending
        val reached = pending.filter { it.originally.status == DueStatus.DUE }.map { it.occurrence }
        assertEquals(2, reached.size)
        val completion = history("one-oil-service", rule("oil"), 1_001, "2025-03-01")
        repo.reconcile("time-and-mileage", completion, reached.map { ObligationSelection(it.id, it.revision) }, now, AnchorChoice(key("oil"), 1))
        val next = due("first-service", LocalDate.of(2025, 3, 2)).rules.single { it.item.baseline.id == key("oil") }
        assertEquals(listOf(5_000L), next.pending.mapNotNull { it.occurrence.originalKm })
        assertEquals(listOf("2025-09-01"), next.pending.mapNotNull { it.occurrence.originalDate })
        assertEquals(DueStatus.NOT_DUE, next.evaluation.status)
        assertEquals(2, store.snapshot().satisfactions.size)
    }

    @Test fun unknownOilTimeDoesNotBlockMileageAndUnsupportedRulesNeverDisappear() {
        register(999)
        val first = due()
        val oil = first.rules.single { it.item.baseline.id == key("oil") }
        assertEquals(DueStatus.UNKNOWN, oil.evaluation.status)
        assertEquals(BranchStatus.UNKNOWN, oil.evaluation.calendar)
        assertEquals(DueStatus.NOT_DUE, first.rules.single { it.item.baseline.id == key("oil-filter") }.evaluation.status)
        assertEquals(4, first.rules.count { it.support == RuleSupport.MANUAL_REVIEW })
        assertTrue(first.rules.filter { it.support == RuleSupport.MANUAL_REVIEW }.all { it.evaluation.status == DueStatus.MANUAL_REVIEW })
        putReading("at", 1_000)
        assertEquals(DueStatus.DUE, due("at").rules.single { it.item.baseline.id == key("oil") }.evaluation.status)
        assertTrue(first.coverage.contains("cannot establish"))
    }

    @Test fun carriedDualIntervalCanHaveOneEffectiveThresholdWithoutLosingOriginalDueStatus() {
        register(22_100)
        registration.addItem("dual-remainder", "bike", RuleValues("Replace", "Synthetic remainder", SourceKind.CUSTOM,
            scheduleKind = ScheduleKind.INTERVAL, intervalKm = 2_000, intervalMonths = 3), now)
        val values = store.snapshot().baselines.single { it.id == "dual-remainder" }.rule
        val prior = history("dual-prior", values, 20_000, "2026-01-31")
        store.write("dual-history", WriteBatch(histories = listOf(prior)), now)
        repo.qualifyAnchor("dual-anchor", "dual-remainder", prior.id, 0, false, now)
        val parent = due().rules.single { it.item.baseline.id == "dual-remainder" }.pending.single().occurrence
        val remainder = parent.copy(id = "dual-carried", parentId = parent.id, effectiveKm = 23_000, effectiveDate = null)
        store.write("dual-carry", WriteBatch(occurrences = listOf(remainder), occurrenceChanges = listOf(
            parent.copy(state = OccurrenceState.POSTPONED, revision = 1))), now)
        val projected = due().pending.single { it.occurrence.id == remainder.id }
        assertEquals(DueStatus.DUE, projected.originally.status)
        assertEquals(DueStatus.NOT_DUE, projected.effective.status)
        assertEquals(BranchStatus.NOT_APPLICABLE, projected.effective.calendar)
        assertEquals("dual-remainder", projected.groupId)
        assertFalse(due().pending.any { it.occurrence.id == parent.id })
    }

    @Test fun templateEditsAndProgrammedRemaindersKeepOriginalSnapshotsAndOutstandingWork() {
        register(20_800); due()
        val original = marks("oil").first()
        val work = history("record", rule("oil"), 20_800, "2026-09-01")
        repo.reconcile("record", work, select(original), now)
        val oldFilter = marks("oil-filter").first()
        val parent = MaintenanceOccurrence("programmed", "bike", null,
            RuleValues("Repair", "Synthetic component", SourceKind.CUSTOM), null, null, null, null,
            kind = MaintenanceKind.PROGRAMMED)
        store.write("job", WriteBatch(occurrences = listOf(parent)), now)
        store.write("carry", WriteBatch(occurrenceChanges = listOf(parent.copy(state = OccurrenceState.POSTPONED,
            revision = 1)), occurrences = listOf(parent.copy(id = "remainder", parentId = parent.id, effectiveKm = 30_000))), now)
        registration.editItem(key("oil-filter"), 0, rule("oil-filter").copy(scheduleKind = ScheduleKind.INTERVAL, intervalKm = 500), now)
        reopen()
        val model = due()
        assertTrue(model.pending.any { it.occurrence.id == oldFilter.id && it.occurrence.rule == oldFilter.rule })
        assertFalse(model.pending.any { it.occurrence.id == parent.id })
        assertEquals(DueStatus.NOT_DUE, model.pending.single { it.occurrence.id == "remainder" }.effective.status)
        assertEquals(work, store.snapshot().histories.single())
        assertEquals(original.rule, store.snapshot().occurrences.single { it.id == original.id }.rule)
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("DELETE FROM ObligationSatisfaction") }
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("UPDATE PreventiveObligation SET branch = 'changed'") }
    }

    @Test fun admissionAndProvenanceFailuresCannotCreateQualifyingWorkAndConcurrentRefreshIsStable() {
        register(20_800, false)
        assertTrue(due().rules.isEmpty()); assertTrue(store.snapshot().occurrences.isEmpty())
        registration.confirmManual("bike", 2017, now)
        val wrong = history("wrong", rule("oil").copy(component = "Different component"), 20_000, "2026-08-01")
        val uncertain = history("uncertain", rule("oil"), 20_000, "2026-08-01").copy(certainty = Certainty.UNCERTAIN)
        store.write("bad-evidence", WriteBatch(histories = listOf(wrong, uncertain)), now)
        listOf(wrong, uncertain).forEach { entry ->
            assertThrows(IllegalArgumentException::class.java) { repo.qualifyAnchor("qualify:${entry.id}", key("oil"), entry.id, 0, false, now) }
        }
        assertTrue(store.snapshot().anchors.isEmpty())
        val executor = Executors.newFixedThreadPool(3)
        try { executor.invokeAll((1..6).map { Callable { due() } }).forEach { it.get() } }
        finally { executor.shutdownNow() }
        assertEquals(4, store.snapshot().obligations.size)
        val saved = store.snapshot(); due(); assertEquals(saved, store.snapshot())
    }
}
