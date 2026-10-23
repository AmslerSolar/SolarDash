// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.enphase

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/** One GET on the gateway, returning the response body. Throws [EnphaseAuthException] when the token is refused. */
fun interface HttpFetch {
    suspend fun get(path: String): String
}

/** The gateway refused the token (HTTP 401/403, or a redirect to its login page), or none is configured. */
class EnphaseAuthException(message: String) : IOException(message)

/** HTTP 404: the endpoint doesn't exist on this gateway / firmware. */
class EnphaseNotAvailableException(path: String) : IOException("$path not available on this gateway")

/**
 * Enphase IQ Gateway (Envoy) with software 7.x+, over its local HTTPS API with token-based authentication.
 *
 * Reference: Enphase technical brief "Accessing IQ Gateway local APIs or local UI with token-based
 * authentication" (January 2023, docs/specs/enphase/). Not tested against a real gateway yet.
 * - HTTPS (port 443) with a self-signed certificate ("Access the IQ Gateway local UI", step 3; the brief's curl
 *   example uses `-k`). As for the LG ESS, certificate and hostname checks are off for this device's
 *   connections only.
 * - Every request carries `Authorization: Bearer <token>` and `Accept: application/json` ("Access the IQ Gateway
 *   APIs"). The token is the owner token from entrez.enphaseenergy.com, pasted into the device's password field;
 *   it's valid for 1 year (owner) or 12 hours (installer). Fetching it from Enlighten with the owner's
 *   credentials is not implemented.
 * - Endpoints used (all from the brief's API table and appendix): `/ivp/meters` (CT configuration),
 *   `/ivp/meters/readings` (CT measurements), `/api/v1/production/inverters` (per microinverter),
 *   `/ivp/livedata/status` (aggregates incl. storage). The brief says readings and inverter data "get updated
 *   once every 5 minutes"; inverter data is therefore cached for 5 minutes. Readings are fetched every poll
 *   (UNVERIFIED whether the gateway really updates them only every 5 minutes; fetching more often is harmless).
 * - UNVERIFIED (not in the brief): the `/info` XML used for serial and firmware in [identify], the sign of the
 *   net-consumption CT (taken as + = import, the usual Enphase convention; the brief's sample is consistent
 *   with it: the net meter has received 1.24 MWh of the 1.61 MWh produced), the CT energy unit (Wh),
 *   the "total-consumption" / "storage" measurement types, and the storage sign in livedata (- = charging,
 *   inferred from the sample: pv 329.5 W = load 108.7 W + 220.8 W into storage).
 */
