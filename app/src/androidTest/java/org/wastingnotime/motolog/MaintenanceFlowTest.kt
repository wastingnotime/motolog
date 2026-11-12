package org.wastingnotime.motolog

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.feature.registration.RegistrationScreen

class MaintenanceFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MotoLogDatabase
    @Before fun setup() { context.deleteDatabase("maintenance-flow.db"); db = MotoLogDatabase.open(context, "maintenance-flow.db") }
    @After fun cleanup() { db.close(); context.deleteDatabase("maintenance-flow.db") }
    @Test fun largeJumpShowsReviewFeedbackAndRetainedObligations() {
        val registration = RegistrationRepository(db)
        val repo = MaintenanceRepository(db)
        val now = System.currentTimeMillis()
        registration.register("synthetic", 2017, true, OdometerReading("reading", "synthetic", 999,
            Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now - 1, "UTC", 0), now), now)
        repo.refresh("synthetic", "reading", java.time.LocalDate.now(), now)
        LocalStore(db).write("jump", WriteBatch(readings = listOf(OdometerReading("jump", "synthetic", 999_999_000_000,
            Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now, "UTC", 0), now))), now)
        compose.setContent { org.wastingnotime.motolog.feature.maintenance.MaintenanceScreen(repo, "synthetic", "jump") {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("due-coverage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mileage jump requires review:", substring = true).assertExists()
        compose.onAllNodesWithText("Original target: 1000 km / date unknown", substring = true)[0].performScrollTo().assertIsDisplayed()
        Assert.assertEquals(2, LocalStore(db).snapshot().obligations.size)
    }

    @Test fun dueScreenShowsUnknownCalendarPendingSourceAndPartialCoverage() {
        val registration = RegistrationRepository(db)
        val now = System.currentTimeMillis()
        registration.register("synthetic", 2017, true, OdometerReading("reading", "synthetic", 20_800,
            Certainty.CONFIRMED, ReadingSource.OWNER, Observation(now, "UTC", 0), now), now)
        compose.setContent { RegistrationScreen(registration, MaintenanceRepository(db)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Maintenance due and pending").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Maintenance due and pending").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("due-coverage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("due-coverage").assertExists()
        compose.onNodeWithText("Oil calendar status unknown; mileage is evaluated independently.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Original target: 20000 km / date unknown", substring = true)[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Source: Yamaha FAZER", substring = true)[0].assertExists()
        java.io.File(context.cacheDir, "maintenance-due.png").outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
