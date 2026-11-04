// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import ch.amslersolar.solardash.data.HistoryRepository
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantSnapshot
import ch.amslersolar.solardash.model.Sample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * What an observer code holds (observer protocol, "Observer code"): the plant's id on the stores, its read token,
 * data key and stores. Read from the link in the QR code, the app link, or the code typed or pasted.
 */
data class ObserverCode(val remoteId: String, val token: ByteArray, val dataKey: ByteArray, val stores: List<String>) {
    fun fragment(): String {
        val list = Contents.json.encodeToString(ListSerializer(String.serializer()), stores)
        return listOf(remoteId, Wire.b64(token), Wire.b64(dataKey), Wire.b64(list.toByteArray())).joinToString(".")
    }

    fun link() = "$WEB_VIEW#${fragment()}"

    companion object {
        const val WEB_VIEW = "https://amsler-solar.ch/solardash/o"
        private val ID = Regex("[A-Za-z0-9_-]{22}")

        /** The code in a link (`https://…#…`, `solardash://observe#…`) or on its own; null when it isn't one. */
        fun parse(text: String): ObserverCode? = try {
            val fragment = text.trim().substringAfter('#').let { URLDecoder.decode(it, "UTF-8") }.trim()
            val parts = fragment.split('.')
            if (parts.size != 4 || !ID.matches(parts[0])) null
            else {
                val token = Wire.unb64(parts[1])
                val key = Wire.unb64(parts[2])
                val stores = Contents.json.decodeFromString(ListSerializer(String.serializer()), String(Wire.unb64(parts[3])))
                    .filter { it.startsWith("https://") || it.startsWith("http://") }
                if (token.size != 16 || key.size != 32 || stores.isEmpty()) null else ObserverCode(parts[0], token, key, stores)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun equals(other: Any?) = other is ObserverCode && other.fragment() == fragment()
    override fun hashCode() = fragment().hashCode()
}

/**
 * An observer's side of remote viewing: adds an observed plant from its code, and keeps it current from the
 * plant's stores — the plant's description, its history (plant and device channels), day totals and fault log
 * into the local history database, so the screens work as for a plant of one's own, also offline. Every envelope
 * is checked against the plant's key (which must hash to its id) and decrypted with the data key; anything older
 * than what was seen before (a store replaying) is ignored.
 */
class Observer(
    private val repository: PlantRepository,
    private val history: HistoryRepository,
    private val stateFile: File,
    private val client: (url: String, token: ByteArray) -> StoreClient = { url, token -> StoreClient(url, token) },
) {
    @Serializable private data class PlantProgress(
        /** Per store: the index has been read up to this seq. */
        val since: Map<String, Long> = emptyMap(),
        /** The highest seq seen per kind and name, for the items that are replaced over time. */
        val seen: Map<String, Long> = emptyMap(),
        /** Per device the channels as (key, label, unit), from the plant item. */
        val channels: Map<String, List<List<String>>> = emptyMap(),
    )

    @Serializable private data class State(val plants: Map<String, PlantProgress> = emptyMap())

    private val lock = Mutex()
    private var state = load()
    private val syncing = Mutex()

    /** Adds the observed plant (or returns the one with the same code) and reads its description. */
    suspend fun add(code: ObserverCode): String {
        repository.plants.first().find { it.observed?.remoteId == code.remoteId }?.let { existing ->
            repository.setObserved(existing.id, observed(code, existing.observed?.publicKey))
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        repository.addPlant(Plant(id = id, name = code.remoteId.take(6), observed = observed(code, null)))
        runCatching { sync(id) }
        return id
    }

    private fun observed(code: ObserverCode, publicKey: String?) =
        Plant.Observed(code.remoteId, Wire.b64(code.token), Wire.b64(code.dataKey), code.stores, publicKey)

    /** "I'm watching": the collector sends live values while this is renewed. */
    suspend fun watch(plant: Plant) {
        val o = plant.observed ?: return
        for (url in o.stores) if (runCatching { client(url, Wire.unb64(o.token)).watch(o.remoteId) }.isSuccess) return
    }

    /** The plant's latest live snapshot from the first store that has one, or null. */
    suspend fun live(plant: Plant): PlantSnapshot? {
        val o = plant.observed ?: return null
        val key = publicKey(plant) ?: return null
        for (url in o.stores) {
            try {
                val e = client(url, Wire.unb64(o.token)).live(o.remoteId) ?: continue
                if (e.kind != Kind.LIVE || e.name != "live" || !Wire.verify(e, key)) continue
                if (!fresh(plant.id, e)) continue
                val live = Contents.json.decodeFromString(Contents.Live.serializer(), String(Wire.open(e, Wire.unb64(o.dataKey))))
                return Contents.snapshot(live, plant)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next store.
            }
        }
        return null
    }

    /** Reads what's new on the plant's stores into the app: description, history, days, fault log. */
    suspend fun sync(plantId: String) = syncing.withLock {
        val plant = repository.plant(plantId).first() ?: return@withLock
        val o = plant.observed ?: return@withLock
        val key = publicKey(plant) ?: throw IOException("No store answered for this plant")
        val dataKey = Wire.unb64(o.dataKey)
        for (url in o.stores) {
            try {
                val store = client(url, Wire.unb64(o.token))
                var since = progress(plantId).since[url] ?: 0L
                do {
                    val (items, more) = store.index(o.remoteId, since)
                    // The description first (devices, channel catalogue), then the rest in order.
                    val wanted = items.sortedBy { if (it.first == Kind.PLANT.code) 0 else 1 }
                    for (chunk in wanted.chunked(FETCH)) {
                        val asked = chunk.mapNotNull { (k, n, _) -> runCatching { Kind.of(k) }.getOrNull()?.let { it to n } }
                        for (e in store.fetch(o.remoteId, asked)) {
                            if ((e.kind to e.name) !in asked || !Wire.verify(e, key) || !fresh(plantId, e)) continue
                            apply(plantId, e, Wire.open(e, dataKey))
                        }
                    }
                    items.maxOfOrNull { it.third }?.let { since = maxOf(since, it) }
                    setSince(plantId, url, since)
                } while (more && items.isNotEmpty())
                return@withLock
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next store.
            }
        }
    }

    private suspend fun apply(plantId: String, e: Envelope, content: ByteArray) {
        when (e.kind) {
            Kind.PLANT -> {
                val info = Contents.json.decodeFromString(Contents.PlantInfo.serializer(), String(content))
                val current = repository.plant(plantId).first() ?: return
                repository.updateObserved(plantId, Contents.plant(plantId, info, requireNotNull(current.observed)))
                lock.withLock {
                    val p = state.plants[plantId] ?: PlantProgress()
                    state = state.copy(plants = state.plants + (plantId to p.copy(channels = info.channels)))
                    write()
                }
            }
            Kind.BLOCK -> block(plantId, e.name, Block.decode(content))
            Kind.DAY -> Contents.day(Contents.json.decodeFromString(Contents.Day.serializer(), String(content)))?.let { (day, _) ->
                history.insert(plantId, emptyList(), listOf(day))
            }
            Kind.EVENT -> history.upsertObservedEvent(Contents.event(plantId, Contents.json.decodeFromString(Contents.Event.serializer(), String(content))))
            Kind.STORES -> {
                val stores = Contents.json.decodeFromString(ListSerializer(String.serializer()), String(content))
                    .filter { it.startsWith("https://") || it.startsWith("http://") }
                val current = repository.plant(plantId).first()?.observed ?: return
                if (stores.isNotEmpty() && stores != current.stores) repository.setObserved(plantId, current.copy(stores = stores))
            }
            else -> {}
        }
    }

    private suspend fun block(plantId: String, name: String, block: Block) {
        val deviceId = name.substringAfter('.', "")
        val ts = { i: Int -> block.start + i.toLong() * block.interval }
        if (deviceId.isEmpty()) {
            fun ch(n: String) = block.channels.find { it.name == n }?.values
            val pv = ch("pv"); val load = ch("load"); val battery = ch("battery"); val grid = ch("grid"); val soc = ch("soc")
            val samples = (0 until block.slots).mapNotNull { i ->
                val values = listOf(pv, load, battery, grid, soc).map { it?.getOrNull(i)?.roundToInt() }
                if (values.all { it == null }) null else Sample(ts(i), values[0], values[1], values[2], values[3], values[4])
            }
            history.insert(plantId, samples, emptyList())
        } else {
            val catalog = progress(plantId).channels[deviceId].orEmpty().associate { e -> e[0] to (e.getOrElse(1) { e[0] } to e.getOrElse(2) { "" }) }
            val rows = (0 until block.slots).mapNotNull { i ->
                val values = block.channels.mapNotNull { c -> c.values.getOrNull(i)?.let { c.name to it } }.toMap()
                if (values.isEmpty()) null else ts(i) to values
            }
            history.insertDeviceSamples(deviceId, block.channels.map { c -> val known = catalog[c.name]; Triple(c.name, known?.first ?: c.name, known?.second ?: "") }, rows)
        }
    }

    /** The plant's public key: learned from a store once, and only if it hashes to the plant's id. */
    private suspend fun publicKey(plant: Plant): ByteArray? {
        val o = plant.observed ?: return null
        o.publicKey?.let { return Wire.unb64(it) }
        for (url in o.stores) {
            val spki = runCatching { client(url, Wire.unb64(o.token)).key(o.remoteId) }.getOrNull() ?: continue
            if (Wire.plantId(spki) != o.remoteId) continue
            repository.setObserved(plant.id, o.copy(publicKey = Wire.b64(spki)))
            return spki
        }
        return null
    }

    /**
     * Whether [e] is no older than what was seen before under its kind and name: a store replaying an older one is
     * ignored. The live snapshot may come again unchanged; blocks and the live snapshot are remembered while the app
     * runs, the rest in the state file.
     */
    private suspend fun fresh(plantId: String, e: Envelope): Boolean = lock.withLock {
        val k = "${e.kind.code}:${e.name}"
        val kept = e.kind == Kind.BLOCK || e.kind == Kind.LIVE
        val p = state.plants[plantId] ?: PlantProgress()
        val last = if (kept) memory["$plantId/$k"] else p.seen[k]
        if (last != null && (e.seq < last || e.seq == last && e.kind != Kind.LIVE)) return@withLock false
        if (kept) memory["$plantId/$k"] = e.seq
        else { state = state.copy(plants = state.plants + (plantId to p.copy(seen = p.seen + (k to e.seq)))); write() }
        true
    }

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun progress(plantId: String) = lock.withLock { state.plants[plantId] ?: PlantProgress() }

    private suspend fun setSince(plantId: String, url: String, since: Long) = lock.withLock {
        val p = state.plants[plantId] ?: PlantProgress()
        state = state.copy(plants = state.plants + (plantId to p.copy(since = p.since + (url to since))))
        write()
    }

    /** The plant was removed: its progress goes too. */
    suspend fun forget(plantId: String) = lock.withLock {
        state = state.copy(plants = state.plants - plantId)
        write()
    }

    private fun load(): State = try {
        if (stateFile.exists()) Contents.json.decodeFromString(State.serializer(), stateFile.readText()) else State()
    } catch (_: Exception) {
        State()
    }

    private fun write() {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(Contents.json.encodeToString(State.serializer(), state))
        tmp.renameTo(stateFile)
    }

    private companion object {
        const val FETCH = 100
    }
}
