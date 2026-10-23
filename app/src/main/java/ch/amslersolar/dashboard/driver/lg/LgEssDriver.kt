// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.lg

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.abs
import kotlin.math.roundToInt

class LgEssAuthException(message: String) : IOException(message)

/** HTTP 404: the endpoint doesn't exist on this model or firmware. */
class NotAvailableException(path: String) : IOException("$path not available on this unit")

/**
 * LG ESS Home 8 / Home 10 hybrid inverters with battery, over their local HTTPS API.
 *
 * Reference: "LG ESS Inverter — Local HTTPS API" (docs/specs/lg ess/), checked against a real Home 10
 * (firmware 10.05.7437), which differs from the document in places:
 * - HTTPS on port 443. Every unit shares the same self-signed certificate, so it can't be verified; checks are
 *   off for this device only.
 * - The user password is the inverter's MAC address, lowercase hex without separators. The unit accepts it on
 *   `PUT /v1/user/setting/login`; the documented `/v1/login` answers "password_mismatched" there, so both are
 *   tried. Every read is a `POST` with `{"auth_key"}` in the body.
 * - An invalid or expired key answers 405 `{"auth": "auth_key failed"}` on the unit, 200 `{"status": "fail"}`
 *   per the document; both mean log in again. 404 means the endpoint doesn't exist on this model / firmware.
 * - `/v1/logout` is documented but returns 404 on the unit; it's tried once when polling stops.
 * - This driver only reads (earlier versions copied pyess's clock sync after login, a write the document
 *   doesn't list).
 * - Values are JSON strings. Power in W, daily energy in Wh; power magnitudes are unsigned and the direction
 *   comes from the 0/1 flags in `home.direction`.
 */
