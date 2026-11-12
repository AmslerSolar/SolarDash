// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import ch.amslersolar.solardash.driver.net.Subnet
import ch.amslersolar.solardash.driver.net.ipLiteral

/** The tablet's network, as far as the plant status needs it. */
data class NetworkState(
    /** On a Wi-Fi or Ethernet network (with or without internet). */
    val localNetwork: Boolean,
    /** Android has checked that the internet is reachable. */
    val internet: Boolean,
    /** Address ranges of the local networks, to tell "device off" from "tablet on another Wi-Fi". */
    val subnets: List<Subnet> = emptyList(),
) {
    companion object {
        /** Before the first network callback: assume all is well rather than flashing an alarm. */
        val UNKNOWN = NetworkState(localNetwork = true, internet = true)
    }
}

enum class Severity { INFO, WARNING, ALARM }

/** Built-in help pages (assets/guide/<language>/<id>.md). */
enum class Guide(val id: String) {
    NO_WIFI("no_wifi"),
    WRONG_NETWORK("wrong_network"),
    NOT_ANSWERING("not_answering"),
    REFUSED("refused"),
    LOGIN("login"),
    DEVICE_SETTINGS("device_settings"),
    NOT_READY("not_ready"),
    DEVICE_FAULT("device_fault"),
    DEVICE_WARNING("device_warning"),
    MAINS_LOST("mains_lost"),
    GENERATOR_OFF("generator_off"),
    BACKUP_MODE("backup_mode"),
    NO_INTERNET("no_internet"),
}

enum class IssueKind(val severity: Severity, val guide: Guide) {
    NO_LOCAL_NETWORK(Severity.ALARM, Guide.NO_WIFI),
    WRONG_NETWORK(Severity.ALARM, Guide.WRONG_NETWORK),
    NOT_ANSWERING(Severity.ALARM, Guide.NOT_ANSWERING),
    /** A PV inverter that stopped answering after its power fell to almost nothing: normal at night. */
    ASLEEP(Severity.INFO, Guide.NOT_ANSWERING),
    CONNECTION_REFUSED(Severity.ALARM, Guide.REFUSED),
    LOGIN_FAILED(Severity.ALARM, Guide.LOGIN),
    DEVICE_SETTINGS(Severity.ALARM, Guide.DEVICE_SETTINGS),
    NOT_READY(Severity.WARNING, Guide.NOT_READY),
    DEVICE_FAULT(Severity.ALARM, Guide.DEVICE_FAULT),
    DEVICE_WARNING(Severity.WARNING, Guide.DEVICE_WARNING),
    MAINS_LOST(Severity.WARNING, Guide.MAINS_LOST),
    /** Off-grid plant without AC input: the generator is off. */
    NO_AC_INPUT(Severity.INFO, Guide.GENERATOR_OFF),
    /** A grid-tied plant supplied from its battery in backup mode (usually during a power cut). */
    BACKUP_MODE(Severity.WARNING, Guide.BACKUP_MODE),
    /** An off-grid plant's inverter in off-grid mode: its normal state, shown for information. */
    OFF_GRID_MODE(Severity.INFO, Guide.BACKUP_MODE),
    NO_INTERNET(Severity.INFO, Guide.NO_INTERNET),
}

/** One thing to show in the plant status. [detail] is the device's own text (English, translated for display). */
data class Issue(val kind: IssueKind, val device: DeviceConfig? = null, val detail: String? = null, val percent: Int? = null) {
    val severity: Severity get() = kind.severity

    /** A problem the device itself reported ([detail] is its text): it can be hidden ([DeviceConfig.hiddenProblems]). */
    val hideable: Boolean get() = device != null && detail != null && kind in PlantStatusRules.DEVICE_PROBLEMS
}

/** [hidden]: the device problems present now that the plant's settings hide (counted, not shown). */
data class PlantStatus(val issues: List<Issue>, val hidden: List<Issue> = emptyList()) {
    /** Worst severity, or null when there's nothing at all to show. */
    val severity: Severity? get() = issues.maxOfOrNull { it.severity }

    /** Nothing that needs looking at (information only, like "no internet", is fine). */
    val ok: Boolean get() = issues.none { it.severity != Severity.INFO }

    /** The public grid is gone (a grid-tied plant; off-grid plants have no grid to lose). */
    val gridFailure: Boolean get() = issues.any { it.kind == IssueKind.MAINS_LOST || it.kind == IssueKind.BACKUP_MODE }
}

/**
 * The last successful reading of every device, across polls: a PV inverter that went quiet with almost no power
 * is asleep (night), not broken. [shared] is kept for the whole process (screens, kiosk, fault log) and filled
 * from the recorded history at start, so a screen opened or an app restarted at night knows it too.
 */
class LastReadings {
    private val last = java.util.concurrent.ConcurrentHashMap<String, DeviceReading>()
    private val peaks = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Called when a device's highest solar power grows, to keep it across restarts. */
    @Volatile var onPeak: ((deviceId: String, w: Int) -> Unit)? = null

    fun update(snapshot: PlantSnapshot?) {
        snapshot?.devices?.forEach { s ->
            s.reading?.let { r ->
                last[s.device.id] = r
                r.pvPowerW?.let { raisePeak(s.device.id, it, notify = true) }
            }
        }
    }

    /** A reading from the history, unless a newer one is known. */
    fun seed(deviceId: String, reading: DeviceReading) {
        last.putIfAbsent(deviceId, reading)
    }

    /** A highest solar power from earlier (stored, or from the history). */
    fun seedPeak(deviceId: String, w: Int) = raisePeak(deviceId, w, notify = false)

    operator fun get(deviceId: String): DeviceReading? = last[deviceId]