class EnphaseGatewayDriver internal constructor(
    private val fetch: HttpFetch,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDriver {
    constructor(host: String, port: Int = 443, token: String) : this(httpsFetch(host, port, token))

    private val mutex = Mutex()
    private var meters: List<JsonObject>? = null
    private var metersAt = 0L
    private var inverters: JsonArray? = null
    private var invertersAt = 0L
    private val missing = mutableSetOf<String>() // endpoints that answered 404

    override suspend fun identify(): DeviceInfo = mutex.withLock {
        val config = getArray(METERS)
        val ok = if (config.isNotEmpty()) config.all { it.isMeter() } else {
            // No CTs installed: the meter list is empty, so check the microinverter list too.
            val inv = runCatching { getArray(INVERTERS) }.getOrNull()
            inv != null && inv.isNotEmpty() && inv.all { (it as? JsonObject)?.str("serialNumber") != null }
        }
        if (!ok) throw IOException("Not an Enphase IQ Gateway: unexpected answer from $METERS")
        val info = optionalText(INFO)?.let { EnphaseParser.parseInfo(it) }
        val model = "Enphase IQ Gateway" + (info?.firmware?.let { " (firmware $it)" } ?: "")
        DeviceInfo(model = model, serial = info?.serial ?: "?")
    }

    override suspend fun read(): DeviceReading = mutex.withLock {
        val now = clock()
        if (meters == null || now - metersAt >= METERS_REFRESH_MS) {
            meters = getArray(METERS).filterIsInstance<JsonObject>()
            metersAt = now
        }
        val enabled = meters.orEmpty().filter { it.str("state") == "enabled" }
        val readings = if (enabled.isEmpty()) null else getArray(READINGS)
        if (inverters == null || now - invertersAt >= INVERTERS_REFRESH_MS) {
            val fresh = optionalText(INVERTERS)?.let { runCatching { parseArray(INVERTERS, it) }.getOrNull() }
            if (fresh != null || inverters == null) {
                inverters = fresh
                invertersAt = now
            }
        }
        val live = optionalText(LIVEDATA)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (readings == null && inverters == null) throw IOException("Gateway returned neither meter readings nor inverter data")
        EnphaseParser.parse(meters.orEmpty(), readings, inverters, live)
    }

    private suspend fun getArray(path: String): JsonArray = parseArray(path, fetch.get(path))

    private fun parseArray(path: String, text: String): JsonArray =
        runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: throw IOException("Not an Enphase IQ Gateway: $path did not return a JSON list")

    /** Optional extras: a missing endpoint is remembered and skipped; an auth failure is still reported. */
    private suspend fun optionalText(path: String): String? {
        if (path in missing) return null
        return try {
            fetch.get(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EnphaseAuthException) {
            if (path == INFO) null else throw e
        } catch (_: EnphaseNotAvailableException) {
            missing += path
            null
        } catch (_: IOException) {
            null
        }
    }

    internal companion object {
        const val METERS = "/ivp/meters"
        const val READINGS = "/ivp/meters/readings"
        const val INVERTERS = "/api/v1/production/inverters"
        const val LIVEDATA = "/ivp/livedata/status"
        const val INFO = "/info" // UNVERIFIED: not in the brief
        const val TIMEOUT_MS = 5_000
        const val METERS_REFRESH_MS = 10 * 60_000L
        const val INVERTERS_REFRESH_MS = 5 * 60_000L

        const val AUTH_HINT = "token expired or missing: create a new one at entrez.enphaseenergy.com " +
            "and paste it into the device's password field"

        private fun JsonElement.isMeter() = this is JsonObject && this["eid"] is JsonPrimitive && str("measurementType") != null

        /** Accepts the gateway's self-signed certificate. Used only for connections to the configured gateway. */
        val trustingContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }

        fun httpsFetch(host: String, port: Int, token: String): HttpFetch {
            val base = if (port == 443 || port == 0) "https://$host" else "https://$host:$port"
            val bearer = token.trim()
            return HttpFetch { path ->
                if (bearer.isEmpty()) throw EnphaseAuthException("No access token set ($AUTH_HINT)")
                withContext(Dispatchers.IO) { get(base, path, bearer) }
            }
        }

        private fun get(base: String, path: String, token: String): String {
            val conn = URL(base + path).openConnection() as HttpsURLConnection
            try {
                conn.sslSocketFactory = trustingContext.socketFactory
                conn.setHostnameVerifier { _, _ -> true }
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                val status = conn.responseCode
                checkStatus(path, status)
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** The brief's curl uses `-f` "in case of unauthenticated redirection", so a redirect means not logged in. */
        fun checkStatus(path: String, status: Int) {
            when {
                status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN ->
                    throw EnphaseAuthException("Gateway refused the token (HTTP $status): $AUTH_HINT")
                status in 300..399 -> throw EnphaseAuthException("Gateway redirected $path to its login page (HTTP $status): $AUTH_HINT")
                status == HttpURLConnection.HTTP_NOT_FOUND -> throw EnphaseNotAvailableException(path)
                status != HttpURLConnection.HTTP_OK -> throw IOException("HTTP $status from $path")
            }
        }
    }
}

private fun JsonElement.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** Maps the gateway's JSON responses (shapes as in the brief's appendix) to a [DeviceReading]. */
object EnphaseParser {
    data class Info(val serial: String?, val firmware: String?)

    /** UNVERIFIED: `/info` isn't in the brief; on gateways seen so far it's XML with `<device><sn>` and `<software>`. */
    fun parseInfo(xml: String): Info? {
        fun tag(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(xml)?.groupValues?.get(1)
        val device = Regex("<device>(.*?)</device>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        fun devTag(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(device)?.groupValues?.get(1)
        return Info(devTag("sn") ?: tag("sn"), devTag("software"))
    }

    fun parse(meters: List<JsonObject>, readings: JsonArray?, inverters: JsonArray?, live: JsonObject?): DeviceReading {
        val types = meters.filter { it.str("state") == "enabled" }.associate { it.str("eid") to it.str("measurementType") }
        val byType = readings.orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { r -> types[r.str("eid")]?.let { it to r } }.toMap()
        val production = byType["production"]
        val net = byType["net-consumption"]
        val total = byType["total-consumption"] // UNVERIFIED name; the brief only shows production and net-consumption

        val invList = inverters.orEmpty().filterIsInstance<JsonObject>()
        val invSum = if (invList.isEmpty()) null else invList.sumOf { it.num("lastReportWatts") ?: 0.0 }

        val pvW = (production?.num("activePower") ?: invSum)?.coerceAtLeast(0.0)
        // UNVERIFIED sign: net-consumption > 0 = importing, so our grid (+ = export) is its negation.
        val gridW = when {
            net != null -> net.num("activePower")?.let { -it }
            total != null && pvW != null -> total.num("activePower")?.let { pvW - it }
            else -> null
        }
        val loadW = when {
            total != null -> total.num("activePower")
            net != null && pvW != null -> net.num("activePower")?.let { it + pvW }
            else -> null
        }

        // Grid phases from the grid-side CT; the brief's sample lists 3 channels for a 2-phase meter, so cap at phaseCount.
        val phaseMeterType = if (net != null) "net-consumption" else if (production != null) "production" else null
        val phaseMeter = phaseMeterType?.let { byType[it] }
        val phaseCount = meters.firstOrNull { it.str("measurementType") == phaseMeterType }?.num("phaseCount")?.toInt() ?: 3
        val phases = (phaseMeter?.get("channels") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(phaseCount)
            .mapIndexedNotNull { i, ch ->
                val v = ch.num("voltage") ?: return@mapIndexedNotNull null
                if (v <= 0) return@mapIndexedNotNull null
                val p = if (phaseMeter === net) ch.num("activePower")?.let { -it.roundToInt() } else null
                PhaseReading("L${i + 1}", v, ch.num("current"), ch.num("freq") ?: 0.0, p)
            }

        val abnormal = meters.filter { it.str("state") == "enabled" && it.str("meteringStatus") != "normal" }
        val status = when {
            abnormal.isNotEmpty() -> abnormal.joinToString { "${it.str("measurementType")} meter ${it.str("meteringStatus")}" }
            pvW != null && pvW > 0 -> "Producing"
            else -> "Idle"
        }

        return DeviceReading(
            pvPowerW = pvW?.roundToInt(),
            battery = battery(live),
            gridPowerW = gridW?.roundToInt(),
            phases = phases,
            loadPowerW = loadW?.roundToInt(),
            status = status,
            // No daily counters are documented, so `today` stays empty.
            lifetimePvKwh = production?.num("actEnergyDlvd")?.div(1000), // UNVERIFIED unit: Wh
            details = details(meters, byType, invList, live),
        )
    }

    /** Storage from livedata (mW), only when the gateway reports Encharge / AC Battery capacity. */
    private fun battery(live: JsonObject?): BatteryState? {
        val m = live?.get("meters") as? JsonObject ?: return null
        val capacity = (m.num("enc_agg_energy") ?: 0.0) + (m.num("acb_agg_energy") ?: 0.0)
        if (capacity <= 0) return null
        val mw = (m["storage"] as? JsonObject)?.num("agg_p_mw") ?: return null
        val w = (mw / 1000).roundToInt() // UNVERIFIED sign: - = charging, matching our convention
        val mode = when {
            w > 0 -> "Discharging"
            w < 0 -> "Charging"
            else -> "Standby"
        }
        return BatteryState(w, m.num("enc_agg_soc")?.roundToInt(), null, mode)
    }

    private fun details(meters: List<JsonObject>, byType: Map<String, JsonObject>, inverters: List<JsonObject>, live: JsonObject?): List<DetailSection> {
        val sections = mutableListOf<DetailSection>()
        meters.forEach { m ->
            val type = m.str("measurementType") ?: "?"
            val r = byType[type]
            sections += Details.section(
                "Meter: $type",
                "EID" to m.str("eid"), "State" to m.str("state"), "Phase mode" to m.str("phaseMode"),
                "Phase count" to m.str("phaseCount"), "Metering status" to m.str("meteringStatus"),
                "Status flags" to (m["statusFlags"] as? JsonArray)?.joinToString { it.str() }?.ifEmpty { "none" },
                "Active power" to Details.fmt(r?.num("activePower"), "W", 1),
                "Apparent power" to Details.fmt(r?.num("apparentPower"), "VA", 1),
                "Reactive power" to Details.fmt(r?.num("reactivePower"), "var", 1),
                "Power factor" to Details.fmt(r?.num("pwrFactor"), "", 3),
                "Voltage" to Details.fmt(r?.num("voltage"), "V", 1),
                "Current" to Details.fmt(r?.num("current"), "A", 2),
                "Frequency" to Details.fmt(r?.num("freq"), "Hz", 2),
                "Energy delivered (actEnergyDlvd)" to Details.fmt(r?.num("actEnergyDlvd")?.div(1000), "kWh", 1),
                "Energy received (actEnergyRcvd)" to Details.fmt(r?.num("actEnergyRcvd")?.div(1000), "kWh", 1),
                "Reading time" to r?.num("timestamp")?.let { Instant.ofEpochSecond(it.toLong()).toString() },
            )
        }
        if (inverters.isNotEmpty()) {
            sections += DetailSection(
                "Microinverters (${inverters.size})",
                inverters.map { inv ->
                    val now = Details.fmt(inv.num("lastReportWatts")?.roundToInt(), "W") ?: "?"
                    val max = Details.fmt(inv.num("maxReportWatts")?.roundToInt(), "W")
                    val at = inv.num("lastReportDate")?.let { Instant.ofEpochSecond(it.toLong()).toString() }
                    (inv.str("serialNumber") ?: "?") to listOfNotNull(now, max?.let { "max $it" }, at).joinToString(", ")
                },
            )
        }
        val lm = live?.get("meters") as? JsonObject
        if (lm != null) {
            fun w(key: String) = Details.fmt((lm[key] as? JsonObject)?.num("agg_p_mw")?.div(1000), "W", 1)
            sections += Details.section(
                "Live data",
                "PV" to w("pv"), "Storage" to w("storage"), "Grid" to w("grid"), "Load" to w("load"), "Generator" to w("generator"),
                "Encharge SoC" to Details.fmt(lm.num("enc_agg_soc")?.roundToInt(), "%"),
                "Encharge energy" to Details.fmt(lm.num("enc_agg_energy")?.roundToInt(), "Wh"),
                "AC Battery SoC" to Details.fmt(lm.num("acb_agg_soc")?.roundToInt(), "%"),
                "AC Battery energy" to Details.fmt(lm.num("acb_agg_energy")?.roundToInt(), "Wh"),
                "Last update" to lm.num("last_update")?.let { Instant.ofEpochSecond(it.toLong()).toString() },
            )
        }
        return sections.filter { it.rows.isNotEmpty() }
    }

    private fun JsonElement.str(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()
}
