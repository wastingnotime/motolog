package org.wastingnotime.motolog.feature.ride

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.wastingnotime.motolog.MainActivity
import org.wastingnotime.motolog.MotoLogApplication
import org.wastingnotime.motolog.R
import org.wastingnotime.motolog.data.repository.RideRepository
import org.wastingnotime.motolog.domain.model.Observation
import org.wastingnotime.motolog.domain.ride.*
import java.util.UUID
import java.util.concurrent.Executors

/** Single ordered IO lane shared by app recovery, service commands and callbacks. */
class RideRuntime(application: MotoLogApplication) {
    private val executor = Executors.newSingleThreadExecutor()
    val repository = RideRepository(application.database)
    val error = MutableStateFlow<String?>(null)
    @Volatile var running = false
    val epoch: String = UUID.randomUUID().toString() // New process always breaks the monotonic anchor.
    init { execute { repository.interrupt("PROCESS_RESTART", observationAt(System.currentTimeMillis())) } }
    fun execute(block: () -> Unit) = execute(block, null)
    fun execute(block: () -> Unit, failure: (() -> Unit)?) {
        executor.execute {
            try { block() }
            catch (_: Exception) {
                running = false
                error.value = "Recording interrupted: capture or storage failed. Committed records are retained; up to five seconds may be missing. Reopen to recover."
                try { repository.interrupt("CAPTURE_OR_WRITE_FAILURE", observationAt(System.currentTimeMillis())) } catch (_: Exception) { /* Recovered on next process open. */ }
                failure?.invoke()
            }
        }
    }
}

class RideCaptureService : Service(), LocationListener {
    private val runtime get() = (application as MotoLogApplication).rides
    private val manager get() = getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var capturing = false
    private var currentRide: String? = null
    private val pulse = object : Runnable {
        override fun run() {
            if (!capturing) return
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                interrupt("PERMISSION_REVOKED"); return
            }
            val at = observationAt(System.currentTimeMillis()); val elapsed = SystemClock.elapsedRealtime()
            runtime.execute({ runtime.repository.heartbeat(at, elapsed, runtime.epoch) }, ::failed)
            main.postDelayed(this, 1_000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val commandGeneration = ++generation
        if (intent?.action == STOP) {
            val id = intent.getStringExtra("ride") ?: currentRide
            if (currentRide != null && id != currentRide) return START_NOT_STICKY
            stopCapture()
            runtime.execute({
                if (id != null) runtime.repository.stop(id, observationAt(System.currentTimeMillis()))
                main.post { if (generation == commandGeneration) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) } }
            }, ::failed)
            return START_NOT_STICKY
        }
        if (intent?.action != START && intent?.action != RESUME) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            runtime.error.value = "Precise location is required. Saved records remain available."
            interrupt("PERMISSION_DENIED"); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification(null))
        } catch (_: RuntimeException) {
            runtime.error.value = "Could not start location recording. Open MotoLog while stopped and try again."
            interrupt("SERVICE_START_FAILED"); return START_NOT_STICKY
        }
        val at = observationAt(System.currentTimeMillis())
        val minimumElapsed = SystemClock.elapsedRealtime()
        val rideId = intent.getStringExtra("ride")
        val operation = intent.getStringExtra("operation") ?: UUID.randomUUID().toString()
        val bike = intent.getStringExtra("bike") ?: ""
        runtime.execute({
            val id = if (intent.action == RESUME) {
                requireNotNull(rideId)
                runtime.repository.resume(rideId, at, runtime.epoch, minimumElapsed); rideId
            } else {
                val saved = runtime.repository.start(operation, bike, at, runtime.epoch, minimumElapsed)
                if (saved.state != org.wastingnotime.motolog.domain.model.RideState.ACTIVE) {
                    main.post { if (!capturing && generation == commandGeneration) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) } }
                    return@execute
                }
                saved.id
            }
            main.post {
                if (generation != commandGeneration) return@post
                currentRide = id
                getSystemService(NotificationManager::class.java).notify(1, notification(id))
                beginCapture()
            }
        }, ::failed)
        return START_NOT_STICKY // Explicit foreground recovery, no hidden restart/boot start.
    }

    private fun beginCapture() {
        if (capturing) return
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                interrupt("PERMISSION_REVOKED"); return
            }
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                interrupt("GPS_DISABLED"); return
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            capturing = true; runtime.running = true; runtime.error.value = null
            main.post(pulse)
        } catch (_: SecurityException) { interrupt("PERMISSION_REVOKED") }
        catch (_: RuntimeException) { interrupt("LOCATION_UNAVAILABLE") }
    }
    override fun onLocationChanged(location: Location) {
        if (!capturing) return
        val fix = RideMetrics.Fix(observationAt(location.time), runtime.epoch, location.elapsedRealtimeNanos / 1_000_000,
            SystemClock.elapsedRealtime(), location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null)
        val id = UUID.randomUUID().toString()
        runtime.execute({ runtime.repository.accept(id, fix) }, ::failed)
    }
    override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) interrupt("GPS_DISABLED") }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun interrupt(reason: String) {
        stopCapture()
        runtime.execute({ runtime.repository.interrupt(reason, observationAt(System.currentTimeMillis()))
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }, ::failed)
    }
    private fun failed() { main.post { stopCapture(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
    private fun stopCapture() {
        capturing = false; runtime.running = false; main.removeCallbacks(pulse)
        manager.removeUpdates(this)
    }
    override fun onDestroy() {
        stopCapture()
        runtime.execute { runtime.repository.interrupt("SERVICE_INTERRUPTED", observationAt(System.currentTimeMillis())) }
        super.onDestroy()
    }
    private fun notification(id: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_motolog)
            .setContentTitle("MotoLog ride recording").setContentText("GPS estimate · gaps excluded · tap to open")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
        if (id != null) {
            val stop = PendingIntent.getService(this, 1, Intent(this, RideCaptureService::class.java).setAction(STOP).putExtra("ride", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Stop ride", stop).build())
        }
        return builder.build()
    }
    companion object {
        const val START = "motolog.START_MANUAL"
        const val RESUME = "motolog.RESUME_MANUAL"
        const val STOP = "motolog.STOP_MANUAL"
        private const val CHANNEL = "ride-recording"
    }
}
