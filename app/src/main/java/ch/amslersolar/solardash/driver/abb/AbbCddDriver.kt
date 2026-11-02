// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.Details
import ch.amslersolar.solardash.driver.net.DeviceNetwork
import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** One GET on the CDD, returning the response body. */
fun interface CddFetch {
    suspend fun get(path: String): String
}

/**
 * ABB CDD (Concentrator Data Device): the RF gateway and logger for ABB MICRO-0.3 microinverters, read over its
 * local web server (plain HTTP, port 80, no login).
 *
 * No documentation from ABB; everything here comes from a real CDD (serial 3281xx, firmware of 2026, 16 inverters
 * of which 14 report) read on 2026-11-02:
 * - `/plant.xml`: a `<data>` header (time, sunrise/sunset, `sun`, cloud connection), a `<cdd>` element with the plant
 *   totals (`pout_W`, `etot_kWh`, `status` "OK" / "ALARM") and one `<edd>` per microinverter (`Pout_W`, `Vin_V`,
 *   `Vout_V`, `Iout_A`, `Freq_Hz`, temperatures, `Etot_kWh`, `invstat`, `rssi`, alarm registers). `/summary.xml`
 *   is the same without the `<edd>` elements; [identify] uses it.
 * - `/alarm.xml`: the CDD's event log, `<alarm code desc sn device time len status>`; `status="Active"` with
 *   `len="99:59:59"` is still open. Codes seen: I005 "CDD Wake Up" (stays active after every boot, so I codes are
 *   ignored), E501 "Comm Fault" (an inverter that doesn't answer over RF), W021 "Gateway connec" (cloud).
 *   About one read in six returns the header without any `<alarm>`: an empty log is re-read once and otherwise
 *   ignored, as every CDD logs at least its wake-up. The log changes rarely, so it's read every 5 minutes.
 * - Values are XML attributes, some padded with spaces (`Etot_Wh=" 0.00"`, `desc="Comm Fault      "`).
 *   Times are local, `DD-MM-YY HH:MM:SS`; an inverter that never reported has `ts="01-01-00 00:00:00"`.
 * - An inverter that never answered stays in `invstat="START-UP"` with all values 0, `alarmstat="1"` and
 *   `CS_status="Waiting"`. UNVERIFIED: the states and values at night (`sun` other than "DAY").
 * - The microinverters are single-phase and may sit on different phases, so no per-phase grid values are reported;
 *   the CDD has no daily counters and no grid meter.
 */
