package org.wastingnotime.motolog

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.feature.registration.RegistrationScreen
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class FuelFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun enter(label: String, text: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(text) }
    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun observation(minutesAgo: Long) {
        enter("Observed local timestamp", Instant.ofEpochMilli(System.currentTimeMillis() - minutesAgo * 60_000)
            .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        enter("Observation zone", "UTC"); enter("UTC offset", "Z")
    }

    @Test fun bothFormsReviewRestorationOdometerAndReadOnlyHistory() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "fuel-flow-test.db"
        context.deleteDatabase(name)
        val db = MotoLogDatabase.open(context, name)
        try {
            val registration = RegistrationRepository(db)
            val repository = FuelRepository(db)
            registration.register("bike", null, false, null, System.currentTimeMillis())
            val restore = StateRestorationTester(compose)
            restore.setContent { RegistrationScreen(registration, fuel = repository) }
            waitFor("Refuel and owner odometer")
            click("Refuel and owner odometer")
            waitFor("New refuel")
            compose.onNodeWithTag("fuel-distance").assertTextContains("unavailable", substring = true)
            click("New refuel")
            enter("Total paid BRL", "100.001"); enter("Liters", "20.000")
            enter("Recorded owner odometer km", "100"); observation(10)
            click("Review refuel")
            compose.onNodeWithTag("fuel-input-error").assertTextContains("Total paid must be positive", substring = true)
            compose.onNodeWithText("Save refuel").assertDoesNotExist()
            enter("Total paid BRL", "100.00")
            click("Review refuel")
            compose.onNodeWithTag("fuel-review").performScrollTo().assertTextContains("Price per liter: BRL 5.000/L", substring = true)
            restore.emulateSavedInstanceStateRestore()
            click("Save refuel")
            waitFor("New refuel")
            assertEquals(1, repository.snapshot().fuels.size)
            click("New refuel")
            enter("Total paid BRL", "100.00"); enter("Liters", "20")
            enter("Recorded owner odometer km", "110"); observation(5)
            click("Review refuel")
            click("Enter price per liter instead")
            compose.onNodeWithText("Save refuel").assertDoesNotExist()
            enter("Price per liter BRL/L", "6.000")
            click("Review refuel")
            compose.onNodeWithTag("fuel-review").performScrollTo().assertTextContains("Liters: 16.667 L", substring = true)
            // Editing any reviewed field requires review again.
            enter("Recorded owner odometer km", "111")
            compose.onNodeWithText("Save refuel").assertDoesNotExist()
            enter("Recorded owner odometer km", "110"); click("Review refuel"); click("Save refuel")
            waitFor("New refuel")
            click("Update odometer")
            enter("Recorded owner odometer km", "120"); observation(1)
            click("Review owner reading"); click("Save owner reading")
            waitFor("New refuel")
            compose.onNodeWithTag("fuel-distance").assertTextEquals("Kilometers since refuel: 10 km")
            compose.onNodeWithText("Last refuel odometer: 110 km").assertExists()
            click("Refuel history")
            listOf("Recorded odometer: 110 km", "Liters: 16.667 L", "Price per liter: BRL 6.000/L",
                "Entered mode: Total paid + price per liter").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("Recorded odometer: 100 km").performScrollTo().assertIsDisplayed()
            assertEquals(2, repository.snapshot().fuels.size)
            assertEquals(3, repository.snapshot().readings.size)
            assertEquals(1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
