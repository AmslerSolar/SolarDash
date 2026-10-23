// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ch.amslersolar.dashboard.model.Channel
import ch.amslersolar.dashboard.model.Channels
import ch.amslersolar.dashboard.model.DailyEnergy
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.HistoryMath
import ch.amslersolar.dashboard.model.PlantSnapshot
import ch.amslersolar.dashboard.model.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "history.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples (
                plant_id TEXT NOT NULL, ts INTEGER NOT NULL,
                pv INTEGER, load INTEGER, battery INTEGER, grid INTEGER, soc INTEGER,
                PRIMARY KEY (plant_id, ts))""",
        )
        // day is ISO yyyy-MM-dd, so months and years group by prefix.
        db.execSQL(
            """CREATE TABLE daily (
                plant_id TEXT NOT NULL, day TEXT NOT NULL,
                pv REAL, load REAL, export REAL, import REAL,
                PRIMARY KEY (plant_id, day))""",
        )
        createChannelTables(db)
        createDeviceTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createChannelTables(db)
        if (oldVersion < 3) createDeviceTable(db)
    }

    /**
     * Devices whose data is stored, by type and serial number, so a device that's removed and added again gets its
     * old id (and data) back. removed_at is set while it's in no plant.
     */
    private fun createDeviceTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY, type TEXT NOT NULL, serial TEXT NOT NULL, name TEXT NOT NULL, removed_at INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS devices_identity ON devices (type, serial)")
    }

    /** Per-device channels (see Channels.kt): a catalogue, and one compressed row of values per device and sample. */
    private fun createChannelTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS channels (
                id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, key TEXT NOT NULL, label TEXT NOT NULL, unit TEXT NOT NULL,
                UNIQUE (device_id, key))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS device_samples (
                device_id TEXT NOT NULL, ts INTEGER NOT NULL, data BLOB NOT NULL,
                PRIMARY KEY (device_id, ts))""",
        )
    }
}

/** All channels of a device, every minute (roughly 300–500 MB per device and year). */
const val DEVICE_SAMPLE_INTERVAL_S = 60L

/** Plant-level power samples and daily energy totals, kept on the device. */
class HistoryRepository(context: Context) {
    private val db = HistoryDatabase(context)
    private val writeLock = Mutex()
    private val _version = MutableStateFlow(0L)

