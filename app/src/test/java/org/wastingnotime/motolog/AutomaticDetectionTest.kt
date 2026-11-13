package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.domain.ride.*
import java.time.ZoneId

class AutomaticDetectionTest {
    private fun fix(t: Long, speed: Double = 6.0) = RideMetrics.Fix(
        observationAt(1_800_000_000_000 + t, ZoneId.of("UTC")), "epoch", t, t,
        0.0, 0.0, 5.0, speed / 3.6, 1.0)
    @Test fun movementRequiresTimeCountAndContinuousQuality() {
        fun result(times: List<Long>, change: (RideMetrics.Fix) -> RideMetrics.Fix = { it }): AutomaticDetection.Result {
            var r = AutomaticDetection.Result(AutomaticDetection.Window())
            times.forEach { r = AutomaticDetection.reduce(r.window, change(fix(it)), false) }
            return r
        }
        assertTrue(result(listOf(0, 5_000, 10_000, 15_000)).start)
        assertFalse(result(listOf(0, 5_000, 10_000, 14_999)).start)
        assertFalse(result(listOf(0, 6_000, 12_000)).start)
        assertFalse(result(listOf(0, 5_000, 11_001, 16_001)).start)
        assertFalse(result(listOf(0, 5_000, 10_000, 15_000)) { if (it.elapsed == 5_000L) it.copy(speed = 5.9 / 3.6) else it }.start)
        assertFalse(result(listOf(0, 5_000, 10_000, 15_000)) { if (it.elapsed == 5_000L) it.copy(speedAccuracy = null) else it }.start)
        assertFalse(result(listOf(0, 5_000, 10_000, 15_000)) { if (it.elapsed == 5_000L) it.copy(longitude = 1.0) else it }.start)
        assertFalse(result(listOf(0, 5_000, 10_000, 15_000)) { if (it.elapsed == 5_000L) it.copy(clockEpoch = "other") else it }.start)
    }
    @Test fun idleIncludesMeasuredStopsAndResetsOnMovementQualityGapsAndClockChanges() {
        var w = AutomaticDetection.Window()
        for (t in 0L..299_000L step 1_000) {
            val r = AutomaticDetection.reduce(w, fix(t, 3.0), true); assertFalse(r.stop); w = r.window
        }
        assertTrue(AutomaticDetection.reduce(w, fix(300_000, 3.0), true).stop)
        listOf(fix(300_000, 3.01), fix(300_000).copy(accuracy = 26.0),
            fix(310_000, 0.0), fix(300_000, 0.0).copy(clockEpoch = "new"),
            fix(300_000, 0.0).copy(observed = observationAt(1_800_000_500_000))).forEach {
            assertFalse(AutomaticDetection.reduce(w, it, true).stop)
        }
    }
}
