package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.domain.ride.*
import java.time.Instant
import java.time.ZoneId

class RideMetricsTest {
    private val start = Instant.parse("2026-01-01T23:59:55Z").toEpochMilli()
    private fun fix(t: Long = 0, speed: Double = 40.0, lon: Double = 0.0, zone: String = "UTC") =
        RideMetrics.Fix(observationAt(start + t, ZoneId.of(zone)), "epoch", t + 1_000, t + 1_000,
            0.0, lon, 5.0, speed / 3.6, 1.0)

    @Test fun thresholdUsesLastEventInEitherDirectionAndResetsAtGap() {
        var last: Double? = null
        val events = listOf(40.0, 44.999, 45.0, 49.999, 40.001, 40.0, 34.999).filter { speed ->
            RideMetrics.speedEvent(last, speed / 3.6).also { if (it) last = speed / 3.6 }
        }
        assertEquals(listOf(40.0, 45.0, 40.0, 34.999), events)
        assertTrue(RideMetrics.speedEvent(null, 44.0 / 3.6))
        assertFalse(RideMetrics.speedEvent(40.0 / 3.6, 44.999 / 3.6))
        assertTrue(RideMetrics.speedEvent(40.0 / 3.6, 45.001 / 3.6))
    }
    @Test fun qualityBoundariesRetainNoInventedNativeSpeed() {
        val f = fix()
        assertNull(RideMetrics.rejection(f.copy(accuracy = 25.0, speedAccuracy = 2.0, receivedElapsed = f.elapsed + 10_000)))
        assertEquals("HORIZONTAL_ACCURACY", RideMetrics.rejection(f.copy(accuracy = 25.001)))
        assertEquals("HORIZONTAL_ACCURACY", RideMetrics.rejection(f.copy(accuracy = 0.0)))
        assertEquals("FIX_AGE", RideMetrics.rejection(f.copy(receivedElapsed = f.elapsed + 10_001)))
        assertEquals("FIX_AGE", RideMetrics.rejection(f.copy(receivedElapsed = f.elapsed - 1)))
        assertEquals("NATIVE_SPEED", RideMetrics.rejection(f.copy(speed = null)))
        assertEquals("SPEED_ACCURACY", RideMetrics.rejection(f.copy(speedAccuracy = null)))
        assertEquals("SPEED_ACCURACY", RideMetrics.rejection(f.copy(speedAccuracy = 2.001)))
        assertNull(RideMetrics.rejection(f.copy(speed = 200.0 / 3.6)))
        assertEquals("NATIVE_SPEED", RideMetrics.rejection(f.copy(speed = 200.001 / 3.6)))
        assertEquals("COORDINATES", RideMetrics.rejection(f.copy(latitude = Double.NaN)))
    }
    @Test fun pairRejectsOutlierMissingRebootAndClockChangesAndIncludesStops() {
        val a = fix()
        assertEquals("IMPLIED_SPEED", RideMetrics.pair(a, fix(1_000, lon = 1.0)).reason)
        assertEquals("MISSING_FIX", RideMetrics.pair(a, fix(10_001)).reason)
        assertNull(RideMetrics.pair(a, fix(10_000)).reason)
        assertEquals("NON_INCREASING_TIME", RideMetrics.pair(a, a).reason)
        assertEquals("CLOCK_EPOCH", RideMetrics.pair(a, fix(1_000).copy(clockEpoch = "reboot")).reason)
        assertEquals("WALL_CLOCK_CHANGE", RideMetrics.pair(a, fix(1_000).copy(observed = observationAt(start + 3_001))).reason)
        assertNull(RideMetrics.pair(a, fix(1_000).copy(observed = observationAt(start + 3_000))).reason)
        val stopped = RideMetrics.pair(fix(speed = 3.0), fix(10_000, 3.0, .0001))
        assertEquals(0.0, stopped.meters, 0.0); assertEquals(10_000, stopped.millis)
        val moving = RideMetrics.pair(fix(speed = 3.0), fix(10_000, 3.001, .0001))
        assertEquals(11.131949, moving.meters, .0001)
    }
    @Test fun midnightAllocatesExactlyAndZoneChangeDoesNotRebucketEarlierSegment() {
        val a = fix(); val b = fix(10_000, lon = .001)
        val result = RideMetrics.pair(a, b)
        val days = RideMetrics.allocate(a, b, result)
        assertEquals(listOf("2026-01-01", "2026-01-02"), days.map { it.date })
        assertEquals(listOf(5_000L, 5_000L), days.map { it.millis })
        assertEquals(result.meters, days.sumOf { it.meters }, 1e-10)
        assertEquals(result.meters / 2, days[0].meters, 1e-10)
        val changed = RideMetrics.allocate(a, b.copy(observed = observationAt(start + 10_000, ZoneId.of("America/Sao_Paulo"))), result)
        assertEquals(days, changed)
        val c = fix(20_000, lon = .002, zone = "America/Sao_Paulo")
        val after = RideMetrics.allocate(b.copy(observed = c.observed.copy(epochMillis = start + 10_000)), c, result)
        assertEquals("America/Sao_Paulo", after.single().zone)
        assertEquals("2026-01-01", after.single().date)
    }
    @Test fun dstUsesActualTimeAndDateRatherThanAssumedDayLength() {
        for (instant in listOf("2026-03-08T06:59:55Z", "2026-11-01T05:59:55Z")) {
            val t = Instant.parse(instant).toEpochMilli(); val zone = ZoneId.of("America/New_York")
            val a = fix().copy(observed = observationAt(t, zone))
            val b = fix(10_000).copy(observed = observationAt(t + 10_000, zone))
            val pair = RideMetrics.pair(a, b)
            assertNull(pair.reason)
            val days = RideMetrics.allocate(a, b, pair)
            assertEquals(10_000, days.single().millis)
            assertEquals(observationAt(t, zone).zoneId, days.single().zone)
        }
    }
}
