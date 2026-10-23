// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.kiosk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import ch.amslersolar.dashboard.MainActivity

/**
 * The app as the tablet's home app (chosen once in Android's settings, see [isDefaultHome]): Android opens it
 * after every start and on the Home button, and it goes straight to the kiosk screen if kiosk mode is on, else
 * to the plant list. That's what brings the wall display back by itself after a power outage.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plantId = Kiosk.plantId(this)
        startActivity(
            if (plantId != null) KioskActivity.intent(this, plantId)
            else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        /** True when this app is the tablet's default home app. */
        fun isDefaultHome(context: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == context.packageName
        }
    }
}
