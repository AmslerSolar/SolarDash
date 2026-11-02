// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.amslersolar.dashboard.MainActivity
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.SolarApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps every plant read and recorded (once a minute) for as long as the app runs, whatever screen is open and
 * with the screen off: the history has no gaps while the app is in the background. It keeps each plant's live
 * poll subscribed, the same poll the screens show, so devices still get one connection only. Holds a partial
 * wake lock and a Wi-Fi lock like the kiosk service. Ends when the app is swiped away; started again by the
 * app's screens and after an app update.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Exception) {
            // Android may refuse a foreground start from the background; the next screen start tries again.
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        acquireLocks()
        val app = application as SolarApp
        scope.launch {
            // The plants with devices; a plant added or removed restarts the set.
            app.repository.plants.map { list -> list.filter { it.devices.isNotEmpty() }.map { it.id } }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    coroutineScope {
                        // Subscribed is enough: the poll reads and records while anyone collects it.
                        for (id in ids) launch { app.live.state(id).collect { } }
                    }
                }
        }
        return START_STICKY
    }

    /** The app was swiped away: it no longer runs, so neither does the recording (kiosk mode has its own). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarDash:recording")
                .apply {
                    setReferenceCounted(false)
                    @Suppress("WakelockTimeout") // held while the app runs, released in onDestroy
                    acquire()
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION") // still the only mode that keeps Wi-Fi up with the screen off
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SolarDash:recording")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Created every time: for an existing channel this only updates its name (after a language change).
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.screens_recording_channel), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_kiosk)
            .setContentTitle(getString(R.string.screens_recording_title))
            .setContentText(getString(R.string.screens_recording_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 2

        /** Starts recording (or keeps it running); allowed while one of the app's screens is in front. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)) }
        }
    }
}