    /** The highest solar power the device was seen producing: how large it is. */
    fun peak(deviceId: String): Int? = peaks[deviceId]

    private fun raisePeak(deviceId: String, w: Int, notify: Boolean) {
        if (w <= 0 || w > Plausible.MAX_POWER_W) return
        var raised = false
        peaks.compute(deviceId) { _, old -> if (old == null || w > old) { raised = true; w } else old }
        if (raised && notify) onPeak?.invoke(deviceId, w)
    }

    companion object {
        val shared = LastReadings()
    }
}

object PlantStatusRules {
    /**
     * Below this a PV inverter's last power counts as "the sun was gone" when it stops answering: 2 % of the highest
     * solar power it was seen producing ([peakW], its size), at least 50 W and at most 500 W. A 27.6 kW TRIO that
     * reached 25 kW goes quiet below 500 W, a 3 kW inverter below 60 W.
     */
    fun asleepBelowW(peakW: Int?): Int = ((peakW ?: 0) / 50).coerceIn(ASLEEP_MIN_W, ASLEEP_MAX_W)

    private const val ASLEEP_MIN_W = 50
    private const val ASLEEP_MAX_W = 500

    /** Issues made from a device's own [DeviceProblem]s. */
    val DEVICE_PROBLEMS = setOf(
        IssueKind.DEVICE_FAULT, IssueKind.DEVICE_WARNING, IssueKind.MAINS_LOST, IssueKind.NO_AC_INPUT,
        IssueKind.BACKUP_MODE, IssueKind.OFF_GRID_MODE,
    )

    /**
     * [applyHidden]: leave out the problems a device's settings hide ([DeviceConfig.hiddenProblems], taken from [plant],
     * which is newer than the snapshot's copy); the fault log passes false, it records them all.
     */
    fun evaluate(
        plant: Plant,
        snapshot: PlantSnapshot?,
        net: NetworkState,
        last: LastReadings = LastReadings(),
        applyHidden: Boolean = true,
    ): PlantStatus {
        val issues = mutableListOf<Issue>()
        val hidden = mutableListOf<Issue>()
        val hiddenTexts = plant.devices.associate { it.id to it.hiddenProblems.toSet() }
        val noNetwork = plant.devices.any { it.type.needsHost } && !net.localNetwork
        if (noNetwork) issues += Issue(IssueKind.NO_LOCAL_NETWORK)

        for (s in snapshot?.devices.orEmpty()) {
            val device = s.device
            if (s.reading == null && s.error != null) {
                if (noNetwork && device.type.needsHost) continue // one "no Wi-Fi" instead of an alarm per device
                connectionIssue(device, s, net, last)?.let { issues += it }
            }
            for (p in s.reading?.problems.orEmpty()) {
                val hide = applyHidden && p.text in hiddenTexts[device.id].orEmpty()
                val issue = when (p.kind) {
                    DeviceProblem.Kind.FAULT -> Issue(IssueKind.DEVICE_FAULT, device, p.text)
                    DeviceProblem.Kind.WARNING -> Issue(IssueKind.DEVICE_WARNING, device, p.text)
                    DeviceProblem.Kind.MAINS_LOST -> Issue(if (plant.offGrid) IssueKind.NO_AC_INPUT else IssueKind.MAINS_LOST, device, p.text)
                    DeviceProblem.Kind.BACKUP_MODE -> Issue(if (plant.offGrid) IssueKind.OFF_GRID_MODE else IssueKind.BACKUP_MODE, device, p.text)
                }
                (if (hide) hidden else issues).add(issue)
            }
        }
        // A grid loss and the backup mode show once for the plant, not once per inverter that noticed them.
        for (group in listOf(setOf(IssueKind.MAINS_LOST, IssueKind.NO_AC_INPUT), setOf(IssueKind.BACKUP_MODE, IssueKind.OFF_GRID_MODE))) {
            issues.removeAll(issues.filter { it.kind in group }.drop(1).toSet())
        }

        if (!net.internet) issues += Issue(IssueKind.NO_INTERNET)
        return PlantStatus(issues.distinct().sortedByDescending { it.severity }, hidden.distinct())
    }

    /**
     * An inverter without a battery whose [reading] is near zero solar power: it may go quiet or answer only now
     * and then (night, dawn, dusk), which is normal.
     */
    fun sleepy(device: DeviceConfig, reading: DeviceReading, peakW: Int?): Boolean =
        device.type.kind == DeviceKind.INVERTER && reading.battery == null && (reading.pvPowerW ?: 0) < asleepBelowW(peakW)

    private fun connectionIssue(device: DeviceConfig, s: DeviceStatus, net: NetworkState, last: LastReadings): Issue? {
        val kind = s.errorKind ?: ConnectionError.OTHER
        val unreachable = kind == ConnectionError.TIMEOUT || kind == ConnectionError.UNREACHABLE
        if (unreachable) {
            val address = ipLiteral(device.host)
            if (address != null && net.subnets.isNotEmpty() && net.subnets.none { address in it }) {
                return Issue(IssueKind.WRONG_NETWORK, device)
            }
            val before = last[device.id]
            if (before != null && sleepy(device, before, last.peak(device.id))) return Issue(IssueKind.ASLEEP, device)
            return Issue(IssueKind.NOT_ANSWERING, device)
        }
        return when (kind) {
            ConnectionError.REFUSED, ConnectionError.CLOSED -> Issue(IssueKind.CONNECTION_REFUSED, device)
            ConnectionError.LOGIN -> Issue(IssueKind.LOGIN_FAILED, device)
            ConnectionError.NOT_READY -> Issue(IssueKind.NOT_READY, device)
            else -> Issue(IssueKind.DEVICE_SETTINGS, device, s.error)
        }
    }
}
