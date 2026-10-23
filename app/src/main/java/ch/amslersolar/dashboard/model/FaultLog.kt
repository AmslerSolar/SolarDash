// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

/** One fault log entry (times in epoch seconds; [ended] null while the problem lasts). */
data class LoggedEvent(
    val id: Long = 0,
    val plantId: String,
    val key: String,
    /** [IssueKind] name; an unknown name (from a later or earlier version) is still shown, by name. */
    val kind: String,
    val severity: String,
    val deviceId: String?,
    val deviceName: String?,
    val detail: String?,
    val percent: Int?,
    val started: Long,
    val ended: Long? = null,
    /** The situation when it started, as JSON (see [FaultDetails]). */
    val details: String? = null,
    /** The situation when it ended. */
    val endDetails: String? = null,
) {
    val issueKind: IssueKind? get() = IssueKind.entries.find { it.name == kind }
}

/** Where the fault log is kept (the history database; a map in tests). */
interface EventStore {
    suspend fun openEvents(plantId: String): List<LoggedEvent>
    suspend fun openEvent(e: LoggedEvent)
    suspend fun closeEvent(id: Long, ended: Long, endDetails: String?)
}

/**
 * Turns the plant status of every poll into a fault log: a problem gets an entry when it starts and its end time
 * when it's gone. To keep one dropped poll or a short hiccup out of the log, a problem must last [settleS] before
 * it's logged, and be gone for [settleS] before it's closed (the end time is when it was first missing). Single
 * reads (the background recorder, every 15 min) log and close at once.
 */
class FaultLog(private val store: EventStore, private val settleS: Long = 30, private val appVersion: String = "") {
    private class Memory {
        val last = LastReadings()
        val firstSeen = HashMap<String, Long>()
        /** The situation when a pending problem was first seen, stored once it's logged. */
        val firstDetails = HashMap<String, String>()
        val missingSince = HashMap<String, Long>()
        val missingDetails = HashMap<String, String>()
    }

    private val memory = HashMap<String, Memory>()

    suspend fun update(plant: Plant, snapshot: PlantSnapshot, net: NetworkState, continuous: Boolean = true) {
        val mem = synchronized(memory) { memory.getOrPut(plant.id) { Memory() } }
        val now = snapshot.readAt.epochSecond
        val status = PlantStatusRules.evaluate(plant, snapshot, net, mem.last)
        mem.last.update(snapshot)
        val current = status.issues.filter { logged(it.kind) }.associateBy(::key)
        val open = store.openEvents(plant.id).associateBy { it.key }

        mem.firstSeen.keys.retainAll(current.keys)
        mem.firstDetails.keys.retainAll(current.keys)
        for ((k, issue) in current) {
            mem.missingSince.remove(k)
            mem.missingDetails.remove(k)
            if (k in open) continue
            val since = mem.firstSeen.getOrPut(k) { now }
            val details = mem.firstDetails.getOrPut(k) { FaultDetails.build(plant, snapshot, issue, net, appVersion).toString() }
            if (!continuous || now - since >= settleS) {
                store.openEvent(
                    LoggedEvent(
                        plantId = plant.id, key = k, kind = issue.kind.name, severity = issue.severity.name,
                        deviceId = issue.device?.id, deviceName = issue.device?.name, detail = issue.detail,
                        percent = issue.percent, started = since, details = details,
                    ),
                )
                mem.firstSeen.remove(k)
                mem.firstDetails.remove(k)
            }
        }
        for ((k, e) in open) {
            if (k in current) continue
            val since = mem.missingSince.getOrPut(k) { now }
            // The device as it was once the problem was gone (e.g. answering again, no fault code).
            val device = plant.devices.find { it.id == e.deviceId }
            val details = mem.missingDetails.getOrPut(k) {
                FaultDetails.build(plant, snapshot, Issue(e.issueKind ?: IssueKind.DEVICE_FAULT, device), net, appVersion).toString()
            }
            if (!continuous || now - since >= settleS) {
                store.closeEvent(e.id, since, details)
                mem.missingSince.remove(k)
                mem.missingDetails.remove(k)
            }
        }
    }

    companion object {
        /** Information that comes and goes all the time stays out of the log. */
        fun logged(kind: IssueKind) = kind != IssueKind.NO_INTERNET && kind != IssueKind.ASLEEP && kind != IssueKind.OFF_GRID_MODE

        /** The same problem across polls: device texts count for device problems, not for connection errors. */
        fun key(issue: Issue): String {
            val withDetail = issue.kind in setOf(IssueKind.DEVICE_FAULT, IssueKind.DEVICE_WARNING, IssueKind.MAINS_LOST, IssueKind.NO_AC_INPUT, IssueKind.BACKUP_MODE)
            return listOfNotNull(issue.kind.name, issue.device?.id, if (withDetail) issue.detail else null).joinToString("|")
        }
    }
}