class LgEssDriver(host: String, port: Int, private val password: String) : DeviceDriver {
    private val base = if (port == 443 || port == 0) "https://$host" else "https://$host:$port"
    private val mutex = Mutex()
    private var authKey: String? = null
    private var systemInfo: JsonObject? = null // read once for the detail view
    private var extras: Map<String, JsonObject> = emptyMap()
    private val missing = mutableSetOf<String>() // endpoints that answered 404
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val info = post(SYSTEMINFO)
        val pms = info.obj("pms")
        val version = info.obj("version")?.str("pms_version")
        val model = (pms?.str("model") ?: "LG ESS") + (version?.let { " (firmware $it)" } ?: "")
        return DeviceInfo(model = model, serial = pms?.str("serialno") ?: "?")
    }

    override suspend fun read(): DeviceReading {
        val home = post(HOME)
        val common = optional(COMMON) // strings, grid voltage and daily totals are extras
        if (systemInfo == null) systemInfo = optional(SYSTEMINFO) ?: JsonObject(emptyMap())
        if (polls % EXTRA_REFRESH_POLLS == 0) {
            extras = EXTRA_READS.mapNotNull { (title, path) -> optional(path)?.let { title to it } }.toMap()
        }
        polls++
        return LgEssParser.parse(home, common).copy(details = LgEssParser.details(home, common, systemInfo, extras))
    }

    /** End the session; the unit may not have the endpoint (404), which is fine. */
    override fun close() {
        val key = authKey ?: return
        authKey = null
        runCatching { request("POST", LOGOUT, buildJsonObject { put("auth_key", key) }) }
    }

    private suspend fun optional(path: String): JsonObject? {
        if (path in missing) return null
        return try {
            post(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NotAvailableException) {
            missing += path
            null
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun post(path: String): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            repeat(2) {
                val key = authKey ?: login()
                val (status, body) = request("POST", path, buildJsonObject { put("auth_key", key) })
                if (authFailed(body)) {
                    authKey = null // expired: log in again and retry once
                } else {
                    if (status != 200) throw IOException("HTTP $status from $path")
                    return@withContext body
                }
            }
            throw LgEssAuthException("Device rejected the login")
        }
    }

    private fun login(): String {
        if (password.isEmpty()) throw LgEssAuthException("No device password set")
        var mismatched = false
        for (path in LOGIN_PATHS) {
            val (_, body) = try {
                request("PUT", path, buildJsonObject { put("password", password) })
            } catch (_: NotAvailableException) {
                continue
            }
            val key = body.str("auth_key")
            if (body.str("status") == "success" && key != null) {
                authKey = key
                return key
            }
            mismatched = mismatched || body.str("status")?.contains("mismatch") == true
        }
        throw LgEssAuthException(
            if (mismatched) "Wrong device password (it's the inverter's MAC address, lowercase, no colons)" else "Login failed",
        )
    }

    private fun request(method: String, path: String, body: JsonObject): Pair<Int, JsonObject> {
        val conn = URL(base + path).openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = trustingContext.socketFactory
            conn.setHostnameVerifier { _, _ -> true }
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw NotAvailableException(path)
            // An expired key comes back as 405 with a JSON body, so read the error stream too.
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            return status to json
        } finally {
            conn.disconnect()
        }
    }

    internal companion object {
        val LOGIN_PATHS = listOf("/v1/user/setting/login", "/v1/login")
        const val LOGOUT = "/v1/logout"
        const val HOME = "/v1/user/essinfo/home"
        const val COMMON = "/v1/user/essinfo/common"
        const val SYSTEMINFO = "/v1/user/setting/systeminfo"
        const val TIMEOUT_MS = 5_000
        const val EXTRA_REFRESH_POLLS = 12

        /** Read-only extras for the detail view, refreshed about once a minute; missing ones (404) are skipped. */
        val EXTRA_READS = listOf(
            "Faults" to "/v1/user/operation/faultlist", "Battery settings" to "/v1/user/setting/batt",
            "Clock" to "/v1/user/setting/time", "PCS settings" to "/v1/user/setting/pcssetting",
            "Overload" to "/v1/user/essinfo/overloadfaultview", "Firmware update" to "/v1/user/imageupdate/status",
        )

        /** 405 {"auth": "auth_key failed"} on the real unit, 200 {"status": "fail"} per LG's document. */
        fun authFailed(body: JsonObject) = body.str("auth") in setOf("auth_key failed", "auth failed") || body.str("status") == "fail"

        /** Accepts the unit's self-signed certificate. Used only for connections to the configured LG ESS. */
        val trustingContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Maps the device's `home` (live values, direction flags) and `common` (details, daily totals) responses. */
object LgEssParser {
    /** Raw fields named after a phase get the L1/L2/L3 names used everywhere else. */
    private val PHASE_LABELS = mapOf("a_phase" to "L1 voltage (a_phase)", "b_phase" to "L2 voltage (b_phase)", "c_phase" to "L3 voltage (c_phase)")

    /** 'is_battery_charging_' → 'Is battery charging'; the device's own spellings (e.g. 'enery') are kept. */
    fun label(key: String): String = PHASE_LABELS[key]
        ?: key.trim('_').replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** One section per JSON object, with every field the unit sent (values as reported). */
    private fun flatten(prefix: String, obj: JsonObject): List<DetailSection> {
        val rows = obj.entries.filter { it.value !is JsonObject }.map { (k, v) ->
            label(k) to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
        }
        val nested = obj.entries.filter { it.value is JsonObject }.flatMap { (k, v) -> flatten("$prefix · $k", v as JsonObject) }
        return (if (rows.isNotEmpty()) listOf(DetailSection(prefix, rows)) else emptyList()) + nested
    }

    /** Raw fields as the unit reports them. Units: power W, energies Wh, SoC and self-consumption %. */
    fun details(home: JsonObject, common: JsonObject?, systemInfo: JsonObject?, extras: Map<String, JsonObject> = emptyMap()): List<DetailSection> =
        (systemInfo?.let { flatten("System info", it) } ?: emptyList()) + flatten("Home", home) +
            (common?.let { flatten("Details", it) } ?: emptyList()) + extras.flatMap { (title, body) -> flatten(title, body) }

    private fun JsonObject?.num(key: String): Double? = this?.str(key)?.toDoubleOrNull()
    private fun JsonObject?.int(key: String): Int? = num(key)?.roundToInt()
    private fun JsonObject?.kwh(key: String): Double? = num(key)?.div(1000)
    private fun JsonObject?.flag(key: String): Boolean = this?.str(key) == "1"

    fun parse(home: JsonObject, common: JsonObject?): DeviceReading {
        val stats = home.obj("statistics")
        val direction = home.obj("direction")

        val battery = if (stats?.str("bat_use") == "0") null else {
            val magnitude = stats.int("batconv_power") ?: 0
            val power = when {
                direction.flag("is_battery_discharging_") -> magnitude
                direction.flag("is_battery_charging_") -> -magnitude
                else -> 0
            }
            var mode = when {
                power > 0 -> "Discharging"
                power < 0 -> "Charging"
                else -> "Standby"
            }
            if (direction.flag("is_charging_from_grid_")) mode += " (from grid)"
            BatteryState(power, stats.int("bat_user_soc"), null, mode)
        }

        val grid = stats.int("grid_power")?.let { m ->
            when {
                direction.flag("is_grid_selling_") -> m
                direction.flag("is_grid_buying_") -> -m
                else -> 0
            }
        }

        val fault = home.obj("pcs_fault")?.str("pcs_status")
        val status = if (fault != null && fault != "pcs_ok") "Fault ($fault)" else
            when (val op = home.obj("operation")?.str("status")) {
                "start" -> "Running"
                "stop" -> "Stopped"
                null -> "Unknown"
                else -> op
            }

        val pv = common?.obj("PV")
        val g = common?.obj("GRID")
        val strings = (1..2).mapNotNull { i ->
            val v = pv.num("pv${i}_voltage") ?: 0.0
            val p = pv.int("pv${i}_power")
            if (v > 0 || (p ?: 0) > 0) PvString(i, v, pv.num("pv${i}_current") ?: 0.0, p) else null
        }
        // The API reports one phase voltage and only a total power: no per-phase current or power.
        val volts = g.num("a_phase")
        val phases = if (volts != null && volts > 0) listOf(PhaseReading("L1", volts, null, g.num("freq") ?: 0.0, null)) else emptyList()

        return DeviceReading(
            pvPowerW = stats.int("pcs_pv_total_power"),
            pvStrings = strings,
            battery = battery,
            gridPowerW = grid,
            phases = phases,
            loadPowerW = stats.int("load_power"),
            status = status,
            today = if (common == null) EnergyTotals() else EnergyTotals(
                pvKwh = pv.kwh("today_pv_generation_sum"),
                exportKwh = g.kwh("today_grid_feed_in_energy"),
                importKwh = g.kwh("today_grid_power_purchase_energy"),
                loadKwh = common.obj("LOAD").kwh("today_load_consumption_sum"),
            ),
        )
    }
}
