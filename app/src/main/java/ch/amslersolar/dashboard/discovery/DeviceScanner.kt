// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.discovery

import ch.amslersolar.dashboard.net.LocalNetworks
import ch.amslersolar.dashboard.driver.net.DeviceNetwork
import android.content.Context
import ch.amslersolar.dashboard.driver.apsystems.ApsystemsEcuDriver
import ch.amslersolar.dashboard.driver.fimer.FimerSunSpecDriver
import ch.amslersolar.dashboard.driver.fronius.FroniusGen24Driver
import ch.amslersolar.dashboard.driver.kaco.KacoBlueplanetDriver
import ch.amslersolar.dashboard.driver.schneider.ConextClDriver
import ch.amslersolar.dashboard.driver.kostal.KostalPlenticoreDriver
import ch.amslersolar.dashboard.driver.lg.LgEssSunSpecDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.spec.SpecDevices
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.dashboard.driver.sunspec.SunSpecProfiles
import ch.amslersolar.dashboard.driver.victron.VictronGxDriver
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/** An inverter found on the network; [type] is null when something answered but wasn't recognised. */
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
)

sealed interface ScanEvent {
    data class Progress(val done: Int, val total: Int) : ScanEvent
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
) {
    fun scan(hosts: List<String>, known: Map<String, String>): Flow<ScanEvent> = channelFlow {
        val targets = hosts.flatMap { h -> CANDIDATES.keys.map { p -> h to p } }
        val done = AtomicInteger()
        send(ScanEvent.Progress(0, targets.size))
        val connects = Semaphore(PARALLEL_CONNECTS)
        val probes = Semaphore(PARALLEL_PROBES)
        // mDNS and the port scan run side by side; whichever reaches a host:port first reports it.
        val claimed = ConcurrentHashMap.newKeySet<String>()
        suspend fun report(host: String, port: Int, viaMdns: Boolean, candidates: List<Pair<DeviceType, Int>>) {
            val configured = known["$host:$port"]
            val device = if (configured != null) FoundDevice(host, port, null, 0, "Already added", alreadyIn = configured)
            else probes.withPermit { probe(host, port, candidates) }
            send(ScanEvent.Found(device.copy(viaMdns = viaMdns)))
        }
        launch {
            withTimeoutOrNull(MDNS_WINDOW_MS) {
                mdns().collect { s -> if (claimed.add("${s.host}:${s.port}")) launch { report(s.host, s.port, true, candidatesFor(s)) } }
            }
        }
        for ((host, port) in targets) launch {
            val key = "$host:$port"
            if (key in claimed) Unit
            else if (known.containsKey(key)) { if (claimed.add(key)) report(host, port, false, emptyList()) }
            else if (connects.withPermit { portOpen(host, port) } && claimed.add(key)) report(host, port, false, CANDIDATES.getValue(port))
            send(ScanEvent.Progress(done.incrementAndGet(), targets.size))
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
    suspend fun probe(host: String, port: Int, candidates: List<Pair<DeviceType, Int>> = CANDIDATES[port].orEmpty()): FoundDevice {
        for ((type, unitId) in candidates) {
            val info = try {
                identify(type, host, port, unitId)
            } catch (e: CancellationException) {
                throw e
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

        const val MDNS_WINDOW_MS = 8_000L
        private const val PARALLEL_CONNECTS = 64
        private const val PARALLEL_PROBES = 8
        private const val CONNECT_TIMEOUT_MS = 400
        private const val PROBE_TIMEOUT_MS = 2_000

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
        fun scanLocalNetwork(context: Context, known: Map<String, String>): Flow<ScanEvent> {
            val (own, prefix) = localNetwork(context) ?: return channelFlow {
                send(ScanEvent.Failed("Not connected to a local network. Connect to the plant's Wi-Fi and try again."))
            }
            return DeviceScanner(mdns = { MdnsBrowser.browse(context) }).scan(hosts(own, prefix), known)
        }

        private fun tcpOpen(host: String, port: Int): Boolean = try {
            DeviceNetwork.current.socket(host).use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        } catch (_: Exception) {
            false
        }

        private suspend fun identifyWith(type: DeviceType, host: String, port: Int, unitId: Int): DeviceInfo =
            ModbusTcpClient(host, port, PROBE_TIMEOUT_MS).use { client ->
                when (type) {
                    DeviceType.FRONIUS -> FroniusGen24Driver(client, unitId).identify()
                    DeviceType.KACO_NX3 -> KacoBlueplanetDriver(client, unitId).identify()
                    DeviceType.FIMER -> FimerSunSpecDriver(client, unitId).identify()
                    DeviceType.APSYSTEMS_ECU -> ApsystemsEcuDriver(client, unitId).identify()
                    DeviceType.CONEXT_CL -> ConextClDriver(client, unitId).identify()
                    DeviceType.SOLAREDGE -> SunSpecInverterDriver(client, unitId, SunSpecProfiles.SOLAREDGE).identify()
                    DeviceType.SUNSPEC -> SunSpecInverterDriver(client, unitId, SunSpecProfiles.GENERIC).identify()
                    DeviceType.VICTRON -> VictronGxDriver(client, unitId).identify()
                    DeviceType.KOSTAL_PLENTICORE -> KostalPlenticoreDriver(client, unitId).identify()
                    DeviceType.LG_ESS_SUNSPEC -> LgEssSunSpecDriver(client, unitId).identify()
                    DeviceType.GOODWE_ET, DeviceType.CONEXT_CL60, DeviceType.CONEXT_GATEWAY, DeviceType.KOSTAL_PIKO_CI, DeviceType.POWER_ONE, DeviceType.GROWATT, DeviceType.SMA, DeviceType.SUNGROW, DeviceType.FENECON, DeviceType.HUAWEI ->
                        SpecDevices.create(type, client, unitId)!!.identify()
                    else -> throw UnsupportedOperationException()
                }
            }
    }
}
