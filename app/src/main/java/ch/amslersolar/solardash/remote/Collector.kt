// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ch.amslersolar.solardash.data.HistoryRepository
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The plant tablet's side of remote viewing (docs/design/remote-viewing.md): for every plant shared with observers
 * it sends the history (one-minute samples in hour blocks, plant and device channels), day totals, the fault log,
 * the plant's description and its store list to each store, catching up after time offline from its own database;
 * and live snapshots every [LIVE_INTERVAL_MS] while a store says someone watches.
 *
 * Everything is signed with the plant's key in the Android keystore and sealed with its data key. A store that
 * fails is tried again on the next minute; one that says 429 or 503 is left alone until its Retry-After.
 */
class Collector(
    private val repository: PlantRepository,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
    private val stateFile: File,
    private val keys: Keys = AndroidKeys,
    private val client: (String) -> StoreClient = { StoreClient(it) },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** The plants' signing keys. */
    interface Keys {
        fun signer(plantId: String, create: Boolean): PlantSigner?
        fun delete(plantId: String)
    }

    @Serializable private data class StoreProgress(
        /** SHA-256 (base64url) of the read token this store was given. */
        val registered: String? = null,
        /** Every hour before this one (epoch second) is on the store. */
        val confirmedHour: Long? = null,
        val plantHash: String? = null,
        val storesHash: String? = null,
        /** Fault log entries sent, by id, with their end time then (0: still going on). */
        val events: Map<Long, Long> = emptyMap(),
    )

    @Serializable private data class PlantProgress(val seq: Long = 0, val stores: Map<String, StoreProgress> = emptyMap())

    @Serializable private data class State(val plants: Map<String, PlantProgress> = emptyMap())

    private val lock = Mutex()
    private var state: State = load()
    private val _watched = MutableStateFlow<Set<String>>(emptySet())
    private val lastLive = ConcurrentHashMap<String, Long>()
    private val watchLoops = ConcurrentHashMap<String, Job>()

    /** Plants someone watches right now: their live poll runs every [LIVE_INTERVAL_MS]. */
    val watched: StateFlow<Set<String>> = _watched.asStateFlow()

    /** Keeps a watch loop running for each shared plant (from app start). */
    fun start() {
        scope.launch {
            repository.plants.map { list -> list.filter { it.sharing != null && it.observed == null }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { shared ->
                    watchLoops.keys.filter { it !in shared }.forEach { watchLoops.remove(it)?.cancel(); _watched.update { w -> w - it } }
                    for (id in shared) watchLoops.computeIfAbsent(id) { scope.launch { watchLoop(id) } }
                }
        }
    }

    /** The plant id on the stores: the hash of its public key (null when it isn't shared). */
    fun remoteId(plantId: String): String? = keys.signer(plantId, create = false)?.let { Wire.plantId(it.publicKey) }

    /** The observer code's link (QR code), or null when the plant isn't shared. */
    fun observerLink(plant: Plant): String? {
        val sharing = plant.sharing ?: return null
        val id = remoteId(plant.id) ?: return null
        return ObserverCode(id, Wire.unb64(sharing.token), Wire.unb64(sharing.dataKey), sharing.stores).link()
    }

    /** Starts sharing: a new key pair, read token and data key; the history goes out from the next minute. */
    suspend fun share(plantId: String, stores: List<String> = listOf(DEFAULT_STORE)) {
        keys.signer(plantId, create = true)
        repository.setSharing(plantId, Plant.Sharing(Wire.b64(Wire.randomBytes(16)), Wire.b64(Wire.randomBytes(32)), stores))
        forget(plantId)
        sync(plantId)
    }

    /** "New code": a new token and data key; every observer has to scan the new code. Everything is sent again. */
    suspend fun newCode(plantId: String) {
        val plant = repository.plant(plantId).first() ?: return
        val sharing = plant.sharing ?: return
        repository.setSharing(plantId, sharing.copy(token = Wire.b64(Wire.randomBytes(16)), dataKey = Wire.b64(Wire.randomBytes(32))))
        forget(plantId)
        sync(plantId)
    }

    suspend fun setStores(plantId: String, stores: List<String>) {
        val sharing = repository.plant(plantId).first()?.sharing ?: return
        repository.setSharing(plantId, sharing.copy(stores = stores.distinct()))
        sync(plantId)
    }

    /** Stops sharing: each store is asked to delete the plant, then the key is gone. */
    suspend fun stop(plantId: String) {
        val plant = repository.plant(plantId).first() ?: return
        val sharing = plant.sharing
        val signer = keys.signer(plantId, create = false)
        if (sharing != null && signer != null) {
            val id = Wire.plantId(signer.publicKey)
            for (url in sharing.stores) {
                runCatching { client(url).delete(id, Wire.envelope(signer, Kind.DELETE, nextSeq(plantId), now(), "delete", ByteArray(0), null)) }
            }
        }
        repository.setSharing(plantId, null)
        keys.delete(plantId)
        forget(plantId)
    }

    /** After a recorded sample (once a minute) or a background reading: sends what's new. */
    fun recorded(plantId: String) {
        scope.launch { runCatching { sync(plantId) } }
    }

    /** The live poll's latest snapshot: sent while someone watches, at most every [LIVE_INTERVAL_MS]. */
    fun live(plantId: String, snapshot: PlantSnapshot) {
        if (plantId !in _watched.value) return
        val now = System.currentTimeMillis()
        if (now - (lastLive[plantId] ?: 0) < LIVE_INTERVAL_MS - 500) return
        lastLive[plantId] = now
        scope.launch {
            val plant = repository.plant(plantId).first() ?: return@launch
            val sharing = plant.sharing ?: return@launch
            val signer = keys.signer(plantId, create = false) ?: return@launch
            val id = Wire.plantId(signer.publicKey)
            val content = Contents.json.encodeToString(Contents.Live.serializer(), Contents.live(snapshot)).toByteArray()
            val e = Wire.envelope(signer, Kind.LIVE, nextSeq(plantId), now(), "live", content, Wire.unb64(sharing.dataKey))
            for (url in sharing.stores) runCatching { client(url).items(id, listOf(e)) }
        }
    }

    private suspend fun watchLoop(plantId: String) {
        while (true) {
            val plant = repository.plant(plantId).first()
            val sharing = plant?.sharing
            val signer = keys.signer(plantId, create = false)
            val watching = if (sharing != null && signer != null) {
                val id = Wire.plantId(signer.publicKey)
                sharing.stores.any { url -> runCatching { client(url).watching(id) }.getOrDefault(false) }
            } else false
            _watched.update { if (watching) it + plantId else it - plantId }
            delay(if (watching) WATCHED_CHECK_MS else WATCH_CHECK_MS)
        }
    }

    /** Sends to every store what it doesn't have yet. */
    suspend fun sync(plantId: String) {
        val plant = repository.plant(plantId).first() ?: return
        val sharing = plant.sharing ?: return
        if (plant.observed != null) return
        val signer = keys.signer(plantId, create = false) ?: return
        for (url in sharing.stores) {
            try {
                syncStore(plant, sharing, signer, url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreachable, or refusing for now: the next minute tries again from where it got.
            }
        }
    }

    private suspend fun syncStore(plant: Plant, sharing: Plant.Sharing, signer: PlantSigner, url: String) {
        val store = client(url)
        val id = Wire.plantId(signer.publicKey)
        val dataKey = Wire.unb64(sharing.dataKey)
        var progress = progress(plant.id, url)
        val tokenHash = Wire.b64(Wire.sha256(Wire.unb64(sharing.token)))
        if (progress.registered != tokenHash) {
            val payload = signer.publicKey + Wire.sha256(Wire.unb64(sharing.token))
            store.register(id, Wire.envelope(signer, Kind.REGISTER, nextSeq(plant.id), now(), "register", payload, null))
            progress = progress.copy(registered = tokenHash)
            save(plant.id, url, progress)
        }

        suspend fun send(items: List<Pair<Kind, Pair<String, ByteArray>>>) {
            if (items.isEmpty()) return
            // One batch, each envelope with its own seq; split where a request would grow too large.
            var batch = mutableListOf<Envelope>()
            var size = 0
            for ((kind, item) in items) {
                val (name, content) = item
                val e = Wire.envelope(signer, kind, nextSeq(plant.id), now(), name, content, dataKey)
                val n = e.encode().size + 4
                if (size + n > MAX_REQUEST && batch.isNotEmpty()) { store.items(id, batch); batch = mutableListOf(); size = 0 }
                batch += e; size += n
            }
            if (batch.isNotEmpty()) store.items(id, batch)
        }

        // The plant's description and the store list, when they changed.
        val catalogs = plant.devices.associate { d -> d.id to catalog(d.id) }.filterValues { it.isNotEmpty() }
        val info = Contents.json.encodeToString(Contents.PlantInfo.serializer(), Contents.plant(plant, catalogs)).toByteArray()
        val stores = Contents.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), sharing.stores).toByteArray()
        val infoHash = Wire.b64(Wire.sha256(info))
        val storesHash = Wire.b64(Wire.sha256(stores))
        val head = buildList {
            if (progress.plantHash != infoHash) add(Kind.PLANT to ("plant" to info))
            if (progress.storesHash != storesHash) add(Kind.STORES to ("stores" to stores))
        }
        send(head)
        progress = progress.copy(plantHash = infoHash, storesHash = storesHash)
        save(plant.id, url, progress)

        // History: from the last confirmed hour (at first the beginning of the history, at most two years back) to now,
        // a few hours per minute while catching up. The current hour goes out every time; it's confirmed once it's over.
        val nowS = now() / 1000
        val currentHour = Block.hourOf(nowS)
        val first = progress.confirmedHour ?: history.firstDay(plant.id)?.atStartOfDay(zone())?.toEpochSecond()?.let(Block::hourOf) ?: currentHour
        var hour = maxOf(first, currentHour - RETENTION_S)
        var hours = 0
        var looked = 0
        while (hour <= currentHour && hours < CATCH_UP_HOURS && looked++ < CATCH_UP_LOOK) {
            val blocks = blocks(plant, hour)
            val day = Instant.ofEpochSecond(hour).atZone(zone()).toLocalDate()
            val days = days(plant.id, day, Instant.ofEpochSecond(hour + 3599).atZone(zone()).toLocalDate())
            send(blocks.map { Kind.BLOCK to it } + days.map { Kind.DAY to it })
            if (hour < currentHour) {
                progress = progress.copy(confirmedHour = hour + 3600)
                save(plant.id, url, progress)
            }
            hour += 3600
            if (blocks.isNotEmpty()) hours++
        }
        if (hour > currentHour && progress.confirmedHour == null) {
            progress = progress.copy(confirmedHour = currentHour)
            save(plant.id, url, progress)
        }

        // The fault log: new entries and those that ended since.
        val events = history.events(plant.id, limit = EVENTS)
        val changed = events.filter { e -> progress.events[e.id] != (e.ended ?: 0L) }
        val hosts = plant.devices.map { it.host }
        send(changed.map { e -> Kind.EVENT to ("e${e.id}" to Contents.json.encodeToString(Contents.Event.serializer(), Contents.event(e, hosts)).toByteArray()) })
        if (changed.isNotEmpty()) {
            progress = progress.copy(events = events.associate { it.id to (it.ended ?: 0L) })
            save(plant.id, url, progress)
        }
    }

    /** The hour's plant block and one block per device with recorded channels; hours without samples give none. */
    private suspend fun blocks(plant: Plant, hour: Long): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        val samples = history.samples(plant.id, hour, hour + 3599)
        if (samples.isNotEmpty()) {
            fun slots(value: (ch.amslersolar.solardash.model.Sample) -> Int?): List<Double?> {
                val slots = arrayOfNulls<Double>(Block.SLOTS)
                for (s in samples) {
                    val i = ((s.epochSecond - hour) / Block.INTERVAL_S).toInt()
                    if (i in 0 until Block.SLOTS) value(s)?.let { slots[i] = it.toDouble() }
                }
                return slots.toList()
            }
            val channels = listOf(
                Block.Channel("pv", 0, slots { it.pvW }), Block.Channel("load", 0, slots { it.loadW }),
                Block.Channel("battery", 0, slots { it.batteryW }), Block.Channel("grid", 0, slots { it.gridW }),
                Block.Channel("soc", 0, slots { it.socPercent }),
            ).filter { c -> c.values.any { it != null } }
            out += Block.name(hour) to Block(hour, channels).encode()
        }
        for (d in plant.devices) {
            val catalog = catalog(d.id)
            if (catalog.isEmpty()) continue
            val series = history.channelSeries(d.id, catalog.map { it.first }, hour, hour + 3599)
            val channels = catalog.mapNotNull { (key, _, unit) ->
                val points = series[key].orEmpty()
                if (points.isEmpty()) return@mapNotNull null
                val slots = arrayOfNulls<Double>(Block.SLOTS)
                for ((ts, v) in points) {
                    val i = ((ts - hour) / Block.INTERVAL_S).toInt()
                    if (i in 0 until Block.SLOTS) slots[i] = v
                }
                Block.Channel(key, Block.exponent(unit), slots.toList())
            }
            if (channels.isNotEmpty()) out += "${Block.name(hour)}.${d.id}" to Block(hour, channels).encode()
        }
        return out
    }

    /** A device's recorded channels as (key, label, unit), without those naming a serial number (Contents.privateChannel). */
    private suspend fun catalog(deviceId: String) = history.channelCatalog(deviceId).filterNot { (key, label, _) -> Contents.privateChannel(key, label) }

    private suspend fun days(plantId: String, from: LocalDate, to: LocalDate): List<Pair<String, ByteArray>> =
        history.days(plantId, from, to).map { d ->
            "d${d.day}" to Contents.json.encodeToString(Contents.Day.serializer(), Contents.day(d)).toByteArray()
        }

    // ---------------------------------------------------------------- progress, kept in a small file

    private fun now() = System.currentTimeMillis()

    private suspend fun nextSeq(plantId: String): Long = lock.withLock {
        val p = state.plants[plantId] ?: PlantProgress()
        val seq = maxOf(p.seq + 1, System.currentTimeMillis())
        state = state.copy(plants = state.plants + (plantId to p.copy(seq = seq)))
        write()
        seq
    }

    private suspend fun progress(plantId: String, url: String): StoreProgress =
        lock.withLock { state.plants[plantId]?.stores?.get(url) ?: StoreProgress() }

    private suspend fun save(plantId: String, url: String, progress: StoreProgress) = lock.withLock {
        val p = state.plants[plantId] ?: PlantProgress()
        state = state.copy(plants = state.plants + (plantId to p.copy(stores = p.stores + (url to progress))))
        write()
    }

    /** Forgets what the stores were sent (a new key, token or data key): everything goes out again. */
    private suspend fun forget(plantId: String) = lock.withLock {
        val p = state.plants[plantId] ?: return@withLock
        state = state.copy(plants = state.plants + (plantId to PlantProgress(seq = p.seq)))
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

    /** P-256 signing keys in the Android keystore: they can't be read out of the tablet. */
    object AndroidKeys : Keys {
        private fun alias(plantId: String) = "observer-$plantId"

        override fun signer(plantId: String, create: Boolean): PlantSigner? {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(alias(plantId))) {
                if (!create) return null
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias(plantId), KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
            }
            val entry = ks.getEntry(alias(plantId), null) as? KeyStore.PrivateKeyEntry ?: return null
            return object : PlantSigner {
                override val publicKey: ByteArray = entry.certificate.publicKey.encoded
                private val key: PrivateKey = entry.privateKey
                override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                    initSign(key); update(data); sign()
                }
            }
        }

        override fun delete(plantId: String) {
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(plantId)) }
        }
    }

    companion object {
        const val DEFAULT_STORE = "https://amsler-solar.ch/solardash/store.php"
        const val LIVE_INTERVAL_MS = 3_000L
        /** How often a store is asked whether someone watches: rarely while nobody does. */
        private const val WATCH_CHECK_MS = 15_000L
        private const val WATCHED_CHECK_MS = 30_000L
        /** While catching up, per minute: hours with samples sent, and hours looked at (empty ones cost a query). */
        private const val CATCH_UP_HOURS = 12
        private const val CATCH_UP_LOOK = 500
        private const val RETENTION_S = 2 * 366 * 86_400L
        private const val EVENTS = 1000
        private const val MAX_REQUEST = 3 shl 20
    }
}
