package org.wastingnotime.motolog

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import java.time.LocalDate
import java.util.UUID

/** Synthetic platform-GPS input exercises the real foreground service, IO lane and notification. */
class RideCaptureDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MotoLogApplication
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
    } }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate()) { if (SystemClock.elapsedRealtime() > deadline) fail("Capture condition timed out: ${app.rides.error.value}"); Thread.sleep(100) }
    }
    private fun view(bike: String) = app.rides.repository.view(bike, LocalDate.now().toString())
    private fun bike(): String {
        val dao = app.database.dao()
        return dao.bikeRows().firstOrNull()?.id ?: "synthetic-capture-bike".also {
            LocalStore(app.database).write("synthetic-capture-bike", WriteBatch(bikes = listOf(Bike(it))), System.currentTimeMillis())
        }
    }

    @Test fun foregroundBackgroundMissingFixRecoveryAndNotificationStop() {
        val packageName = context.packageName
        shell("pm grant $packageName ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set $packageName android:mock_location allow")
        val manager = context.getSystemService(LocationManager::class.java)
        val bike = bike()
        app.rides.repository.status().first?.let { app.rides.repository.stop(it.id, observationAt(System.currentTimeMillis()), true) }
        @Suppress("DEPRECATION")
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, 1, 1)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val initial = view(bike)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val operation = UUID.randomUUID().toString()
            scenario.onActivity { it.startService(Intent(it, RideCaptureService::class.java)
                .setAction(RideCaptureService.START).putExtra("bike", bike).putExtra("operation", operation)) }
            waitFor { app.rides.running && app.rides.repository.status().first?.state == RideState.ACTIVE }
            fun emit() {
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0; longitude = 0.0; accuracy = 5f; speed = 0f; speedAccuracyMetersPerSecond = 1f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
                Thread.sleep(1_100)
            }
            emit(); emit()
            waitFor { view(bike).millis > initial.millis }
            val foreground = view(bike)
            assertEquals(operation, foreground.active!!.id)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 && it.notification.actions.isNotEmpty() })
            shell("input keyevent KEYCODE_HOME")
            emit(); emit()
            waitFor { view(bike).millis > foreground.millis }
            val beforeGap = view(bike).millis
            Thread.sleep(11_500)
            waitFor { view(bike).cursor?.gapReason == "MISSING_FIX" }
            emit(); emit()
            waitFor { view(bike).cursor?.gap == null }
            val recovered = view(bike)
            assertEquals(operation, recovered.active!!.id)
            assertTrue(recovered.millis - beforeGap in 500..5_000)
            assertTrue(recovered.gaps.any { it.reason == "MISSING_FIX" })
            assertEquals(initial.meters, recovered.meters, 0.0)
            assertEquals(0.0, app.rides.repository.detail(operation).total!!.meters, 0.0)
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 1 }
            notification.notification.actions.single().actionIntent.send()
            waitFor { app.rides.repository.status().first == null && !app.rides.running }
            assertTrue(app.database.dao().rideSampleRows().any { it.rideId == operation })
            assertEquals(RideState.ENDED, app.database.dao().ride(operation)!!.state)
        } finally {
            context.stopService(Intent(context, RideCaptureService::class.java))
            scenario.close(); manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set $packageName android:mock_location default")
        }
    }
}
