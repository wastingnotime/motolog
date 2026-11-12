package org.wastingnotime.motolog

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CorrectiveServiceTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val now = 1_800_000_000_000L
    private fun details() = PerformedDetails("2026-09-15", "America/Sao_Paulo", 20_000,
        "Repaired synthetic wiring", "", "Synthetic intermittent light", "45.50", "Synthetic garage", "Synthetic connector")

    /** phase=seed/verify are also run in separate instrumentation processes. */
    @Test fun correctiveFieldsReceiptsAndAllKindsSurviveRestartWithoutChangingPendingWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "corrective-restart.db"
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try {
                val repo = MaintenanceRepository(db)
                RegistrationRepository(db).register("bike", 2017, true, null, now)
                repo.createProgrammedJob("pending", "bike", "Replace", "Synthetic pending", null, true, now)
                repo.createProgrammedJob("done", "bike", "Repair", "Synthetic done", null, true, now)
                repo.applyOutcome("programmed", ChecklistOutcome(listOf(ObligationSelection("job:done", 0)),
                    listOf(PerformedAction(listOf("job:done"), PerformedDetails("2026-09-01", "UTC", null,
                        "Synthetic job work", "Synthetic confirmation")))), null, today, now)
                val store = LocalStore(db)
                val rule = RegistrationRepository(db).items(store.snapshot()).first().effective
                store.write("prior", WriteBatch(histories = listOf(MaintenanceHistory("prior", "bike", null, rule,
                    null, null, null, null, "2026-08-01", "UTC", null, Certainty.CONFIRMED, null, null,
                    "Synthetic prior", "Synthetic work", null, "Synthetic evidence", now, MaintenanceKind.PREVENTIVE))), now)
                assertTrue(repo.recordCorrective("corrective", "bike", details(), today, now))
                val saved = repo.completedHistory("bike")
                val item = RegistrationRepository(db).items(store.snapshot()).first()
                RegistrationRepository(db).editItem(item.baseline.id, item.revision, item.effective.copy(component = "Synthetic edited"), now)
                repo.applyOutcome("postpone", ChecklistOutcome(listOf(ObligationSelection("job:pending", 0)), emptyList(),
                    WorkTarget(date = "2026-11-01"), "Synthetic postponement"), null, today, now)
                assertEquals(saved, repo.completedHistory("bike"))
            } finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val repo = MaintenanceRepository(db)
                assertFalse(repo.recordCorrective("corrective", "bike", details(), today.plusDays(1), now + 1))
                val history = repo.completedHistory("bike")
                assertEquals(listOf(MaintenanceKind.CORRECTIVE, MaintenanceKind.PROGRAMMED, MaintenanceKind.PREVENTIVE), history.map { it.kind })
                val row = history.first()
                assertEquals(details().date, row.performedDate); assertEquals(details().zone, row.performedZone)
                assertEquals(details().odometerKm, row.odometerKm); assertEquals("45.5", row.costBrl)
                assertEquals(details().location, row.location); assertEquals(details().description, row.description)
                assertEquals(details().work, row.performedWork); assertEquals(details().replacements, row.replacements)
                val snapshot = LocalStore(db).snapshot()
                assertTrue(snapshot.anchors.isEmpty()); assertNull(row.occurrenceId)
                assertEquals(1, repo.existing("bike", null, today).pending.size)
                assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("DELETE FROM MaintenanceHistory") }
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidInputRollbackUnknownZeroAndConcurrentRetries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "corrective-validation.db"
        context.deleteDatabase(name)
        val db = MotoLogDatabase.open(context, name)
        try {
            val repo = MaintenanceRepository(db); val store = LocalStore(db)
            RegistrationRepository(db).register("bike", 2017, false, null, now)
            val invalid = listOf(details().copy(date = "invalid"), details().copy(date = "2026-02-30"),
                details().copy(date = "2027-01-01"), details().copy(zone = "invalid"), details().copy(description = " "),
                details().copy(work = ""), details().copy(odometerKm = -1), details().copy(odometerKm = 1_000_000_000_000),
                details().copy(costBrl = "-1"), details().copy(costBrl = "1.001"), details().copy(costBrl = "NaN"),
                details().copy(costBrl = "1000000000000"))
            val before = store.snapshot()
            invalid.forEach { input ->
                val error = assertThrows(IllegalArgumentException::class.java) { repo.recordCorrective("retry", "bike", input, today, now) }
                assertTrue(error.message.orEmpty().isNotBlank()); assertEquals(before, store.snapshot())
                assertFalse(repo.operationApplied("retry"))
            }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_corrective BEFORE INSERT ON AppliedOperation WHEN NEW.id = 'retry' BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
            assertThrows(Exception::class.java) { repo.recordCorrective("retry", "bike", details(), today, now) }
            assertEquals(before, store.snapshot())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_corrective")
            val pool = Executors.newFixedThreadPool(3)
            try {
                val results = pool.invokeAll((1..6).map { Callable { repo.recordCorrective("retry", "bike", details(), today, now) } }).map { it.get() }
                assertEquals(1, results.count { it })
            } finally { pool.shutdownNow() }
            assertThrows(OperationConflict::class.java) { repo.recordCorrective("retry", "bike", details().copy(work = "Changed"), today, now) }
            repo.recordCorrective("unknown", "bike", details().copy(odometerKm = null, costBrl = null, location = null, replacements = null), today, now)
            repo.recordCorrective("zero", "bike", details().copy(costBrl = "0.00"), today, now)
            assertNull(repo.completedHistory("bike").single { it.id == "corrective:unknown" }.costBrl)
            assertEquals("0", repo.completedHistory("bike").single { it.id == "corrective:zero" }.costBrl)
            assertThrows(IllegalArgumentException::class.java) { repo.recordCorrective("earlier-high", "bike", details().copy(date = "2026-09-14", odometerKm = 20_001), today, now) }
            assertThrows(IllegalArgumentException::class.java) { repo.recordCorrective("later-low", "bike", details().copy(date = "2026-09-16", odometerKm = 19_999), today, now) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
