// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.kiosk

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun rememberKiosk(plantId: String?) {
        app.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit().apply {
            if (plantId == null) remove("plant") else putString("plant", plantId)
        }.commit()
    }

    @Test
    fun afterARestartKioskModeStartsAgain() {
        rememberKiosk("p1")
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val service = shadowOf(app).nextStartedService
        assertEquals(KioskService::class.java.name, service.component?.className)
        assertEquals("p1", service.getStringExtra(KioskService.EXTRA_PLANT))
        assertEquals(KioskActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test
    fun withoutKioskModeNothingStarts() {
        rememberKiosk(null)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun otherBroadcastsAreIgnored() {
        rememberKiosk("p1")
        BootReceiver().onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
        assertNull(shadowOf(app).nextStartedService)
    }
}
