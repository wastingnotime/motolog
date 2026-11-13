package org.wastingnotime.motolog

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.observationAt
import org.wastingnotime.motolog.feature.ride.*

class RideScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeStartStopAndExplicitSameRideRecoveryRemainAccessibleWithoutFixes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MotoLogApplication
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
        }
        val repository = app.rides.repository
        repository.status().first?.let { repository.stop(it.id, observationAt(System.currentTimeMillis()), true) }
        val bike = app.database.dao().bikeRows().firstOrNull()?.id ?: "synthetic-screen-bike".also {
            LocalStore(app.database).write("synthetic-screen-bike", WriteBatch(bikes = listOf(Bike(it))), System.currentTimeMillis())
        }
        compose.setContent { RideScreen(app.rides, bike) {} }
        fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
        try {
            waitText("Start ride")
            compose.onNodeWithText("Automatic mode: off").assertExists()
            click("Arm automatic mode"); waitText("Disarm automatic mode")
            assertNotNull(repository.automatic()); assertNull(repository.status().first)
            click("Disarm automatic mode"); waitText("Arm automatic mode")
            assertNull(repository.automatic())
            click("Start ride"); waitText("Stop ride")
            val id = repository.status().first!!.id
            assertEquals(RideState.ACTIVE, repository.status().first!!.state)
            context.stopService(Intent(context, RideCaptureService::class.java))
            waitText("Resume same ride")
            compose.onNodeWithTag("ride-state").assertTextContains("Interrupted", substring = true)
            click("Resume same ride"); waitText("Stop ride")
            assertEquals(id, repository.status().first!!.id)
            click("Stop ride"); waitText("Start ride")
            assertNull(repository.status().first)
            click("Start ride"); waitText("Stop ride")
            val nextId = repository.status().first!!.id
            assertNotEquals(id, nextId)
            context.stopService(Intent(context, RideCaptureService::class.java))
            waitText("End at last observation"); click("End at last observation"); waitText("Start ride")
            assertNull(repository.status().first)
            assertEquals(RideState.ENDED, app.database.dao().ride(nextId)!!.state)
        } finally { context.stopService(Intent(context, RideCaptureService::class.java)) }
    }
}
