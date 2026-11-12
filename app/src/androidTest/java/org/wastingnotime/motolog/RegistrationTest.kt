package org.wastingnotime.motolog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.maintenance.FazerInventory
import org.wastingnotime.motolog.domain.model.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RegistrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "registration-test.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var repository: RegistrationRepository
    private val now = 1_780_000_000_000L
    @Before fun setup() { context.deleteDatabase(name); open() }
    private fun open() { db = MotoLogDatabase.open(context, name); repository = RegistrationRepository(db) }
    private fun reopen() { db.close(); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun register(match: Boolean = false) = repository.register("synthetic-bike", if (match) 2016 else null, match, null, now)
    private fun draft(id: String = "row", kind: EntryKind = EntryKind.NOTE) = SetupEntry(id, "synthetic-bike", kind,
        "Synthetic source note", action = "Inspect", component = "Synthetic component")

    @Test fun unknownSetupSurvivesReopenAndSingleBikeFailureIsAtomic() {
        assertTrue(register())
        reopen()
        val saved = repository.snapshot()
        assertEquals("synthetic-bike", saved.bikes.single().id)
        assertFalse(saved.bikes.single().manualConfirmed)
        assertNull(saved.bikes.single().manufactureYear)
        assertTrue(saved.baselines.isEmpty())
        assertTrue(saved.readings.isEmpty())
        assertTrue(saved.histories.isEmpty())
        assertFalse(register())
        assertThrows(Exception::class.java) {
            repository.register("second-bike", 2017, true, null, now)
        }
        assertEquals(saved, repository.snapshot())
        assertThrows(IllegalArgumentException::class.java) { repository.confirmManual("wrong-bike", 2016, now) }
        repository.confirmManual("synthetic-bike", 2016, now)
        val admitted = repository.snapshot()
        assertTrue(admitted.bikes.single().manualConfirmed)
        assertEquals(FazerInventory.rules.size, admitted.baselines.size)
        reopen()
        repository.confirmManual("synthetic-bike", 2016, now)
        assertEquals(admitted, repository.snapshot())
        assertTrue(admitted.histories.isEmpty())
    }

    @Test fun confirmedSeedsHaveExactLocatorsAndDoNotInventIntervalsOrAnchors() {
        register(true)
        val saved = repository.snapshot()
        saved.baselines.forEach { baseline ->
            assertEquals(SourceKind.MANUAL, baseline.rule.sourceKind)
            assertTrue(baseline.rule.sourceIdentity!!.contains(FazerInventory.sha256))
            assertTrue(baseline.rule.locator!!.contains("printed"))
            assertTrue(baseline.rule.locator.contains("PDF"))
        }
        val oil = saved.baselines.single { it.id.contains(":oil:") }.rule
        val filter = saved.baselines.single { it.id.contains(":oil-filter:") }.rule
        assertEquals(ScheduleKind.MILESTONE, oil.scheduleKind)
        assertNull(oil.intervalKm)
        assertNull(filter.intervalMonths)
        assertEquals(ScheduleKind.CONDITION, saved.baselines.single { it.id.contains(":chain-care:") }.rule.scheduleKind)
        assertFalse(saved.baselines.any { it.rule.component.contains("Valve") })
        assertTrue(saved.occurrences.isEmpty())
        assertTrue(saved.histories.isEmpty())
    }

    @Test fun intervalOnlyEditsKeepOriginalBaselineAndOwnerItemsKeepOwnerSource() {
        register(true)
        val baseline = repository.snapshot().baselines.single { it.rule.component == "External fuel filter" }
        repository.editItem(baseline.id, 0, baseline.rule.copy(intervalKm = 12_000), now)
        reopen()
        var item = repository.items(repository.snapshot()).single { it.baseline.id == baseline.id }
        assertEquals(baseline, item.baseline)
        assertEquals(15_000L, item.baseline.rule.intervalKm)
        assertEquals(12_000L, item.effective.intervalKm)
        assertEquals("Custom", item.label)
        assertTrue(item.source.contains("6-4"))
        assertEquals(1, item.effective.overrideRevision)
        assertThrows(IllegalArgumentException::class.java) {
            repository.editItem(baseline.id, 0, baseline.rule.copy(component = "Stale"), now)
        }
        repository.editItem(baseline.id, 1, item.effective.copy(action = "Owner action"), now)
        repository.addItem("owner-item", "synthetic-bike", RuleValues("Inspect", "Synthetic accessory", SourceKind.CUSTOM,
            scheduleKind = ScheduleKind.INTERVAL, intervalMonths = 3), now)
        item = repository.items(repository.snapshot()).single { it.baseline.id == "owner-item" }
        assertEquals("Owner", item.source)
        assertEquals("Custom", item.label)
        repository.editItem("owner-item", 0, item.effective.copy(intervalMonths = 4), now)
        reopen()
        assertEquals(3, repository.snapshot().overrides.size)
        assertEquals("Owner", repository.items(repository.snapshot()).single { it.baseline.id == "owner-item" }.source)
    }

    @Test fun previewCancelUnknownHistoryPendingJobsAndConfirmedImportRetryAreDistinct() {
        register(true)
        val before = repository.snapshot()
        repository.preview(draft(), now)
        assertEquals(before, repository.snapshot()) // Preview/cancel has no effect.
        val note = repository.preview(draft(), now)
        assertTrue(repository.confirm(note, false, now))
        assertFalse(repository.confirm(note, false, now))
        val saved = repository.snapshot()
        assertEquals(1, saved.importRows.size)
        assertTrue(saved.histories.isEmpty())
        assertTrue(saved.readings.isEmpty())
        val jobs = (1..3).map { number -> repository.preview(draft("job-$number", EntryKind.PROGRAMMED).copy(note = "Synthetic pending $number", component = "Synthetic component $number"), now) }
        jobs.forEach { repository.confirm(it, false, now) }
        reopen()
        assertEquals(3, repository.snapshot().occurrences.size)
        repository.snapshot().occurrences.forEach {
            assertEquals(MaintenanceKind.PROGRAMMED, it.kind)
            assertEquals(OccurrenceState.PENDING, it.state)
            assertNull(it.originalKm); assertNull(it.originalDate)
            assertNull(it.effectiveKm); assertNull(it.effectiveDate)
        }
        assertTrue(repository.snapshot().histories.isEmpty())
        val duplicate = repository.preview(draft("different-id"), now)
        assertTrue(duplicate.duplicateCandidates.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(duplicate, false, now) }
        assertTrue(repository.confirm(duplicate, true, now))
    }

    @Test fun historicalReadingsKeepObservationAndUncertainWorkDoesNotQualify() {
        register(true)
        val observation = Observation(now - 86_400_000 * 10L, "UTC", 0)
        val reading = repository.preview(draft(kind = EntryKind.READING).copy(
            km = 20_000, observed = observation, certainty = Certainty.CONFIRMED), now)
        repository.confirm(reading, false, now)
        val baseline = repository.snapshot().baselines.single { it.rule.component == "Brake fluid" }
        val uncertain = repository.preview(draft("service", EntryKind.PERFORMED).copy(
            note = "Synthetic ambiguous service", baselineId = baseline.id, certainty = Certainty.UNCERTAIN,
            date = null, km = null, cost = null), now)
        repository.confirm(uncertain, false, now)
        reopen()
        val saved = repository.snapshot()
        assertEquals(observation, saved.readings.single().observed)
        assertEquals(now, saved.readings.single().recordedAt)
        val history = saved.histories.single()
        assertNull(history.anchorEvidence); assertNull(history.performedDate)
        assertNull(history.costBrl); assertNull(history.odometerKm)
        repository.editItem(baseline.id, 0, baseline.rule.copy(intervalMonths = 12), now)
        assertEquals(history, repository.snapshot().histories.single())
        assertThrows(IllegalArgumentException::class.java) {
            repository.preview(draft("bad", EntryKind.READING).copy(certainty = Certainty.CONFIRMED), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.preview(draft("future", EntryKind.READING).copy(observed = observation.copy(epochMillis = now + 1)), now)
        }
    }

    @Test fun conflictingHistoricalReadingRollsBackSourceRowAndConcurrentEditHasOneWinner() {
        register(true)
        fun reading(id: String, km: Long, time: Long) = repository.preview(draft(id, EntryKind.READING).copy(
            note = "Synthetic $id", km = km, observed = Observation(time, "UTC", 0), certainty = Certainty.CONFIRMED), now)
        repository.confirm(reading("later", 20_000, now - 1), false, now)
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(reading("earlier", 21_000, now - 2), false, now) }
        assertEquals(before, repository.snapshot())
        val baseline = before.baselines.first()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = executor.invokeAll((1..2).map { count -> Callable {
                runCatching { repository.editItem(baseline.id, 0, baseline.rule.copy(component = "Synthetic $count"), now) }.isSuccess
            } }).map { it.get() }
            assertEquals(1, outcomes.count { it })
        } finally { executor.shutdownNow() }
        assertEquals(1, repository.snapshot().overrides.size)
    }

    @Test fun duplicatesCommittedAfterPreviewMustAlsoBeReviewed() {
        register()
        val first = repository.preview(draft("first"), now)
        val second = repository.preview(draft("second"), now)
        repository.confirm(first, false, now)
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(second, false, now) }
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(second, true, now) }
        val refreshed = repository.preview(draft("second"), now)
        assertTrue(refreshed.duplicateCandidates.any { it.contains("Synthetic source note") })
        assertTrue(repository.confirm(refreshed, true, now))
    }
    @Test fun failedManualAdmissionCannotLeaveConfirmedBikeOrPartialSeeds() {
        register()
        val (key, rule) = FazerInventory.rules.entries.first()
        LocalStore(db).write("synthetic-collision", WriteBatch(baselines = listOf(
            Baseline("synthetic-bike:manual:$key:1", "synthetic-bike", 1, rule))), now)
        val before = repository.snapshot()
        assertThrows(Exception::class.java) { repository.confirmManual("synthetic-bike", 2016, now) }
        reopen()
        assertEquals(before, repository.snapshot())
        assertFalse(repository.snapshot().bikes.single().manualConfirmed)
    }

    @Test fun confirmedInitialReadingAndPerformedEvidenceKeepValuesAndRejectFutureSetup() {
        val observation = Observation(now - 1_000, "UTC", 0)
        val reading = OdometerReading("initial", "synthetic-bike", 20_000, Certainty.CONFIRMED,
            ReadingSource.OWNER, observation, now)
        assertThrows(IllegalArgumentException::class.java) { repository.register("synthetic-bike", null, true, null, now) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.register("synthetic-bike", 2016, true, reading.copy(observed = observation.copy(epochMillis = now + 1)), now)
        }
        assertTrue(repository.snapshot().bikes.isEmpty())
        repository.register("synthetic-bike", 2016, true, reading, now)
        val baseline = repository.snapshot().baselines.single { it.rule.component == "External fuel filter" }
        val preview = repository.preview(draft("performed", EntryKind.PERFORMED).copy(note = "Synthetic exact replacement evidence",
            certainty = Certainty.CONFIRMED, date = "2026-01-02", km = 19_000, cost = "0.00", baselineId = baseline.id), now)
        repository.confirm(preview, false, now)
        reopen()
        val history = repository.snapshot().histories.single()
        assertEquals("0", history.costBrl)
        assertEquals("2026-01-02", history.performedDate)
        assertEquals(19_000L, history.odometerKm)
        assertNotNull(history.anchorEvidence)
        assertEquals(baseline.rule, history.rule)
        assertEquals(reading, repository.snapshot().readings.single())
    }

    @Test fun sameObservationAndOpenActionCandidatesRequireReviewEvenWithDifferentNotes() {
        register()
        val first = repository.preview(draft("reading", EntryKind.READING).copy(km = 20_000,
            observed = Observation(now - 1_000, "UTC", 0), certainty = Certainty.CONFIRMED), now)
        repository.confirm(first, false, now)
        val second = repository.preview(draft("other-reading", EntryKind.READING).copy(note = "Synthetic other reading note",
            km = 20_000, observed = Observation(now - 1_000, "UTC", 0), certainty = Certainty.CONFIRMED), now)
        assertTrue(second.duplicateCandidates.any { it.contains("reading 20000") })
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(second, false, now) }
        val job = repository.preview(draft("job", EntryKind.PROGRAMMED).copy(note = "Synthetic open work"), now)
        repository.confirm(job, false, now)
        val otherJob = repository.preview(draft("other-job", EntryKind.PROGRAMMED).copy(note = "Synthetic different note"), now)
        assertTrue(otherJob.duplicateCandidates.any { it.contains("open Inspect") })
        assertThrows(IllegalArgumentException::class.java) { repository.confirm(otherJob, false, now) }
    }

}
