// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import ch.amslersolar.dashboard.model.LoggedEvent
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A history.db written by the first version (schema 1) opens in this version with its data intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Version1(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE samples (plant_id TEXT NOT NULL, ts INTEGER NOT NULL,
                    pv INTEGER, load INTEGER, battery INTEGER, grid INTEGER, soc INTEGER, PRIMARY KEY (plant_id, ts))""",
            )
            db.execSQL(
                """CREATE TABLE daily (plant_id TEXT NOT NULL, day TEXT NOT NULL,
                    pv REAL, load REAL, export REAL, import REAL, PRIMARY KEY (plant_id, day))""",
            )
            db.execSQL("INSERT INTO samples VALUES ('p1', 1760000000, 3000, 800, -500, 1700, 55)")
            db.execSQL("INSERT INTO daily VALUES ('p1', '2025-10-09', 21.5, 9.25, 14.0, 1.75)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun version1DataSurvivesAndNewTablesWork() = runBlocking {
        Version1(context).writableDatabase.close()
        val history = HistoryRepository(context)
        assertEquals(LocalDate.of(2025, 10, 9), history.firstDay("p1"))
        val day = history.days("p1", LocalDate.of(2025, 10, 9), LocalDate.of(2025, 10, 9)).single()
        assertEquals(21.5, day.energy.pvKwh!!, 1e-9)
        assertEquals(3000, history.samples("p1", 0, Long.MAX_VALUE).single().pvW)
        // Tables added in versions 2 and 3 exist.
        assertEquals(emptyList<Triple<String, String, String>>(), history.channelCatalog("d1"))
        assertNull(history.storedDeviceId("GOODWE_ET", "123", emptySet()))
        // Version 4: the fault log.
        history.openEvent(LoggedEvent(plantId = "p1", key = "DEVICE_FAULT|d1|x", kind = "DEVICE_FAULT", severity = "ALARM",
            deviceId = "d1", deviceName = "Axpert", detail = "x", percent = null, started = 100, ended = null))
        val open = history.openEvents("p1").single()
        history.closeEvent(open.id, 160)
        assertEquals(160L, history.events("p1").single().ended)
        assertEquals(emptyList<LoggedEvent>(), history.openEvents("p1"))
    }
}
