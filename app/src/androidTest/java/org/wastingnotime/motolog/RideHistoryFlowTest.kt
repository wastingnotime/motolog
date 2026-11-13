package org.wastingnotime.motolog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.ride.*
import org.wastingnotime.motolog.feature.common.observationText
import org.wastingnotime.motolog.feature.ride.RideHistoryScreen

class RideHistoryFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    @Test fun historicalUnavailableMeasuredZeroCoverageAndPagedEventsAreReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ride-history-flow.db"
        context.deleteDatabase(name)
        val db = MotoLogDatabase.open(context, name)
        try {
            val repo = RideRepository(db)
            val now = System.currentTimeMillis() - 100_000
            RegistrationRepository(db).register("bike", null, false, null, now)
            val emptyStart = observationAt(now - 3_600_000)
            repo.start("empty", "bike", emptyStart, "epoch"); repo.stop("empty", emptyStart)
            val stoppedStart = observationAt(now)
            repo.start("measured", "bike", stoppedStart, "epoch")
            repeat(25) { i ->
                repo.accept("$i", RideMetrics.Fix(observationAt(now + i * 1000), "epoch", i * 1000L + 1000, i * 1000L + 1000,
                    0.0, 0.0, 5.0, if (i % 2 == 0) 0.0 else 2.0, 1.0))
            }
            repo.stop("measured", observationAt(now + 25_000))
            compose.setContent { RideHistoryScreen(repo, "bike") {} }
            waitFor("${observationText(emptyStart)} · Ended")
            click("${observationText(emptyStart)} · Ended")
            waitFor("GPS distance: unavailable · no eligible pairs")
            compose.onNodeWithText("Average speed: unavailable · no eligible time").assertExists()
            compose.onNodeWithText("Eligible duration: unavailable").assertExists()
            click("Back to rides"); waitFor("${observationText(stoppedStart)} · Ended")
            click("${observationText(stoppedStart)} · Ended")
            waitFor("GPS distance: 0.000 km")
            compose.onNodeWithText("Average speed: 0.0 km/h").assertExists()
            compose.onNodeWithText("Eligible duration: 24 seconds").assertExists()
            compose.onNodeWithText("Partial GPS coverage", substring = true).assertExists()
            compose.onNodeWithText(RideMetrics.VERSION, substring = true).assertDoesNotExist()
            click("Next speed events"); waitFor("First speed events")
            click("First speed events"); waitFor("Next speed events")
            click("Recording source details")
            compose.onNodeWithText("Projection: ${RideMetrics.VERSION}", substring = true).performScrollTo().assertExists()
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
