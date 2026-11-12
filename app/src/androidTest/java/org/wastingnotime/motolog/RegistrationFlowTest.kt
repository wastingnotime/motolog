package org.wastingnotime.motolog

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.wastingnotime.motolog.data.local.MotoLogDatabase
import org.wastingnotime.motolog.data.repository.RegistrationRepository
import org.wastingnotime.motolog.feature.registration.RegistrationScreen

class RegistrationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MotoLogDatabase
    private lateinit var repository: RegistrationRepository
    @Before fun setup() {
        context.deleteDatabase("registration-flow.db")
        db = MotoLogDatabase.open(context, "registration-flow.db")
        repository = RegistrationRepository(db)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("registration-flow.db") }
    private fun start() { compose.setContent { RegistrationScreen(repository) } }
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun enter(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)
    }

    @Test fun offlineRegistrationUnknownHistoryAndOwnerAddedIntervalAreVisible() {
        start(); waitFor("Register offline"); click("Register offline")
        waitFor("Manual applicability unconfirmed")
        compose.onNodeWithText("Owner odometer: unknown.", substring = true).assertExists()
        compose.onNodeWithTag("coverage-gaps").assertExists()
        assertTrue(repository.snapshot().baselines.isEmpty())
        click("Add item")
        enter("Action", "Inspect")
        enter("Component / scope", "Synthetic accessory")
        enter("Interval months (optional)", "3")
        click("Save Custom item")
        waitFor("Custom · Inspect Synthetic accessory")
        compose.onNodeWithText("Source: Owner").assertExists()
        assertEquals(3, repository.snapshot().baselines.single().rule.intervalMonths)
        assertTrue(repository.snapshot().histories.isEmpty())
    }

    @Test fun intervalOnlyEditShowsCustomOriginalBaselineAndManualLocator() {
        repository.register("synthetic", 2016, true, null, System.currentTimeMillis())
        start(); waitFor("Edit External fuel filter")
        click("Edit External fuel filter")
        compose.onNodeWithText("Interval km (optional)").performScrollTo().performTextReplacement("12000")
        click("Save Custom item")
        waitFor("Custom · Replace External fuel filter")
        compose.onNodeWithText("Original baseline: Replace External fuel filter", substring = true).assertExists()
        compose.onNodeWithText("Source: Owner override; original source retained", substring = true).assertExists()
        assertEquals(15_000L, repository.snapshot().baselines.single { it.rule.component == "External fuel filter" }.rule.intervalKm)
        assertEquals(12_000L, repository.snapshot().overrides.single().rule.intervalKm)
    }

    @Test fun setupPreviewCanCancelAndPendingWorkNeverCreatesPerformedHistory() {
        repository.register("synthetic", null, false, null, System.currentTimeMillis())
        start(); waitFor("Local setup entry"); click("Local setup entry")
        click("Open programmed work")
        enter("Original source note", "Synthetic pending job")
        enter("Performed / planned action", "Inspect")
        enter("Component / actual scope", "Synthetic accessory")
        click("Preview local entry"); waitFor("Review entry")
        assertTrue(repository.snapshot().occurrences.isEmpty())
        click("Cancel preview")
        assertTrue(repository.snapshot().importRows.isEmpty())
        click("Preview local entry"); waitFor("Review entry"); click("Confirm local entry")
        waitFor("Open programmed work")
        assertEquals(1, repository.snapshot().occurrences.size)
        assertTrue(repository.snapshot().histories.isEmpty())
        assertNull(repository.snapshot().occurrences.single().effectiveDate)
    }
}
