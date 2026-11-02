// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import ch.amslersolar.solardash.model.Plausible
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.DeflaterOutputStream

/** A GoodWe ET's "not available" (all ones) stored as 4294967295 W and 429496729.5 kWh by version 6 is removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ImplausibleHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun notAvailablePatterns() {
        for (v in listOf(4294967295.0, 429496729.5, 42949672.95, 4294967.295, 2147483647.0, -2147483648.0)) assertTrue("$v", Plausible.notAvailable(v))
        for (v in listOf(0.0, 230.4, 4294967.0, 1_000_000.0, -1.0)) assertFalse("$v", Plausible.notAvailable(v))
        assertNull(Plausible.power(-2147483648 + 1))
        assertEquals(5_000, Plausible.power(5_000))
    }

    private fun deflate(s: String) = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private inner class Version6 : SQLiteOpenHelper(context, "history.db", null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples (plant_id TEXT NOT NULL, ts INTEGER NOT NULL, pv INTEGER, load INTEGER, battery INTEGER, grid INTEGER, soc INTEGER, PRIMARY KEY (plant_id, ts))")
            db.execSQL("CREATE TABLE daily (plant_id TEXT NOT NULL, day TEXT NOT NULL, pv REAL, load REAL, export REAL, import REAL, PRIMARY KEY (plant_id, day))")
            db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, key TEXT NOT NULL, label TEXT NOT NULL, unit TEXT NOT NULL, UNIQUE (device_id, key))")
            db.execSQL("CREATE TABLE device_samples (device_id TEXT NOT NULL, ts INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (device_id, ts))")
            db.execSQL("CREATE TABLE devices (device_id TEXT PRIMARY KEY, type TEXT NOT NULL, serial TEXT NOT NULL, name TEXT NOT NULL, removed_at INTEGER)")
            db.execSQL(
                """CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, plant_id TEXT NOT NULL, key TEXT NOT NULL, kind TEXT NOT NULL,
                    severity TEXT NOT NULL, device_id TEXT, device_name TEXT, detail TEXT, percent INTEGER, started INTEGER NOT NULL,
                    ended INTEGER, details TEXT, end_details TEXT)""",
            )
            db.execSQL("CREATE TABLE device_daily (device_id TEXT NOT NULL, day TEXT NOT NULL, pv REAL, load REAL, export REAL, import REAL, PRIMARY KEY (device_id, day))")
            db.execSQL("INSERT INTO samples VALUES ('p', 100, 4294967295, 2440, 0, 2870, 82)")
            db.execSQL("INSERT INTO samples VALUES ('p', 160, 5110, 2580, -3100, 0, 82)")
            db.execSQL("INSERT INTO daily VALUES ('p', '2026-11-02', 429496729.5, 15.9, 0.3, 0.1)")
            db.execSQL("INSERT INTO channels VALUES (1, 'gw', 'r:pv_power', 'Solar power', 'W')")
            db.execSQL("INSERT INTO channels VALUES (2, 'gw', 'r:today_pv', 'Solar yield today', 'kWh')")
            db.execSQL("INSERT INTO channels VALUES (3, 'gw', 'd:Measurements:PV Total Power', 'PV Total Power', 'W')")
            val bad = deflate("""{"1":4294967295,"2":429496729.5,"3":4294967295}""")
            val good = deflate("""{"1":5110,"2":31.4,"3":5110}""")
            db.insert("device_samples", null, android.content.ContentValues().apply { put("device_id", "gw"); put("ts", 100); put("data", bad) })
            db.insert("device_samples", null, android.content.ContentValues().apply { put("device_id", "gw"); put("ts", 160); put("data", good) })
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun version6ReadingsThatNoPlantMakesAreRemoved() = runBlocking {
        context.deleteDatabase("history.db")
        Version6().writableDatabase.close()
        val history = HistoryRepository(context)
        val samples = history.samples("p", 0, Long.MAX_VALUE)
        assertNull(samples[0].pvW)
        assertEquals(2440, samples[0].loadW) // the rest of that sample stays
        assertEquals(5110, samples[1].pvW)
        val day = history.days("p", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2)).single().energy
        assertNull(day.pvKwh)
        assertEquals(15.9, day.loadKwh!!, 1e-9)
        val series = history.channelSeries("gw", listOf("r:pv_power", "r:today_pv", "d:Measurements:PV Total Power"), 0, Long.MAX_VALUE)
        assertEquals(listOf(160L to 5110.0), series["r:pv_power"])
        assertEquals(listOf(160L to 31.4), series["r:today_pv"])
        assertEquals(listOf(160L to 5110.0), series["d:Measurements:PV Total Power"])
    }
}
