package com.dropbop.commuteoptimizer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

data class Fix(
    val tMillis: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float?,
    val spd: Float?,
    val brg: Float?
)

class LocationRecordingService : Service() {

    companion object {
        const val ACTION_START = "START_RECORDING"
        const val ACTION_STOP = "STOP_RECORDING"
        const val ACTION_EXPORT = "EXPORT_TRIP"
        const val EXTRA_ROUTE_LABEL = "ROUTE_LABEL"

        private const val NOTIF_ID = 42
        private const val NOTIF_CHANNEL = "commute_recorder"
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var request: LocationRequest
    private val fixes = java.util.concurrent.CopyOnWriteArrayList<Fix>()
    private var startUtc: Long = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                if (loc.hasAccuracy() && loc.accuracy > 50f) continue
                fixes += Fix(
                    tMillis = System.currentTimeMillis(),
                    lat = loc.latitude,
                    lon = loc.longitude,
                    acc = if (loc.hasAccuracy()) loc.accuracy else null,
                    spd = if (loc.hasSpeed()) loc.speed else null,
                    brg = if (loc.hasBearing()) loc.bearing else null
                )
            }
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)

        // Balanced accuracy, ~1s interval, 3 m min distance.
        request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, /* intervalMillis */ 1000L
        )
            .setMinUpdateDistanceMeters(3f)
            .setWaitForAccurateLocation(false)
            .build()

        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording() // remove updates; keep service alive awaiting export
            ACTION_EXPORT -> {
                val label = intent.getStringExtra(EXTRA_ROUTE_LABEL) ?: "unlabeled"
                TripWriter.saveTrip(this, fixes.toList(), startUtc, System.currentTimeMillis(), label)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        fixes.clear()
        startUtc = System.currentTimeMillis()

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Commute Logger")
            .setContentText("Recording commute…")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }

        fused.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun stopRecording() {
        fused.removeLocationUpdates(callback)

        val waiting = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Commute Logger")
            .setContentText("Waiting for route label…")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, waiting,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, waiting)
        }
    }

    private fun updateNotification() {
        val last = fixes.lastOrNull()
        val text = if (last != null)
            "Fixes: ${fixes.size} • acc≈${last.acc ?: "-"} m • t+${SystemClock.elapsedRealtime()/1000}s"
        else "Waiting for first fix…"

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("Commute Logger")
                .setContentText(text)
                .setOngoing(true)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL, "Commute recording",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
