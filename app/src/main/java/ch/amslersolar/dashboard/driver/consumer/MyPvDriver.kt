// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import java.util.Locale

/**
 * my-PV PV heating controllers over Modbus TCP, read only: AC ELWA-E, AC ELWA 2, AC•THOR and AC•THOR 9s.
 *
 * Specs: my-PV "Documentation of Controls" for AC ELWA-E (230725), AC ELWA 2 (241205) and AC-THOR / AC-THOR 9s
 * (241205) (docs/specs/my-pv/, not to be redistributed). Verified there:
 * - Read with FC03 (holding registers); all registers 16-bit unsigned except 1078/1079 and 1087/1088 (32-bit, high
 *   word first: "high word" is the lower address); 1069 meter power is signed (negative = feed-in).
 * - Addresses are "real" (0-based) addresses as printed: 1000 = power.
 * - Enable on the device: control type ("kind of control") = Modbus TCP in the web interface. This driver never
 *   writes; reading works in other control modes too (UNVERIFIED).
 * - Device type: the documents have no type register; they list the 16-digit serial number prefixes
 *   (1018–1025, 2 ASCII chars per register) per product, and that is what is used here: 160124/160140/160129/160142
 *   AC ELWA-E, 160150/160151/160152 AC ELWA 2, 200100/200101/200103 AC•THOR (CH, i), 200300 AC•THOR 9s. my-PV
 *   notes that it doesn't recommend identifying by serial; other prefixes (e.g. SOL•THOR 140100) are read with the
 *   common registers only.
 *
 * Registers: 1000 power setpoint W, 1001 Temp 1 (water) 0.1 °C, 1002 target temperature 0.1 °C, 1003 status,
 * 1005 boost mode, 1006 boost / minimum temperature, 1014 max power (ELWA-E: fuse type A), 1015 controller
 * temperature 0.1 °C, 1016/1017/1028 firmware, 1018–1025 serial, 1030–1032 further temperatures (ELWA 2: 1030 only;
 * on the ELWA-E 1030 is a meter value), 1058 relays, 1059 load state, 1060 load nominal power, 1061–1064 / 1067 /
 * 1068 / 1072 / 1073 voltages, currents, frequency mHz, 1066 9s state, 1069 meter power, 1070 control type,
 * 1071 Pmax_abs, 1074–1076 output powers, 1077 operation state, 1081 device state, 1082 power of the queried
 * device (= 1083 solar part + 1084 grid part), 1085 PWM out, 1087/1088 meter power 32-bit.
 *
 * Current consumption ([DeviceReading.loadPowerW]): 1082 where the device has it (ELWA-E ≥ 00203.00, ELWA 2,
 * THOR ≥ a0020303); else the output powers 1074+1075(+1076) (ELWA 2, THOR 9s); else the power setpoint 1000
 * (UNVERIFIED as a measurement: it's the commanded power).
 *
 * UNVERIFIED: port 502 and unit id 1 (neither is in the documents); the ELWA-E tempchip unit (taken as 0.1 °C like
 * the other models); firmware display formats (ELWA-E "00203.00", ELWA 2 "e…", THOR "a…" built from main and sub
 * version registers); temperature sensors that aren't connected may read 0 or another placeholder.
 */
class MyPvDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    enum class Model(val displayName: String) {
        ELWA_E("my-PV AC ELWA-E"),
        ELWA_2("my-PV AC ELWA 2"),
        THOR("my-PV AC•THOR"),
        THOR_9S("my-PV AC•THOR 9s"),
        OTHER("my-PV"),
    }

    private val fetcher = FieldFetcher(reader, unitId, maxGap = 4)
    private var model: Model? = null
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val r = Regs(loadStatic())
        val m = model!!
        return DeviceInfo(model = m.displayName, serial = r.text(1018, 8).orEmpty())
    }

    private suspend fun loadStatic(): Map<Int, Int> {
        fetcher.forget()
        val regs = fetcher.fetch(listOf(1003 to 1, 1013 to 4, 1016 to 13))
        val serial = Regs(regs).text(1018, 8)
        if (serial == null || !SERIAL.matches(serial) || regs[1003] == null) {
            throw IOException("Not a my-PV device (serial '$serial' at 1018)")
        }
        model = modelOf(serial)
        static = regs
        return regs
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) loadStatic()
        polls++
        val m = model ?: Model.OTHER
        val r = Regs(static.orEmpty() + fetcher.fetch(fields(m)))
        return parse(r, m)
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        private val SERIAL = Regex("\\d{16}")

        fun modelOf(serial: String): Model = when (serial.take(6)) {
            "160124", "160140", "160129", "160142" -> Model.ELWA_E
            "160150", "160151", "160152" -> Model.ELWA_2
            "200100", "200101", "200103" -> Model.THOR
            "200300" -> Model.THOR_9S
            else -> Model.OTHER
        }

        private fun range(a: Int, b: Int) = (a..b).map { it to 1 }

        /** Documented readable registers per model (write-only 1012 of the ELWA-E left out). */
        fun fields(m: Model): List<Pair<Int, Int>> = when (m) {
            Model.ELWA_E -> range(1000, 1011) + range(1013, 1028) + listOf(1069 to 1, 1070 to 1, 1071 to 1, 1081 to 1, 1082 to 1, 1087 to 2)
            Model.ELWA_2 -> range(1000, 1030) + listOf(1058 to 1, 1061 to 1, 1064 to 1, 1065 to 1) + range(1067, 1071) +
                listOf(1074 to 1, 1075 to 1) + range(1077, 1084) + listOf(1086 to 3)
            Model.THOR, Model.THOR_9S -> range(1000, 1032) + range(1041, 1085) + listOf(1087 to 2)
            Model.OTHER -> range(1000, 1028)
        }

        private val ELWA_STATES = mapOf(
            1 to "No control signal", 2 to "Heating", 3 to "Standby", 4 to "Boost heating", 5 to "Heat finished", 9 to "Setup",
            20 to "Legionella boost", 21 to "Device disabled", 22 to "Device blocked",
        )
        private val ELWA_E_ERRORS = mapOf(
            201 to "Overtemperature, fuse blown", 202 to "Overtemperature measured", 203 to "Overtemperature electronics",
            204 to "Hardware fault", 205 to "Temperature sensor fault", 209 to "Mainboard error",
        )
        private val ELWA_2_ERRORS = mapOf(
            201 to "Safety thermostat triggered", 202 to "Power stage overtemperature", 203 to "Power stage temperature probe fault",
            204 to "Hardware fault", 205 to "Temperature sensor fault", 209 to "Mainboard error",
        )

        private fun Regs.tenth(a: Int) = u16(a)?.let { it / 10.0 }

        fun power(r: Regs, m: Model): Int? {
            if (m != Model.OTHER) r.u16(1082)?.let { return it }
            val outs = when (m) {
                Model.ELWA_2 -> listOf(1074, 1075)
                Model.THOR_9S -> listOf(1074, 1075, 1076)
                else -> emptyList()
            }.map { r.u16(it) }
            if (outs.isNotEmpty() && outs.all { it != null }) return outs.sumOf { it!! }
            return r.u16(1000)
        }

        fun stateText(code: Int, m: Model): String = when (m) {
            Model.THOR, Model.THOR_9S -> when {
                code == 0 -> "Off"
                code in 1..8 -> "Starting"
                code == 9 -> "Operation"
                code >= 200 -> "Power stage error $code"
                else -> "State $code"
            }
            Model.ELWA_E -> ELWA_E_ERRORS[code]?.let { "Error: $it" } ?: ELWA_STATES[code] ?: "State $code"
            else -> ELWA_2_ERRORS[code]?.let { "Error: $it" } ?: ELWA_STATES[code] ?: "State $code"
        }

        fun status(r: Regs, m: Model, power: Int?): String? {
            val code = r.u16(1003) ?: return null
            if ((m == Model.THOR || m == Model.THOR_9S) && code == 9) return if ((power ?: 0) > 0) "Heating" else "Standby"
            return stateText(code, m)
        }

        fun firmware(r: Regs, m: Model): String? {
            val main = r.u16(1016) ?: return null
            val sub = r.u16(1028) ?: 0
            return when (m) {
                Model.ELWA_E -> String.format(Locale.ROOT, "%05d.%02d", main, sub)
                Model.ELWA_2 -> String.format(Locale.ROOT, "e%05d%02d", main, sub)
                Model.THOR, Model.THOR_9S -> String.format(Locale.ROOT, "a%05d%02d", main, sub)
                Model.OTHER -> "$main.$sub"
            }
        }

        fun phases(r: Regs, m: Model): List<PhaseReading> {
            val hz = r.u16(1064)?.div(1000.0) ?: 0.0
            return when (m) {
                Model.THOR, Model.THOR_9S -> listOf(Triple("L1", 1061, 1062), Triple("L2", 1067, 1068), Triple("L3", 1072, 1073)).mapNotNull { (n, u, i) ->
                    val v = r.u16(u)?.toDouble()?.takeIf { it > 0 } ?: return@mapNotNull null // AC•THOR replies 0 for L2/L3
                    PhaseReading(n, v, r.tenth(i), hz, null)
                }
                Model.ELWA_2 -> listOfNotNull(r.u16(1061)?.takeIf { it > 0 }?.let { PhaseReading("L1", it.toDouble(), null, hz, null) })
                else -> emptyList()
            }
        }

        fun parse(r: Regs, m: Model): DeviceReading {
            val p = power(r, m)
            return DeviceReading(
                loadPowerW = p?.coerceAtLeast(0),
                temperatureC = r.tenth(1001),
                status = status(r, m, p),
                phases = phases(r, m),
                details = details(r, m),
            )
        }

        private fun temp(r: Regs, a: Int) = fmt(r.tenth(a), "°C", 1)
        private fun w(r: Regs, a: Int) = fmt(r.u16(a), "W")

        fun details(r: Regs, m: Model): List<DetailSection> {
            val thor = m == Model.THOR || m == Model.THOR_9S
            val meter32 = r.s32(1087)
            return listOf(
                section(
                    "Heating",
                    "Status" to r.u16(1003)?.let { "${stateText(it, m)} ($it)" },
                    "Power of this device" to w(r, 1082),
                    "Solar part" to w(r, 1083),
                    "Grid part" to w(r, 1084),
                    "Power setpoint" to w(r, 1000),
                    "Output 1 power" to w(r, 1074),
                    "Output 2 power" to if (m == Model.ELWA_2) fmt(r.u16(1075), "W") else w(r, 1075),
                    "Output 3 power" to if (m == Model.THOR_9S) w(r, 1076) else null,
                    "Max. power possible (incl. slaves)" to w(r, 1071),
                    "Water temperature (Temp 1)" to temp(r, 1001),
                    "Target temperature" to temp(r, 1002),
                    "Boost / min. temperature" to if (m == Model.ELWA_E) fmt(r.u16(1006), "°C") else temp(r, 1006),
                    "Temp 2" to if (m == Model.ELWA_2 || thor) temp(r, 1030) else null,
                    "Temp 3" to if (thor) temp(r, 1031) else null,
                    "Temp 4" to if (thor) temp(r, 1032) else null,
                    "Controller temperature" to temp(r, 1015),
                    "Boost mode" to r.u16(1005)?.toString(),
                    "Boost time 1" to r.u16(1007)?.let { s -> r.u16(1008)?.let { e -> "$s–$e h" } },
                    "Boost time 2" to r.u16(1026)?.let { s -> r.u16(1027)?.let { e -> "$s–$e h" } },
                    "Meter power seen by device" to fmt(meter32 ?: r.s16(1069), "W"),
                    "Voltage L1" to fmt(r.u16(1061), "V"),
                    "Current L1" to if (thor) fmt(r.tenth(1062), "A", 1) else null,
                    "Output voltage" to if (thor) fmt(r.u16(1063), "V") else null,
                    "Frequency" to fmt(r.u16(1064)?.div(1000.0), "Hz", 2),
                    "AUX relay voltage" to if (m == Model.ELWA_2) fmt(r.u16(1067), "V") else null,
                    "AUX relay current" to if (m == Model.ELWA_2) fmt(r.u16(1068)?.div(10.0), "A", 1) else null,
                    "Relay status" to r.u16(1058)?.toString(),
                    "Load state" to if (thor) r.u16(1059)?.toString() else null,
                    "Load nominal power" to if (thor) w(r, 1060) else null,
                    "9s state" to if (m == Model.THOR_9S) r.u16(1066)?.toString() else null,
                    "PWM out" to if (thor) fmt(r.u16(1085), "%") else null,
                    "Operation state" to r.u16(1077)?.toString(),
                    "Operation mode" to r.u16(1065)?.toString(),
                    "Device state" to r.u16(1081)?.toString(),
                    "Control type" to r.u16(1070)?.toString(),
                ),
                section(
                    "Device",
                    "Model" to m.displayName,
                    "Serial number" to r.text(1018, 8),
                    "Firmware" to firmware(r, m),
                    "Power stage firmware" to r.u16(1017)?.toString(),
                    "Co-controller firmware" to if (m == Model.ELWA_2) r.u16(1086)?.toString() else null,
                    "Device number" to r.u16(1013)?.toString(),
                    if (m == Model.ELWA_E) "Fuse type" to fmt(r.u16(1014), "A") else "Max. power" to w(r, 1014),
                    "Power timeout" to fmt(r.u16(1004), "s"),
                ),
            )
        }
    }
}
