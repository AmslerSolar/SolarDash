// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.discovery

import ch.amslersolar.solardash.driver.TrafficPause
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.driver.spec.SpecTables
import ch.amslersolar.solardash.driver.spec.SpecDriver
import ch.amslersolar.solardash.driver.spec.SpecIndexEntry
import ch.amslersolar.solardash.driver.spec.SpecLibrary
import ch.amslersolar.solardash.net.LocalNetworks
import ch.amslersolar.solardash.driver.net.DeviceNetwork
import android.content.Context
import ch.amslersolar.solardash.driver.abb.AuroraDriver
import ch.amslersolar.solardash.driver.abb.TcpAuroraTransport
import ch.amslersolar.solardash.driver.apsystems.ApsystemsEcuDriver
import ch.amslersolar.solardash.driver.fimer.FimerSunSpecDriver
import ch.amslersolar.solardash.driver.fronius.FroniusGen24Driver
import ch.amslersolar.solardash.driver.kaco.KacoBlueplanetDriver
import ch.amslersolar.solardash.driver.schneider.ConextClDriver
import ch.amslersolar.solardash.driver.lg.LgEssSunSpecDriver
import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.driver.spec.SpecDevices
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.solardash.driver.sunspec.SunSpecProfiles
import ch.amslersolar.solardash.driver.victron.VictronGxDriver
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A device found on the network; [type] is null when a port answered but nothing recognised it (or while it's
 * still being identified: [identifying]). [specId]: the ModbusSpec file, for the generic ModbusSpec types.
 */
data class FoundDevice(
    val host: String,
    val port: Int,
    val type: DeviceType?,
    val unitId: Int,
    val model: String,
    val serial: String = "",
    /** Set for devices already configured ("Plant · Device"); those aren't probed. */
    val alreadyIn: String? = null,
    /** Found through its own mDNS announcement rather than the port scan. */
    val viaMdns: Boolean = false,
    val specId: String = "",
    /** The port answered; identification is still to come (a later event for the same host:port replaces it). */
    val identifying: Boolean = false,
    /** Recognised with Modbus RTU frames: an RS485 gateway in transparent mode. */
    val rtuOverTcp: Boolean = false,
    /** While identifying: device descriptions (drivers, ModbusSpec files) tried so far, of [toTry]. */
    val tried: Int = 0,
    val toTry: Int = 0,
    /** Reached through an energy manager on the same host:port (see [DeviceScanner.HUBS]), on its own unit id. */
    val behindHub: Boolean = false,
) {
    val key: String get() = if (behindHub) "$host:$port/$unitId" else "$host:$port"
}

/** Ports first, then identifying what answered. */
enum class ScanPhase { PORTS, IDENTIFY }

sealed interface ScanEvent {
    data class Progress(val done: Int, val total: Int, val phase: ScanPhase = ScanPhase.PORTS) : ScanEvent
    data class Found(val device: FoundDevice) : ScanEvent
    data class Failed(val message: String) : ScanEvent
}

/**
 * Finds supported inverters on the phone's local network: every address of the subnet is tried on the Modbus TCP
 * ports the drivers use, and each open port is asked with the matching drivers' identify call. Host:port pairs
 * that are already configured are reported but not probed, since some devices accept only one client and a
 * second connection would disturb the running poll.
 */
