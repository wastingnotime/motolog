package org.wastingnotime.motolog

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.MotoLogDatabase
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*

/** Separate instrumentation seed/verify processes retain synthetic operands and operation receipts. */
class FuelRestartProbeTest {
    @Test fun durableRefuelAndOwnerReading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "fuel-restart-probe.db"
        val time = 1_700_000_000_000L
        val command = RefuelCommand("synthetic-refuel", "bike", FuelMode.UNIT_PRICE, "100.00", "6.000",
            "100", Observation(time, "UTC", 0))
        val reading = ReadingCommand("synthetic-reading", "bike", "150", Observation(time + 1_000, "UTC", 0))
        if (phase != "verify") {
            context.deleteDatabase(name)
            val db = MotoLogDatabase.open(context, name)
            try {
                RegistrationRepository(db).register("bike", null, false, null, time)
                val repository = FuelRepository(db)
                assertTrue(repository.refuel(command, time + 5_000))
                assertTrue(repository.updateOdometer(reading, time + 5_000))
            } finally { db.close() }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val repository = FuelRepository(db)
                val saved = repository.snapshot()
                assertEquals("6", saved.fuels.single().enteredQuantity)
                assertEquals("100", saved.fuels.single().totalBrl)
                assertEquals(FuelMode.UNIT_PRICE, saved.fuels.single().mode)
                assertEquals(2, saved.readings.size)
                val projection = FuelProjection.evaluate("bike", saved.fuels, saved.readings, time + 10_000)
                assertEquals(50L, projection.kilometersSince)
                assertEquals(100L, projection.refuelReading!!.kilometers)
                assertFalse(repository.refuel(command, time + 10_000))
                assertFalse(repository.updateOdometer(reading, time + 10_000))
                assertEquals(saved, repository.snapshot())
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
