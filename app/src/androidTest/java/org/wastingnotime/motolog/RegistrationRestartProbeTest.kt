package org.wastingnotime.motolog

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.MotoLogDatabase
import org.wastingnotime.motolog.data.repository.*

/** Separate seed/verify instrumentation processes exercise a real app process boundary. */
class RegistrationRestartProbeTest {
    @Test fun registrationAndCustomRulesSurviveProcessRestartOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "registration-restart.db"
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try {
                val repository = RegistrationRepository(db)
                repository.register("synthetic-restart-bike", 2016, true, null, 1_780_000_000_000)
                val rule = repository.snapshot().baselines.single { it.rule.component == "External fuel filter" }
                repository.editItem(rule.id, 0, rule.rule.copy(intervalKm = 12_000), 1_780_000_000_000)
                val job = repository.preview(SetupEntry("synthetic-job", "synthetic-restart-bike", EntryKind.PROGRAMMED,
                    "Synthetic pending job", action = "Inspect", component = "Synthetic accessory"), 1_780_000_000_000)
                repository.confirm(job, false, 1_780_000_000_000)
            } finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val repository = RegistrationRepository(db)
                val saved = repository.snapshot()
                assertEquals("synthetic-restart-bike", saved.bikes.single().id)
                val item = repository.items(saved).single { it.baseline.rule.component == "External fuel filter" }
                assertEquals("Custom", item.label)
                assertEquals(15_000L, item.baseline.rule.intervalKm)
                assertEquals(12_000L, item.effective.intervalKm)
                assertTrue(item.source.contains("6-4"))
                assertTrue(saved.histories.isEmpty()); assertTrue(saved.readings.isEmpty())
                assertEquals(1, saved.occurrences.size)
                assertEquals(1, saved.importRows.size)
                val preview = repository.preview(SetupEntry("synthetic-job", "synthetic-restart-bike", EntryKind.PROGRAMMED,
                    "Synthetic pending job", action = "Inspect", component = "Synthetic accessory"), 1_780_000_000_000)
                assertFalse(repository.confirm(preview, false, 1_780_000_000_001))
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
