// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.DeviceType
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * Which device types read through a ModbusSpec file (app/modbusspec.txt lists the bundled files), and their
 * hooks. A type moves here once [SpecGoldenTest] shows the spec driver reads what its hand-written driver read.
 */
object SpecDevices {
    /** Spec files per device type, tried in this order until one's identify checks pass (see [ChoosingSpecDriver]). */
    val SPEC_IDS: Map<DeviceType, List<String>> = mapOf(
        DeviceType.EASTRON to listOf("eastron-sdm"),
        DeviceType.KEBA_P30 to listOf("keba-kecontact-p30"),
        DeviceType.FENECON to listOf("fenecon-fems"),
        DeviceType.HUAWEI to listOf("huawei-sun2000"),
        DeviceType.GROWATT to listOf("growatt-sph", "growatt-tl-x", "growatt-wit"),
        DeviceType.GOODWE_ET to listOf("goodwe-et"),
        DeviceType.SMA to listOf("sma-inverter"),
        DeviceType.SUNGROW to listOf("sungrow-shx", "sungrow-sg"),
        DeviceType.CARLO_GAVAZZI to listOf("carlogavazzi-em24-e1", "carlogavazzi-em300", "carlogavazzi-em210", "carlogavazzi-em24-din"),
        DeviceType.INEPRO_PRO380 to listOf("inepro-pro380"),
        DeviceType.ABB_B2X to listOf("abb-b23-b24"),
        DeviceType.SCHNEIDER_IEM to listOf("schneider-iem3000"),
        DeviceType.SIEMENS_PAC to listOf("siemens-pac2200"),
        DeviceType.KOSTAL_KSEM to listOf("kostal-ksem"),
        DeviceType.SHELLY_PRO_3EM to listOf("shelly-pro-3em"),
        DeviceType.KOSTAL_PIKO_CI to listOf("kostal-piko-ci"),
        DeviceType.CONEXT_CL60 to listOf("schneider-conext-cl60"),
        DeviceType.CONEXT_GATEWAY to listOf("schneider-conext-gateway"),
        DeviceType.CPS to listOf("chint-cps-403x"),
        DeviceType.POWER_ONE to listOf("powerone-trio", "powerone-trio-50"),
        DeviceType.INGETEAM to listOf("ingeteam-ingecon-sun", "ingeteam-ingecon-sun-legacy"),
        DeviceType.RIELLO_CENTRAL to listOf("riello-central"),
        DeviceType.MY_PV to listOf("mypv-elwa-acthor"),
        DeviceType.STIEBEL_ELTRON_ISG to listOf("stiebel-wpm"),
        DeviceType.ALFEN_EVE to listOf("alfen-eve-socket"),
        DeviceType.MENNEKES_AMTRON to listOf("bender-ecu"),
        DeviceType.GO_E to listOf("goe-charger"),
        DeviceType.NIBE_S to listOf("nibe-s-series"),
        DeviceType.HOVAL_TOPTRONIC to listOf("hoval-toptronic-e"),
    )

    /**
     * Address offsets to try per device type (see [SpecTables.addressOffset]), after the spec ids: for devices
     * documented with 1-based register numbers whose gateways may or may not renumber (the files take register − 1).
     */
    val ADDRESS_OFFSETS: Map<DeviceType, List<Int>> = mapOf(
        DeviceType.POWER_ONE to listOf(0, 1),
        DeviceType.RIELLO_CENTRAL to listOf(0, 1),
    )

    /** Devices that ignore requests sent right after the TCP connection opens (Huawei: about 1 s). */
    private val CONNECT_DELAY_MS: Map<DeviceType, Long> = mapOf(DeviceType.HUAWEI to 1_000L)

    /**
     * Devices that want a pause between polls rather than between requests: SMA's general TI asks for 10 s between
     * data transfers on older Speedwire devices. The poll's requests go out back to back and the reading is reused
     * until the pause is over (the file's min_interval_ms, 10 s between requests, would make one poll take minutes).
     */
    private val MIN_POLL_INTERVAL_MS: Map<DeviceType, Long> = mapOf(DeviceType.SMA to 10_000L)

