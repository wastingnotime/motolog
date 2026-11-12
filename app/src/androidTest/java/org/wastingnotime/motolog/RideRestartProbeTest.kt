package org.wastingnotime.motolog

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import java.time.ZoneId

/** Run seed/verify in separate instrumentation processes, with force-stop in between. */
class RideRestartProbeTest {
    @Test fun activeRideAndProjectionRecoverWithoutBridgingOrDuplicateEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val name = "ride-restart-probe.db"
        val t = 1_700_000_000_000L
        fun at(delta: Long) = observationAt(t + delta, ZoneId.of("UTC"))
        fun fix(delta: Long, epoch: String) = RideMetrics.Fix(at(delta), epoch, delta, delta, 0.0, 0.0, 5.0, 0.0, 1.0)
        if (phase != "verify") {
            context.deleteDatabase(name)
            MotoLogDatabase.open(context, name).let { db ->
                try {
                    LocalStore(db).write("bike", WriteBatch(bikes = listOf(Bike("bike"))), t)
                    val rides = RideRepository(db)
                    rides.start("ride", "bike", at(0), "old-process")
                    rides.accept("a", fix(0, "old-process")); rides.accept("b", fix(1_000, "old-process"))
                } finally { db.close() }
            }
        }
        if (phase != "seed") {
            val db = MotoLogDatabase.open(context, name)
            try {
                val rides = RideRepository(db)
                rides.interrupt("PROCESS_RESTART", at(10_000))
                assertEquals(RideState.INTERRUPTED, rides.status().first!!.state)
                rides.resume("ride", at(10_000), "new-process")
                rides.accept("b", fix(1_000, "old-process")) // exact callback replay
                rides.accept("c", fix(20_000, "new-process")); rides.accept("d", fix(21_000, "new-process"))
                val saved = LocalStore(db).snapshot()
                assertEquals(1, saved.rides.size); assertEquals(4, saved.samples.size)
                assertEquals(2_000, rides.view("bike", "2023-11-14").millis)
                assertTrue(saved.gaps.any { it.reason == "PROCESS_RESTART" && it.started == at(1_000) && it.ended == at(20_000) })
                rides.stop("ride", at(22_000)); rides.stop("ride", at(23_000))
                assertNull(rides.status().first)
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }
}
