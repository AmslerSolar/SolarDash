// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.kiosk

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Kiosk mode for a wall-mounted tablet: the energy flow full screen, dark after [DIM_AFTER_MS] without a
 * touch, and recording in a foreground service even if the screen really goes off. The plant is remembered,
 * so the app goes back into kiosk mode when it's started again (after a reboot or a crash).
 */
object Kiosk {
    const val DIM_AFTER_MS = 30_000L
    private const val PREFS = "kiosk"
    private const val KEY_PLANT = "plant"
    private const val KEY_HOME_HINT = "home_hint_shown"

    private val _exits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when kiosk mode is ended from the notification, so an open kiosk screen closes too. */
    val exits: SharedFlow<Unit> = _exits

    /** Whether the "choose as home app" hint should be shown (once, and only while the app isn't the home app). */
    fun showHomeHint(context: Context): Boolean =
        !HomeActivity.isDefaultHome(context) && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_HINT, false)

    fun homeHintShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_HINT, true).apply()
    }

    fun plantId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLANT, null)

    /** Opens the kiosk screen for a plant and starts recording in the background. */
    fun start(context: Context, plantId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PLANT, plantId).apply()
        context.startActivity(KioskActivity.intent(context, plantId))
    }

    fun startService(context: Context, plantId: String) {
        ContextCompat.startForegroundService(context, Intent(context, KioskService::class.java).putExtra(KioskService.EXTRA_PLANT, plantId))
    }

    /** Ends kiosk mode: forgets the plant, stops the service and closes the kiosk screen. */
    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PLANT).apply()
        context.stopService(Intent(context, KioskService::class.java))
        _exits.tryEmit(Unit)
    }
}
