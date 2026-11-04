// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.amslersolar.solardash.data.HistoryRepository
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.LoggedEvent
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A plant shared by its collector, through a store, to an observer on the same machine: what the observer gets is
 * what the collector has. The store here is a small in-process stand-in that keeps the protocol's rules (signatures
 * checked, higher seq wins, token checked); the PHP store has its own conformance tests (store/tests/).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RemoteViewingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var plants: PlantRepository
    private lateinit var history: HistoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = FakeStore()
    private val dir = File(context.filesDir, "observer-test")

    /** Software keys standing in for the keystore. */
    private val keys = object : Collector.Keys {
        val signers = ConcurrentHashMap<String, SoftwareSigner>()
        override fun signer(plantId: String, create: Boolean): PlantSigner? =
            signers[plantId] ?: if (create) SoftwareSigner.generate().also { signers[plantId] = it } else null
        override fun delete(plantId: String) { signers.remove(plantId) }
    }

    private val inverter = DeviceConfig(id = "dev-1", name = "Inverter", type = DeviceType.GOODWE_ET, host = "192.168.0.10", serial = "GW-SECRET", password = "pw")

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase("history.db")
        dir.deleteRecursively()
        plants = PlantRepository(context)
        plants.plants.first().forEach { plants.removePlant(it.id) }
        history = HistoryRepository(context)
        plants.addPlant(Plant(id = "home", name = "Home", devices = listOf(inverter)))
    }

    @After
    fun tearDown() {
        scope.cancel()
        store.stop()
    }

    private suspend fun record(at: Instant, pvW: Int, problem: DeviceProblem? = null) {
        val reading = DeviceReading(pvPowerW = pvW, gridPowerW = 300, battery = BatteryState(-200, 64, null, "Charging"), problems = listOfNotNull(problem))
        history.record("home", PlantAggregator.aggregate(listOf(DeviceStatus(inverter, reading, null)), at))
    }

    @Test
    fun anObserverSeesThePlantItsHistoryFaultsAndLiveValues() = runBlocking {
        val now = Instant.now()
        for (m in 30 downTo 1) record(now.minusSeconds(60L * m), 1000 + m)
        history.openEvent(LoggedEvent(plantId = "home", key = "k1", kind = "MAINS_LOST", severity = "ALARM", deviceId = "dev-1", deviceName = "Inverter", detail = "Grid lost", percent = null, started = now.epochSecond - 600))

        val collector = Collector(plants, history, scope, File(dir, "collector.json"), keys)
        collector.share("home", listOf(store.url))
        val shared = plants.plant("home").first()!!
        val link = collector.observerLink(shared)!!
        assertTrue(link, link.startsWith("https://amsler-solar.ch/solardash/o#"))

        // What went to the store: no address, serial or password anywhere, even before decryption.
        val all = store.items.values.flatMap { it.values }.joinToString { String(it.encode(), Charsets.ISO_8859_1) }
        for (secret in listOf("192.168.0.10", "GW-SECRET")) assertTrue(secret, secret !in all)

        val observer = Observer(plants, history, File(dir, "observer.json"))
        val code = ObserverCode.parse(link)!!
        val id = observer.add(code)
        val observed = plants.plant(id).first()!!
        assertEquals("Home", observed.name)
        assertEquals(listOf("Inverter"), observed.devices.map { it.name })
        assertEquals("", observed.devices.single().host)
        assertEquals("", observed.devices.single().serial)
        assertNotNull(observed.observed?.publicKey)

        val samples = history.samples(id, now.epochSecond - 3600, now.epochSecond)
        assertEquals(30, samples.size)
        assertEquals(1030, samples.first().pvW)
        assertEquals(300, samples.first().gridW)
        assertEquals(listOf("MAINS_LOST"), history.events(id).map { it.kind })

        // Live: nobody watches → nothing sent; once the observer watches, the collector learns it and sends.
        assertNull(observer.live(observed))
        observer.watch(observed)
        collector.start()
        withTimeout(20_000) { while ("home" !in collector.watched.value) delay(100) }
        val reading = DeviceReading(pvPowerW = 3200, battery = BatteryState(-500, 70, null, "Charging"), problems = listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Fan")))
        collector.live("home", PlantAggregator.aggregate(listOf(DeviceStatus(inverter, reading, null))))
        val live = withTimeout(10_000) { var s = observer.live(observed); while (s == null) { delay(100); s = observer.live(observed) }; s }
        assertEquals(3200, live.pvPowerW)
        assertEquals(70, live.battery?.socPercent)
        assertEquals("Fan", live.devices.single().reading?.problems?.single()?.text)

        // A store replaying an older plant item is ignored.
        val before = store.items.getValue(code.remoteId).getValue("5:plant")
        plants.renamePlant("home", "Home 2")
        collector.sync("home")
        observer.sync(id)
        assertEquals("Home 2", plants.plant(id).first()!!.name)
        store.items.getValue(code.remoteId)["5:plant"] = before
        store.lowerSeqAllowed = true
        observer.sync(id)
        assertEquals("Home 2", plants.plant(id).first()!!.name)
    }

    /**
     * The same flow against the PHP store (store/store.php), when STORE_URL names one, e.g. a local `php -S`:
     * share, observe, history, fault log, watch and live.
     */
    @Test
    fun theFlowWorksAgainstThePhpStore() = runBlocking {
        val url = System.getenv("STORE_URL") ?: return@runBlocking
        val now = Instant.now()
        for (m in 10 downTo 1) record(now.minusSeconds(60L * m), 2000 + m)
        history.openEvent(LoggedEvent(plantId = "home", key = "k2", kind = "DEVICE_FAULT", severity = "ALARM", deviceId = "dev-1", deviceName = "Inverter", detail = "F04", percent = null, started = now.epochSecond - 300))
        val collector = Collector(plants, history, scope, File(dir, "collector.json"), keys)
        collector.share("home", listOf(url))
        val code = ObserverCode.parse(collector.observerLink(plants.plant("home").first()!!)!!)!!
        val observer = Observer(plants, history, File(dir, "observer.json"))
        val id = observer.add(code)
        val observed = plants.plant(id).first()!!
        assertEquals("Home", observed.name)
        assertEquals(10, history.samples(id, now.epochSecond - 3600, now.epochSecond).size)
        assertEquals(listOf("DEVICE_FAULT"), history.events(id).map { it.kind })
        observer.watch(observed)
        collector.start()
        withTimeout(30_000) { while ("home" !in collector.watched.value) delay(200) }
        collector.live("home", PlantAggregator.aggregate(listOf(DeviceStatus(inverter, DeviceReading(pvPowerW = 4100), null))))
        val live = withTimeout(10_000) { var s = observer.live(observed); while (s == null) { delay(200); s = observer.live(observed) }; s }
        assertEquals(4100, live.pvPowerW)
        collector.stop("home")
        assertNull(observer.live(observed))
    }

    @Test
    fun aWrongCodeOrAForgedEnvelopeGivesNothing() = runBlocking {
        record(Instant.now().minusSeconds(120), 500)
        val collector = Collector(plants, history, scope, File(dir, "collector.json"), keys)
        collector.share("home", listOf(store.url))
        val code = ObserverCode.parse(collector.observerLink(plants.plant("home").first()!!)!!)!!
        assertNull(ObserverCode.parse("https://amsler-solar.ch/solardash/o#nonsense"))

        // Another plant's key answering for this id: it doesn't hash to the id, so nothing is accepted.
        store.keyOverride = SoftwareSigner.generate().publicKey
        val observer = Observer(plants, history, File(dir, "observer.json"))
        val id = observer.add(code)
        assertEquals(emptyList<Any>(), history.samples(id, 0, Long.MAX_VALUE))
        assertNull(plants.plant(id).first()!!.observed?.publicKey)

        // The right key, but a wrong data key: signatures pass, decryption doesn't.
        store.keyOverride = null
        val wrong = observer.add(code.copy(dataKey = Wire.randomBytes(32)))
        assertEquals(id, wrong)
        assertEquals(emptyList<Any>(), history.samples(id, 0, Long.MAX_VALUE))
    }

    /** One HTTP exchange of the tiny test server. */
    private class Exchange(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray) {
        var status = 200
        var out = ByteArray(0)
    }

    /** The protocol's store, in memory, over a minimal HTTP/1.0 server (one request per connection). */
    private class FakeStore {
        val keys = ConcurrentHashMap<String, ByteArray>()
        val tokens = ConcurrentHashMap<String, ByteArray>()
        /** Per plant: "kind:name" → envelope. */
        val items = ConcurrentHashMap<String, MutableMap<String, Envelope>>()
        val watchedAt = ConcurrentHashMap<String, Long>()
        @Volatile var keyOverride: ByteArray? = null
        @Volatile var lowerSeqAllowed = false
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/store.php"

        init {
            Thread {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread { s.use { serve(it) } }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(s: java.net.Socket) {
            val input = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String {
                val b = java.io.ByteArrayOutputStream()
                while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
                return b.toString("ISO-8859-1")
            }
            val (method, target) = line().split(' ').let { it[0] to it[1] }
            val headers = HashMap<String, String>()
            while (true) { val h = line(); if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var read = 0
            while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) break; read += n }
            val ex = Exchange(method, target.substringBefore('?'), target.substringAfter('?', ""), headers, body)
            handle(ex)
            val o = s.getOutputStream()
            o.write("HTTP/1.0 ${ex.status} X\r\nContent-Length: ${ex.out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            o.write(ex.out)
            o.flush()
        }

        private fun handle(ex: Exchange) {
            val query = ex.query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val route = query["r"].orEmpty()
            val parts = route.trim('/').split('/')
            val id = parts.getOrNull(2).orEmpty()
            fun answer(status: Int, bytes: ByteArray = ByteArray(0)) { ex.status = status; ex.out = bytes }
            fun json(s: String) = answer(200, s.toByteArray())
            val observe = parts.getOrNull(1) == "observe"
            if (observe && (tokens[id] == null || !Wire.sha256(Wire.unb64(ex.headers["x-observe-token"].orEmpty())).contentEquals(tokens[id]))) {
                answer(403, "{\"error\":\"no\"}".toByteArray()); return
            }
            when {
                !observe && parts.size == 3 -> {
                    val e = Envelope.decode(ex.body)
                    val spki = e.payload.copyOf(e.payload.size - 32)
                    if (Wire.plantId(spki) != id || !Wire.verify(e, spki)) { answer(403); return }
                    keys[id] = spki; tokens[id] = e.payload.copyOfRange(e.payload.size - 32, e.payload.size)
                    json("{\"ok\":true}")
                }
                !observe && parts[3] == "items" -> {
                    val list = Frames.decode(ex.body)
                    if (list.any { !Wire.verify(it, keys.getValue(id)) }) { answer(403); return }
                    val m = items.getOrPut(id) { ConcurrentHashMap() }
                    var stored = 0
                    for (e in list) {
                        val k = "${e.kind.code}:${e.name}"
                        if ((m[k]?.seq ?: -1) < e.seq) { m[k] = e; stored++ }
                    }
                    json("{\"stored\":$stored,\"skipped\":${list.size - stored}}")
                }
                !observe && parts[3] == "watch" -> json("{\"watching\":${System.currentTimeMillis() - (watchedAt[id] ?: 0) < 120_000}}")
                !observe && parts[3] == "delete" -> { items.remove(id); keys.remove(id); tokens.remove(id); json("{\"ok\":true}") }
                parts[3] == "key" -> answer(200, keyOverride ?: keys.getValue(id))
                parts[3] == "index" -> {
                    val since = query["since"]?.toLongOrNull() ?: 0
                    val list = items[id].orEmpty().values.filter { it.kind != Kind.LIVE && (it.seq > since || lowerSeqAllowed) }.sortedBy { it.seq }
                    json("{\"items\":[" + list.joinToString(",") { "[${it.kind.code},\"${it.name}\",${it.seq}]" } + "],\"more\":false}")
                }
                parts[3] == "fetch" -> {
                    val asked = Json.parseToJsonElement(String(ex.body)).jsonArray.map { "${it.jsonArray[0].jsonPrimitive.int}:${it.jsonArray[1].jsonPrimitive.content}" }
                    answer(200, Frames.encode(asked.mapNotNull { items[id]?.get(it) }))
                }
                parts[3] == "live" && ex.method == "GET" -> items[id]?.get("4:live")?.let { answer(200, it.encode()) } ?: answer(204)
                parts[3] == "watch" -> { watchedAt[id] = System.currentTimeMillis(); answer(204) }
                else -> answer(404)
            }
        }

        fun stop() = socket.close()
    }
}
