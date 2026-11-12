package org.wastingnotime.motolog

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*

/** Can run in separate instrumentation processes with -e phase seed / verify. Synthetic only. */
class RestartProbeTest {
    @Test fun durableRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "process-restart-probe.db"
        val time = Observation(1_700_000_000_000, "UTC", 0)
        val rule = RuleValues("Inspect", "Synthetic", SourceKind.CUSTOM)
        val fixture = WriteBatch(bikes = listOf(Bike("synthetic-bike")),
            readings = listOf(OdometerReading("reading", "synthetic-bike", 20_000,
                Certainty.CONFIRMED, ReadingSource.OWNER, time, time.epochMillis)),
            histories = listOf(MaintenanceHistory("service", "synthetic-bike", null, rule,
                null, null, null, null, null, null, null, Certainty.UNKNOWN, null, null,
                "Synthetic history", "Inspected", null, null, time.epochMillis)),
            fuels = listOf(FuelEvent("fuel", "synthetic-bike", "reading", FuelMode.UNIT_PRICE,
                "100", "6", time, time.epochMillis)),
            rides = listOf(RideSession("ride", "synthetic-bike", RideState.INTERRUPTED, 1,
                RideOwnership.MANUAL, time, null, "test-v1", time.epochMillis)),
            samples = listOf(RideSample("sample", "synthetic-bike", "ride", 0, time,
                "epoch", 1000, 0.0, 0.0, 5.0, 0.0, 1.0, true, time.epochMillis)))
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.INTERNET))
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try { assertTrue(LocalStore(db).write("restart-fixture", fixture, time.epochMillis)) }
            finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val store = LocalStore(db)
                assertEquals(fixture, store.snapshot())
                assertFalse(store.write("restart-fixture", fixture, time.epochMillis))
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
