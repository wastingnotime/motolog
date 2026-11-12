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
import org.wastingnotime.motolog.domain.model.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "foundation-test.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var store: LocalStore
    private val observed = Observation(1_700_000_000_000, "UTC", 0)
    private val custom = RuleValues("Inspect", "Synthetic component", SourceKind.CUSTOM)
    private val manual = RuleValues("Replace", "Synthetic component", SourceKind.MANUAL,
        "Synthetic source", "test edition", "test table", 1)

    @Before fun setup() {
        context.deleteDatabase(name)
        db = MotoLogDatabase.open(context, name)
        store = LocalStore(db)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun reopen() { db.close(); db = MotoLogDatabase.open(context, name); store = LocalStore(db) }
    private fun bike() { store.write("setup", WriteBatch(bikes = listOf(Bike("bike"))), observed.epochMillis) }
    private fun reading(id: String = "reading", km: Long = 20_000, time: Long = observed.epochMillis) =
        OdometerReading(id, "bike", km, Certainty.CONFIRMED, ReadingSource.OWNER,
            observed.copy(epochMillis = time), time)
    private fun occurrence(id: String = "occurrence") = MaintenanceOccurrence(id, "bike", null,
        manual, 21_000, "2024-01-01", 21_000, "2024-01-01")
    private fun history(id: String = "history", occurrenceId: String? = "occurrence") =
        MaintenanceHistory(id, "bike", occurrenceId, manual, 21_000, "2024-01-01",
            22_000, "2024-02-01", null, null, null, Certainty.UNKNOWN, null, null,
            "Synthetic service", "Synthetic performed action", null, null, observed.epochMillis)
    private fun ride(id: String = "ride") = RideSession(id, "bike", RideState.ACTIVE, 1,
        RideOwnership.MANUAL, observed, null, "test-v1", observed.epochMillis)
    private fun sample() = RideSample("sample", "bike", "ride", 0, observed,
        "synthetic-epoch", 1_000, 0.0, 0.0, 5.0, 10.0, 1.0, true, observed.epochMillis)

    @Test fun representativeRecordsSurviveReopenAndKeepUnknownsAndProvenance() {
        val batch = WriteBatch(
            bikes = listOf(Bike("bike")),
            baselines = listOf(Baseline("baseline-v1", "bike", 1, manual)),
            overrides = listOf(OwnerOverride("override-v1", "bike", "baseline-v1", 1, custom)),
            readings = listOf(reading(), OdometerReading("uncertain", "bike", null,
                Certainty.UNCERTAIN, ReadingSource.OWNER_IMPORT, null, observed.epochMillis,
                "Synthetic ambiguous note")),
            occurrences = listOf(occurrence()), histories = listOf(history()),
            annotations = listOf(HistoryAnnotation("annotation", "bike", "history", "Additional evidence", 1)),
            fuels = listOf(FuelEvent("fuel", "bike", "reading", FuelMode.LITERS, "100", "20", observed, 1)),
            rides = listOf(ride()), samples = listOf(sample()),
            gaps = listOf(RideGap("gap", "bike", "ride", "MISSING_FIX", 3, observed, null)),
            importRows = listOf(ImportRow("import-row", "bike", "import", "row-1", "Synthetic note", Certainty.UNKNOWN)))
        assertTrue(store.write("all", batch, 1))
        val saved = store.snapshot()
        reopen()
        assertEquals(saved, store.snapshot())
        assertNull(store.snapshot().histories.single().costBrl)
        assertNull(store.snapshot().histories.single().performedDate)
        assertEquals(SourceKind.MANUAL, store.snapshot().histories.single().rule.sourceKind)
        assertEquals(20_000L, store.snapshot().readings.first { it.id == "reading" }.kilometers)
        assertFalse(store.write("all", batch, 2))
    }

    @Test fun partialCompletionRollsBackLateFailureAndRetryHasOneOutcome() {
        bike()
        val original = occurrence()
        store.write("pending", WriteBatch(occurrences = listOf(original)), 1)
        val batch = WriteBatch(
            occurrences = listOf(original.copy(id = "remainder", parentId = original.id,
                effectiveKm = 22_000, effectiveDate = null)),
            histories = listOf(history()),
            fuels = listOf(FuelEvent("bad-link", "bike", "missing", FuelMode.LITERS, "100", "20", observed, 1)),
            occurrenceChanges = listOf(original.copy(state = OccurrenceState.POSTPONED, revision = 1)))
        assertThrows(Exception::class.java) { store.write("partial", batch, 1) }
        assertEquals(listOf(original), store.snapshot().occurrences)
        assertTrue(store.snapshot().histories.isEmpty())
        val valid = batch.copy(fuels = emptyList())
        assertTrue(store.write("partial", valid, 2))
        reopen()
        assertFalse(store.write("partial", valid, 3))
        assertEquals(2, store.snapshot().occurrences.size)
        assertEquals(1, store.snapshot().histories.size)
        assertEquals(1, store.occurrenceRevisions().size)
        assertEquals(21_000L, store.occurrenceRevisions().single().effectiveKm)
        assertEquals(0, store.occurrenceRevisions().single().revision)
        assertEquals("occurrence", store.snapshot().occurrences.first { it.id == "remainder" }.parentId)
        assertThrows(OperationConflict::class.java) { store.write("partial", WriteBatch(), 4) }
    }

    @Test fun staleChangesCannotPartiallyCommitAndCompletedHistoryCannotMutate() {
        bike()
        val original = occurrence()
        store.write("pending", WriteBatch(occurrences = listOf(original)), 1)
        store.write("complete", WriteBatch(histories = listOf(history()), occurrenceChanges =
            listOf(original.copy(state = OccurrenceState.COMPLETED, revision = 1))), 2)
        assertThrows(StaleRevision::class.java) {
            store.write("stale", WriteBatch(histories = listOf(history("duplicate")),
                occurrenceChanges = listOf(original.copy(revision = 1))), 3)
        }
        assertThrows(Exception::class.java) {
            db.openHelper.writableDatabase.execSQL("UPDATE MaintenanceHistory SET description = 'changed'")
        }
        assertThrows(Exception::class.java) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM MaintenanceHistory")
        }
        reopen()
        assertEquals(1, store.snapshot().histories.size)
        assertEquals("Synthetic service", store.snapshot().histories.single().description)
    }

    @Test fun gpsSamplesAndRideChangesCannotOverwriteOwnerOdometer() {
        bike()
        store.write("reading", WriteBatch(readings = listOf(reading()), rides = listOf(ride())), 1)
        store.write("gps", WriteBatch(samples = listOf(sample()), rideChanges =
            listOf(ride().copy(revision = 1, reducerState = "Synthetic checkpoint"))), 2)
        reopen()
        assertEquals(listOf(reading()), store.snapshot().readings)
        assertEquals(1, store.snapshot().samples.size)
        assertThrows(IllegalArgumentException::class.java) {
            store.write("backwards", WriteBatch(readings = listOf(reading("bad", 19_999,
                observed.epochMillis + 1))), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("backdated", WriteBatch(readings = listOf(reading("bad", 20_001,
                observed.epochMillis - 1))), 3)
        }
        assertEquals(listOf(reading()), store.snapshot().readings)
    }

    @Test fun singleBikeAndActiveRideAndImportRowUniquenessSurviveRetries() {
        bike()
        assertThrows(Exception::class.java) {
            store.write("second-bike", WriteBatch(bikes = listOf(Bike("second"))), 1)
        }
        store.write("start", WriteBatch(rides = listOf(ride()), importRows = listOf(
            ImportRow("import-1", "bike", "batch", "row", "Synthetic", Certainty.UNKNOWN))), 1)
        assertThrows(Exception::class.java) {
            store.write("second-ride", WriteBatch(rides = listOf(ride("second"))), 1)
        }
        assertThrows(Exception::class.java) {
            store.write("duplicate-import", WriteBatch(importRows = listOf(
                ImportRow("different-id", "bike", "batch", "row", "Synthetic", Certainty.UNKNOWN))), 1)
        }
        val interrupted = ride().copy(state = RideState.INTERRUPTED, revision = 1)
        store.write("interrupt", WriteBatch(rideChanges = listOf(interrupted)), 1)
        reopen()
        assertEquals(RideState.INTERRUPTED, store.snapshot().rides.single().state)
        store.write("end", WriteBatch(rideChanges = listOf(interrupted.copy(
            state = RideState.ENDED, activeSlot = null, ended = observed, revision = 2))), 1)
        store.write("new-start", WriteBatch(rides = listOf(ride("new"))), 1)
        assertEquals(2, store.snapshot().rides.size)
    }

    @Test fun concurrentRetriesCommitOnlyOnce() {
        bike()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val batch = WriteBatch(readings = listOf(reading()))
            val results = executor.invokeAll((1..8).map { Callable { store.write("same", batch, 1) } })
                .map { it.get() }
            assertEquals(1, results.count { it })
            assertEquals(1, store.snapshot().readings.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun unsupportedDatabaseVersionFailsWithoutDestructiveFallback() {
        bike(); db.close()
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.version = 4; raw.close()
        db = MotoLogDatabase.open(context, name)
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase }
        db.close()
        val retained = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        retained.rawQuery("SELECT id FROM Bike", null).use { assertTrue(it.moveToFirst()); assertEquals("bike", it.getString(0)) }
        retained.version = 3; retained.close()
        db = MotoLogDatabase.open(context, name); store = LocalStore(db)
        assertEquals("bike", store.snapshot().bikes.single().id)
    }
}
