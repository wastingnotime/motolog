package org.wastingnotime.motolog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.data.repository.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import org.wastingnotime.motolog.feature.ride.*
import java.util.UUID

/** Separate adb-driven phases verify actual permission-triggered process death and reopen. */
class RideLifecycleProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MotoLogApplication
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate()) { if (SystemClock.elapsedRealtime() > deadline) fail("Lifecycle probe timeout: ${app.rides.error.value}"); Thread.sleep(100) }
    }
    @Test fun permissionAndProcessRecoveryPhases() {
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: return
        val preferences = context.getSharedPreferences("synthetic-ride-lifecycle-probe", Context.MODE_PRIVATE)
        val repository = app.rides.repository
        val dao = app.database.dao()
        if (phase == "seed") {
            waitFor { app.rides.error.value == null }
            repository.status().first?.let { repository.stop(it.id, observationAt(System.currentTimeMillis()), true) }
            val bike = dao.bikeRows().firstOrNull()?.id ?: "synthetic-probe-bike".also {
                LocalStore(app.database).write("synthetic-probe-bike", WriteBatch(bikes = listOf(Bike(it))), System.currentTimeMillis())
            }
            var id = ""
            val manager = context.getSystemService(LocationManager::class.java)
            @Suppress("DEPRECATION")
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, 1, 1)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { it.startService(Intent(it, RideCaptureService::class.java).setAction(RideCaptureService.ARM)
                    .putExtra("bike", bike)) }
                waitFor { app.rides.running && repository.automatic() != null }
                repeat(5) {
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 0.0; longitude = 0.0; accuracy = 5f; speed = 6.1f / 3.6f; speedAccuracyMetersPerSecond = 1f
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                    Thread.sleep(5_100)
                }
                waitFor { repository.status().first?.ownership == RideOwnership.AUTOMATIC }
                id = repository.status().first!!.id
                preferences.edit().putString("ride", id).commit()
                waitFor { dao.lastSample(id)?.sequence?.let { it >= 1 } == true }
                preferences.edit().putLong("sequence", dao.lastSample(id)!!.sequence).commit()
                assertEquals(RideState.ACTIVE, repository.status().first!!.state)
            } finally { manager.removeTestProvider(LocationManager.GPS_PROVIDER); scenario.close() }
        } else if ((phase == "verify" || phase == "verifyRestart")) {
            val id = requireNotNull(preferences.getString("ride", null))
            waitFor { repository.status().first?.state == RideState.INTERRUPTED }
            val saved = repository.status()
            assertEquals(id, saved.first!!.id)
            assertNull(repository.automatic())
            assertNotNull(saved.second!!.gap)
            assertEquals(preferences.getLong("sequence", -1), dao.lastSample(id)!!.sequence)
            if (phase == "verify") assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            repository.stop(id, observationAt(System.currentTimeMillis()), true)
            assertEquals(dao.lastSample(id)!!.observed, dao.ride(id)!!.ended)
        } else if (phase == "denied") {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            val before = dao.rideSessionRows().size
            val bike = dao.bikeRows().first().id
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                for (action in listOf(RideCaptureService.ARM, RideCaptureService.START)) {
                    scenario.onActivity { it.startService(Intent(it, RideCaptureService::class.java).setAction(action)
                        .putExtra("bike", bike).putExtra("operation", UUID.randomUUID().toString())) }
                    waitFor { app.rides.error.value?.contains("Precise location") == true }
                    assertNull(repository.status().first); assertNull(repository.automatic())
                    assertEquals(before, dao.rideSessionRows().size)
                }
            } finally { scenario.close() }
        }
    }
}
