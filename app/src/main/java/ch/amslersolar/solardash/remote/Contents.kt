// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.ChartChannel
import ch.amslersolar.solardash.model.ConnectionError
import ch.amslersolar.solardash.model.ConsumerLoad
import ch.amslersolar.solardash.model.DailyEnergy
import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.GridSource
import ch.amslersolar.solardash.model.LoggedEvent
import ch.amslersolar.solardash.model.MeterRole
import ch.amslersolar.solardash.model.PhaseReading
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantBattery
import ch.amslersolar.solardash.model.PlantSnapshot
import ch.amslersolar.solardash.model.PvString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/**
 * The JSON contents of the observer protocol (Live, Plant, Day, Event), and the conversions from and to the app's
 * models. What leaves the plant never holds device addresses, passwords or serial numbers.
 */
object Contents {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; coerceInputValues = true }

    @Serializable data class Energy(val pv: Double? = null, val export: Double? = null, val import: Double? = null, val load: Double? = null)
    @Serializable data class Battery(val w: Int, val soc: Int? = null, val v: Double? = null, val mode: String? = null)
    @Serializable data class Phase(val name: String, val v: Double, val a: Double? = null, val hz: Double, val w: Int? = null)
    @Serializable data class Str(val i: Int, val v: Double, val a: Double, val w: Int? = null)
    @Serializable data class Problem(val kind: String, val text: String)
    @Serializable data class Section(val title: String, val rows: List<List<String>>)

    @Serializable data class Reading(
        val pv: Int? = null, val grid: Int? = null, val load: Int? = null, val temp: Double? = null, val status: String? = null,
        val lifetimePv: Double? = null, val battery: Battery? = null, val phases: List<Phase> = emptyList(),
        val strings: List<Str> = emptyList(), val today: Energy? = null, val problems: List<Problem> = emptyList(),
        val details: List<Section> = emptyList(),
    )

    @Serializable data class DeviceLive(val id: String, val error: String? = null, val errorKind: String? = null, val reading: Reading? = null)
    @Serializable data class ConsumerLive(val id: String, val w: Int? = null, val status: String? = null)

    @Serializable data class Live(
        val t: Long, val pv: Int? = null, val load: Int? = null, val grid: Int? = null, val gridSource: String? = null,
        val battery: Battery? = null, val today: Energy? = null, val devices: List<DeviceLive> = emptyList(),
        val consumers: List<ConsumerLive> = emptyList(),
    )

    @Serializable data class Device(
        val id: String, val name: String, val type: String, val meterRole: String = "GRID", val model: String = "",
        val specId: String = "", val hiddenProblems: List<String> = emptyList(),
    )
    @Serializable data class Channel(val deviceId: String, val key: String, val slot: Int)
    /** [channels]: per device the recorded channels as (key, label, unit), for the device blocks. */
    @Serializable data class PlantInfo(
        val name: String, val offGrid: Boolean = false, val devices: List<Device> = emptyList(),
        val chartChannels: List<Channel> = emptyList(), val channels: Map<String, List<List<String>>> = emptyMap(),
    )

    @Serializable data class Day(val day: String, val plant: Energy, val devices: Map<String, Energy> = emptyMap())

    @Serializable data class Event(
        val id: Long, val key: String, val kind: String, val severity: String, val deviceId: String? = null,
        val deviceName: String? = null, val detail: String? = null, val percent: Int? = null, val started: Long,
        val ended: Long? = null, val details: String? = null, val endDetails: String? = null,
    )

    // ---------------------------------------------------------------- from the app (collector)

    private fun energy(e: EnergyTotals) = Energy(e.pvKwh, e.exportKwh, e.importKwh, e.loadKwh)

    fun reading(r: DeviceReading) = Reading(
        pv = r.pvPowerW, grid = r.gridPowerW, load = r.loadPowerW, temp = r.temperatureC, status = r.status,
        lifetimePv = r.lifetimePvKwh,
        battery = r.battery?.let { Battery(it.powerW, it.socPercent, it.voltageV, it.mode) },
        phases = r.phases.map { Phase(it.name, it.voltageV, it.currentA, it.frequencyHz, it.powerW) },
        strings = r.pvStrings.map { Str(it.index, it.voltageV, it.currentA, it.powerW) },
        today = energy(r.today).takeIf { it != Energy() },
        problems = r.problems.map { Problem(it.kind.name, it.text) },
        details = r.details.map { s -> Section(s.title, s.rows.filterNot { identifies(it.first) }.map { listOf(it.first, it.second) }) },
    )

    fun live(s: PlantSnapshot) = Live(
        t = s.readAt.toEpochMilli(), pv = s.pvPowerW, load = s.loadPowerW, grid = s.gridPowerW, gridSource = s.gridSource?.name,
        battery = s.battery?.let { Battery(it.powerW, it.socPercent) },
        today = energy(s.today),
        devices = s.devices.map { DeviceLive(it.device.id, it.error?.let { e -> hide(e, it.device.host) }, it.errorKind?.name, it.reading?.let(::reading)) },
        consumers = s.consumers.map { ConsumerLive(it.device.id, it.powerW, it.status) },
    )

    fun plant(p: Plant, channels: Map<String, List<Triple<String, String, String>>> = emptyMap()) = PlantInfo(
        name = p.name, offGrid = p.offGrid,
        devices = p.devices.map { Device(it.id, it.name, it.type.name, it.meterRole.name, it.model, it.specId, it.hiddenProblems) },
        chartChannels = p.chartChannels.map { Channel(it.deviceId, it.key, it.slot) },
        channels = channels.mapValues { (_, list) -> list.map { listOf(it.first, it.second, it.third) } },
    )

    fun day(d: DailyEnergy, devices: Map<String, EnergyTotals> = emptyMap()) =
        Day(d.day.toString(), energy(d.energy), devices.mapValues { energy(it.value) })

    /** [hosts]: the plant's device addresses, cut out of the texts as well. */
    fun event(e: LoggedEvent, hosts: Collection<String> = emptyList()) = Event(
        e.id, e.key, e.kind, e.severity, e.deviceId, e.deviceName, e.detail?.let { hide(it, hosts) }, e.percent, e.started, e.ended,
        e.details?.let { details(it, hosts) }, e.endDetails?.let { details(it, hosts) },
    )

    /** Detail rows that name one piece of hardware: serial numbers and MAC addresses stay on the plant. */
    private val IDENTIFYING = Regex("""(?i)serial|seriennummer|\bs/?n\b|\bmac\b""")

    fun identifies(label: String) = IDENTIFYING.containsMatchIn(label)

    private fun hide(text: String, host: String) = hide(text, listOf(host))

    private fun hide(text: String, hosts: Collection<String>) =
        hosts.filter { it.isNotBlank() }.sortedByDescending { it.length }.fold(text) { t, h -> t.replace(h, "…") }

    /**
     * The fault log's situation (model/FaultDetails) without what stays on the plant: the device's address, port,
     * unit id and serial number, identifying detail rows, the tablet's subnets, and addresses in error messages.
     */
    fun details(text: String, hosts: Collection<String> = emptyList()): String? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val all = hosts + listOfNotNull((o["device"] as? JsonObject)?.get("host")?.jsonPrimitive?.contentOrNull)
        fun clean(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (_, v) -> clean(v) })
            is JsonArray -> JsonArray(
                e.filterNot { r -> r is JsonArray && r.size == 2 && (r[0] as? JsonPrimitive)?.isString == true && identifies(r[0].jsonPrimitive.content) }
                    .map(::clean),
            )
            is JsonPrimitive -> if (e.isString) JsonPrimitive(hide(e.content, all)) else e
        }
        val out = o.toMutableMap()
        (o["device"] as? JsonObject)?.let { d -> out["device"] = JsonObject(d - setOf("host", "port", "unit_id", "rtu_over_tcp", "serial")) }
        (o["network"] as? JsonObject)?.let { n -> out["network"] = JsonObject(n - "subnets") }
        return clean(JsonObject(out)).toString()
    }

    // ---------------------------------------------------------------- into the app (observer)

    private fun totals(e: Energy?) = EnergyTotals(e?.pv, e?.export, e?.import, e?.load)

    /** A device as the observer knows it: no address; a type this version doesn't know reads as a demo device. */
    fun device(d: Device) = DeviceConfig(
        id = d.id, name = d.name,
        type = DeviceType.entries.find { it.name == d.type } ?: DeviceType.MODBUS_SPEC_INVERTER,
        meterRole = MeterRole.entries.find { it.name == d.meterRole } ?: MeterRole.GRID,
        model = d.model, specId = d.specId, hiddenProblems = d.hiddenProblems,
    )

    fun plant(id: String, info: PlantInfo, observed: Plant.Observed) = Plant(
        id = id, name = info.name, offGrid = info.offGrid, devices = info.devices.map(::device),
        chartChannels = info.chartChannels.map { ChartChannel(it.deviceId, it.key, it.slot) }, observed = observed,
    )

    fun reading(r: Reading) = DeviceReading(
        pvPowerW = r.pv, gridPowerW = r.grid, loadPowerW = r.load, temperatureC = r.temp, status = r.status,
        lifetimePvKwh = r.lifetimePv,
        battery = r.battery?.let { BatteryState(it.w, it.soc, it.v, it.mode ?: "") },
        phases = r.phases.map { PhaseReading(it.name, it.v, it.a, it.hz, it.w) },
        pvStrings = r.strings.map { PvString(it.i, it.v, it.a, it.w) },
        today = totals(r.today),
        problems = r.problems.mapNotNull { p -> DeviceProblem.Kind.entries.find { it.name == p.kind }?.let { DeviceProblem(it, p.text) } },
        details = r.details.map { s -> DetailSection(s.title, s.rows.mapNotNull { if (it.size == 2) it[0] to it[1] else null }) },
    )

    fun snapshot(live: Live, plant: Plant): PlantSnapshot {
        val byId = plant.devices.associateBy { it.id }
        return PlantSnapshot(
            readAt = Instant.ofEpochMilli(live.t),
            devices = live.devices.mapNotNull { d ->
                val config = byId[d.id] ?: return@mapNotNull null
                DeviceStatus(config, d.reading?.let(::reading), d.error, ConnectionError.entries.find { it.name == d.errorKind })
            },
            pvPowerW = live.pv,
            battery = live.battery?.let { PlantBattery(it.w, it.soc) },
            gridPowerW = live.grid,
            gridSource = GridSource.entries.find { it.name == live.gridSource },
            loadPowerW = live.load,
            today = totals(live.today),
            consumers = live.consumers.mapNotNull { c -> byId[c.id]?.let { ConsumerLoad(it, c.w, c.status) } },
        )
    }

    fun day(d: Day): Pair<DailyEnergy, Map<String, EnergyTotals>>? {
        val day = runCatching { LocalDate.parse(d.day) }.getOrNull() ?: return null
        return DailyEnergy(day, totals(d.plant)) to d.devices.mapValues { totals(it.value) }
    }

    fun event(plantId: String, e: Event) = LoggedEvent(
        e.id, plantId, e.key, e.kind, e.severity, e.deviceId, e.deviceName, e.detail, e.percent, e.started, e.ended, e.details, e.endDetails,
    )
}
