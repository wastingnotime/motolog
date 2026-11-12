package org.wastingnotime.motolog

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import java.util.Collections
import java.util.concurrent.Executor

class WritePathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "write-path-test.db"
    private lateinit var db: MotoLogDatabase
    private lateinit var store: LocalStore
    private val time = Observation(1_700_000_000_000, "UTC", 0)
    private data class Query(val sql: String, val args: List<Any?>)
    private val queries = Collections.synchronizedList(mutableListOf<Query>())

    @Before fun setup() {
        context.deleteDatabase(name)
        @Suppress("DEPRECATION")
        val builder = MotoLogDatabase.builder(context, name).setQueryCallback(
            RoomDatabase.QueryCallback { sql, args -> queries.add(Query(sql, args.toList())) },
            Executor { it.run() })
        db = builder.build()
        store = LocalStore(db)
        store.write("bike", WriteBatch(bikes = listOf(Bike("bike"))), time.epochMillis)
        queries.clear()
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun at(delta: Long) = time.copy(epochMillis = time.epochMillis + delta)
    private fun reading(id: String, km: Long, delta: Long, certainty: Certainty = Certainty.CONFIRMED) =
        OdometerReading(id, "bike", km, certainty, ReadingSource.OWNER, at(delta), time.epochMillis)
    private fun ride(id: String = "active") = RideSession(id, "bike", RideState.ACTIVE, 1,
        RideOwnership.MANUAL, time, null, "test-v1", time.epochMillis)
    private fun sample(id: String, rideId: String, sequence: Long, elapsed: Long) =
        RideSample(id, "bike", rideId, sequence, at(elapsed), "epoch", elapsed,
            0.0, 0.0, 5.0, 0.0, 1.0, false, time.epochMillis)

    @Test fun touchedReadingsCheckBothNeighborsAndSameTimePeersIncludingUnsortedBatch() {
        store.write("bounds", WriteBatch(readings = listOf(reading("before", 100, 0),
            reading("after", 300, 100), reading("uncertain", 999, 50, Certainty.UNCERTAIN))), 1)
        queries.clear()
        store.write("backdated", WriteBatch(readings = listOf(reading("middle", 200, 50))), 1)
        val neighborQueries = queries.filter { it.sql.contains("FROM OdometerReading") }
        assertEquals(3, neighborQueries.size)
        assertTrue(neighborQueries.all { it.sql.contains("LIMIT 1") && it.sql.contains("bikeId =") })
        assertThrows(IllegalArgumentException::class.java) {
            store.write("below-before", WriteBatch(readings = listOf(reading("bad-low", 199, 75))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("above-after", WriteBatch(readings = listOf(reading("bad-high", 301, 75))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("same-time-conflict", WriteBatch(readings = listOf(reading("bad-peer", 201, 50))), 1)
        }
        store.write("same-time-agreement", WriteBatch(readings = listOf(reading("peer", 200, 50))), 1)
        // Equal-time peers must not hide strict chronological neighbors in the same command.
        assertThrows(IllegalArgumentException::class.java) {
            store.write("bad-peers", WriteBatch(readings = listOf(
                reading("bad-peer-a", 50, 60), reading("bad-peer-b", 50, 60))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("bad-unsorted", WriteBatch(readings = listOf(
                reading("bad-later", 250, 80), reading("bad-earlier", 275, 70))), 1)
        }
        // Failed batch leaves neither reading nor receipt; corrected retry can use those IDs.
        assertTrue(store.write("bad-unsorted", WriteBatch(readings = listOf(
            reading("bad-later", 275, 80), reading("bad-earlier", 250, 70))), 1))
        assertEquals(7, store.snapshot().readings.size)
    }

    @Test fun recordingTouchesOnlyActiveRideBoundariesRegardlessOfSavedHistory() {
        val history = (1..30).map { number -> ride("old-$number").copy(
            state = RideState.ENDED, activeSlot = null, ended = at(10_000)) }
        val samples = history.flatMap { ride -> (1..10).map { sequence ->
            sample("${ride.id}-$sequence", ride.id, sequence.toLong(), sequence * 1_000L)
        } }
        store.write("history", WriteBatch(readings = listOf(reading("owner", 20_000, 0)),
            rides = history + ride(), samples = samples + sample("first", "active", 0, 1_000)), 1)
        queries.clear()
        store.write("checkpoint", WriteBatch(samples = listOf(sample("second", "active", 1, 2_000)),
            rideChanges = listOf(ride().copy(revision = 1, reducerState = "checkpoint"))), 1)
        val selects = queries.filter { it.sql.trimStart().startsWith("SELECT", ignoreCase = true) }
        assertFalse(selects.any { it.sql.contains("OdometerReading") })
        val sampleQueries = selects.filter { it.sql.contains("FROM RideSample") }
        assertEquals(2, sampleQueries.size)
        assertTrue(sampleQueries.all { it.sql.contains("WHERE rideId =") && it.sql.contains("LIMIT 1") })
        assertTrue(sampleQueries.all { it.args.first() == "active" })
        val rideQueries = selects.filter { it.sql.contains("FROM RideSession") }
        assertTrue(rideQueries.isNotEmpty())
        assertTrue(rideQueries.all { it.sql.contains("WHERE id =") && it.args.single() == "active" })
        queries.clear()
        store.write("state-only", WriteBatch(rideChanges = listOf(ride().copy(revision = 2,
            reducerState = "next checkpoint"))), 1)
        assertFalse(queries.any { it.sql.contains("FROM RideSample") || it.sql.contains("OdometerReading") })
        assertEquals(302, store.snapshot().samples.size)
    }

    @Test fun insertedSamplesAndNewEndCheckIndexedBoundariesAtomically() {
        store.write("start", WriteBatch(rides = listOf(ride()), samples = listOf(
            sample("first", "active", 0, 1_000), sample("last", "active", 4, 5_000))), 1)
        store.write("middle", WriteBatch(samples = listOf(sample("middle", "active", 2, 3_000))), 1)
        assertThrows(IllegalArgumentException::class.java) {
            store.write("bad-next", WriteBatch(samples = listOf(sample("bad-next", "active", 1, 4_000))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("bad-previous", WriteBatch(samples = listOf(sample("bad-previous", "active", 3, 2_000))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write("early-end", WriteBatch(rideChanges = listOf(ride().copy(
                state = RideState.ENDED, activeSlot = null, ended = at(4_000), revision = 1))), 1)
        }
        assertEquals(RideState.ACTIVE, store.snapshot().rides.single().state)
        queries.clear()
        assertTrue(store.write("early-end", WriteBatch(rideChanges = listOf(ride().copy(
            state = RideState.ENDED, activeSlot = null, ended = at(5_000), revision = 1))), 1))
        val boundary = queries.single { it.sql.contains("FROM RideSample") }
        assertTrue(boundary.sql.contains("EXISTS") && boundary.sql.contains("observed_epochMillis >"))
        assertEquals(listOf("active", at(5_000).epochMillis), boundary.args)
        assertEquals(3, store.snapshot().samples.size)
    }

    @Test fun sqlitePlansUseChronologyAndSampleIndexesWithoutHistoryScansOrSorting() {
        fun plan(sql: String, vararg args: Any): List<String> =
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
        val readingIndex = "index_OdometerReading_bikeId_certainty_observed_epochMillis"
        for (direction in listOf("<" to "DESC", ">" to "ASC")) {
            val details = plan("SELECT * FROM OdometerReading WHERE bikeId = ? AND certainty = 'CONFIRMED' " +
                "AND observed_epochMillis ${direction.first} ? ORDER BY observed_epochMillis ${direction.second} LIMIT 1",
                "bike", time.epochMillis)
            assertTrue(details.any { it.contains("SEARCH OdometerReading USING INDEX $readingIndex") })
            assertFalse(details.any { it.contains("SCAN OdometerReading") || it.contains("TEMP B-TREE") })
        }
        val sameTime = plan("SELECT * FROM OdometerReading WHERE bikeId = ? AND certainty = 'CONFIRMED' " +
            "AND observed_epochMillis = ? AND id != ? LIMIT 1", "bike", time.epochMillis, "reading")
        assertTrue(sameTime.any { it.contains("SEARCH OdometerReading USING INDEX $readingIndex") })
        for (direction in listOf("<" to "DESC", ">" to "ASC")) {
            val details = plan("SELECT * FROM RideSample WHERE rideId = ? AND sequence ${direction.first} ? " +
                "ORDER BY sequence ${direction.second} LIMIT 1", "active", 1)
            assertTrue(details.any { it.contains("SEARCH RideSample USING INDEX index_RideSample_rideId_sequence") })
            assertFalse(details.any { it.contains("SCAN RideSample") || it.contains("TEMP B-TREE") })
        }
        val end = plan("SELECT EXISTS(SELECT 1 FROM RideSample WHERE rideId = ? AND observed_epochMillis > ?)",
            "active", time.epochMillis)
        assertTrue(end.any { it.contains("SEARCH RideSample USING COVERING INDEX index_RideSample_rideId_observed_epochMillis") })
        assertFalse(end.any { it.contains("SCAN RideSample") || it.contains("TEMP B-TREE") })
    }
}