class AbbCddDriver internal constructor(
    private val fetch: CddFetch,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDriver {
    constructor(host: String, port: Int = 80) : this(httpFetch(host, port))

    private val mutex = Mutex()
    private var alarms: List<AbbCddParser.Alarm>? = null
    private var alarmsAt = 0L
    private var alarmsTried = false

    override suspend fun identify(): DeviceInfo = mutex.withLock {
        val plant = AbbCddParser.parse(fetch.get(SUMMARY))
        val cdd = plant.cdd ?: throw IOException("Not an ABB CDD: $SUMMARY has no <cdd> element")
        val count = plant.header["micro"]?.toIntOrNull()
        DeviceInfo(
            model = "ABB CDD" + (count?.let { ", $it microinverters" } ?: ""),
            serial = cdd["sn"] ?: "?",
        )
    }

    override suspend fun read(): DeviceReading = mutex.withLock {
        val plant = AbbCddParser.parse(fetch.get(PLANT))
        if (plant.cdd == null) throw IOException("Not an ABB CDD: $PLANT has no <cdd> element")
        val now = clock()
        if (!alarmsTried || now - alarmsAt >= ALARMS_REFRESH_MS) {
            readAlarms()?.let { alarms = it }
            alarmsAt = now
            alarmsTried = true
        }
        AbbCddParser.reading(plant, alarms)
    }

    /** The alarm log, or null when it couldn't be read or came back empty twice (see the class comment). */
    private suspend fun readAlarms(): List<AbbCddParser.Alarm>? {
        repeat(2) {
            val list = try {
                AbbCddParser.parse(fetch.get(ALARMS)).alarms
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return null
            }
            if (list.isNotEmpty()) return list
        }
        return null
    }

    internal companion object {
        const val PLANT = "/plant.xml"
        const val SUMMARY = "/summary.xml"
        const val ALARMS = "/alarm.xml"
        const val TIMEOUT_MS = 5_000
        const val ALARMS_REFRESH_MS = 5 * 60_000L

        fun httpFetch(host: String, port: Int): CddFetch {
            val base = if (port == 80 || port == 0) "http://$host" else "http://$host:$port"
            return CddFetch { path -> withContext(Dispatchers.IO) { get(base, path) } }
        }

        private fun get(base: String, path: String): String {
            val conn = DeviceNetwork.current.openConnection(URL(base + path)) as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status from $path")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** Reads the CDD's XML files and maps them to a [DeviceReading]. */
object AbbCddParser {
    /** One XML file: the `<data>` header's attributes, the `<cdd>` element's, and the `<edd>` / `<alarm>` elements. */
    data class Plant(
        val header: Map<String, String>,
        val cdd: Map<String, String>?,
        val powerLimit: Map<String, String>?,
        val inverters: List<Map<String, String>>,
        val alarms: List<Alarm>,
    )

    data class Alarm(val code: String, val desc: String, val serial: String, val device: String, val time: String, val active: Boolean)

    private val ELEMENT = Regex("""<(data|cdd|powerlim|edd|alarm)\b([^>]*?)/?>""")
    private val ATTRIBUTE = Regex("""([\w.-]+)\s*=\s*"([^"]*)"""")
    private val TIME = Regex("""^(\d{2})-(\d{2})-(\d{2}) (\d{2}:\d{2}:\d{2})$""")
    private val WAKEUP = Regex("""^(\d{2}:\d{2}:\d{2}) (\d{2})/(\d{2})/(\d{2})""")

    fun parse(xml: String): Plant {
        var header = emptyMap<String, String>()
        var cdd: Map<String, String>? = null
        var powerLimit: Map<String, String>? = null
        val inverters = mutableListOf<Map<String, String>>()
        val alarms = mutableListOf<Alarm>()
        for (m in ELEMENT.findAll(xml)) {
            val attrs = ATTRIBUTE.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
            when (m.groupValues[1]) {
                "data" -> header = attrs
                "cdd" -> cdd = attrs
                "powerlim" -> powerLimit = attrs
                "edd" -> inverters += attrs
                "alarm" -> alarms += Alarm(
                    code = attrs["code"].orEmpty(),
                    desc = attrs["desc"].orEmpty(),
                    serial = attrs["sn"].orEmpty(),
                    device = attrs["device"].orEmpty(),
                    time = attrs["time"].orEmpty(),
                    active = attrs["status"].equals("Active", ignoreCase = true),
                )
            }
        }
        return Plant(header, cdd, powerLimit, inverters, alarms)
    }

    /** [alarms] null: the log couldn't be read, so the problems come from the inverters' own alarm flag. */
    fun reading(plant: Plant, alarms: List<Alarm>?): DeviceReading {
        val cdd = plant.cdd.orEmpty()
        val powerW = cdd.num("pout_W")?.coerceAtLeast(0.0)
        val day = plant.header["sun"]?.equals("DAY", ignoreCase = true) ?: true
        val status = when {
            powerW != null && powerW > 0 -> "Producing"
            !day -> "Night"
            else -> "Idle"
        }
        return DeviceReading(
            pvPowerW = powerW?.roundToInt(),
            status = status,
            lifetimePvKwh = cdd.num("etot_kWh"),
            details = details(plant, alarms),
            problems = problems(plant, alarms),
        )
    }

    private fun problems(plant: Plant, alarms: List<Alarm>?): List<DeviceProblem> {
        val fromLog = alarms?.filter { it.active }?.mapNotNull { a ->
            val kind = when (a.code.firstOrNull()?.uppercaseChar()) {
                'E', 'F' -> DeviceProblem.Kind.FAULT
                'W' -> DeviceProblem.Kind.WARNING
                else -> return@mapNotNull null // I = information, e.g. I005 "CDD Wake Up" stays active after every boot
            }
            val what = if (a.device.equals("Cdd", ignoreCase = true)) "CDD" else "microinverter ${a.serial}"
            DeviceProblem(kind, "${a.desc.ifEmpty { "Alarm" }} (${a.code}): $what")
        }
        val fromInverters = if (fromLog != null) emptyList() else plant.inverters.filter { it["alarmstat"].let { s -> s != null && s != "0" } }.map {
            val text = if (neverReported(it)) "No communication" else "Alarm (${it["invstat"] ?: "?"})"
            DeviceProblem(DeviceProblem.Kind.FAULT, "$text: microinverter ${it["sn"] ?: "?"}")
        }
        return (fromLog.orEmpty() + fromInverters).distinct()
    }

    private fun neverReported(inv: Map<String, String>) = inv["CS_status"] == "Waiting" || time(inv["ts"]) == null

    private fun details(plant: Plant, alarms: List<Alarm>?): List<DetailSection> {
        val h = plant.header
        val cdd = plant.cdd.orEmpty()
        val limit = plant.powerLimit
        val sections = mutableListOf(
            Details.section(
                "CDD",
                "Serial number" to cdd["sn"],
                "Model" to cdd["idmodel"],
                "State" to cdd["status"],
                "Power" to Details.fmt(cdd.num("pout_W"), "W", 1),
                "Lifetime energy" to Details.fmt(cdd.num("etot_kWh"), "kWh", 1),
                "CO2 saved" to Details.fmt(cdd.num("co2_t"), "t", 2),
                "Microinverters" to h["micro"],
                "Grid code" to cdd["countryname"],
                "Power limit" to limit?.let { if (it["enabl"] == "1") Details.fmt(it.num("plimval"), "%", 0) else "Disabled" },
                "Daylight" to h["sun"],
                "Sunrise" to h["sunrise"],
                "Sunset" to h["sunset"],
                "Aurora Vision gateway" to h["gateway"],
                "Aurora Vision data portal" to h["data_portal"],
                "Aurora Vision upgrade portal" to h["upgrade_portal"],
                "Last boot" to cdd["wakeup"]?.let { w -> WAKEUP.find(w)?.let { "20${it.groupValues[4]}-${it.groupValues[3]}-${it.groupValues[2]} ${it.groupValues[1]}" } },
                "CDD time" to time(h["ts"]),
                "RF MAC address" to cdd["macrf"],
            ),
        )
        if (plant.inverters.isNotEmpty()) {
            sections += DetailSection(
                "Microinverters (${plant.inverters.size})",
                plant.inverters.map { inv ->
                    val value = if (neverReported(inv)) "${inv["invstat"] ?: "?"}, never reported" else listOfNotNull(
                        inv["invstat"],
                        Details.fmt(inv.num("Pout_W"), "W", 1),
                        Details.fmt(inv.num("Vin_V"), "V DC", 1),
                        inv["rssi"]?.let { "RSSI $it" },
                    ).joinToString(", ")
                    (inv["sn"] ?: "?") to value
                },
            )
            plant.inverters.forEach { inv ->
                val silent = neverReported(inv)
                fun v(key: String, unit: String, decimals: Int) = if (silent) null else Details.fmt(inv.num(key), unit, decimals)
                sections += Details.section(
                    "Microinverter ${inv["sn"] ?: "?"}",
                    "Model" to inv["modeldesc"]?.takeIf { it != "???" },
                    "State" to inv["invstat"],
                    "Booster state" to inv["booststat"],
                    "Power" to v("Pout_W", "W", 1),
                    "DC voltage" to v("Vin_V", "V", 1),
                    "AC voltage" to v("Vout_V", "V", 1),
                    "AC current" to v("Iout_A", "A", 3),
                    "Grid frequency" to v("Freq_Hz", "Hz", 2),
                    "Temperature (DSP)" to v("Tdsp_degC", "°C", 1),
                    "Temperature (MOSFET)" to v("Tmos_degC", "°C", 1),
                    "Lifetime energy" to v("Etot_kWh", "kWh", 1),
                    "RF signal (RSSI)" to inv["rssi"]?.takeIf { !silent },
                    "Last report" to (time(inv["ts"]) ?: "never"),
                    "DSP alarms" to inv["dspalarm"],
                    "MCU alarms" to inv["mcualarm"],
                    "MCU warnings" to inv["mcuwarning"],
                    "Firmware (micro / DSP / RF)" to listOfNotNull(inv["fwmicro"], inv["fwdsp"], inv["fwrf"]).joinToString(" / ").takeIf { !silent },
                    "RF MAC address" to inv["macrf"],
                )
            }
        }
        val log = alarms.orEmpty().distinctBy { listOf(it.code, it.serial, it.time, it.active) }
        if (log.isNotEmpty()) {
            // Open alarms first, then the newest closed ones; the log lists them oldest first.
            val shown = log.filter { it.active } + log.filter { !it.active }.reversed().take(10)
            sections += DetailSection(
                "Alarm log",
                shown.map { a ->
                    val who = if (a.device.equals("Cdd", ignoreCase = true)) "CDD" else a.serial
                    "${a.code} ${a.desc}, $who" to listOfNotNull(time(a.time), if (a.active) "active" else "closed").joinToString(", ")
                },
            )
        }
        return sections.filter { it.rows.isNotEmpty() }
    }

    /** `DD-MM-YY HH:MM:SS` (local time) as `20YY-MM-DD HH:MM:SS`; null for the CDD's "never" (`01-01-00 00:00:00`). */
    fun time(text: String?): String? {
        val m = TIME.matchEntire(text?.trim() ?: return null) ?: return null
        val (d, mo, y, t) = m.destructured
        if (y == "00") return null
        return "20$y-$mo-$d $t"
    }

    private fun Map<String, String>.num(key: String): Double? = this[key]?.toDoubleOrNull()
}
