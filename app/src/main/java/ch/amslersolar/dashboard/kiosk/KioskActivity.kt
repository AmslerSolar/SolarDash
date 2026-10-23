// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.kiosk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.amslersolar.dashboard.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ch.amslersolar.dashboard.SolarApp
import ch.amslersolar.dashboard.ui.theme.SolarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The energy flow full screen, for a wall-mounted tablet. The screen is kept on, so Android never locks it:
 * after [Kiosk.DIM_AFTER_MS] without a touch the page goes black and the backlight drops to its minimum, and
 * a touch brings the flow back. It also shows over the lock screen and turns the screen on, for when the
 * screen did go off (power button). Back or the × button ends kiosk mode.
 */
class KioskActivity : ComponentActivity() {
    private var dimmed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plantId = intent.getStringExtra(EXTRA_PLANT) ?: Kiosk.plantId(this)
        if (plantId == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Kiosk.startService(this, plantId)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Only for the "Kiosk mode" notification with its End button; recording runs either way.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        lifecycleScope.launch { Kiosk.exits.collect { finish() } }
        onBackPressedDispatcher.addCallback(this) { Kiosk.stop(this@KioskActivity) }

        val app = application as SolarApp
        setContent {
            SolarTheme {
                val plant by remember { app.repository.plant(plantId) }.collectAsStateWithLifecycle(initialValue = null)
                val state by remember { app.live.state(plantId) }.collectAsStateWithLifecycle()
                var lastTouch by remember { mutableStateOf(0L) }
                LaunchedEffect(lastTouch, dimmed) {
                    if (!dimmed) {
                        delay(Kiosk.DIM_AFTER_MS)
                        applyDim(true)
                    }
                }
                var homeHint by remember { mutableStateOf(Kiosk.showHomeHint(this)) }
                if (homeHint) {
                    HomeHintDialog(
                        onChoose = {
                            Kiosk.homeHintShown(this)
                            homeHint = false
                            openHomeSettings()
                        },
                        onLater = {
                            Kiosk.homeHintShown(this)
                            homeHint = false
                        },
                    )
                }
                KioskScreen(
                    plantName = plant?.name.orEmpty(),
                    state = state,
                    dimmed = dimmed,
                    onTouch = {
                        lastTouch = System.nanoTime()
                        applyDim(false)
                    },
                    onExit = { Kiosk.stop(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDim(false)
    }

    override fun onResume() {
        super.onResume()
        // Coming back (screen switched on, notification tapped): show the flow, not the black page.
        applyDim(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() // bars swiped in come back hidden
    }

    /** Android's "default home app" page (where the user picks this app), or the settings if it has none. */
    private fun openHomeSettings() {
        val intents = listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        intents.firstOrNull { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    private fun applyDim(value: Boolean) {
        if (dimmed == value && window.attributes.screenBrightness == brightness(value)) return
        dimmed = value
        window.attributes = window.attributes.apply { screenBrightness = brightness(value) }
    }

    /** Lowest backlight while dark; otherwise the user's own brightness. */
    private fun brightness(dim: Boolean) =
        if (dim) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_PLANT = "plant"

        fun intent(context: Context, plantId: String): Intent = Intent(context, KioskActivity::class.java)
            .putExtra(EXTRA_PLANT, plantId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** Asked once on the first kiosk start: being the home app is what brings the display back after a power outage. */
@Composable
private fun HomeHintDialog(onChoose: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.screens_home_hint_title)) },
        text = { Text(stringResource(R.string.screens_home_hint_text)) },
        confirmButton = { TextButton(onClick = onChoose) { Text(stringResource(R.string.screens_home_hint_choose)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.screens_home_hint_later)) } },
    )
}
