// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.kiosk

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
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.SolarApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the kiosk plant's live poll running (and so its once-a-minute recording) while kiosk mode is on,
 * whatever the screen does. Holds a partial wake lock and a Wi-Fi lock, so the CPU and Wi-Fi stay up when
 * the screen goes off. The poll is the same one the kiosk screen shows, so devices get only one connection.
 */
class KioskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poll: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Kiosk.stop(this)
            return START_NOT_STICKY
        }
        // After the system restarts the service, the intent is null: use the remembered plant.
        val plantId = intent?.getStringExtra(EXTRA_PLANT) ?: Kiosk.plantId(this)
        if (plantId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(plantId, null),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Exception) {
            // Android may refuse a foreground start from the background (e.g. a restart after the app was killed).
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()

        val app = application as SolarApp
        poll?.cancel()
        poll = scope.launch {
            val name = app.repository.plant(plantId).first()?.name
            if (name == null) { // the plant was removed
                Kiosk.stop(this@KioskService)
                return@launch
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(plantId, name))
            app.live.state(plantId).collect { } // keeping it subscribed keeps the plant polled and recorded
        }
        return START_STICKY
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
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarDash:kiosk")
                .apply {
                    setReferenceCounted(false)
                    @Suppress("WakelockTimeout") // held for as long as kiosk mode is on, released in onDestroy
                    acquire()
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION") // still the only mode that keeps Wi-Fi up with the screen off
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SolarDash:kiosk")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun notification(plantId: String, plantName: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Created every time: for an existing channel this only updates its name (after a language change).
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.screens_kiosk_mode), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, KioskActivity.intent(this, plantId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KioskService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_kiosk)
            .setContentTitle(plantName?.let { getString(R.string.screens_kiosk_notification_title, it) } ?: getString(R.string.screens_kiosk_mode))
            .setContentText(getString(R.string.screens_kiosk_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.screens_end_kiosk_mode), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val EXTRA_PLANT = "plant"
        private const val ACTION_STOP = "ch.amslersolar.dashboard.kiosk.STOP"
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1
    }
}
