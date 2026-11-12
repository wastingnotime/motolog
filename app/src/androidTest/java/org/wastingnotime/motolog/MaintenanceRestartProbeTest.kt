package org.wastingnotime.motolog

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate

class MaintenanceRestartProbeTest {
    @Test fun adoptedTargetsAndPostponementSurviveSeparateOfflineProcesses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "maintenance-restart.db"
        val now = 1_800_000_000_000L
        val date = LocalDate.of(2026, 10, 1)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try {
                val registration = RegistrationRepository(db)
                val repo = MaintenanceRepository(db)
                val store = LocalStore(db)
                registration.register("synthetic", 2017, true, OdometerReading("reading", "synthetic",
                    20_800, Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now, "UTC", 0), now), now)
                repo.refresh("synthetic", "reading", date, now)
                val oil = store.snapshot().occurrences.single { it.rule.component == "Engine oil" && it.originalKm == 20_000L }
                store.write("postpone", WriteBatch(occurrenceChanges = listOf(oil.copy(effectiveKm = 26_000,
                    revision = 1, state = OccurrenceState.POSTPONED, changeReason = "Synthetic reason"))), now)
                registration.editItem("synthetic:manual:oil-filter:1", 0,
                    store.snapshot().baselines.single { it.rule.component == "Oil-filter element" }.rule.copy(
                        scheduleKind = ScheduleKind.INTERVAL, intervalKm = 1_500), now)
            } finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val store = LocalStore(db)
                val snapshot = store.snapshot()
                val view = MaintenanceRepository(db).refresh("synthetic", "reading", date, now)
                assertEquals(snapshot, store.snapshot())
                assertEquals(4, snapshot.obligations.size)
                assertTrue(snapshot.histories.isEmpty())
                val oil = view.pending.single { it.occurrence.rule.component == "Engine oil" && it.occurrence.originalKm == 20_000L }
                assertEquals(26_000L, oil.occurrence.effectiveKm)
                assertEquals(20_000L, oil.occurrence.originalKm)
                assertEquals(org.wastingnotime.motolog.domain.maintenance.DueStatus.DUE, oil.originally.status)
                assertEquals(org.wastingnotime.motolog.domain.maintenance.DueStatus.NOT_DUE, oil.effective.status)
                assertEquals(1, store.occurrenceRevisions().size)
                assertFalse(store.write("postpone", WriteBatch(occurrenceChanges = listOf(oil.occurrence)), now))
                assertTrue(view.pending.any { it.occurrence.rule.component == "Oil-filter element" &&
                    it.occurrence.rule.sourceKind == SourceKind.MANUAL })
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