    /** Bumped after every write, so open history screens can reload. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun record(plantId: String, snapshot: PlantSnapshot) {
        if (snapshot.devices.all { it.reading == null }) return
        val sample = Sample(
            epochSecond = snapshot.readAt.epochSecond,
            pvW = snapshot.pvPowerW,
            loadW = snapshot.loadPowerW,
            batteryW = snapshot.battery?.powerW,
            gridW = snapshot.gridPowerW,
            socPercent = snapshot.battery?.socPercent,
        )
        val time = snapshot.readAt.atZone(zone)
        val counters = if (HistoryMath.countersUsable(time.toLocalTime())) snapshot.today else null
        write {
            val prev = lastSampleBefore(it, plantId, sample.epochSecond)
            val day = time.toLocalDate()
            val merged = HistoryMath.merge(daily(it, plantId, day), counters, HistoryMath.integrate(prev, sample))
            insertSample(it, plantId, sample)
            upsertDaily(it, plantId, day, merged)
            recordDevices(it, snapshot)
        }
    }

    /**
     * Every channel of every responding device, at most every [DEVICE_SAMPLE_INTERVAL_S] (checked against the
     * database, so live polling and the background worker don't double up).
     */
    private fun recordDevices(db: SQLiteDatabase, snapshot: PlantSnapshot) {
        val ts = snapshot.readAt.epochSecond
        for (status in snapshot.devices) {
            val reading = status.reading ?: continue
            val deviceId = status.device.id
            val last = db.rawQuery("SELECT MAX(ts) FROM device_samples WHERE device_id = ?", arrayOf(deviceId)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            if (last != null && ts - last < DEVICE_SAMPLE_INTERVAL_S - 10) continue
            val values = buildJsonObject {
                for (ch in Channels.extract(reading)) {
                    val id = channelId(db, deviceId, ch)
                    put(id.toString(), Math.round(ch.value * 1e6) / 1e6)
                }
            }
            val cv = ContentValues().apply {
                put("device_id", deviceId)
                put("ts", ts)
                put("data", compress(values.toString()))
            }
            db.insertWithOnConflict("device_samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun channelId(db: SQLiteDatabase, deviceId: String, ch: Channel): Long {
        db.rawQuery("SELECT id, label, unit FROM channels WHERE device_id = ? AND key = ?", arrayOf(deviceId, ch.key)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                if (c.getString(1) != ch.label || c.getString(2) != ch.unit) {
                    db.update("channels", ContentValues().apply { put("label", ch.label); put("unit", ch.unit) }, "id = ?", arrayOf(id.toString()))
                }
                return id
            }
        }
        return db.insert("channels", null, ContentValues().apply {
            put("device_id", deviceId); put("key", ch.key); put("label", ch.label); put("unit", ch.unit)
        })
    }

    /** (key, label, unit) of every channel recorded for the device. */
    suspend fun channelCatalog(deviceId: String): List<Triple<String, String, String>> = query {
        it.rawQuery("SELECT key, label, unit FROM channels WHERE device_id = ? ORDER BY id", arrayOf(deviceId)).use { c ->
            c.map { Triple(c.getString(0), c.getString(1), c.getString(2)) }
        }
    }

    /** Values of the given channels between [fromEpoch] and [toEpoch], per key, as (epoch second, value). */
    suspend fun channelSeries(deviceId: String, keys: List<String>, fromEpoch: Long, toEpoch: Long): Map<String, List<Pair<Long, Double>>> = query { db ->
        if (keys.isEmpty()) return@query emptyMap()
        val ids = db.rawQuery(
            "SELECT id, key FROM channels WHERE device_id = ? AND key IN (${keys.joinToString(",") { "?" }})",
            arrayOf(deviceId, *keys.toTypedArray()),
        ).use { c -> c.map { c.getLong(0).toString() to c.getString(1) }.toMap() }
        val out = keys.associateWith { mutableListOf<Pair<Long, Double>>() }
        if (ids.isEmpty()) return@query out
        db.rawQuery(
            "SELECT ts, data FROM device_samples WHERE device_id = ? AND ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(deviceId, fromEpoch.toString(), toEpoch.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                val values = Json.parseToJsonElement(decompress(c.getBlob(1))).jsonObject
                for ((id, key) in ids) values[id]?.jsonPrimitive?.doubleOrNull?.let { out.getValue(key) += ts to it }
            }
        }
        out
    }

    /** Deletes devices' recorded data for good (the remove dialog's "also delete its recorded data"). */
    suspend fun deleteDevices(deviceIds: List<String>) = write { w ->
        deviceIds.forEach { id ->
            w.delete("device_samples", "device_id = ?", arrayOf(id))
            w.delete("channels", "device_id = ?", arrayOf(id))
            w.delete("devices", "device_id = ?", arrayOf(id))
        }
    }

    /** Remembers a configured device by type and serial (no-op without a serial) and marks it as in use. */
    suspend fun rememberDevice(deviceId: String, type: String, serial: String, name: String) {
        if (serial.isBlank()) return
        write { w ->
            w.insertWithOnConflict("devices", null, ContentValues().apply {
                put("device_id", deviceId); put("type", type); put("serial", serial.trim()); put("name", name); putNull("removed_at")
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Devices left the app's plants: their data stays, ready for the same device to be added again. */
    suspend fun markRemoved(deviceIds: List<String>, at: Long = System.currentTimeMillis() / 1000) = write { w ->
        deviceIds.forEach { id -> w.update("devices", ContentValues().apply { put("removed_at", at) }, "device_id = ?", arrayOf(id)) }
    }

    /** The id under which data of the device with this type and serial is stored, other than [excluding]. */
    suspend fun storedDeviceId(type: String, serial: String, excluding: Set<String>): String? = query { db ->
        if (serial.isBlank()) return@query null
        db.rawQuery(
            "SELECT device_id FROM devices WHERE type = ? AND serial = ? ORDER BY removed_at IS NULL, removed_at DESC",
            arrayOf(type, serial.trim()),
        ).use { c -> c.map { c.getString(0) }.firstOrNull { it !in excluding } }
    }

    /**
     * Moves everything recorded under [from] to [to] (a device added again before its serial was known): channels
     * with the same key are merged, sample rows re-keyed. Rows [to] already has for the same minute win.
     */
    suspend fun mergeDevice(from: String, to: String) = write { w ->
        if (from == to) return@write
        val idMap = mutableMapOf<String, String>()
        w.rawQuery("SELECT id, key FROM channels WHERE device_id = ?", arrayOf(from)).use { c ->
            c.map { c.getLong(0) to c.getString(1) }
        }.forEach { (id, key) ->
            val existing = w.rawQuery("SELECT id FROM channels WHERE device_id = ? AND key = ?", arrayOf(to, key)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (existing != null) {
                w.delete("channels", "id = ?", arrayOf(id.toString()))
                idMap[id.toString()] = existing.toString()
            } else {
                w.update("channels", ContentValues().apply { put("device_id", to) }, "id = ?", arrayOf(id.toString()))
            }
        }
        val rows = w.rawQuery("SELECT ts, data FROM device_samples WHERE device_id = ?", arrayOf(from)).use { c ->
            c.map { c.getLong(0) to c.getBlob(1) }
        }
        for ((ts, data) in rows) {
            val values = Json.parseToJsonElement(decompress(data)).jsonObject
            val remapped = if (idMap.isEmpty()) data else compress(
                kotlinx.serialization.json.JsonObject(values.mapKeys { (k, _) -> idMap[k] ?: k }).toString(),
            )
            w.insertWithOnConflict("device_samples", null, ContentValues().apply {
                put("device_id", to); put("ts", ts); put("data", remapped)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        w.delete("device_samples", "device_id = ?", arrayOf(from))
        w.delete("devices", "device_id = ?", arrayOf(from))
    }

    private fun compress(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun decompress(data: ByteArray): String = InflaterInputStream(data.inputStream()).use { it.readBytes().decodeToString() }

    /** Bulk import, e.g. generated demo history. Replaces existing rows with the same keys. */
    suspend fun insert(plantId: String, samples: List<Sample>, days: List<DailyEnergy>) = write { w ->
        samples.forEach { insertSample(w, plantId, it) }
        days.forEach { upsertDaily(w, plantId, it.day, it.energy) }
    }

    suspend fun deletePlant(plantId: String) = write {
        it.delete("samples", "plant_id = ?", arrayOf(plantId))
        it.delete("daily", "plant_id = ?", arrayOf(plantId))
    }

    suspend fun samples(plantId: String, fromEpoch: Long, toEpoch: Long): List<Sample> = query {
        it.rawQuery(
            "SELECT ts, pv, load, battery, grid, soc FROM samples WHERE plant_id = ? AND ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(plantId, fromEpoch.toString(), toEpoch.toString()),
        ).use { c -> c.map(::readSample) }
    }

    suspend fun days(plantId: String, from: LocalDate, to: LocalDate): List<DailyEnergy> = query {
        it.rawQuery(
            "SELECT day, pv, load, export, import FROM daily WHERE plant_id = ? AND day BETWEEN ? AND ? ORDER BY day",
            arrayOf(plantId, from.toString(), to.toString()),
        ).use { c -> c.map { DailyEnergy(LocalDate.parse(c.getString(0)), readEnergy(c, 1)) } }
    }

    suspend fun months(plantId: String, year: Int): Map<YearMonth, EnergyTotals> = query {
        it.rawQuery(
            "SELECT substr(day, 1, 7) m, SUM(pv), SUM(load), SUM(export), SUM(import) FROM daily " +
                "WHERE plant_id = ? AND day LIKE ? GROUP BY m",
            arrayOf(plantId, "$year-%"),
        ).use { c -> c.map { YearMonth.parse(c.getString(0)) to readEnergy(c, 1) }.toMap() }
    }

    suspend fun years(plantId: String): Map<Int, EnergyTotals> = query {
        it.rawQuery(
            "SELECT substr(day, 1, 4) y, SUM(pv), SUM(load), SUM(export), SUM(import) FROM daily WHERE plant_id = ? GROUP BY y",
            arrayOf(plantId),
        ).use { c -> c.map { c.getString(0).toInt() to readEnergy(c, 1) }.toMap() }
    }

    suspend fun firstDay(plantId: String): LocalDate? = query {
        it.rawQuery("SELECT MIN(day) FROM daily WHERE plant_id = ?", arrayOf(plantId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null
        }
    }

    private suspend fun <T> query(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { block(db.readableDatabase) }

    private suspend fun write(block: (SQLiteDatabase) -> Unit) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val w = db.writableDatabase
                w.beginTransaction()
                try {
                    block(w)
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
        }
        _version.value++
    }

    private fun lastSampleBefore(db: SQLiteDatabase, plantId: String, epoch: Long): Sample? =
        db.rawQuery(
            "SELECT ts, pv, load, battery, grid, soc FROM samples WHERE plant_id = ? AND ts < ? ORDER BY ts DESC LIMIT 1",
            arrayOf(plantId, epoch.toString()),
        ).use { c -> if (c.moveToFirst()) readSample(c) else null }

    private fun daily(db: SQLiteDatabase, plantId: String, day: LocalDate): EnergyTotals? =
        db.rawQuery(
            "SELECT pv, load, export, import FROM daily WHERE plant_id = ? AND day = ?",
            arrayOf(plantId, day.toString()),
        ).use { c -> if (c.moveToFirst()) readEnergy(c, 0) else null }

    private fun insertSample(db: SQLiteDatabase, plantId: String, s: Sample) {
        val values = ContentValues().apply {
            put("plant_id", plantId)
            put("ts", s.epochSecond)
            putInt("pv", s.pvW)
            putInt("load", s.loadW)
            putInt("battery", s.batteryW)
            putInt("grid", s.gridW)
            putInt("soc", s.socPercent)
        }
        db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertDaily(db: SQLiteDatabase, plantId: String, day: LocalDate, e: EnergyTotals) {
        val values = ContentValues().apply {
            put("plant_id", plantId)
            put("day", day.toString())
            putDouble("pv", e.pvKwh)
            putDouble("load", e.loadKwh)
            putDouble("export", e.exportKwh)
            putDouble("import", e.importKwh)
        }
        db.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readSample(c: Cursor) = Sample(c.getLong(0), c.intOrNull(1), c.intOrNull(2), c.intOrNull(3), c.intOrNull(4), c.intOrNull(5))

    /** Reads pv, load, export, import starting at column [first]. */
    private fun readEnergy(c: Cursor, first: Int) = EnergyTotals(
        pvKwh = c.doubleOrNull(first),
        loadKwh = c.doubleOrNull(first + 1),
        exportKwh = c.doubleOrNull(first + 2),
        importKwh = c.doubleOrNull(first + 3),
    )
}

private fun <T> Cursor.map(row: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += row(this)
    return out
}

private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

private fun ContentValues.putInt(key: String, value: Int?) = if (value == null) putNull(key) else put(key, value)

private fun ContentValues.putDouble(key: String, value: Double?) = if (value == null) putNull(key) else put(key, value)
