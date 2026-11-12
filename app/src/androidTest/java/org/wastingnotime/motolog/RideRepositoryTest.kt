package org.wastingnotime.motolog

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Executor

class RideRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "ride-repository-test.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var rides: RideRepository
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private val start = Instant.parse("2026-01-01T23:59:55Z").toEpochMilli()
    private fun at(t: Long) = observationAt(start + t, ZoneId.of("UTC"))
    private fun fix(t: Long, speed: Double = 40.0, lon: Double = t / 1_000.0 * .0001) =
        RideMetrics.Fix(at(t), "epoch", t + 1_000, t + 1_000, 0.0, lon, 5.0, speed / 3.6, 1.0)
    @Before fun setup() {
        context.deleteDatabase(name)
        @Suppress("DEPRECATION")
        val builder = MotoLogDatabase.builder(context, name).setQueryCallback(
            RoomDatabase.QueryCallback { sql, _ -> queries.add(sql) }, Executor { it.run() })
        db = builder.build(); rides = RideRepository(db)
        LocalStore(db).write("bike", WriteBatch(bikes = listOf(Bike("bike"))), start)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun concurrentOfflineStartRetryStopAndResumeKeepOneRideAndNoDoubleCounting() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val ids = (1..16).map { pool.submit<String> { rides.start("start-$it", "bike", at(0), "epoch").id } }.map { it.get() }
            assertEquals(1, ids.distinct().size)
            val id = ids.first()
            rides.accept("0", fix(0)); rides.accept("1", fix(1_000)); rides.accept("1", fix(1_000))
            var view = rides.view("bike", "2026-01-01")
            assertEquals(1_000, view.millis)
            assertEquals(2, db.dao().rideSampleRows().size)
            rides.interrupt("PROCESS_RESTART", at(8_000))
            assertThrows(IllegalArgumentException::class.java) { rides.start("other", "bike", at(8_000), "new") }
            rides.resume(id, at(8_000), "new")
            rides.accept("2", fix(9_000).copy(clockEpoch = "new", elapsed = 100, receivedElapsed = 100))
            rides.accept("3", fix(10_000).copy(clockEpoch = "new", elapsed = 1_100, receivedElapsed = 1_100))
            val allTime = rides.view("bike", "2026-01-01").millis + rides.view("bike", "2026-01-02").millis
            assertEquals(2_000, allTime)
            assertEquals(id, rides.view("bike", "2026-01-02").active!!.id)
            rides.stop(id, at(11_000)); rides.stop(id, at(12_000))
            assertTrue(rides.view("bike", "2026-01-02").gaps.any { it.reason == "STOP_TAIL" && it.started == at(10_000) && it.ended == at(11_000) })
            // Every coalesced start operation retains its original result even after End.
            for (i in 1..16) assertEquals(id, rides.start("start-$i", "bike", at(12_000), "epoch").id)
            assertNull(rides.view("bike", "2026-01-02").active)
            assertEquals(1, db.dao().rideSessionRows().size)
            val next = rides.start("new-ride", "bike", at(12_000), "new")
            rides.stop(id, at(13_000)) // stale Stop may never end a newer ride
            assertEquals(next.id, rides.view("bike", "2026-01-02").active!!.id)
        } finally { pool.shutdownNow() }
    }

    @Test fun rawQualityGapsOutliersSpeedEventsStoppedAndMidnightSurviveReopen() {
        rides.start("ride", "bike", at(0), "epoch")
        listOf(40.0, 44.999, 45.0, 41.0, 40.0).forEachIndexed { i, speed -> rides.accept("$i", fix(i * 1_000L, speed)) }
        rides.accept("invalid", fix(5_000).copy(speed = null))
        rides.accept("after-gap", fix(6_000, 41.0))
        rides.accept("jump", fix(7_000, 42.0, 1.0))
        rides.accept("stopped-1", fix(8_000, 0.0, 1.0))
        rides.accept("stopped-2", fix(9_000, 0.0, 1.000001))
        rides.accept("duplicate-time", fix(9_000))
        rides.accept("missing", fix(20_000, 0.0, 1.000001))
        val samples = db.dao().rideSampleRows().sortedBy { it.sequence }
        assertEquals(listOf(true, false, true, false, true, true, true, true, false, true), samples.map { it.speedEvent })
        val days = rides.view("bike", "2026-01-01")
        assertEquals(4_000, days.millis)
        val day2 = rides.view("bike", "2026-01-02")
        assertEquals(2_000, day2.millis)
        assertEquals(0.0, day2.meters, 0.0)
        assertEquals(0.0, day2.averageKmh!!, 0.0)
        assertTrue(day2.gaps.any { it.reason == "NATIVE_SPEED" && it.rejectedCount == 1L })
        assertTrue(day2.gaps.any { it.reason == "IMPLIED_SPEED" })
        assertTrue(day2.gaps.any { it.reason == "NON_INCREASING_TIME" })
        val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM QualityFix").use { it.moveToFirst(); it.getLong(0) }
        assertEquals(12, count)
        db.close(); db = MotoLogDatabase.open(context, name); rides = RideRepository(db)
        rides.interrupt("PROCESS_RESTART", at(25_000))
        val recovered = rides.view("bike", "2026-01-02")
        assertEquals(RideState.INTERRUPTED, recovered.active!!.state)
        assertEquals(day2.millis, recovered.millis)
        rides.stop("ride", at(30_000), true)
        assertEquals(at(20_000), db.dao().ride("ride")!!.ended)
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("DELETE FROM QualityFix") }
    }

    @Test fun midnightAndMultipleRidesSumNumeratorsAndMeasuredStoppedTime() {
        rides.start("one", "bike", at(0), "epoch")
        rides.accept("a", fix(0)); rides.accept("b", fix(10_000))
        val before = rides.view("bike", "2026-01-01")
        val after = rides.view("bike", "2026-01-02")
        assertEquals(5_000, before.millis); assertEquals(5_000, after.millis)
        assertEquals(before.meters, after.meters, 1e-8)
        rides.stop("one", at(10_000))
        rides.start("two", "bike", at(11_000), "epoch")
        rides.accept("c", fix(11_000, 0.0)); rides.accept("d", fix(21_000, 0.0))
        val total = rides.view("bike", "2026-01-02")
        assertEquals(15_000, total.millis)
        assertEquals(after.meters, total.meters, 0.0)
        assertEquals(after.meters / 15_000 * 3600, total.averageKmh!!, 1e-8)
        rides.stop("two", at(21_000))
        rides.start("zero", "bike", at(22_000), "epoch")
        rides.stop("zero", at(22_000))
        assertEquals(total.millis, rides.view("bike", "2026-01-02").millis)
    }

    @Test fun cachedFixBeforeStartOrResumeCannotContributeMetricsAndManualTakesOwnership() {
        rides.start("ride", "bike", at(0), "epoch", 2_000)
        rides.accept("cached", fix(0))
        assertTrue(db.dao().rideSampleRows().isEmpty())
        rides.accept("first", fix(1_000)); rides.accept("second", fix(2_000))
        assertEquals(1_000, rides.view("bike", "2026-01-01").millis)
        rides.interrupt("SERVICE_INTERRUPTED", at(3_000))
        rides.resume("ride", at(3_000), "epoch", 5_000)
        rides.accept("cached-resume", fix(3_000))
        rides.accept("resume", fix(6_000)); rides.accept("later", fix(7_000))
        assertEquals(1_000, rides.view("bike", "2026-01-01").millis)
        assertEquals(1_000, rides.view("bike", "2026-01-02").millis)
        val active = db.dao().ride("ride")!!
        db.dao().update(active.copy(ownership = RideOwnership.AUTOMATIC))
        assertEquals("ride", rides.start("takeover", "bike", at(8_000), "epoch").id)
        assertEquals(RideOwnership.MANUAL, rides.status().first!!.ownership)
    }

    @Test fun lateDeliveryUsesFixTimestampsRatherThanHeartbeatOrCallbackCadence() {
        rides.start("late", "bike", at(0), "epoch")
        rides.accept("a", fix(0))
        rides.heartbeat(at(15_000), 16_000, "epoch")
        assertTrue(rides.status().second!!.tentativeGap)
        rides.accept("late-b", fix(10_000).copy(receivedElapsed = 16_000))
        val total = rides.view("bike", "2026-01-01").millis + rides.view("bike", "2026-01-02").millis
        assertEquals(10_000, total)
        assertNull(rides.status().second!!.gap)
        assertEquals(1, rides.view("bike", "2026-01-01").days.sumOf { it.gaps }) // only initial waiting
        rides.heartbeat(at(25_000), 26_000, "epoch")
        rides.accept("far-c", fix(26_000))
        assertTrue(rides.view("bike", "2026-01-02").gaps.any { it.reason == "MISSING_FIX" })
        assertEquals(10_000, rides.view("bike", "2026-01-01").millis + rides.view("bike", "2026-01-02").millis)
    }

    @Test fun writeFailureRollsBackRawEvidenceCursorReceiptAndProjectionTogether() {
        rides.start("atomic", "bike", at(0), "epoch")
        rides.accept("a", fix(0)); rides.accept("b", fix(1_000))
        val before = rides.view("bike", "2026-01-01")
        val cursor = rides.status().second
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_write_failure BEFORE INSERT ON RideSample BEGIN SELECT RAISE(ABORT, 'Synthetic disk write failure'); END")
        assertThrows(Exception::class.java) { rides.accept("c", fix(2_000)) }
        assertEquals(before, rides.view("bike", "2026-01-01"))
        assertEquals(cursor, rides.status().second)
        assertEquals(2, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM QualityFix").use { it.moveToFirst(); it.getInt(0) })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_write_failure")
        rides.accept("c", fix(2_000))
        assertEquals(2_000, rides.view("bike", "2026-01-01").millis)
    }

    @Test fun longSessionAndDailyRefreshUseOnlyIndexedCursorAndTouchedDayQueries() {
        rides.start("long", "bike", at(0), "epoch")
        // Two hours at the requested one-second cadence; replay each transaction once.
        for (i in 0..7_200) {
            val f = fix(i * 1_000L, 0.0, 0.0)
            rides.accept("$i", f); rides.accept("$i", f)
            if (i == 7_190) queries.clear()
        }
        val view = rides.view("bike", "2026-01-02")
        assertEquals(7_195_000, view.millis)
        assertEquals(0.0, view.meters, 0.0)
        assertEquals(7_201, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM QualityFix").use { it.moveToFirst(); it.getInt(0) })
        val selected = synchronized(queries) { queries.toList() }.filter { it.trimStart().startsWith("SELECT *") }
        assertFalse(selected.any { it.contains("OdometerReading") || it.contains("MaintenanceHistory") || it.contains("FuelEvent") || it.contains("FROM QualityFix") })
        assertTrue(selected.filter { it.contains("FROM RideSample") }.all { it.contains("WHERE id =") })
        assertTrue(selected.filter { it.contains("FROM RideSession") }.all { it.contains("LIMIT") || it.contains("WHERE id =") })
        assertTrue(selected.filter { it.contains("FROM RideDay") }.all { it.contains("bikeId =") && it.contains("date =") })
        fun plan(sql: String) = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
        }
        assertTrue(plan("SELECT * FROM RideDay WHERE bikeId = 'bike' AND date = '2026-01-02'").contains("SEARCH RideDay"))
        assertTrue(plan("SELECT * FROM RideSession WHERE activeSlot = 1 LIMIT 1").contains("USING INDEX index_RideSession_activeSlot"))
    }
}
