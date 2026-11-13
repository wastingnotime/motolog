package org.wastingnotime.motolog

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.maintenance.*
import org.wastingnotime.motolog.domain.ride.*
import java.time.*
import java.util.concurrent.Executor

class DashboardDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "dashboard-data.db"
    private lateinit var db: MotoLogDatabase
    private val queries = mutableListOf<String>()
    private val now = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 1, 15)
    @Before fun setup() {
        context.deleteDatabase(name)
        @Suppress("DEPRECATION")
        val builder = MotoLogDatabase.builder(context, name).setQueryCallback(
            RoomDatabase.QueryCallback { sql, _ -> queries.add(sql) }, Executor { it.run() })
        db = builder.build()
        RegistrationRepository(db).register("bike", null, false, null, now)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun at(time: Long) = observationAt(time, ZoneId.of("UTC"))
    private fun fix(t: Long, speed: Double = 0.0) = RideMetrics.Fix(at(now + t), "epoch", t + 1_000, t + 1_000, 0.0, 0.0, 5.0, speed, 1.0)

    @Test fun unavailableMeasuredZeroGapResumeAndReopenHavePersistedPerRideTotals() {
        var rides = RideRepository(db)
        rides.start("ride", "bike", at(now), "epoch")
        assertEquals(0L, rides.detail("ride").total!!.eligibleMillis)
        assertNull(rides.detail("ride").averageKmh)
        rides.accept("0", fix(0)); rides.accept("1", fix(1_000))
        rides.accept("1", fix(1_000)) // duplicate cannot change either projection
        assertEquals(0.0, rides.detail("ride").averageKmh!!, 0.0)
        rides.interrupt("PROCESS_RESTART", at(now + 2_000))
        rides.resume("ride", at(now + 3_000), "epoch")
        rides.accept("2", fix(3_000)); rides.accept("3", fix(4_000))
        rides.stop("ride", at(now + 5_000))
        val detail = rides.detail("ride")
        assertEquals(2_000L, detail.total!!.eligibleMillis)
        assertEquals(0.0, detail.total.meters, 0.0)
        assertEquals(3, detail.gapCount.toInt())
        assertEquals(2_000L, rides.view("bike", today.toString()).millis)
        db.close(); db = MotoLogDatabase.open(context, name); rides = RideRepository(db)
        assertEquals(detail.total, rides.detail("ride").total)
        assertEquals(2, rides.events("ride").size) // baseline event after each gap
    }

    @Test fun failedPairProjectionRollsBackQualitySamplesTotalsDaysAndReceiptForRetry() {
        val rides = RideRepository(db)
        rides.start("ride", "bike", at(now), "epoch")
        rides.accept("0", fix(0))
        val before = rides.detail("ride").total
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_total BEFORE UPDATE ON RideTotal BEGIN SELECT RAISE(ABORT, 'Synthetic storage failure'); END")
        assertThrows(Exception::class.java) { rides.accept("1", fix(1_000)) }
        assertEquals(before, rides.detail("ride").total)
        assertEquals(1, db.dao().rideSampleRows().size)
        assertEquals(0L, rides.view("bike", today.toString()).millis)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM QualityFix").use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_total")
        rides.accept("1", fix(1_000))
        assertEquals(1_000L, rides.detail("ride").total!!.eligibleMillis)
        assertEquals(1_000L, rides.view("bike", today.toString()).millis)
    }

    @Test fun staleMileageKeepsReachedTargetsButNeverEstablishesCurrentNotDue() {
        val fuel = FuelRepository(db)
        val maintenance = MaintenanceRepository(db)
        fuel.updateOdometer(ReadingCommand("old", "bike", "100", at(now - 25 * 3_600_000)), now)
        maintenance.createProgrammedJob("reached", "bike", "Inspect", "Reached", WorkTarget(km = 100), false, now)
        maintenance.createProgrammedJob("future", "bike", "Inspect", "Future", WorkTarget(km = 101), false, now)
        maintenance.createProgrammedJob("calendar", "bike", "Inspect", "Calendar", WorkTarget(km = 101, date = today.toString()), false, now)
        var view = maintenance.refresh("bike", "old:owner-reading", today, now)
        fun status(component: String) = view.pending.single { it.occurrence.rule.component == component }.effective.status
        assertEquals(DueStatus.DUE, status("Reached"))
        assertEquals(DueStatus.UNKNOWN, status("Future"))
        assertEquals(DueStatus.DUE, status("Calendar"))
        view = maintenance.refresh("bike", "old:owner-reading", today, now - 3_600_000)
        assertEquals(DueStatus.NOT_DUE, status("Future")) // exactly 24 hours is fresh
        view = maintenance.refresh("bike", "old:owner-reading", today, now - 3_600_000 + 1)
        assertEquals(DueStatus.UNKNOWN, status("Future"))
        view = maintenance.existing("bike", "old:owner-reading", today, now - 26 * 3_600_000)
        assertEquals(DueStatus.UNKNOWN, status("Reached")) // future observation is not evidence of reached mileage
    }

    @Test fun longHistoryManagementEditsAndBoundedHistoryQueriesNeverLoadAllGpsEvidence() {
        val rides = RideRepository(db)
        repeat(25) { ride ->
            val start = now - 30_000_000 + ride * 10_000L
            rides.start("ride-$ride", "bike", at(start), "epoch")
            repeat(40) { sample ->
                val time = start + sample * 100L
                rides.accept("$ride-$sample", RideMetrics.Fix(at(time), "epoch", sample * 100L + 1_000, sample * 100L + 1_000,
                    0.0, 0.0, 5.0, if (sample % 2 == 0) 0.0 else 10.0, 1.0))
            }
            rides.stop("ride-$ride", at(start + 4_000))
        }
        queries.clear()
        val registration = RegistrationRepository(db)
        val maintenance = MaintenanceRepository(db)
        val fuel = FuelRepository(db)
        registration.snapshot(); fuel.snapshot()
        fuel.refuel(RefuelCommand("fuel", "bike", FuelMode.LITERS, "100", "20", "100", at(now)), now)
        registration.addItem("custom", "bike", RuleValues("Inspect", "Synthetic", SourceKind.CUSTOM), now)
        registration.editItem("custom", 0, RuleValues("Inspect", "Synthetic edited", SourceKind.CUSTOM), now)
        maintenance.refresh("bike", "fuel:owner-reading", today, now); maintenance.completedHistory("bike")
        rides.view("bike", today.toString()); rides.detail("ride-24")
        assertEquals(20, rides.history("bike").size)
        assertEquals(5, rides.history("bike", 20).size)
        val events = rides.events("ride-24")
        assertEquals(20, events.size)
        assertEquals(20, rides.events("ride-24", events.last().sequence).size)
        rides.gaps("ride-24")
        val evidence = queries.filter { it.startsWith("SELECT", true) && Regex("FROM (RideSample|QualityFix|RideGap)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        assertTrue(evidence.toString(), evidence.all { it.contains("WHERE rideId =") })
        assertTrue(evidence.toString(), evidence.filter { it.startsWith("SELECT *") }.all { it.contains("LIMIT 20") })
        fun plan(sql: String) = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { c -> buildString { while (c.moveToNext()) append(c.getString(3)) } }
        assertTrue(plan("SELECT * FROM RideSample WHERE rideId = 'ride-24' AND sequence > 0 ORDER BY sequence LIMIT 20").contains("index_RideSample_rideId_sequence"))
        assertTrue(plan("SELECT * FROM RideDay WHERE bikeId = 'bike' AND date = '2026-01-15'").contains("INDEX"))
    }
}
