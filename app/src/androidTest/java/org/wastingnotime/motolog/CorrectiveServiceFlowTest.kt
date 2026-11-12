package org.wastingnotime.motolog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.feature.maintenance.MaintenanceScreen

class CorrectiveServiceFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun validationRetryRestorationAndReadOnlyHistoryExposeEveryField() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("corrective-ui.db")
        val db = MotoLogDatabase.open(context, "corrective-ui.db")
        try {
            RegistrationRepository(db).register("bike", 2017, false, null, System.currentTimeMillis())
            val repo = MaintenanceRepository(db)
            val restore = StateRestorationTester(compose)
            restore.setContent { MaintenanceScreen(repo, "bike", null) {} }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New corrective service").fetchSemanticsNodes().isNotEmpty() }
            fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
            fun enter(label: String, text: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(text) }
            click("New corrective service")
            enter("Service date YYYY-MM-DD", "invalid")
            enter("Issue / description", "Synthetic issue")
            enter("Work performed", "Synthetic repaired wiring")
            enter("Motorcycle owner odometer km (optional)", "20000")
            enter("Service cost BRL (optional)", "1.001")
            enter("Service location (optional)", "Synthetic location")
            enter("Service replacements (optional)", "Synthetic connector")
            click("Save corrective service")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("maintenance-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Service date must be today or earlier (YYYY-MM-DD).").assertExists()
            enter("Service date YYYY-MM-DD", "2026-09-15")
            click("Save corrective service")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Cost must be nonnegative BRL with up to 12 whole digits and two decimal places, or blank for unknown.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(LocalStore(db).snapshot().histories.isEmpty())
            enter("Service cost BRL (optional)", "0.00")
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Synthetic issue").assertExists()
            click("Save corrective service")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New corrective service").fetchSemanticsNodes().isNotEmpty() }
            click("Completed maintenance history")
            listOf("Issue / description: Synthetic issue", "Cost BRL: 0.00", "Location: Synthetic location",
                "Replacements: Synthetic connector").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("Synthetic repaired wiring\nOwner odometer: 20000 km\nEvidence: unknown").performScrollTo().assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().let { assertEquals(1, it.size) }
            assertEquals(1, repo.completedHistory("bike").size)
        } finally { db.close(); context.deleteDatabase("corrective-ui.db") }
    }
}
