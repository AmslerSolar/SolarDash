// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A device driver made from a ModbusSpec file: [SpecReader] reads and decodes the registers, this class turns the
 * vocabulary keys into the app's [DeviceReading] (energy flow, phases, battery, faults) and lists every value as a
 * detail row. What the format can't express goes into [hooks] (per device family, in Kotlin).
 *
 * Values from points the manufacturer documents don't back (only open-source projects name them, not verified)
 * are shown under "Other sources (unverified)" and never feed the energy flow or the plant status.
 */
class SpecDriver(
    spec: Spec,
    vocabulary: Vocabulary,
    tables: SpecTables,
    unitId: Int,
    private val hooks: SpecHooks = SpecHooks.NONE,
    requestGapMs: Long? = null,
    /** The model the user picked (files with `model_choice: user`). */
    model: String? = null,
    /** While asleep, the device is asked again only after this long (each attempt waits for timeouts). */
    private val sleepRetryMs: Long = 60_000,
) : DeviceDriver {
    constructor(spec: Spec, vocabulary: Vocabulary, client: ModbusTcpClient, unitId: Int, hooks: SpecHooks = SpecHooks.NONE, model: String? = null) : this(
        spec, vocabulary,
        SpecTables(
            holding = RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
            input = RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        ),
        unitId, hooks, model = model,
    )

    private val reader = SpecReader(spec, vocabulary, tables, unitId, minIntervalMs = requestGapMs ?: spec.connection.minIntervalMs, chosenModel = model)
    private val spec get() = reader.spec
    private var lastPvW: Int? = null
    private var sleeping = false
    private var lastAttempt = 0L

    override suspend fun identify(): DeviceInfo {
        val (model, serial) = reader.identify() ?: throw IOException("Not a ${spec.manufacturer} ${spec.name}")
        return DeviceInfo(model = "${spec.manufacturer} ${model?.name ?: spec.name}", serial = serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        val now = System.currentTimeMillis()
        if (sleeping && now - lastAttempt < sleepRetryMs) return SLEEPING
        lastAttempt = now
        val values = reader.read()
        // Powered by its panels and nothing answered: asleep (no sun), not a fault, if it was producing little when it
        // last answered. Silence after a higher production, or before any reading, is a failure.
        if (reader.asleep) {
            if (!sleeping && (lastPvW ?: Int.MAX_VALUE) > SLEEP_MAX_W) throw IOException("${spec.manufacturer} ${spec.name}: no answer")
            sleeping = true
            return SLEEPING
        }
        sleeping = false
        if (values.isEmpty()) throw SpecMismatchException("${spec.manufacturer} ${spec.name}: no register answered")
        return hooks.adjust(values, reading(values)).also { lastPvW = it.pvPowerW }
    }

    private fun reading(all: Map<String, SpecValue>): DeviceReading {
        val v = all.filterValues { it.trusted }
        fun n(key: String) = v[key]?.number
        fun w(key: String) = n(key)?.roundToInt()
        val consumer = spec.kind !in setOf("inverter", "meter", "battery")

        // A consumer (wallbox, heat pump …) has no PV, grid or battery of its own: values it reports under those keys
        // (e.g. the grid meter reading a heating rod works from) are details, not energy flow.
        val battery = if (!consumer && (n("battery_power") != null || n("battery_soc") != null)) {
            val p = w("battery_power") ?: 0
            BatteryState(
                powerW = p,
                socPercent = n("battery_soc")?.roundToInt(),
                voltageV = n("battery_voltage"),
                mode = when { p > 0 -> "Discharging"; p < 0 -> "Charging"; else -> "Idle" },
            )
        } else null

        val phases = (1..3).mapNotNull { i ->
            val volts = n("l${i}_voltage")
            val amps = n("l${i}_current")
            val watts = w("l${i}_power")
            if (volts == null && amps == null && watts == null) null
            else PhaseReading("L$i", volts ?: 0.0, amps, n("frequency") ?: 0.0, watts)
        }

        val problems = reader.problems(all).map { (kind, text) ->
            DeviceProblem(
                when (kind) {
                    "fault" -> DeviceProblem.Kind.FAULT
                    "mains_lost" -> DeviceProblem.Kind.MAINS_LOST
                    "backup_mode" -> DeviceProblem.Kind.BACKUP_MODE
                    else -> DeviceProblem.Kind.WARNING
                },
                text,
            )
        }.distinct()

        return DeviceReading(
            pvPowerW = if (consumer) null else w("pv_power"),
            battery = battery,
            gridPowerW = if (consumer) null else w("grid_power"),
            phases = phases,
            loadPowerW = if (consumer) w("consumer_power") else w("load_power"),
            temperatureC = n("temperature"),
            status = v["state"]?.let { it.enumText ?: it.text ?: it.number?.let(::num) },
            today = EnergyTotals(pvKwh = n("pv_energy_today")),
            lifetimePvKwh = n("pv_energy"),
            details = details(all),
            problems = problems,
        )
    }

    private fun details(all: Map<String, SpecValue>): List<DetailSection> {
        val (trusted, other) = all.values.filter { it.point != null }.partition { it.trusted }
        val (standard, device) = trusted.partition { !it.point!!.key.startsWith("x.") }
        return listOf(
            DetailSection("Measurements", standard.map { it.point!!.label to format(it) }),
            DetailSection("Device", device.map { it.point!!.label to format(it) }),
            DetailSection("Other sources (unverified)", other.map { it.point!!.label to format(it) }),
            DetailSection("Specification", listOf("ModbusSpec" to spec.id) + listOfNotNull(reader.model?.let { "Model" to it.name })),
        ).filter { it.rows.isNotEmpty() }
    }

    private fun format(v: SpecValue): String {
        v.text?.let { return it }
        val n = v.number ?: return ""
        if (v.point?.enum != null) return "${v.enumText} (${n.toLong()})"
        if (v.point?.flags != null) return if (v.flags.isEmpty()) "none" else v.flags.joinToString(", ")
        val unit = v.point?.unit?.takeIf { it.isNotEmpty() }
        return num(n) + (unit?.let { " $it" } ?: "")
    }

    private companion object {
        val SLEEPING = DeviceReading(pvPowerW = 0, status = "Sleeping (no sun)")
        /** Highest solar power at the last answer for which no answer then means asleep (dusk), not a failure. */
        const val SLEEP_MAX_W = 500
    }

    private fun num(d: Double): String = when {
        d == Math.rint(d) && abs(d) < 1e15 -> d.toLong().toString()
        else -> String.format(Locale.ROOT, "%.3f", d).trimEnd('0').trimEnd('.')
    }
}

/** Per-device code for what a ModbusSpec file can't express (computed values, plausibility rules …). */
fun interface SpecHooks {
    fun adjust(values: Map<String, SpecValue>, reading: DeviceReading): DeviceReading

    companion object {
        val NONE = SpecHooks { _, r -> r }
    }
}

/** The ModbusSpec files the app bundles; [load] gives a file's text (assets in the app, files in tests). */
class SpecLibrary(private val load: (String) -> String) {
    val vocabulary: Vocabulary by lazy { Vocabulary.parse(load("vocabulary.json")) }
    private val specs = HashMap<String, Spec>()

    @Synchronized
    fun spec(id: String): Spec = specs.getOrPut(id) { Spec.parse(load("$id.json")) }

    companion object {
        @Volatile var current: SpecLibrary? = null
    }
}
