// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ch.amslersolar.solardash.model.DeviceKind
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.MeterRole
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantAggregator
import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.Channel
import ch.amslersolar.solardash.model.Channels
import ch.amslersolar.solardash.model.DailyEnergy
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.HistoryMath
import ch.amslersolar.solardash.model.EventStore
import ch.amslersolar.solardash.model.LoggedEvent
import ch.amslersolar.solardash.model.PlantSnapshot
import ch.amslersolar.solardash.model.Plausible
import ch.amslersolar.solardash.model.Sample
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

private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "history.db", null, 8) {
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
        createEventTable(db)
        createDeviceDailyTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createChannelTables(db)
        if (oldVersion < 3) createDeviceTable(db)
        if (oldVersion < 4) createEventTable(db) // already with the version-5 columns
        if (oldVersion == 4) {
            // Fault details for the installer (version 4 had none).
            db.execSQL("ALTER TABLE events ADD COLUMN details TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN end_details TEXT")
        }
        if (oldVersion < 6) createDeviceDailyTable(db)
        if (oldVersion < 7) removeImplausible(db)
        // Device days worked out while yesterday's solar counter still counted (HistoryMath.pvCounterFresh): a cache,
        // worked out again when needed.
        if (oldVersion < 8) db.execSQL("DELETE FROM device_daily")
    }

    /**
     * Readings no plant produces, stored before recording checked them (see Plausible): a GoodWe ET's "not
     * available" (all ones) read as 4294967295 W of solar and 429496729.5 kWh today. Plant samples and day totals
     * lose such values; device channels lose the "not available" patterns and power channels above the limit.
     */
    private fun removeImplausible(db: SQLiteDatabase) {
        for (c in listOf("pv", "load", "battery", "grid")) {
            db.execSQL("UPDATE samples SET $c = NULL WHERE ABS($c) > ${Plausible.MAX_POWER_W}")
        }
        for (t in listOf("daily", "device_daily")) for (c in listOf("pv", "load", "export", "import")) {
            db.execSQL("UPDATE $t SET $c = NULL WHERE ABS($c) > ${Plausible.MAX_DAY_KWH}")
        }
        val powerIds = db.rawQuery("SELECT id FROM channels WHERE key LIKE 'r:%power'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0).toString()) }
        }
        val fixed = mutableListOf<Triple<String, Long, ByteArray>>()
        db.rawQuery("SELECT device_id, ts, data FROM device_samples", null).use { c ->
            while (c.moveToNext()) {
                val values = Json.parseToJsonElement(InflaterInputStream(c.getBlob(2).inputStream()).use { it.readBytes().decodeToString() }).jsonObject
                val kept = values.filter { (id, v) ->
                    val d = v.jsonPrimitive.doubleOrNull ?: return@filter true
                    !Plausible.notAvailable(d) && !(id in powerIds && kotlin.math.abs(d) > Plausible.MAX_POWER_W)
                }
                if (kept.size == values.size) continue
                val out = ByteArrayOutputStream()
                DeflaterOutputStream(out).use { it.write(kotlinx.serialization.json.JsonObject(kept).toString().toByteArray()) }
                fixed += Triple(c.getString(0), c.getLong(1), out.toByteArray())
            }
        }
        for ((device, ts, data) in fixed) {
            db.update("device_samples", ContentValues().apply { put("data", data) }, "device_id = ? AND ts = ?", arrayOf(device, ts.toString()))
        }
    }

    /**
     * Each device's energy per day, worked out from its recorded values (see HistoryRepository.deviceDays) and
     * kept for past days; a row of nulls: nothing was recorded that day.
     */
    private fun createDeviceDailyTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS device_daily (
                device_id TEXT NOT NULL, day TEXT NOT NULL,
                pv REAL, load REAL, export REAL, import REAL,
                PRIMARY KEY (device_id, day))""",
        )
    }

    /**
     * The fault log: one row per problem, from when it started to when it ended (null while it lasts). [key]
     * identifies the problem across polls (see FaultLog); kind and severity are stored by name.
     */
    private fun createEventTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, plant_id TEXT NOT NULL, key TEXT NOT NULL,
                kind TEXT NOT NULL, severity TEXT NOT NULL, device_id TEXT, device_name TEXT, detail TEXT,
                percent INTEGER, started INTEGER NOT NULL, ended INTEGER, details TEXT, end_details TEXT)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS events_plant ON events (plant_id, started)")
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

private const val EVENT_COLUMNS = "id, plant_id, key, kind, severity, device_id, device_name, detail, percent, started, ended, details, end_details"

/** The channels a device's history view is drawn from (see Channels.extract). */
private val DEVICE_KEYS = listOf(
    "r:pv_power", "r:load_power", "r:battery_power", "r:grid_power", "r:battery_soc",
    "r:today_pv", "r:today_export", "r:today_import", "r:today_load",
)

/** All channels of a device, every minute (roughly 300–500 MB per device and year). */
const val DEVICE_SAMPLE_INTERVAL_S = 60L

/** Plant-level power samples and daily energy totals, kept on the device. */
class HistoryRepository(context: Context) : EventStore {
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
            pvW = Plausible.power(snapshot.pvPowerW),
            loadW = Plausible.power(snapshot.loadPowerW),
            batteryW = Plausible.power(snapshot.battery?.powerW),
            gridW = Plausible.power(snapshot.gridPowerW),
            socPercent = snapshot.battery?.socPercent,
        )
        val time = snapshot.readAt.atZone(zone)
        val counters = if (HistoryMath.countersUsable(time.toLocalTime())) Plausible.day(snapshot.today) else null
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
                    if (Plausible.notAvailable(ch.value) || ch.key.startsWith("r:") && ch.key.endsWith("power") && kotlin.math.abs(ch.value) > Plausible.MAX_POWER_W) continue
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
            w.delete("device_daily", "device_id = ?", arrayOf(id))
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
        // Worked out again from the merged samples.
        w.delete("device_daily", "device_id IN (?, ?)", arrayOf(from, to))
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

    /**
     * An observed plant's device channels (see ch.amslersolar.solardash.remote.Observer): [channels] as (key, label,
     * unit), [rows] as (epoch second, values by key). A label that is just the key (catalogue not known yet) doesn't
     * replace a better one.
     */
    suspend fun insertDeviceSamples(deviceId: String, channels: List<Triple<String, String, String>>, rows: List<Pair<Long, Map<String, Double>>>) = write { w ->
        val ids = channels.associate { (key, label, unit) ->
            val existing = w.rawQuery("SELECT id, label, unit FROM channels WHERE device_id = ? AND key = ?", arrayOf(deviceId, key)).use { c ->
                if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2)) else null
            }
            key to when {
                existing == null -> w.insert("channels", null, ContentValues().apply {
                    put("device_id", deviceId); put("key", key); put("label", label); put("unit", unit)
                })
                label != key && (existing.second != label || existing.third != unit) -> existing.first.also { id ->
                    w.update("channels", ContentValues().apply { put("label", label); put("unit", unit) }, "id = ?", arrayOf(id.toString()))
                }
                else -> existing.first
            }
        }
        for ((ts, values) in rows) {
            val data = buildJsonObject { for ((key, v) in values) ids[key]?.let { put(it.toString(), v) } }
            w.insertWithOnConflict("device_samples", null, ContentValues().apply {
                put("device_id", deviceId); put("ts", ts); put("data", compress(data.toString()))
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** An observed plant's fault log entry: the one with the same key and start is updated, else it's added. */
    suspend fun upsertObservedEvent(e: LoggedEvent) = write { w ->
        val id = w.rawQuery("SELECT id FROM events WHERE plant_id = ? AND key = ? AND started = ?", arrayOf(e.plantId, e.key, e.started.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        val cv = ContentValues().apply {
            put("plant_id", e.plantId); put("key", e.key); put("kind", e.kind); put("severity", e.severity)
            put("device_id", e.deviceId); put("device_name", e.deviceName); put("detail", e.detail); putInt("percent", e.percent)
            put("started", e.started); if (e.ended == null) putNull("ended") else put("ended", e.ended); put("details", e.details); put("end_details", e.endDetails)
        }
        if (id == null) w.insert("events", null, cv) else w.update("events", cv, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deletePlant(plantId: String) = write {
        it.delete("samples", "plant_id = ?", arrayOf(plantId))
        it.delete("daily", "plant_id = ?", arrayOf(plantId))
        it.delete("events", "plant_id = ?", arrayOf(plantId))
    }

    /** Fault log entries that are still going on. */
    override suspend fun openEvents(plantId: String): List<LoggedEvent> = query { db ->
        db.rawQuery("SELECT $EVENT_COLUMNS FROM events WHERE plant_id = ? AND ended IS NULL", arrayOf(plantId)).use { c ->
            buildList { while (c.moveToNext()) add(readEvent(c)) }
        }
    }

    /** The fault log, newest first. */
    suspend fun events(plantId: String, limit: Int = 1000): List<LoggedEvent> = query { db ->
        db.rawQuery(
            "SELECT $EVENT_COLUMNS FROM events WHERE plant_id = ? ORDER BY started DESC, id DESC LIMIT ?",
            arrayOf(plantId, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(readEvent(c)) } }
    }

    override suspend fun openEvent(e: LoggedEvent) = write { w ->
        w.insert("events", null, ContentValues().apply {
            put("plant_id", e.plantId); put("key", e.key); put("kind", e.kind); put("severity", e.severity)
            put("device_id", e.deviceId); put("device_name", e.deviceName); put("detail", e.detail)
            if (e.percent != null) put("percent", e.percent) else putNull("percent")
            put("started", e.started)
            put("details", e.details)
        })
    }

    override suspend fun closeEvent(id: Long, ended: Long, endDetails: String?) = write { w ->
        w.update("events", ContentValues().apply { put("ended", ended); put("end_details", endDetails) }, "id = ?", arrayOf(id.toString()))
    }

    private fun readEvent(c: Cursor) = LoggedEvent(
        id = c.getLong(0), plantId = c.getString(1), key = c.getString(2), kind = c.getString(3), severity = c.getString(4),
        deviceId = c.getString(5), deviceName = c.getString(6), detail = c.getString(7),
        percent = if (c.isNull(8)) null else c.getInt(8), started = c.getLong(9), ended = if (c.isNull(10)) null else c.getLong(10),
        details = c.getString(11), endDetails = c.getString(12),
    )

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

    /** A device's main values (and its day counters) as plant-style samples, from its recorded channels. */
    suspend fun deviceSamples(deviceId: String, fromEpoch: Long, toEpoch: Long): List<Sample> =
        deviceRows(deviceId, fromEpoch, toEpoch).map { (ts, m) -> m.sample(ts) }

    private suspend fun deviceRows(deviceId: String, fromEpoch: Long, toEpoch: Long): List<Pair<Long, Map<String, Double>>> {
        val byTs = sortedMapOf<Long, MutableMap<String, Double>>()
        for ((key, points) in channelSeries(deviceId, DEVICE_KEYS, fromEpoch, toEpoch)) {
            for ((ts, v) in points) byTs.getOrPut(ts) { HashMap() }[key] = v
        }
        return byTs.map { (ts, m) -> ts to m }
    }

    /**
     * A device's last recorded solar and battery power within [withinS] seconds, as a reading (for
     * LastReadings.seed); null when nothing was recorded then.
     */
    suspend fun lastDeviceReading(deviceId: String, withinS: Long = 24 * 3600L): DeviceReading? {
        val now = System.currentTimeMillis() / 1000
        val (_, m) = deviceRows(deviceId, now - withinS, now).lastOrNull() ?: return null
        val s = m.sample(0)
        return DeviceReading(pvPowerW = s.pvW, battery = s.batteryW?.let { BatteryState(it, s.socPercent, null, "") })
    }

    /** A device's highest recorded solar power in the last [days] days (LastReadings.seedPeak); null without any. */
    suspend fun peakDevicePv(deviceId: String, days: Int = 7): Int? {
        val now = System.currentTimeMillis() / 1000
        return channelSeries(deviceId, listOf("r:pv_power"), now - days * 86400L, now)["r:pv_power"]
            ?.maxOfOrNull { it.second }?.let(Math::round)?.toInt()
    }

    private fun Map<String, Double>.sample(ts: Long) = Sample(
        ts, this["r:pv_power"]?.let(Math::round)?.toInt(), this["r:load_power"]?.let(Math::round)?.toInt(),
        this["r:battery_power"]?.let(Math::round)?.toInt(), this["r:grid_power"]?.let(Math::round)?.toInt(),
        this["r:battery_soc"]?.let(Math::round)?.toInt(),
    )

    /**
     * A device's energy per day from [from] to [to] (not after today, not before its first recording): integrated
     * from its power values like the plant's, a day counter of its own winning where it has one. Past days are
     * kept in device_daily once worked out; today is worked out each time.
     */
    suspend fun deviceDays(deviceId: String, from: LocalDate, to: LocalDate): List<DailyEnergy> {
        val first = firstDeviceDay(deviceId) ?: return emptyList()
        val today = LocalDate.now(zone)
        val start = maxOf(from, first)
        val end = minOf(to, today)
        if (start > end) return emptyList()
        val stored = query { db ->
            db.rawQuery(
                "SELECT day, pv, load, export, import FROM device_daily WHERE device_id = ? AND day >= ? AND day <= ?",
                arrayOf(deviceId, start.toString(), end.toString()),
            ).use { c -> c.map { LocalDate.parse(c.getString(0)) to readEnergy(c, 1) }.toMap() }
        }
        val out = mutableListOf<DailyEnergy>()
        val computed = mutableListOf<DailyEnergy>()
        var day = start
        while (day <= end) {
            val energy = stored[day] ?: deviceDay(deviceId, day).also { if (day < today) computed += DailyEnergy(day, it) }
            if (listOf(energy.pvKwh, energy.loadKwh, energy.exportKwh, energy.importKwh).any { it != null }) out += DailyEnergy(day, energy)
            day = day.plusDays(1)
        }
        if (computed.isNotEmpty()) cache { w -> computed.forEach { upsertDeviceDaily(w, deviceId, it.day, it.energy) } }
        return out
    }

    /**
     * Lowers days' solar yield that counted a device's yesterday's counter (see [HistoryMath.pvCounterFresh]):
     * a day keeps its highest counter sum, so the stale morning value stayed. Where every producing device of
     * [plant] has a recorded yield that day, the day gets their sum when that is lower. Devices an energy manager
     * already contains count once, as in the live totals. Returns the days changed.
     */
    suspend fun repairSolarDays(plant: Plant): Int {
        val statuses = plant.devices.map { DeviceStatus(it, DeviceReading(), null) }
        val covered = PlantAggregator.coveredByManager(statuses)
        val producers = plant.devices.filter {
            it.id !in covered && (it.type.kind == DeviceKind.INVERTER || it.type.kind == DeviceKind.METER && it.meterRole == MeterRole.PV)
        }
        if (producers.isEmpty()) return 0
        val stored = query { db ->
            db.rawQuery("SELECT day, pv FROM daily WHERE plant_id = ? AND pv IS NOT NULL", arrayOf(plant.id)).use { c ->
                c.map { LocalDate.parse(c.getString(0)) to c.getDouble(1) }
            }
        }
        if (stored.isEmpty()) return 0
        val from = stored.minOf { it.first }
        val to = stored.maxOf { it.first }
        val perDevice = producers.map { d -> deviceDays(d.id, from, to).associate { it.day to it.energy.pvKwh } }
        val fixed = stored.mapNotNull { (day, pv) ->
            val values = perDevice.map { it[day] }
            if (values.any { it == null }) return@mapNotNull null
            val sum = values.sumOf { it!! }
            if (pv > sum + 0.05) day to sum else null
        }
        if (fixed.isNotEmpty()) write { w ->
            for ((day, pv) in fixed) {
                w.update("daily", ContentValues().apply { put("pv", pv) }, "plant_id = ? AND day = ?", arrayOf(plant.id, day.toString()))
            }
        }
        return fixed.size
    }

    private suspend fun deviceDay(deviceId: String, day: LocalDate): EnergyTotals {
        val from = day.atStartOfDay(zone).toEpochSecond()
        val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        var total: EnergyTotals? = null
        var prev: Sample? = null
        var firstPv: Double? = null
        for ((ts, m) in deviceRows(deviceId, from, to)) {
            val sample = m.sample(ts)
            val time = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalTime()
            val pv = m["r:today_pv"]?.also { if (firstPv == null && HistoryMath.countersUsable(time)) firstPv = it }
            val counters = EnergyTotals(
                pvKwh = pv?.takeIf { HistoryMath.pvCounterFresh(it, firstPv, sample.pvW, time) },
                exportKwh = m["r:today_export"], importKwh = m["r:today_import"], loadKwh = m["r:today_load"],
            ).takeIf { HistoryMath.countersUsable(time) }
            total = HistoryMath.merge(total, counters, HistoryMath.integrate(prev, sample))
            prev = sample
        }
        return total ?: EnergyTotals()
    }

    private suspend fun firstDeviceDay(deviceId: String): LocalDate? = query { db ->
        db.rawQuery("SELECT MIN(ts) FROM device_samples WHERE device_id = ?", arrayOf(deviceId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) java.time.Instant.ofEpochSecond(c.getLong(0)).atZone(zone).toLocalDate() else null
        }
    }

    private fun upsertDeviceDaily(db: SQLiteDatabase, deviceId: String, day: LocalDate, e: EnergyTotals) {
        val cv = ContentValues().apply {
            put("device_id", deviceId)
            put("day", day.toString())
            putDouble("pv", e.pvKwh)
            putDouble("load", e.loadKwh)
            putDouble("export", e.exportKwh)
            putDouble("import", e.importKwh)
        }
        db.insertWithOnConflict("device_daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Like [write], but for derived data: open history screens aren't told to reload. */
    private suspend fun cache(block: (SQLiteDatabase) -> Unit) {
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