    /** Files with `input_equals_holding` whose devices are read with function 04 throughout (SMA: as the STPS X TI does). */
    private val INPUT_FOR_HOLDING: Set<DeviceType> = setOf(DeviceType.SMA)

    /** Hooks that keep state per device: a new instance for every driver. */
    private val HOOK_FACTORIES: Map<String, () -> SpecHooks> = mapOf("sungrow-sg" to { Inverters1Hooks.SungrowSg() })

    val HOOKS: Map<String, SpecHooks> = mapOf(
        "goodwe-et" to SpecHooks { v, r -> goodWe(v, r) },
        "huawei-sun2000" to SpecHooks { v, r -> huawei(v, r) },
        "growatt-sph" to SpecHooks { v, r -> growatt(v, r) },
        "growatt-tl-x" to SpecHooks { v, r -> growatt(v, r) },
        "growatt-wit" to SpecHooks { v, r -> growatt(v, r) },
        "kostal-plenticore" to Inverters1Hooks.KOSTAL_PLENTICORE,
        "sma-inverter" to Inverters1Hooks.SMA,
        "sungrow-shx" to Inverters1Hooks.SUNGROW_SHX,
        // No EssDischargePower (415): the AC-side ESS power (303) minus the hybrid inverter's DC PV (339).
        "fenecon-fems" to SpecHooks { v, r ->
            if (v.n("battery_power") != null) r
            else v.n("x.ess_active_power")?.let { ac -> r.withBatteryPower((ac - (v.n("x.production_dc_actual_power") ?: 0.0)).roundToInt()) } ?: r
        },
        // 1-element metering (number of elements = 1): only the phase with a voltage is measured.
        "abb-b23-b24" to SpecHooks { v, r ->
            val shown = phasesWithVoltage(v, r)
            if (v["x.number_of_elements"]?.number == 1.0) shown.copy(phases = shown.phases.filter { it.voltageV > 0.0 }) else shown
        },
        // Overflow marker and measuring system (see carloGavazzi).
        "carlogavazzi-em24-e1" to SpecHooks(::carloGavazzi),
        "carlogavazzi-em300" to SpecHooks(::carloGavazzi),
        "carlogavazzi-em210" to SpecHooks(::carloGavazzi),
        "carlogavazzi-em24-din" to SpecHooks(::carloGavazzi),
        // A phase whose voltage register is rejected isn't shown.
        "kostal-ksem" to SpecHooks(::phasesWithVoltage),
        // A phase without voltage (rejected, or 0 V: not connected) isn't shown.
        "schneider-iem3000" to SpecHooks { v, r -> phasesWithVoltage(v, r).let { it.copy(phases = it.phases.filter { p -> p.voltageV > 0.0 }) } },
        "kostal-piko-ci" to Inverters2Hooks.KOSTAL_PIKO_CI,
        "schneider-conext-cl60" to Inverters2Hooks.CONEXT_CL60,
        "schneider-conext-gateway" to Inverters2Hooks.CONEXT_GATEWAY,
        "chint-cps-403x" to Inverters2Hooks.CPS_403X,
        "powerone-trio" to Inverters2Hooks.POWER_ONE_TRIO,
        "powerone-trio-50" to Inverters2Hooks.POWER_ONE_TRIO_50,
        "riello-central" to Inverters2Hooks.RIELLO_CENTRAL,
        "ingeteam-ingecon-sun" to Inverters2Hooks.INGETEAM,
        "ingeteam-ingecon-sun-legacy" to Inverters2Hooks.INGETEAM_LEGACY,
        // "Error (state 4)" only when the station gives no error code, as one fault, not two.
        "keba-kecontact-p30" to SpecHooks { _, r ->
            if (r.problems.any { it.text.contains("(code ") }) r.copy(problems = r.problems.filterNot { it.text.endsWith("(state 4)") }) else r
        },
        // Power drawn: 1082 where the firmware has it; else the output powers (ELWA 2: 1074 + 1075, AC•THOR 9s: + 1076);
        // else the power setpoint 1000. Phases only with a voltage (the AC•THOR replies 0 for L2 / L3).
        "mypv-elwa-acthor" to SpecHooks { v, r ->
            fun n(key: String) = v[key]?.takeIf { it.trusted }?.number
            val outputs = when (v["state"]?.point?.enum) {
                "elwa_2_status" -> listOf("x.power_out_1", "x.power_out_2")
                "thor_status" -> if (v.containsKey("x.power_out_3")) listOf("x.power_out_1", "x.power_out_2", "x.power_out_3") else emptyList()
                else -> emptyList()
            }.map(::n)
            val load = r.loadPowerW
                ?: outputs.takeIf { o -> o.isNotEmpty() && o.all { it != null } }?.sumOf { it!! }?.roundToInt()
                ?: n("x.power_setpoint")?.roundToInt()
            r.copy(loadPowerW = load?.coerceAtLeast(0), phases = r.phases.filter { it.voltageV > 0 })
        },
        // Car state 0 ("defective") only when the error register gives no code, as one fault.
        "goe-charger" to SpecHooks { v, r ->
            val code = v["error_code"]?.number
            if (code != null && code != 0.0) r.copy(problems = r.problems.filterNot { it.text.endsWith("(car state 0)") }) else r
        },
        // Reallin meters give NaN for the power sum: then the phase powers' sum.
        "alfen-eve-socket" to SpecHooks { v, r ->
            val load = r.loadPowerW ?: listOf("l1_power", "l2_power", "l3_power").mapNotNull { v[it]?.takeIf { x -> x.trusted }?.number }
                .takeIf { it.isNotEmpty() }?.sum()?.roundToInt()
            r.copy(loadPowerW = load?.coerceAtLeast(0))
        },
        // Software < 5.22 has no 220: the sum of 206–210, or 206 alone (then the total, not a phase) when L2 / L3 read
        // 0xFFFFFFFF. OCPP "Faulted" / vehicle state E only when no error bit explains them.
        "bender-ecu" to SpecHooks { v, r ->
            fun n(key: String) = v[key]?.takeIf { it.trusted }?.number
            val l = listOf(n("l1_power"), n("l2_power"), n("l3_power"))
            val l1IsTotal = l[1] == null && l[2] == null && n("consumer_power") == null
            val load = r.loadPowerW ?: (if (l1IsTotal) l[0] else l.filterNotNull().takeIf { it.isNotEmpty() }?.sum())?.roundToInt()
            val bitFault = r.problems.any { it.kind == DeviceProblem.Kind.FAULT && it.text.contains("(error bit ") }
            val ocpp = r.problems.any { it.text == "Charge point faulted (OCPP status 4)" }
            val problems = r.problems.filterNot {
                (bitFault && (it.text == "Charge point faulted (OCPP status 4)" || it.text == "Vehicle state E (error)")) ||
                    (ocpp && it.text == "Vehicle state E (error)")
            }
            r.copy(
                loadPowerW = load?.coerceAtLeast(0),
                phases = if (l1IsTotal) r.phases.map { it.copy(powerW = null) } else r.phases,
                problems = problems,
            )
        },
        // The heat generator's alarm / fault (18722) only when the WFA status shows no fault. An error code 64…191 that
        // isn't in the guideline's W/B list (the file has no rule for it) is a fault, like the codes outside 64…254.
        "hoval-toptronic-e" to SpecHooks { v, r ->
            var problems = r.problems
            if (problems.any { it.text.startsWith("Heat pump fault (WFA status") }) {
                problems = problems.filterNot { it.text.startsWith("Heat generator alarm") || it.text.startsWith("Heat generator fault") }
            }
            val code = v["error_code"]?.takeIf { it.trusted }
            val n = code?.number?.toInt()
            if (n != null && n in 64..191 && code.enumText.let { it == null || it == "Unknown" }) problems = problems + DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump error code $n")
            // The file's texts carry notes for readers of the file ("name in the error_code enum"): name the code
            // instead, as the display should, e.g. "Heat pump error E12 (code 203)".
            val name = code?.enumText?.takeIf { it != "Unknown" }
            problems = problems.map { p ->
                if (n != null && (p.text.startsWith("Heat pump error (code") || p.text.startsWith("Heat pump message (code"))) {
                    p.copy(text = if (name != null) "Heat pump error $name (code $n)" else "Heat pump error code $n")
                } else p
            }.distinct()
            r.copy(problems = problems)
        },
    )

