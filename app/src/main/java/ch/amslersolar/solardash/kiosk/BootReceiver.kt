// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a restart (e.g. a power outage) or an app update: if kiosk mode was on, recording starts again in the
 * kiosk service and the kiosk screen opens.
 *
 * Android lets a receiver start the service but usually not a screen. The screen comes back reliably when the
 * app is the tablet's home app ([HomeActivity]): Android opens the home app after every start. Without that, the
 * kiosk notification opens it with a tap; recording runs either way.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // An app update ends the running app: recording carries on (after a restart the app isn't running yet).
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) runCatching { ch.amslersolar.solardash.work.RecordingService.start(context) }
        val plantId = Kiosk.plantId(context) ?: return
        runCatching { Kiosk.startService(context, plantId) }
        runCatching { context.startActivity(KioskActivity.intent(context, plantId)) } // allowed on some tablets only
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", // some makers' fast boot
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
