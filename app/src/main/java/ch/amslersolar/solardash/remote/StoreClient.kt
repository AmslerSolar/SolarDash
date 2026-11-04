// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A store's answer that isn't a success; [retryAfterS] for 429 and 503 (the client stops asking until then). */
class StoreException(val status: Int, message: String, val retryAfterS: Long? = null) : IOException(message)

/**
 * One store of the observer protocol (docs/design/observer-protocol.md), over HTTPS. Requests go to
 * `<url>?r=<route>`. After a 429 or 503 nothing is sent to that store until its Retry-After has passed.
 */
class StoreClient(val url: String, private val token: ByteArray? = null) {
    @Volatile private var quietUntil = 0L

    // ---------------------------------------------------------------- collector

    suspend fun register(plantId: String, envelope: Envelope) {
        request("POST", "/v1/plants/$plantId", envelope.encode())
    }

    /** Returns (stored, skipped). */
    suspend fun items(plantId: String, envelopes: List<Envelope>): Pair<Int, Int> {
        val o = Json.parseToJsonElement(String(request("POST", "/v1/plants/$plantId/items", Frames.encode(envelopes)))).jsonObject
        return (o["stored"]?.jsonPrimitive?.int ?: 0) to (o["skipped"]?.jsonPrimitive?.int ?: 0)
    }

    suspend fun watching(plantId: String): Boolean =
        Json.parseToJsonElement(String(request("GET", "/v1/plants/$plantId/watch"))).jsonObject["watching"]?.jsonPrimitive?.booleanOrNull == true

    suspend fun delete(plantId: String, envelope: Envelope) {
        request("POST", "/v1/plants/$plantId/delete", envelope.encode())
    }

    // ---------------------------------------------------------------- observer

    suspend fun key(plantId: String): ByteArray = request("GET", "/v1/observe/$plantId/key")

    /** (kind, name, seq) of stored items with a seq above [since], and whether more follow. */
    suspend fun index(plantId: String, since: Long): Pair<List<Triple<Int, String, Long>>, Boolean> {
        val o = Json.parseToJsonElement(String(request("GET", "/v1/observe/$plantId/index", query = "&since=$since"))).jsonObject
        val items = o["items"]?.jsonArray.orEmpty().map { e ->
            val a = e.jsonArray
            Triple(a[0].jsonPrimitive.int, a[1].jsonPrimitive.content, a[2].jsonPrimitive.long)
        }
        return items to (o["more"]?.jsonPrimitive?.booleanOrNull == true)
    }

    suspend fun fetch(plantId: String, items: List<Pair<Kind, String>>): List<Envelope> {
        if (items.isEmpty()) return emptyList()
        val body = JsonArray(items.map { (k, n) -> JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(k.code), kotlinx.serialization.json.JsonPrimitive(n))) })
        return Frames.decode(request("POST", "/v1/observe/$plantId/fetch", body.toString().toByteArray(), "application/json"))
    }

    /** The latest live envelope, or null when the plant sent none yet. */
    suspend fun live(plantId: String): Envelope? = request("GET", "/v1/observe/$plantId/live").takeIf { it.isNotEmpty() }?.let(Envelope::decode)

    suspend fun watch(plantId: String) {
        request("POST", "/v1/observe/$plantId/watch", ByteArray(0))
    }

    private suspend fun request(
        method: String, route: String, body: ByteArray? = null, contentType: String = "application/octet-stream", query: String = "",
    ): ByteArray = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now < quietUntil) throw StoreException(429, "$url: waiting until the store accepts requests again", (quietUntil - now) / 1000)
        val c = URL("$url?r=${URLEncoder.encode(route, "UTF-8")}$query").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SolarDash-observer/1")
            token?.let { c.setRequestProperty("X-Observe-Token", Wire.b64(it)) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            if (status == 204) return@withContext ByteArray(0)
            if (status in 200..299) return@withContext c.inputStream.use { it.readBytes() }
            val retry = c.getHeaderField("Retry-After")?.toLongOrNull()
            if (status == 429 || status == 503) quietUntil = System.currentTimeMillis() + 1000 * (retry ?: 60)
            val text = runCatching { c.errorStream?.use { String(it.readBytes()) } }.getOrNull()
            val error = runCatching { Json.parseToJsonElement(text.orEmpty()).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw StoreException(status, "$url: ${error ?: "HTTP $status"}", retry)
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