class DeviceScanner(
    private val portOpen: suspend (host: String, port: Int) -> Boolean = ::tcpOpen,
    private val identify: suspend (type: DeviceType, host: String, port: Int, unitId: Int) -> DeviceInfo = ::identifyWith,
    /** mDNS announcements, listened to for the first [MDNS_WINDOW_MS] of a scan. */
    private val mdns: () -> Flow<MdnsService> = { emptyFlow() },
    /**
     * Recognises a device the named drivers don't, with the ModbusSpec files' identify checks (null: not recognised),
     * only on the unit ids and framings of `live` when given (see [rollCall]); calls onTry for each file it tries (or
     * skips), [specCount] of them at most.
     */
    private val specIdentify: suspend (host: String, port: Int, live: Map<Int, Set<Boolean>>?, onTry: () -> Unit) -> FoundDevice? =
        { _, _, _, _ -> null },
    private val specCount: (port: Int, live: Map<Int, Set<Boolean>>?) -> Int = { _, _ -> 0 },
    /** The unit ids the ModbusSpec files would try on a port. */
    private val specUnits: (port: Int) -> List<Int> = { emptyList() },
    /**
     * On an RS485 gateway's port: which of the unit ids answer at all, unit id → framings (false: Modbus TCP, true:
     * RTU), one quick read each; calls onTry per read. Empty when nothing answered.
     */
    private val rollCall: suspend (host: String, port: Int, units: List<Int>, onTry: () -> Unit) -> Map<Int, Set<Boolean>> =
        { _, _, _, _ -> emptyMap() },
    /** The devices an energy manager ([HUBS], by its ModbusSpec file) reaches on other unit ids, without [skip]. */
    private val behind: suspend (host: String, port: Int, hubSpecId: String, skip: Set<Int>) -> List<FoundDevice> =
        { _, _, _, _ -> emptyList() },
    /** On an RS485 gateway's port: the inverters answering ABB's Aurora protocol, by address (see [auroraRollCall]). */
    private val aurora: suspend (host: String, port: Int, onTry: () -> Unit) -> List<FoundDevice> = { _, _, _ -> emptyList() },
) {
    /**
     * Two phases. First every address of [hosts] is tried on Modbus TCP port 502, then, with [extended], on the other
     * ports devices use (1502, 503, 6502, the gateways' 8899 and 4196, …): every open port is reported at once (identifying).
     * Then each one is identified: the named drivers for its port, then the ModbusSpec files that can recognise a
     * device; what nothing recognises stays listed as an open port. An energy manager in [HUBS] also gets the devices
     * behind it listed, each on its unit id; [hubs] are configured ones (host:port → their ModbusSpec file and the unit
     * ids configured there), whose other units are asked too, with polling paused; the hub itself isn't probed.
     * On an RS485 gateway's port the Aurora addresses are asked first: when inverters answer there, the bus speaks
     * Aurora, they're listed (the first on the host:port, the others on their address) and Modbus isn't tried.
     */
    fun scan(
        hosts: List<String>,
        known: Map<String, String>,
        extended: Boolean = false,
        hubs: Map<String, Pair<String, Set<Int>>> = emptyMap(),
    ): Flow<ScanEvent> = channelFlow {
        val ports = if (extended) EXTENDED_PORTS else listOf(MODBUS_PORT)
        val targets = ports.flatMap { p -> hosts.map { h -> h to p } } // port by port: 502 everywhere first
        val done = AtomicInteger()
        send(ScanEvent.Progress(0, targets.size))
        val connects = Semaphore(PARALLEL_CONNECTS)
        val open = ConcurrentHashMap<String, Triple<String, Int, List<Pair<DeviceType, Int>>?>>() // key → host, port, mDNS candidates
        val viaMdns = ConcurrentHashMap.newKeySet<String>()
        suspend fun opened(host: String, port: Int, mdnsCandidates: List<Pair<DeviceType, Int>>?) {
            val key = "$host:$port"
            if (open.putIfAbsent(key, Triple(host, port, mdnsCandidates)) != null) return
            if (mdnsCandidates != null) viaMdns += key
            val configured = known[key]
            send(
                ScanEvent.Found(
                    if (configured != null) FoundDevice(host, port, null, 0, "Already added", alreadyIn = configured, viaMdns = key in viaMdns)
                    else FoundDevice(host, port, null, 0, "Port open", viaMdns = key in viaMdns, identifying = true),
                ),
            )
        }
        coroutineScope {
            // mDNS and the port scan run side by side; whichever reaches a host:port first lists it.
            launch {
                withTimeoutOrNull(MDNS_WINDOW_MS) { mdns().collect { s -> opened(s.host, s.port, candidatesFor(s)) } }
            }
            for (port in ports) {
                coroutineScope {
                    for (host in hosts) launch {
                        val key = "$host:$port"
                        if (!open.containsKey(key) && (known.containsKey(key) || connects.withPermit { portOpen(host, port) })) opened(host, port, null)
                        send(ScanEvent.Progress(done.incrementAndGet(), targets.size))
                    }
                }
            }
        }
        // Identify what answered (configured devices aren't asked: a second client could disturb their poll).
        val toProbe = open.filterKeys { it !in known }.values.sortedWith(compareBy({ it.second }, { it.first }))
        val configuredHubs = hubs.filterKeys { open.containsKey(it) }
        val probes = Semaphore(PARALLEL_PROBES)
        val identified = AtomicInteger()
        val total = toProbe.size + configuredHubs.size
        send(ScanEvent.Progress(0, total, ScanPhase.IDENTIFY))
        suspend fun listBehind(host: String, port: Int, hubSpecId: String, skip: Set<Int>) {
            val devices = runCatching { behind(host, port, hubSpecId, skip) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            for (d in devices) send(ScanEvent.Found(d.copy(behindHub = true, identifying = false)))
        }
        coroutineScope {
            for ((key, hub) in configuredHubs) launch {
                val (host, port, _) = open.getValue(key)
                // It's being polled, and an energy manager may take one client only: polling pauses (and closes its
                // connections) meanwhile, as for a register sweep.
                probes.withPermit { TrafficPause.during { listBehind(host, port, hub.first, hub.second) } }
                send(ScanEvent.Progress(identified.incrementAndGet(), total, ScanPhase.IDENTIFY))
            }
            for ((host, port, mdnsCandidates) in toProbe) launch {
                val key = "$host:$port"
                val device = probes.withPermit {
                    var candidates = mdnsCandidates ?: CANDIDATES[port] ?: CANDIDATES.values.flatten().distinct()
                    // "identifying 7/300": the roll call, the drivers, then the ModbusSpec files tried for this address
                    val toTry = AtomicInteger()
                    val tried = AtomicInteger()
                    val onTry = {
                        val n = tried.incrementAndGet()
                        trySend(ScanEvent.Found(FoundDevice(host, port, null, 0, "Port open", viaMdns = key in viaMdns,
                            identifying = true, tried = n.coerceAtMost(toTry.get()), toTry = toTry.get())))
                        Unit
                    }
                    // Behind a gateway an absent unit id stays silent, and every guess there costs a whole timeout:
                    // first ask each unit id once which ones answer, and in which framing; only those are identified.
                    // When none answers (a device ignoring register 0, say), everything is tried as before.
                    if (port in GATEWAY_PORTS) {
                        toTry.set(AURORA_ADDRESSES.count())
                        val inverters = runCatching { aurora(host, port, onTry) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                        if (inverters.isNotEmpty()) {
                            inverters.drop(1).forEach { trySend(ScanEvent.Found(it.copy(behindHub = true, viaMdns = key in viaMdns))) }
                            return@withPermit inverters.first()
                        }
                    }
                    var live: Map<Int, Set<Boolean>>? = null
                    if (port in GATEWAY_PORTS) {
                        val units = (candidates.map { it.second } + specUnits(port)).filter { it in 1..247 }.distinct()
                        toTry.set(units.size * 2)
                        val answered = rollCall(host, port, units, onTry)
                        if (answered.isNotEmpty()) {
                            live = answered
                            candidates = candidates.filter { (_, unit) -> answered[unit]?.contains(false) == true } // drivers speak Modbus TCP
                        }
                    }
                    toTry.addAndGet(candidates.size + specCount(port, live))
                    val named = probe(host, port, candidates, onTry)
                    // A SunSpec device the generic driver took: its ModbusSpec file may know it better.
                    if (named.type != null && named.type != DeviceType.SUNSPEC) named else (runCatching { specIdentify(host, port, live, onTry) }.getOrNull() ?: named)
                }
                send(ScanEvent.Found(device.copy(viaMdns = key in viaMdns, identifying = false)))
                if (device.specId in HUBS) listBehind(host, port, device.specId, setOf(device.unitId))
                send(ScanEvent.Progress(identified.incrementAndGet(), total, ScanPhase.IDENTIFY))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Looks for the device with [serial] on [port] of [hosts] (skipping [skip], e.g. configured devices) and returns
     * its new address, or null. Used when a device has stopped answering, e.g. after DHCP gave it another IP.
     */
    suspend fun find(
        hosts: List<String>,
        skip: Set<String>,
        type: DeviceType,
        port: Int,
        unitId: Int,
        serial: String,
        announced: List<MdnsService> = emptyList(),
    ): String? {
        // First the announcements: one carrying the serial is taken as is; others on the port are asked.
        val onPort = announced.filter { it.port == port && "${it.host}:$port" !in skip }
        onPort.firstOrNull { it.mentions(serial) }?.let { return it.host }
        onPort.firstOrNull { s -> matches(type, s.host, port, unitId, serial) }?.let { return it.host }
        return coroutineScope {
            val connects = Semaphore(PARALLEL_CONNECTS)
            val open = hosts.filter { "$it:$port" !in skip }
                .map { h -> async { h.takeIf { connects.withPermit { portOpen(h, port) } } } }
                .awaitAll().filterNotNull()
            open.firstOrNull { h -> h !in onPort.map { it.host } && matches(type, h, port, unitId, serial) }
        }
    }

    private suspend fun matches(type: DeviceType, host: String, port: Int, unitId: Int, serial: String): Boolean = try {
        identify(type, host, port, unitId).serial.trim() == serial.trim()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Tries each driver in [candidates] (by default the ones that use [port]) in turn; the first plausible identity wins. */
    suspend fun probe(host: String, port: Int, candidates: List<Pair<DeviceType, Int>> = CANDIDATES[port].orEmpty(), onTry: () -> Unit = {}): FoundDevice {
        // On a port no driver knows (a gateway's 4196, 8899 …) every driver is tried: a unit id that didn't answer
        // in time is silent for the others too (nothing at that address behind the gateway), so it's skipped.
        val gatewayPort = port !in CANDIDATES
        val silent = HashSet<Int>()
        for ((type, unitId) in candidates) {
            onTry()
            if (unitId in silent) continue
            val info = try {
                identify(type, host, port, unitId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.InterruptedIOException) {
                if (gatewayPort) silent += unitId
                continue
            } catch (_: Exception) {
                continue
            }
            if (!plausible(info.model)) continue
            // SunSpec on 6502 is LG's port, but check: other makes are offered as generic SunSpec devices instead.
            if (type == DeviceType.LG_ESS_SUNSPEC && !info.model.contains("LG", ignoreCase = true)) continue
            return FoundDevice(host, port, type, unitId, info.model, info.serial)
        }
        return FoundDevice(host, port, null, 0, "Modbus TCP device, not recognised")
    }

    companion object {
        /** Drivers to try per port, with the unit ids they're usually found on. */
        val CANDIDATES: Map<Int, List<Pair<DeviceType, Int>>> = linkedMapOf(
            502 to listOf(
                DeviceType.GOODWE_ET to 0xF7, DeviceType.FRONIUS to 1, DeviceType.HUAWEI to 1, DeviceType.HUAWEI to 0,
                DeviceType.KACO_NX3 to 1, DeviceType.FIMER to 247, DeviceType.FIMER to 2, DeviceType.APSYSTEMS_ECU to 1,
                DeviceType.CONEXT_CL to 10, DeviceType.CONEXT_CL60 to 1,
                DeviceType.POWER_ONE to 2, DeviceType.SMA to 3, DeviceType.SUNGROW to 1, DeviceType.GROWATT to 1, DeviceType.VICTRON to 100,
                DeviceType.FENECON to 1, DeviceType.SUNSPEC to 1,
            ),
            1502 to listOf(
                DeviceType.KOSTAL_PIKO_CI to 71, DeviceType.KOSTAL_PLENTICORE to 71, DeviceType.SOLAREDGE to 1, DeviceType.SUNSPEC to 1,
            ),
            503 to listOf(DeviceType.CONEXT_GATEWAY to 1),
            6502 to listOf(DeviceType.LG_ESS_SUNSPEC to 1, DeviceType.SUNSPEC to 1),
        )
        /** Drivers to try for an announced service: by its port, else by its service type. */
        fun candidatesFor(s: MdnsService): List<Pair<DeviceType, Int>> = CANDIDATES[s.port] ?: when {
            // The make-specific SunSpec drivers accept only their make; anything else is a generic SunSpec device.
            s.type.startsWith("_sunspec") -> listOf(
                DeviceType.FRONIUS to 1, DeviceType.SOLAREDGE to 1, DeviceType.LG_ESS_SUNSPEC to 1, DeviceType.SUNSPEC to 1,
            )
            else -> CANDIDATES.values.flatten()
        }

        /**
         * Energy managers that reach other devices on their own unit ids (ModbusSpec file → the unit ids to ask and
         * a register those devices answer with their model name). Huawei EMMA: inverters, batteries and chargers are
         * read through it; a unit id without a device gets exception 4 at once (seen on a real EMMA, 2026-11-02).
         */
        val HUBS: Map<String, Hub> = mapOf("huawei-emma" to Hub(1..247, 30000, 15, inputRegisters = false))

        /**
         * [inputRegisters] false: the hub never answers function 4 (the EMMA lets it time out), so files whose identify
         * checks read input registers aren't tried behind it.
         */
        data class Hub(val units: IntRange, val probeRegister: Int, val probeCount: Int, val inputRegisters: Boolean = true)

        const val MDNS_WINDOW_MS = 8_000L
        const val MODBUS_PORT = 502

        /** The extended search: 502, the named drivers' ports, RS485 gateways (8899: Elfin / USR Wi-Fi, 4196: Waveshare Ethernet) and the ModbusSpec files' ports. */
        val EXTENDED_PORTS: List<Int> by lazy {
            (listOf(MODBUS_PORT) + CANDIDATES.keys + GATEWAY_PORTS +
                (SpecLibrary.current?.index?.map { it.port }.orEmpty())).distinct()
        }
        private const val PARALLEL_CONNECTS = 64
        private const val PARALLEL_PROBES = 8
        private const val CONNECT_TIMEOUT_MS = 400
        private const val PROBE_TIMEOUT_MS = 2_000
        /** RS485 gateways' own ports: 8899 (Elfin / USR Wi-Fi), 4196 (Waveshare Ethernet). */
        val GATEWAY_PORTS = setOf(8899, 4196)
        private const val GATEWAY_TIMEOUT_MS = 1_000
        /** Behind a gateway at 9600 baud a one-register answer takes some 50 ms. */
        private const val ROLL_CALL_TIMEOUT_MS = 400
        /** The Aurora addresses asked on a gateway's port, in order (the inverters' range; 2 is their factory setting). */
        val AURORA_ADDRESSES = 2..63
        /** Aurora replies come within some 30 ms at 19200 baud. */
        private const val AURORA_TIMEOUT_MS = 200
        /** The Aurora roll call stops after this many silent addresses in a row past the last one that answered. */
        const val AURORA_MAX_SILENT = 8
        /** Behind a hub: give up after this many refused or unanswered requests (a hub that stopped answering). */
        private const val HUB_MAX_FAILURES = 3
        private const val HUB_RETRY_MS = 1_000L

        /** Text that reads like a model name, not register noise from a device of another make. */
        fun plausible(model: String): Boolean =
            model.length >= 2 && model.any { it.isLetter() } && model.all { it.code in 0x20..0x7E }

        /** All host addresses of the subnet, without [own], the network and broadcast address; at most a /24. */
        fun hosts(own: Inet4Address, prefixLength: Int): List<String> {
            val prefix = prefixLength.coerceIn(24, 30)
            val ip = own.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = ip and mask
            val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
            return (network + 1 until broadcast).filter { it != ip }.map { a ->
                listOf(24, 16, 8, 0).joinToString(".") { ((a shr it) and 0xFF).toString() }
            }
        }

        /** The phone's IPv4 address and prefix on its active network (normally Wi-Fi), or null. */
        /** The phone's address on its Wi-Fi or Ethernet (also without internet), not on mobile data. */
        fun localNetwork(context: Context): Pair<Inet4Address, Int>? = LocalNetworks.get(context).ownIpv4()

        /** Addresses of the phone's local network to search, or null when it isn't on one. */
        fun localHosts(context: Context): List<String>? = localNetwork(context)?.let { (own, prefix) -> hosts(own, prefix) }

        /** Scans the phone's local network; fails with a message when it isn't on one. */
        /**
         * The addresses of a network the user typed: "192.168.1" or "192.168.1.0/24" (/24 to /32), or one address
         * "192.168.1.200". Null when it isn't one of these.
         */
        fun parseRange(text: String): List<String>? {
            val t = text.trim()
            val (addr, prefix) = when {
                "/" in t -> t.substringBefore("/") to (t.substringAfter("/").toIntOrNull() ?: return null)
                t.count { it == '.' } == 2 -> "$t.0" to 24
                else -> t to 32
            }
            if (prefix !in 24..32) return null
            val parts = addr.split(".").map { it.toIntOrNull() ?: return null }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            val ip = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
            fun dotted(a: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((a shr it) and 0xFF).toString() }
            if (prefix >= 31) return listOf(dotted(ip))
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = ip and mask
            return (network + 1 until (network or (mask.inv() and 0xFFFFFFFFL))).map(::dotted)
        }

        /** [range]: another network to search (see [parseRange]) instead of the tablet's own, e.g. a gateway's factory one. */
        fun scanLocalNetwork(
            context: Context,
            known: Map<String, String>,
            extended: Boolean = false,
            range: String = "",
            hubs: Map<String, Pair<String, Set<Int>>> = emptyMap(),
        ): Flow<ScanEvent> {
            if (range.isNotBlank()) {
                val hosts = parseRange(range) ?: return channelFlow { send(ScanEvent.Failed("Can't read the network to search: $range")) }
                return DeviceScanner(mdns = { MdnsBrowser.browse(context) }, specIdentify = ::identifyFromSpecs, specCount = ::specFilesToTry, specUnits = ::specUnitIds, rollCall = ::rollCall, behind = ::devicesBehind, aurora = ::auroraRollCall).scan(hosts, known, extended, hubs)
            }
            val (own, prefix) = localNetwork(context) ?: return channelFlow {
                send(ScanEvent.Failed("Not connected to a local network. Connect to the plant's Wi-Fi and try again."))
            }
            return DeviceScanner(mdns = { MdnsBrowser.browse(context) }, specIdentify = ::identifyFromSpecs, specCount = ::specFilesToTry, specUnits = ::specUnitIds, rollCall = ::rollCall, behind = ::devicesBehind, aurora = ::auroraRollCall).scan(hosts(own, prefix), known, extended, hubs)
        }

        private fun tcpOpen(host: String, port: Int): Boolean = try {
            DeviceNetwork.current.socket(host).use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        } catch (_: Exception) {
            false
        }

        /**
         * The ModbusSpec files that can recognise their device (an identify check naming it) and use [port], tried on
         * their usual unit ids; each unit's registers are read once and shared by all files (a unit that doesn't
         * answer in time is skipped). A file of a named device type gives that type, others the generic ModbusSpec type.
         * On an RS485 gateway's port ([GATEWAY_PORTS]) the files of RS485 devices are tried too, with Modbus TCP
         * frames (a gateway translating) and then with RTU frames (a gateway in transparent mode).
         */
        suspend fun identifyFromSpecs(host: String, port: Int, live: Map<Int, Set<Boolean>>? = null, onTry: () -> Unit = {}): FoundDevice? {
            val library = SpecLibrary.current ?: return null
            for (rtu in framings(port)) {
                val entries = specEntries(library, port, live, rtu)
                if (entries.isNotEmpty()) identifyFromSpecs(library, entries, host, port, rtu, onTry)?.let { return it }
            }
            return null
        }

        /** The files to try with framing [rtu]: on [live]'s unit ids that answered in that framing, if given. */
        private fun specEntries(library: SpecLibrary, port: Int, live: Map<Int, Set<Boolean>>?, rtu: Boolean) =
            specEntries(library, port).filter { live == null || live[it.unit]?.contains(rtu) == true }

        fun specUnitIds(port: Int): List<Int> = SpecLibrary.current?.let { l -> specEntries(l, port).map { it.unit }.distinct() }.orEmpty()

        /**
         * One read of holding register 0 per unit id and framing, quickly and without a second try: an answer, also a
         * Modbus exception, means a device is there. Unit id → the framings it answered in (false: Modbus TCP).
         */
        suspend fun rollCall(host: String, port: Int, units: List<Int>, onTry: () -> Unit = {}): Map<Int, Set<Boolean>> {
            val out = mutableMapOf<Int, MutableSet<Boolean>>()
            for (rtu in listOf(false, true)) {
                ModbusTcpClient(host, port, ROLL_CALL_TIMEOUT_MS, rtu = rtu, retry = false).use { client ->
                    for (unit in units) {
                        onTry()
                        val present = try {
                            client.readHoldingRegisters(unit, 0, 1)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: ModbusException) {
                            true
                        } catch (_: java.io.IOException) {
                            false
                        }
                        if (present) out.getOrPut(unit) { mutableSetOf() } += rtu
                    }
                }
            }
            return out
        }

        private fun specEntries(library: SpecLibrary, port: Int): List<SpecIndexEntry> =
            library.index.filter { it.identifies && (it.port == port || (port in GATEWAY_PORTS && it.rs485)) }

        /** Modbus TCP frames; on a gateway's port also RTU frames (transparent mode). */
        private fun framings(port: Int) = if (port in GATEWAY_PORTS) listOf(false, true) else listOf(false)

        /** How many ModbusSpec files [identifyFromSpecs] tries on [port] at most (each framing counts). */
        fun specFilesToTry(port: Int, live: Map<Int, Set<Boolean>>? = null): Int =
            SpecLibrary.current?.let { l -> framings(port).sumOf { rtu -> specEntries(l, port, live, rtu).size } } ?: 0

        private suspend fun identifyFromSpecs(
            library: SpecLibrary, entries: List<SpecIndexEntry>, host: String, port: Int, rtu: Boolean, onTry: () -> Unit,
        ): FoundDevice? {
            val named = SpecDevices.SPEC_IDS.entries.flatMap { (t, ids) -> ids.map { it to t } }.toMap()
            // behind a gateway an answer takes some 50 ms; every silent unit id costs the whole timeout
            val timeout = if (port in GATEWAY_PORTS) GATEWAY_TIMEOUT_MS else PROBE_TIMEOUT_MS
            return ModbusTcpClient(host, port, timeout, rtu = rtu).use { client ->
                for ((unit, files) in entries.groupBy { it.unit }) {
                    val holding = CachingReader { u, r, c -> client.readHoldingRegisters(u, r, c) }
                    val input = CachingReader { u, r, c -> client.readInputRegisters(u, r, c) }
                    val ordered = files.sortedByDescending { it.documented }
                    for ((i, e) in ordered.withIndex()) {
                        onTry()
                        val info = try {
                            SpecDriver(library.spec(e.id), library.vocabulary, SpecTables(holding, input), unit, requestGapMs = 0).identify()
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (_: java.io.InterruptedIOException) {
                            repeat(ordered.size - i - 1) { onTry() } // this unit id doesn't answer: its other files are done too
                            break
                        } catch (_: Exception) {
                            continue
                        }
                        val type = named[e.id] ?: SpecDevices.genericType(e.kind)
                        return@use FoundDevice(host, port, type, unit, info.model.ifBlank { e.title }, info.serial, specId = if (type in SpecDevices.GENERIC) e.id else "", rtuOverTcp = rtu)
                    }
                }
                null
            }
        }

        /**
         * The devices behind the energy manager at [host]:[port] (its ModbusSpec file [hubSpecId], see [HUBS]): each unit
         * id is asked once for the model register, and every unit that answers is identified with the identify checks of
         * the files of the hub's make. Units in [skip] (the hub, configured devices) aren't asked.
         */
        suspend fun devicesBehind(host: String, port: Int, hubSpecId: String, skip: Set<Int>): List<FoundDevice> {
            val hub = HUBS[hubSpecId] ?: return emptyList()
            val library = SpecLibrary.current ?: return emptyList()
            val maker = library.index.firstOrNull { it.id == hubSpecId }?.manufacturer ?: return emptyList()
            val files = library.index.filter { it.identifies && it.manufacturer == maker && it.id != hubSpecId }
                .filter { e -> hub.inputRegisters || library.spec(e.id).identify?.let { i -> ((i.checks + i.any).flatten() + i.serials.map { it.first }).all { it.table == "holding" } } == true }
                .sortedByDescending { it.documented }
            val named = SpecDevices.SPEC_IDS.entries.flatMap { (t, ids) -> ids.map { it to t } }.toMap()
            return ModbusTcpClient(host, port, PROBE_TIMEOUT_MS, retry = false).use { client ->
                val answering = mutableListOf<Int>()
                var failures = 0
                units@ for (unit in hub.units) {
                    if (unit in skip) continue
                    while (true) {
                        try {
                            client.readHoldingRegisters(unit, hub.probeRegister, hub.probeCount)
                            answering += unit
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: ModbusException) {
                            // no device on this unit id
                        } catch (_: java.io.IOException) {
                            // Refused or no answer: a configured hub's polling connection may still be closing
                            // (single client), so the same unit is asked again after a moment.
                            if (++failures >= HUB_MAX_FAILURES) break@units
                            delay(HUB_RETRY_MS)
                            continue
                        }
                        break
                    }
                }
                answering.mapNotNull { unit ->
                    val holding = CachingReader { u, r, c -> client.readHoldingRegisters(u, r, c) }
                    val input = CachingReader { u, r, c -> client.readInputRegisters(u, r, c) }
                    files.firstNotNullOfOrNull { e ->
                        val info = try {
                            SpecDriver(library.spec(e.id), library.vocabulary, SpecTables(holding, input), unit, requestGapMs = 0).identify()
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (_: Exception) {
                            return@firstNotNullOfOrNull null
                        }
                        val type = named[e.id] ?: SpecDevices.genericType(e.kind)
                        FoundDevice(host, port, type, unit, info.model.ifBlank { e.title }, info.serial,
                            specId = if (type in SpecDevices.GENERIC) e.id else "", behindHub = true)
                    }
                }
            }
        }

        /**
         * The inverters answering Aurora on the gateway at [host]:[port]: each address of [AURORA_ADDRESSES] is asked
         * for its serial number (read-only, command 63) until [AURORA_MAX_SILENT] in a row stay silent; each one that
         * answers is identified. Modbus devices on the bus ignore these frames or answer with an exception, which
         * doesn't pass the Aurora CRC. Calls onTry per address (the rest at once when it stops early).
         */
        suspend fun auroraRollCall(host: String, port: Int, onTry: () -> Unit = {}): List<FoundDevice> =
            TcpAuroraTransport(host, port, AURORA_TIMEOUT_MS).use { bus ->
                val found = mutableListOf<FoundDevice>()
                var silent = 0
                for ((i, address) in AURORA_ADDRESSES.withIndex()) {
                    onTry()
                    val info = try {
                        AuroraDriver(bus, address).identify()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: java.io.IOException) {
                        if (++silent >= AURORA_MAX_SILENT) {
                            repeat(AURORA_ADDRESSES.count() - i - 1) { onTry() }
                            break
                        }
                        continue
                    }
                    silent = 0
                    found += FoundDevice(host, port, DeviceType.AURORA, address, info.model, info.serial)
                }
                found
            }

        private suspend fun identifyWith(type: DeviceType, host: String, port: Int, unitId: Int): DeviceInfo =
            if (type == DeviceType.AURORA) TcpAuroraTransport(host, port).use { AuroraDriver(it, unitId).identify() }
            else ModbusTcpClient(host, port, PROBE_TIMEOUT_MS).use { client ->
                when (type) {
                    DeviceType.FRONIUS -> FroniusGen24Driver(client, unitId).identify()
                    DeviceType.KACO_NX3 -> KacoBlueplanetDriver(client, unitId).identify()
                    DeviceType.FIMER -> FimerSunSpecDriver(client, unitId).identify()
                    DeviceType.APSYSTEMS_ECU -> ApsystemsEcuDriver(client, unitId).identify()
                    DeviceType.CONEXT_CL -> ConextClDriver(client, unitId).identify()
                    DeviceType.SOLAREDGE -> SunSpecInverterDriver(client, unitId, SunSpecProfiles.SOLAREDGE).identify()
                    DeviceType.SUNSPEC -> SunSpecInverterDriver(client, unitId, SunSpecProfiles.GENERIC).identify()
                    DeviceType.VICTRON -> VictronGxDriver(client, unitId).identify()
                    DeviceType.LG_ESS_SUNSPEC -> LgEssSunSpecDriver(client, unitId).identify()
                    DeviceType.GOODWE_ET, DeviceType.CONEXT_CL60, DeviceType.CONEXT_GATEWAY, DeviceType.KOSTAL_PIKO_CI, DeviceType.POWER_ONE, DeviceType.GROWATT, DeviceType.SMA, DeviceType.SUNGROW, DeviceType.FENECON, DeviceType.HUAWEI,
                    DeviceType.KOSTAL_PLENTICORE ->
                        SpecDevices.create(type, client, unitId)!!.identify()
                    else -> throw UnsupportedOperationException()
                }
            }
    }
}

/**
 * Remembers what a unit answered (and what it rejected), so several ModbusSpec files' identify checks on the same
 * registers cost one request each.
 */
internal class CachingReader(private val inner: RegisterReader) : RegisterReader {
    private val words = HashMap<Pair<Int, Int>, Int>()
    private val failed = HashMap<Triple<Int, Int, Int>, Exception>()

    override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
        failed[Triple(unitId, register, count)]?.let { throw it }
        if ((register until register + count).all { (unitId to it) in words }) return block(unitId, register, count)
        val b = try {
            inner.read(unitId, register, count)
        } catch (e: ModbusException) {
            failed[Triple(unitId, register, count)] = e
            throw e
        }
        for (a in register until register + count) words[unitId to a] = b.u16(a)
        return block(unitId, register, count)
    }

    private fun block(unitId: Int, register: Int, count: Int) =
        RegisterBlock(register, ByteArray(count * 2) { i -> words.getValue(unitId to register + i / 2).let { w -> (if (i % 2 == 0) w shr 8 else w).toByte() } })
}
