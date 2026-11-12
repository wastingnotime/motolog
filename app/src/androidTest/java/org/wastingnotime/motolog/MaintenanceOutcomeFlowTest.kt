package org.wastingnotime.motolog

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.feature.maintenance.MaintenanceScreen
import java.time.LocalDate

class MaintenanceOutcomeFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MotoLogDatabase
    private lateinit var repo: MaintenanceRepository
    @Before fun setup() {
        context.deleteDatabase("outcome-flow.db"); db = MotoLogDatabase.open(context, "outcome-flow.db")
        repo = MaintenanceRepository(db)
        RegistrationRepository(db).register("bike", 2017, false, null, System.currentTimeMillis())
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("outcome-flow.db") }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun enter(label: String, text: String) { compose.onNodeWithText(label).performScrollTo().performTextInput(text) }
    private fun pending() { compose.waitUntil(10_000) { compose.onAllNodesWithText("New programmed job").fetchSemanticsNodes().isNotEmpty() } }
    private fun start(restoration: StateRestorationTester? = null) {
        if (restoration == null) compose.setContent { MaintenanceScreen(repo, "bike", null) {} }
        else restoration.setContent { MaintenanceScreen(repo, "bike", null) {} }
        pending()
    }

    @Test fun ordinaryJobCreationSupportsDateMileageBothAndExplicitlyUnscheduledThenInitialScheduling() {
        start()
        val date = LocalDate.now().plusDays(2).toString()
        (0..3).forEach { index ->
            click("New programmed job")
            enter("Action", "Replace")
            enter("Component / job", "Synthetic UI $index")
            if (index == 3) click("Leave unscheduled intentionally")
            else {
                if (index != 0) enter("Absolute km target (optional)", "24000")
                if (index != 1) enter("Due date YYYY-MM-DD (optional)", if (index == 0) "invalid-date" else date)
                if (index == 0) {
                    click("Create programmed job")
                    compose.onNodeWithText("Use a valid date in YYYY-MM-DD.").assertExists()
                    assertTrue(LocalStore(db).snapshot().occurrences.isEmpty())
                    compose.onNodeWithText("Due date YYYY-MM-DD (optional)").performScrollTo().performTextReplacement(date)
                }
            }
            click("Create programmed job"); pending()
        }
        click("Schedule Synthetic UI 3")
        compose.onNodeWithText("Initial scheduling").assertExists()
        enter("Due date YYYY-MM-DD (optional)", date)
        click("Save initial schedule"); pending()
        compose.onNodeWithText("Initial scheduling · originally unscheduled; no performance recorded.").performScrollTo().assertIsDisplayed()
        val snapshot = LocalStore(db).snapshot()
        assertEquals(4, snapshot.occurrences.size); assertTrue(snapshot.histories.isEmpty())
        snapshot.occurrences.forEach { row ->
            when (row.rule.component) {
                "Synthetic UI 0" -> { assertNull(row.originalKm); assertEquals(date, row.originalDate) }
                "Synthetic UI 1" -> { assertEquals(24_000L, row.originalKm); assertNull(row.originalDate) }
                "Synthetic UI 2" -> { assertEquals(24_000L, row.originalKm); assertEquals(date, row.originalDate) }
                "Synthetic UI 3" -> { assertNull(row.originalDate); assertEquals(date, row.effectiveDate) }
            }
        }
    }

    @Test fun fullPostponementFormKeepsEntireSelectedChecklistOpenWithoutHistory() {
        listOf("A", "B").forEach { repo.createProgrammedJob(it, "bike", "Repair", "Synthetic $it", null, true, System.currentTimeMillis()) }
        start()
        click("Select Synthetic A"); click("Select Synthetic B")
        click("Record selected checklist (2)")
        compose.onNodeWithText("Full postponement — no performed history").assertExists()
        enter("New due date YYYY-MM-DD (optional)", LocalDate.now().plusDays(1).toString())
        enter("Postponement reason", "Synthetic full delay")
        click("Save full postponement"); pending()
        val saved = LocalStore(db).snapshot()
        assertTrue(saved.histories.isEmpty()); assertTrue(saved.satisfactions.isEmpty())
        assertEquals(2, saved.occurrences.size)
        assertTrue(saved.occurrences.all { it.state == org.wastingnotime.motolog.domain.model.OccurrenceState.POSTPONED })
        assertEquals(2, repo.existing("bike", null, LocalDate.now()).pending.size)
    }

    @Test fun selectedChecklistCanPartiallyCompleteAndFinishRemainderWithReadOnlyHistory() {
        listOf("A", "B").forEach { repo.createProgrammedJob(it, "bike", "Replace", "Synthetic $it", null, true, System.currentTimeMillis()) }
        val restoration = StateRestorationTester(compose)
        start(restoration)
        click("Select Synthetic A"); click("Select Synthetic B")
        click("Record selected checklist (2)")
        click("Performed: Replace Synthetic A (unscheduled)")
        enter("Performed work / notes", "Synthetic replacement")
        enter("Confirmed action evidence", "Confirmed synthetic physical replacement")
        enter("New due date YYYY-MM-DD (optional)", LocalDate.now().plusDays(1).toString())
        enter("Postponement reason", "Synthetic remaining work")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("save-outcome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic remaining work").assertExists()
        click("Save partial completion and remainder"); pending()
        compose.onNodeWithText("Select Synthetic A").assertDoesNotExist()
        click("Select Synthetic B"); click("Record selected checklist (1)")
        click("Performed: Replace Synthetic B (unscheduled)")
        enter("Performed work / notes", "Synthetic second replacement")
        enter("Confirmed action evidence", "Confirmed second synthetic action")
        click("Save full completion"); pending()
        click("Completed maintenance history")
        compose.onNodeWithText("Read-only · newest performed date first · unknown dates last").assertExists()
        assertEquals(2, LocalStore(db).snapshot().histories.size)
        assertTrue(repo.existing("bike", null, LocalDate.now()).pending.isEmpty())
    }
}
