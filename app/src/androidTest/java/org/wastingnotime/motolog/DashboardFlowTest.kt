package org.wastingnotime.motolog

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import org.wastingnotime.motolog.feature.dashboard.MotoLogScreen
import org.wastingnotime.motolog.feature.ride.RideCaptureService
import java.time.LocalDate

class DashboardFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun enter(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
    private fun shot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        @Suppress("DEPRECATION")
        val directory = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val output = java.io.File(directory, "$name-${context.resources.configuration.fontScale}-${System.currentTimeMillis()}.png")
        // UiAutomation takes executable arguments, not a shell-quoted script.
        require(output.path.none { it.isWhitespace() })
        instrumentation.uiAutomation.executeShellCommand("screencap -p ${output.path}").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        instrumentation.uiAutomation.executeShellCommand("ls ${output.path}").use {
            val result = java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
            check(result.contains(output.name)) { "Screenshot was not saved" }
        }
    }
    @Test fun airplaneModeDashboardQuickRefuelManualControlsAndParkedRoutesKeepRecording() {
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.let {
            assertEquals("Device font scale", it.toFloat(), context.resources.configuration.fontScale, 0.01f)
        }
        val app = context.applicationContext as MotoLogApplication
        val db = app.database
        val registration = RegistrationRepository(db)
        val fuel = FuelRepository(db)
        val maintenance = MaintenanceRepository(db)
        val rides = app.rides.repository
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
        }
        rides.status().first?.let { rides.stop(it.id, observationAt(System.currentTimeMillis()), true) }
        val bike = fuel.snapshot().bikes.firstOrNull()?.id ?: "synthetic-dashboard".also { registration.register(it, null, false, null, System.currentTimeMillis()) }
        assertTrue("Run dashboard flow against a clean app-owned test installation", fuel.snapshot().fuels.isEmpty())
        val shift = java.util.concurrent.atomic.AtomicLong(0)
        compose.setContent { MotoLogScreen(registration, maintenance, fuel, app.rides) { System.currentTimeMillis() + shift.get() } }
        waitText("Quick refuel")
        compose.onNodeWithText("Start ride").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("Quick refuel").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("Arm automatic mode").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("MotoLog · Your Fazer").performScrollTo()
        shot("v1-controls-idle")
        compose.onNodeWithTag("dashboard-distance").performScrollTo().assertTextContains("Unavailable", substring = true)
        compose.onNodeWithTag("dashboard-average").performScrollTo().assertTextContains("Unavailable", substring = true)
        compose.onNodeWithTag("dashboard-fuel").performScrollTo().assertTextContains("No refuel recorded", substring = true)
        compose.onNodeWithTag("dashboard-last-refuel").assertTextEquals("Unavailable")
        click("Quick refuel")
        enter("Total paid BRL", "100"); enter("Liters", "20"); enter("Recorded owner odometer km", "1000")
        compose.onNodeWithText("I observed this reading now").performScrollTo().assertExists()
        click("Review refuel")
        compose.onNodeWithTag("fuel-review").performScrollTo().assertTextContains("Price per liter: BRL 5.000/L", substring = true)
        compose.onNodeWithText("Save refuel").performScrollTo().assertIsDisplayed()
        shot("task20-refuel-review")
        click("Save refuel"); waitText("Quick refuel")
        assertEquals(1, fuel.snapshot().fuels.size)
        compose.onNodeWithTag("dashboard-fuel").performScrollTo().assertTextEquals("0 km")
        compose.onNodeWithTag("dashboard-last-refuel").assertTextEquals("1000 km")
        compose.onNodeWithTag("dashboard-reading").performScrollTo().assertTextContains("fresh", substring = true)
        shot("task20-dashboard-fuel-and-reading")
        shift.set(25 * 3_600_000L)
        click("Settings"); waitText("Automatic tracking: off"); click("Back to dashboard"); waitText("Quick refuel")
        compose.onNodeWithTag("dashboard-fuel").performScrollTo().assertTextContains("older than 24 hours", substring = true)
        compose.onNodeWithTag("dashboard-reading").assertTextContains("not current", substring = true)
        shift.set(-3_600_000L)
        click("Settings"); waitText("Automatic tracking: off"); click("Back to dashboard"); waitText("Quick refuel")
        compose.onNodeWithTag("dashboard-fuel").performScrollTo().assertTextContains("future", substring = true)
        shift.set(0)
        // Deterministic saved evidence: moving pair + stopped pair + missing native speed.
        val start = System.currentTimeMillis() - 20_000
        rides.start("dashboard-trace", bike, observationAt(start), "trace")
        fun fix(t: Long, speed: Double?, lon: Double) = RideMetrics.Fix(observationAt(start + t), "trace", t + 1_000, t + 1_000, 0.0, lon, 5.0, speed, 1.0)
        rides.accept("a", fix(0, 10.0, 0.0)); rides.accept("b", fix(1_000, 10.0, .0001))
        rides.accept("c", fix(2_000, null, .0001)); rides.accept("d", fix(3_000, 0.0, .0001)); rides.accept("e", fix(4_000, 0.0, .000101))
        rides.stop("dashboard-trace", observationAt(start + 5_000))
        val expected = rides.view(bike, LocalDate.now().toString())
        click("Settings"); waitText("Automatic tracking: off"); click("Back to dashboard"); waitText("Quick refuel")
        compose.onNodeWithTag("dashboard-distance").performScrollTo().assertTextEquals(String.format(java.util.Locale.US, "%.3f km", expected.meters / 1000))
        compose.onNodeWithTag("dashboard-average").assertTextEquals(String.format(java.util.Locale.US, "%.1f km/h", expected.averageKmh))
        click("Start ride"); waitText("Stop ride")
        val active = rides.status().first!!.id
        // The pinned status bar and embedded controls refresh independently.
        // Capture their settled state rather than the first header update.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("ride-state") and hasText("Recording", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Start ride").assertDoesNotExist()
        compose.onNodeWithText("Not recording").assertDoesNotExist()
        compose.onAllNodesWithText("Stop ride").onLast().performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("MotoLog · Your Fazer").performScrollTo()
        compose.onAllNodesWithText("Stop ride").onFirst().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        shot("v1-controls-recording")
        // Management remains available and never implicitly stops the ride.
        click("Ride history"); waitText("Ride history")
        compose.onNodeWithText("Parked management · read only").assertExists()
        assertEquals(active, rides.status().first!!.id)
        click("Back to dashboard"); waitText("Quick refuel")
        click("Maintenance and pending work"); waitText("Maintenance due and pending")
        assertEquals(active, rides.status().first!!.id)
        click("Back to checklist") // returns to the invoking dashboard
        waitText("Quick refuel")
        click("Motorcycle and checklist"); waitText("Local setup entry")
        assertEquals(active, rides.status().first!!.id)
        click("Back to dashboard"); waitText("Quick refuel")
        click("Quick refuel"); click("Back to dashboard"); waitText("Quick refuel")
        assertEquals(active, rides.status().first!!.id)
        // Status-bar Stop remains available even if the dashboard is scrolled.
        compose.onAllNodesWithText("Stop ride").onFirst().performClick(); waitText("Start ride")
        assertNull(rides.status().first)
        compose.onNodeWithTag("dashboard-distance").performScrollTo()
        shot("task20-dashboard-metrics")
        click("Start ride"); waitText("Stop ride")
        val next = rides.status().first!!.id
        context.stopService(Intent(context, RideCaptureService::class.java))
        waitText("Resume same ride"); click("Resume same ride"); waitText("Stop ride")
        assertEquals(next, rides.status().first!!.id)
        compose.onAllNodesWithText("Stop ride").onFirst().performClick(); waitText("Start ride")
        click("Settings"); waitText("Automatic tracking: off"); click("Back to dashboard"); waitText("Quick refuel")
        click("Arm automatic mode"); waitText("Disarm automatic mode")
        waitText("Automatic mode armed")
        compose.onNodeWithText("Disarm").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Disarm automatic mode").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("MotoLog · Your Fazer").performScrollTo()
        shot("v1-controls-armed")
        click("Settings"); waitText("Automatic tracking: armed")
        compose.onNodeWithText("Disarm").performClick(); waitText("Automatic tracking: off")
        assertNull(rides.automatic())
        click("Back to dashboard"); waitText("Quick refuel")
        // Freshness and fuel result change from owner evidence only, even with GPS totals present.
        assertEquals(1, fuel.snapshot().readings.size)
        compose.onNodeWithText("MotoLog · Your Fazer").performScrollTo()
        shot("task20-dashboard-controls")
    }
}