    /** The spec driver for [type], or null when it has none (or no spec library is loaded, e.g. in some tests). */
    fun create(type: DeviceType, client: ModbusTcpClient, unitId: Int, model: String? = null): DeviceDriver? {
        val ids = SPEC_IDS[type] ?: return null
        val library = SpecLibrary.current ?: return null
        CONNECT_DELAY_MS[type]?.let { client.connectDelayMs = maxOf(client.connectDelayMs, it) }
        val offsets = ADDRESS_OFFSETS[type] ?: listOf(0)
        val pollGap = MIN_POLL_INTERVAL_MS[type]
        val drivers = ids.flatMap { id ->
            offsets.map { offset ->
                val input = RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }
                // SMA: function 03 and 04 read the same registers (input_equals_holding); the old driver used 04.
                val holding = if (type in INPUT_FOR_HOLDING) input else RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }
                SpecDriver(
                    library.spec(id), library.vocabulary, SpecTables(holding = holding, input = input, addressOffset = offset), unitId, hooks(id),
                    requestGapMs = if (pollGap != null) 0 else null, model = model?.takeIf { it.isNotBlank() },
                )
            }
        }
        val driver = drivers.singleOrNull() ?: ChoosingSpecDriver(drivers)
        return if (pollGap != null) ThrottledDriver(driver, pollGap) else driver
    }

    /** Meters: a phase whose voltage register gave no value (invalid, rejected) isn't shown. */
    private fun phasesWithVoltage(v: Map<String, SpecValue>, r: DeviceReading) =
        r.copy(phases = r.phases.filter { v["${it.name.lowercase()}_voltage"]?.trusted == true })

    /**
     * Carlo Gavazzi: a 32-bit value whose most significant word reads 7FFFh is an overflow ("EEEE"), not a value;
     * the measuring system 1P / 2P limits the phases to L1 / L1 + L2.
     */
    private fun carloGavazzi(v: Map<String, SpecValue>, r: DeviceReading): DeviceReading {
        fun overflow(key: String): Boolean {
            val x = v[key] ?: return false
            val p = x.point ?: return false
            val n = x.number ?: return false
            return p.type == "s32" && kotlin.math.abs(n / p.scale).roundToLong() in 0x7FFF0000L..0x7FFFFFFFL
        }
        val count = when (v["x.measuring_system"]?.enumText) { "1P" -> 1; "2P" -> 2; else -> 3 }
        val phases = phasesWithVoltage(v, r).phases.filter { !overflow("${it.name.lowercase()}_voltage") }.map {
            val k = it.name.lowercase()
            it.copy(currentA = it.currentA.takeUnless { overflow("${k}_current") }, powerW = it.powerW.takeUnless { overflow("${k}_power") })
        }.filter { it.name.drop(1).toInt() <= count }
        return r.copy(gridPowerW = r.gridPowerW.takeUnless { overflow("grid_power") }, phases = phases)
    }

    /**
     * GoodWe ET: PV total from 35301, else the sum of the four PV power registers (older firmware has no 35301);
     * phase power is the inverter's (35124 …; l1_power is the meter's, from a project); the battery only while a
     * battery mode (35184 / 35266) isn't 0, battery 2 added, SOC (37007) of battery 1 only, and the BMS codes only
     * with battery 1; a grid-mode or work-mode fault only when no error bit already reports one.
     */
    private fun goodWe(v: Map<String, SpecValue>, r: DeviceReading): DeviceReading {
        val pv = r.pvPowerW ?: (1..4).map { v.n("x.pv${it}_power") }.takeIf { t -> t.all { it != null } }?.sumOf { it!! }?.roundToInt()
        val phases = r.phases.map { p -> p.copy(powerW = v.n("x.inverter_${p.name.lowercase()}_power")?.roundToInt()) }
        val m1 = v.n("x.battery1_mode")?.toInt() ?: 0
        val m2 = v.n("x.battery2_mode")?.toInt() ?: 0
        val battery = if (m1 == 0 && m2 == 0) null else {
            val p = (if (m1 != 0) v.n("battery_power") ?: 0.0 else 0.0) + (if (m2 != 0) v.n("x.battery2_power") ?: 0.0 else 0.0)
            BatteryState(
                p.roundToInt(), if (m1 != 0) r.battery?.socPercent else null,
                if (m1 != 0) v.n("battery_voltage") else v.n("x.battery2_voltage"), batteryMode(p.roundToInt()),
            )
        }
        var problems = r.problems.filterNot { m1 == 0 && it.text.startsWith("BMS ") }
        fun faultsBesides(vararg texts: String) = problems.any { p ->
            p.kind == DeviceProblem.Kind.FAULT && !p.text.startsWith("BMS ") && texts.none { p.text.endsWith(it) }
        }
        if (faultsBesides("(grid mode 2)", "(work mode 3)")) problems = problems.filterNot { it.text.endsWith("(grid mode 2)") }
        if (faultsBesides("(work mode 3)")) problems = problems.filterNot { it.text.endsWith("(work mode 3)") }
        return r.copy(pvPowerW = pv, phases = phases, battery = battery, problems = problems)
    }

    /**
     * Huawei SUN2000: grid and phases only while the meter is online (37100 = 1), phases with a voltage, currents as
     * amounts; the battery only with a rated capacity (37758 > 0), its fault from the fault ID first, else status 3;
     * "Shutdown: fault" only when no alarm bit already reports a fault.
     */
    private fun huawei(v: Map<String, SpecValue>, r: DeviceReading): DeviceReading {
        val meter = v.n("x.meter_status") == 1.0
        val capacity = (v.n("x.battery_capacity") ?: 0.0) > 0
        val battery = capacity && v.n("x.battery_status") != null
        var problems = r.problems
        if (!capacity) problems = problems.filterNot { it.text.startsWith("Battery fault (") }
        if (problems.any { it.text.startsWith("Battery fault (ID ") }) problems = problems.filterNot { it.text == "Battery fault (status 3)" }
        if (problems.any { it.kind == DeviceProblem.Kind.FAULT && !it.text.startsWith("Battery fault (") && it.text != "Shutdown: fault (0x0300)" }) {
            problems = problems.filterNot { it.text == "Shutdown: fault (0x0300)" }
        }
        return r.copy(
            gridPowerW = if (meter) r.gridPowerW else null,
            phases = if (meter) r.phases.filter { it.voltageV > 0 }.map { it.copy(currentA = it.currentA?.let(Math::abs)) } else emptyList(),
            battery = if (battery) r.battery else null,
            problems = problems,
        )
    }

    /**
     * Growatt VPP V2.01: up to four battery blocks (31200 … 31500; a block of zeros is an empty slot), power summed,
     * SOC weighted by the full-charge capacity when every battery has one; phases only for single-phase output
     * (31106 phase voltage, no line voltages 31107/31108); PV and home power not below 0; a fault code replaces
     * "Fault (state 3)", and a battery fault needs a battery.
     */
    private fun growatt(v: Map<String, SpecValue>, r: DeviceReading): DeviceReading {
        class Bat(val power: Double?, val volts: Double?, val soc: Double?, val fcc: Double?)
        val bats = (1..4).map { k ->
            val p = if (k == 1) "battery_" else "x.battery${k}_"
            Bat(v.discharge(p + "power"), v.n(p + "voltage"), v.n(p + "soc"), v.n(if (k == 1) "x.battery_fcc" else "x.battery${k}_fcc"))
        }.filter { (it.volts ?: 0.0) > 0 || (it.soc ?: 0.0) > 0 || (it.power ?: 0.0) != 0.0 || (it.fcc ?: 0.0) > 0 }
        val battery = if (bats.isEmpty()) null else {
            val power = -(bats.sumOf { -(it.power ?: 0.0) }).roundToInt()
            val withSoc = bats.filter { it.soc != null }
            val soc = when {
                withSoc.isEmpty() -> null
                withSoc.all { (it.fcc ?: 0.0) > 0 } -> (withSoc.sumOf { it.soc!! * it.fcc!! } / withSoc.sumOf { it.fcc!! }).roundToInt()
                else -> withSoc.map { it.soc!! }.average().roundToInt()
            }
            BatteryState(power, soc, bats.first().volts, batteryMode(power))
        }
        val threePhase = (v.n("x.line_voltage_bc") ?: 0.0) > 0 || (v.n("x.line_voltage_ca") ?: 0.0) > 0
        val phases = if (threePhase) emptyList() else listOfNotNull(
            v.n("x.grid_voltage_ab")?.takeIf { it > 0 }?.let { PhaseReading("L1", it, v.n("l1_current"), v.n("frequency") ?: 0.0, null) },
        )
        var problems = r.problems
        if (problems.any { it.text.startsWith("Fault code") }) problems = problems.filterNot { it.text == "Fault (state 3)" }
        if (battery == null) problems = problems.filterNot { it.text.startsWith("Battery fault") }
        return r.copy(
            pvPowerW = r.pvPowerW?.coerceAtLeast(0), loadPowerW = r.loadPowerW?.coerceAtLeast(0),
            battery = battery, phases = phases, problems = problems,
        )
    }

    /** A trusted value's number (hooks never use a project's guess). */
    private fun Map<String, SpecValue>.n(key: String): Double? = this[key]?.takeIf { it.trusted }?.number

    /**
     * A battery power with discharging positive: the engine normalises vocabulary keys only, so an `x.` point
     * documented as `positive: charge` is negated here.
     */
    private fun Map<String, SpecValue>.discharge(key: String): Double? {
        val sv = this[key]?.takeIf { it.trusted } ?: return null
        val n = sv.number ?: return null
        return if (key.startsWith("x.") && sv.point?.positive == "charge") -n else n
    }

    private fun batteryMode(p: Int) = when { p > 0 -> "Discharging"; p < 0 -> "Charging"; else -> "Idle" }

    /** The reading with this battery power (a battery state is made when there was none). */
    private fun DeviceReading.withBatteryPower(p: Int): DeviceReading =
        copy(battery = (battery ?: BatteryState(0, null, null, "Idle")).copy(powerW = p, mode = batteryMode(p)))

    fun hooks(id: String): SpecHooks = HOOK_FACTORIES[id]?.invoke() ?: HOOKS[id] ?: SpecHooks.NONE

    /**
     * The models the user picks from, (id, name), for a type whose spec file can't tell its models apart over
     * Modbus (`model_choice: user`); empty for every other type.
     */
    fun userModels(type: DeviceType): List<Pair<String, String>> {
        val library = SpecLibrary.current ?: return emptyList()
        return SPEC_IDS[type].orEmpty().map { library.spec(it) }.filter { it.modelChoice == "user" }
            .flatMap { s -> s.models.map { it.id to it.name } }
    }
}

/**
 * One device type, several spec files (e.g. a maker's meter families): the first whose identify checks pass is
 * used from then on; until one does, reads fail with the last driver's reason.
 */
class ChoosingSpecDriver(private val candidates: List<SpecDriver>) : DeviceDriver {
    private var chosen: SpecDriver? = null

    private suspend fun choose(): SpecDriver {
        chosen?.let { return it }
        var last: Exception? = null
        for (d in candidates) {
            try {
                d.identify()
                chosen = d
                return d
            } catch (e: java.net.SocketException) {
                throw e // unreachable: no point trying the other files now
            } catch (e: java.io.InterruptedIOException) {
                throw e // timeout
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No matching device file")
    }

    override suspend fun identify() = choose().identify()

    override suspend fun read() = choose().read()
}

/** Reads [inner] at most every [minIntervalMs]; in between, the last reading is returned again. */
class ThrottledDriver(
    private val inner: DeviceDriver,
    private val minIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDriver {
    private var last: DeviceReading? = null
    private var lastAt = 0L

    override suspend fun identify() = inner.identify()

    override suspend fun read(): DeviceReading {
        val now = clock()
        last?.let { if (now - lastAt < minIntervalMs) return it }
        return inner.read().also { last = it; lastAt = now }
    }
}
