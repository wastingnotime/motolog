package org.wastingnotime.motolog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import java.time.Instant
import java.time.ZoneId

class AutomaticRideTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MotoLogDatabase
    private lateinit var rides: RideRepository
    private val base = Instant.parse("2026-01-01T23:59:55Z").toEpochMilli()
    private fun at(t: Long) = observationAt(base + t, ZoneId.of("UTC"))
    private fun fix(t: Long, speed: Double = 6.0) = RideMetrics.Fix(at(t), "epoch", t, t,
        0.0, t / 1000.0 * .00001, 5.0, speed / 3.6, 1.0)
    private fun capture(t: Long, speed: Double = 6.0) = rides.capture("fix:$t", fix(t, speed))
    @Before fun setup() {
        context.deleteDatabase("automatic-test")
        db = MotoLogDatabase.open(context, "automatic-test"); rides = RideRepository(db)
        LocalStore(db).write("bike", WriteBatch(bikes = listOf(Bike("bike"))), base)
        rides.arm("bike", "epoch", 0)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("automatic-test") }

    @Test fun bufferedMidnightStartReplayIdleStopAndRearmKeepExactTotals() {
        listOf(0L, 5_000, 10_000).forEach { capture(it) }
        assertNull(rides.status().first)
        db.close(); db = MotoLogDatabase.open(context, "automatic-test"); rides = RideRepository(db)
        // Reducer checkpoint survives reopen; production process initialization explicitly interrupts/disarms.
        capture(15_000); capture(15_000)
        val ride = rides.status().first!!
        assertEquals(at(0), ride.started); assertEquals(RideOwnership.AUTOMATIC, ride.ownership)
        assertEquals(4, db.dao().rideSampleRows().size)
        assertEquals(5_000, rides.view("bike", "2026-01-01").millis)
        assertEquals(10_000, rides.view("bike", "2026-01-02").millis)
        assertEquals(15_000, rides.detail(ride.id).total!!.eligibleMillis)
        for (t in 20_000L..319_000L step 1_000) capture(t, 3.0)
        assertEquals(ride.id, rides.status().first!!.id)
        capture(320_000, 3.0); capture(320_000, 3.0)
        assertNull(rides.status().first); assertNotNull(rides.automatic())
        assertEquals(320_000, rides.detail(ride.id).total!!.eligibleMillis)
        val before = rides.detail(ride.id).total!!
        listOf(325_000L, 330_000, 335_000, 340_000).forEach { capture(it) }
        assertNotEquals(ride.id, rides.status().first!!.id)
        assertEquals(before, rides.detail(ride.id).total)
        assertEquals(2, db.dao().rideSessionRows().size)
    }

    @Test fun manualTakeoverDisarmRestartAndStaleStopPreserveSingleRide() {
        listOf(0L, 5_000, 10_000, 15_000).forEach { capture(it) }
        val automatic = rides.status().first!!
        val manual = rides.start("manual", "bike", at(16_000), "epoch")
        assertEquals(automatic.id, manual.id); assertEquals(RideOwnership.MANUAL, manual.ownership)
        assertNull(rides.automatic())
        rides.arm("bike", "epoch", 16_000)
        for (t in 20_000L..330_000L step 10_000) capture(t, 0.0)
        assertEquals(manual.id, rides.status().first!!.id)
        rides.interrupt("PERMISSION_REVOKED", at(331_000))
        assertNull(rides.automatic()); assertEquals(RideState.INTERRUPTED, rides.status().first!!.state)
        assertThrows(IllegalArgumentException::class.java) { rides.arm("bike", "new", 0) }
        rides.resume(manual.id, at(332_000), "new")
        assertEquals(RideOwnership.MANUAL, rides.status().first!!.ownership)
        rides.stop(manual.id, at(333_000))
        val next = rides.start("next", "bike", at(334_000), "new")
        rides.stop(manual.id, at(335_000))
        assertEquals(next.id, rides.status().first!!.id)
        rides.arm("bike", "new", 0); rides.interrupt("PROCESS_RESTART", at(336_000))
        assertNull(rides.automatic())
    }

    @Test fun manualStartRacingAutomaticQualificationKeepsOneManualOwnedRide() {
        listOf(0L, 5_000, 10_000).forEach { capture(it) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val automatic = pool.submit { gate.await(); capture(15_000) }
            val manual = pool.submit { gate.await(); rides.start("manual-race", "bike", at(15_000), "epoch") }
            gate.countDown(); automatic.get(); manual.get()
            val ride = rides.status().first!!
            assertEquals(RideOwnership.MANUAL, ride.ownership)
            assertEquals(1, db.dao().rideSessionRows().size)
            assertNull(rides.automatic())
            val total = rides.detail(ride.id).total!!
            val samples = db.dao().rideSampleRows().size
            capture(15_000)
            assertEquals(total, rides.detail(ride.id).total)
            assertEquals(samples, db.dao().rideSampleRows().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun rejectedFixBriefStopsAndRepeatedModeChangesDoNotCreateRides() {
        rides.arm("bike", "epoch", 0)
        listOf(0L, 5_000, 10_000).forEach { capture(it) }
        rides.capture("bad", fix(15_000).copy(speed = null))
        capture(20_000)
        assertNull(rides.status().first)
        rides.disarm(); rides.arm("bike", "epoch", 21_000)
        listOf(25_000L, 30_000, 35_000, 40_000).forEach { capture(it) }
        val id = rides.status().first!!.id
        for (t in 45_000L..335_000L step 10_000) capture(t, 0.0)
        capture(340_000, 4.0)
        capture(345_000, 0.0)
        assertEquals(id, rides.status().first!!.id)
        rides.stop(id, at(346_000)); assertNull(rides.automatic())
        capture(350_000); assertNull(rides.status().first)
        assertEquals(1, db.dao().rideSessionRows().size)
    }
}
