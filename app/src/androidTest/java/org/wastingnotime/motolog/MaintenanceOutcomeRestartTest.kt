package org.wastingnotime.motolog

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate

/** Run phase=seed and phase=verify in separate instrumentation processes without clearing app data. */
class MaintenanceOutcomeRestartTest {
    @Test fun wholeChecklistReceiptsRemaindersAndFailedAttemptSurviveProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "maintenance-outcome-restart.db"
        val now = 1_800_000_000_000L
        val today = LocalDate.of(2026, 10, 1)
        fun partial() = ChecklistOutcome(listOf(ObligationSelection("job:a", 0), ObligationSelection("job:b", 0)),
            listOf(PerformedAction(listOf("job:a"), PerformedDetails("2026-10-01", "UTC", null,
                "Replace synthetic a", "Confirmed synthetic action"))), WorkTarget(date = "2026-11-01"), "Synthetic reason")
        fun postpone() = ChecklistOutcome(listOf(ObligationSelection("job:c", 0)), emptyList(),
            WorkTarget(km = 25_000), "Synthetic full postponement")
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try {
                val repo = MaintenanceRepository(db)
                RegistrationRepository(db).register("bike", 2017, false, null, now)
                listOf("a", "b", "c").forEach { repo.createProgrammedJob(it, "bike", "Replace", "Synthetic $it", null, true, now) }
                repo.applyOutcome("partial", partial(), null, today, now)
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restart BEFORE INSERT ON AppliedOperation WHEN NEW.id = 'postpone' BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
                assertThrows(Exception::class.java) { repo.applyOutcome("postpone", postpone(), null, today, now) }
                assertFalse(repo.operationApplied("postpone"))
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restart")
            } finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val repo = MaintenanceRepository(db); val store = LocalStore(db)
                assertFalse(repo.applyOutcome("partial", partial(), null, today, now + 1))
                assertTrue(repo.applyOutcome("postpone", postpone(), null, today, now + 1))
                assertFalse(repo.applyOutcome("postpone", postpone(), null, today, now + 2))
                val snapshot = store.snapshot()
                assertEquals(1, snapshot.histories.size); assertEquals(1, snapshot.satisfactions.size)
                assertEquals(1, snapshot.occurrences.count { it.parentId == "job:b" })
                val view = repo.existing("bike", null, today)
                assertEquals(2, view.pending.size)
                assertTrue(view.pending.any { it.occurrence.parentId == "job:b" && it.occurrence.effectiveDate == "2026-11-01" })
                assertTrue(view.pending.any { it.occurrence.id == "job:c" && it.occurrence.effectiveKm == 25_000L })
                assertTrue(repo.operationApplied("partial")); assertTrue(repo.operationApplied("postpone"))
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
