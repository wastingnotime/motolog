package org.wastingnotime.motolog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FuelRepositoryTest {
    private lateinit var db: MotoLogDatabase
    private lateinit var repository: FuelRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "fuel-repository-test.db"
    private val time = 1_700_000_000_000L
    private fun at(delta: Long) = Observation(time + delta, "UTC", 0)
    private fun command(id: String, km: String, delta: Long, mode: FuelMode = FuelMode.LITERS,
        quantity: String = "20.000") = RefuelCommand(id, "bike", mode, "100.00", quantity, km, at(delta))
    @Before fun setup() {
        context.deleteDatabase(name)
        db = MotoLogDatabase.open(context, name)
        RegistrationRepository(db).register("bike", null, false, null, time)
        repository = FuelRepository(db)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun equivalentModesBackdatingImmutableHistoryAndReopen() {
        val later = command("later", "120", 2_000, FuelMode.UNIT_PRICE, "5.000")
        val earlier = command("earlier", "100", 0)
        assertTrue(repository.refuel(later, time + 10_000))
        assertTrue(repository.refuel(earlier, time + 20_000))
        assertTrue(repository.refuel(command("between", "110", 1_000), time + 30_000))
        assertTrue(repository.updateOdometer(ReadingCommand("current", "bike", "125", at(3_000)), time + 30_000))
        val snapshot = repository.snapshot()
        assertEquals(3, snapshot.fuels.size)
        val a = snapshot.fuels.single { it.id == "later:refuel" }
        val b = snapshot.fuels.single { it.id == "earlier:refuel" }
        val x = FuelValues.parse(a.mode, a.totalBrl, a.enteredQuantity)
        val y = FuelValues.parse(b.mode, b.totalBrl, b.enteredQuantity)
        assertEquals(0, x.liters.compareTo(y.liters)); assertEquals(0, x.unitPrice.compareTo(y.unitPrice))
        assertEquals("100", a.totalBrl); assertEquals("5", a.enteredQuantity)
        assertEquals(120L, snapshot.readings.single { it.id == a.readingId }.kilometers)
        assertEquals(at(2_000), a.observed)
        assertEquals(time + 10_000, a.recordedAt)
        val status = FuelProjection.evaluate("bike", snapshot.fuels, snapshot.readings, time + 30_000)
        assertEquals(a, status.latestRefuel)
        assertEquals(5L, status.kilometersSince)
        db.close(); db = MotoLogDatabase.open(context, name); repository = FuelRepository(db)
        assertEquals(snapshot, repository.snapshot())
        assertFalse(repository.refuel(later, time + 50_000))
        assertEquals(snapshot, repository.snapshot())
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("UPDATE FuelEvent SET totalBrl = '1'") }
    }

    @Test fun invalidBackdatesAndFutureCommandsRollbackReadingEventAndReceipt() {
        repository.updateOdometer(ReadingCommand("low", "bike", "100", at(0)), time + 10_000)
        repository.updateOdometer(ReadingCommand("high", "bike", "200", at(2_000)), time + 10_000)
        val before = repository.snapshot()
        listOf(command("too-low", "99", 1_000), command("too-high", "201", 1_000),
            command("same-time", "101", 0), command("future", "300", 11_000),
            command("negative", "-1", 1_000), command("fractional", "100.1", 1_000),
            command("overflow", "1000000000000", 1_000), command("zero-liters", "100", 1_000, quantity = "0"),
            command("zero-price", "100", 1_000, FuelMode.UNIT_PRICE, "0"),
            command("precision", "100", 1_000, quantity = "1.0001"),
            command("zero-total", "100", 1_000).copy(totalPaid = "0"),
            command("total-precision", "100", 1_000).copy(totalPaid = "1.001")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { repository.refuel(invalid, time + 10_000) }
            assertEquals(before, repository.snapshot())
            assertNull(db.dao().operation(invalid.operationId))
        }
        // Failure does not consume the command ID. A reviewed correction can be retried.
        assertTrue(repository.refuel(command("too-low", "150", 1_000), time + 10_000))
        assertTrue(repository.updateOdometer(ReadingCommand("equal", "bike", "100", at(0)), time + 10_000))
        assertEquals(1, repository.snapshot().fuels.size)
    }

    @Test fun concurrentSubmissionCreatesOneEventAndChangedIntentConflicts() {
        val command = command("retry", "0", 0)
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        try {
            val results = (1..4).map { pool.submit(Callable { gate.await(); repository.refuel(command, time + 10_000) }) }
            gate.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
        assertEquals(1, repository.snapshot().fuels.size)
        assertEquals(1, repository.snapshot().readings.size)
        assertFalse(repository.refuel(command.copy(totalPaid = "100", quantity = "20"), time + 20_000))
        assertThrows(OperationConflict::class.java) { repository.refuel(command.copy(quantity = "10"), time + 20_000) }
        val reading = ReadingCommand("separate-reading", "bike", "0", at(1_000))
        assertTrue(repository.updateOdometer(reading, time + 10_000))
        assertFalse(repository.updateOdometer(reading, time + 20_000))
        assertEquals(2, repository.snapshot().readings.size)
    }

    @Test fun freshGpsCannotReplaceStaleOwnerEvidence() {
        repository.refuel(command("fuel", "100", 0), time)
        val before = repository.snapshot().readings
        val now = time + FuelProjection.FRESH_MILLIS + 1
        val observed = Observation(now, "UTC", 0)
        LocalStore(db).write("gps-only", WriteBatch(rides = listOf(RideSession("ride", "bike",
            RideState.ACTIVE, 1, RideOwnership.MANUAL, observed, null, "synthetic-test-v1", now)),
            samples = listOf(RideSample("gps", "bike", "ride", 0, observed, "synthetic-clock", 1_000,
                0.0, 0.0, 5.0, 0.0, 1.0, true, now))), now)
        val after = repository.snapshot()
        assertEquals(before, after.readings)
        val status = FuelProjection.evaluate("bike", after.fuels, after.readings, now)
        assertEquals(FuelUnavailable.STALE, status.unavailable)
        assertNull(status.kilometersSince)
        val future = ReadingCommand("future-owner", "bike", "120", Observation(now + 1, "UTC", 0))
        assertThrows(IllegalArgumentException::class.java) { repository.updateOdometer(future, now) }
        assertEquals(before, repository.snapshot().readings)
        assertTrue(repository.updateOdometer(future.copy(observed = observed), now))
        val updated = repository.snapshot()
        assertEquals(20L, FuelProjection.evaluate("bike", updated.fuels, updated.readings, now).kilometersSince)
    }
}
