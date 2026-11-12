package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*

class FuelProjectionTest {
    private val time = 1_700_000_000_000L
    private fun at(delta: Long) = Observation(time + delta, "UTC", 0)
    private fun reading(id: String, km: Long?, delta: Long = 0, certainty: Certainty = Certainty.CONFIRMED) =
        OdometerReading(id, "bike", km, certainty, ReadingSource.OWNER, at(delta), time)
    private fun fuel(id: String, delta: Long = 0, readingId: String? = id) =
        FuelEvent(id, "bike", readingId, FuelMode.LITERS, "100", "20", at(delta), time + 999_999)

    @Test fun exactFreshnessBoundaryExpiresAndNoReadingIsInvented() {
        val readings = listOf(reading("fuel", 100), reading("current", 125, 1_000))
        val events = listOf(fuel("fuel"))
        val boundary = time + 1_000 + FuelProjection.FRESH_MILLIS
        assertEquals(25L, FuelProjection.evaluate("bike", events, readings, boundary).kilometersSince)
        val stale = FuelProjection.evaluate("bike", events, readings, boundary + 1)
        assertEquals(FuelUnavailable.STALE, stale.unavailable)
        assertNull(stale.kilometersSince)
        assertEquals(FuelProjection.FRESH_MILLIS + 1, stale.currentAgeMillis)
        assertEquals(100L, stale.refuelReading!!.kilometers)
        assertEquals(FuelUnavailable.NO_REFUEL, FuelProjection.evaluate("bike", emptyList(), readings, time).unavailable)
        assertEquals(FuelUnavailable.NO_CURRENT, FuelProjection.evaluate("bike", events, emptyList(), time).unavailable)
        assertEquals(0L, FuelProjection.evaluate("bike", events, readings.take(1), time).kilometersSince)
    }

    @Test fun occurredOrderWinsAndTiesRemainAmbiguousWithStableId() {
        val events = listOf(fuel("new", 2_000), fuel("old"))
        val readings = listOf(reading("old", 100), reading("new", 120, 2_000), reading("current", 125, 3_000))
        val result = FuelProjection.evaluate("bike", events, readings, time + 3_000)
        assertEquals("new", result.latestRefuel!!.id)
        assertEquals(5L, result.kilometersSince)
        val tied = FuelProjection.evaluate("bike", events + fuel("z-tie", 2_000, "new"), readings, time + 3_000)
        assertEquals("z-tie", tied.latestRefuel!!.id)
        assertEquals(FuelUnavailable.AMBIGUOUS_REFUEL, tied.unavailable)
        assertNull(tied.kilometersSince)
        assertEquals(tied, FuelProjection.evaluate("bike", (events + fuel("z-tie", 2_000, "new")).reversed(), readings.reversed(), time + 3_000))
    }

    @Test fun newerUnusableRefuelNeverFallsBackAndUncertainReadingsCannotAdvanceCurrent() {
        val readings = listOf(reading("old", 100), reading("uncertain", 1_000, 3_000, Certainty.UNCERTAIN))
        val result = FuelProjection.evaluate("bike", listOf(fuel("old"), fuel("new", 2_000, null)), readings, time + 3_000)
        assertEquals("new", result.latestRefuel!!.id)
        assertEquals(FuelUnavailable.REFUEL_READING, result.unavailable)
        assertEquals("old", result.current!!.id)
        assertNull(result.kilometersSince)
        val uncertain = FuelProjection.evaluate("bike", listOf(fuel("old"), fuel("new", 3_000, "uncertain")), readings, time + 3_000)
        assertEquals(1_000L, uncertain.refuelReading!!.kilometers)
        assertEquals(FuelUnavailable.REFUEL_READING, uncertain.unavailable)
    }

    @Test fun futureDecreasingAndSameTimeConflictingReadingsAreUnavailable() {
        val events = listOf(fuel("fuel"))
        val original = reading("fuel", 100)
        listOf(listOf(original, reading("bad", 99, 1_000)),
            listOf(original, reading("bad", 101)),
            listOf(reading("before", 101, -1_000), original)).forEach {
            val result = FuelProjection.evaluate("bike", events, it, time + 1_000)
            assertEquals(FuelUnavailable.INVALID_SEQUENCE, result.unavailable)
            assertNull(result.kilometersSince)
        }
        assertEquals(FuelUnavailable.FUTURE, FuelProjection.evaluate("bike", events,
            listOf(original, reading("future", 110, 2_000)), time + 1_000).unavailable)
        assertEquals(FuelUnavailable.FUTURE, FuelProjection.evaluate("bike", listOf(fuel("future", 2_000)), emptyList(), time).unavailable)
        assertEquals(0L, FuelProjection.evaluate("bike", events,
            listOf(original, reading("equal", 100)), time).kilometersSince)
        assertEquals(listOf(ReadingSource.OWNER, ReadingSource.OWNER_IMPORT), ReadingSource.entries)
    }
}
