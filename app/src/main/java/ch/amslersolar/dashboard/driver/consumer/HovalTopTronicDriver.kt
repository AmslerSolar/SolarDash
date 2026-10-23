// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.roundToInt

/**
 * Hoval heat pumps with WFA automatic firing device (e.g. UltraSource, Belaria pro) behind a TopTronic E controller,
 * read through the TopTronic E Modbus gateway (TTE-GW). READ ONLY: this driver never writes a register.
 *
 * Specs (docs/specs/hoval/):
 * - "modbus-guideline_en.xlsx", sheet 7 "Heat generator/heat pump information values" — the curated list used here.
 *   Its note: these data points apply to the newer heat pumps with WFA, "not to heat pumps with an old automatic
 *   function device (ECR), e.g. Thermalia, Belaria twin".
 * - "toptronice-gateway-modbus-datapoints.xlsx" (V2-11-12, gateway SW ≥ V2.38) — type, decimals and units of
 *   every register; the registers used here are those of UnitName WEZ, UnitId 1 (the basic module of the first heat
 *   generator, CAN address 1). Further heat generators have their own register ranges and are not read.
 * - Function code 03 (read holding registers) for everything, as listed in the guideline.
 * - Scaling: "Decimal" = number of decimal places (263 with Decimal 1 → 26.3). U8 / LIST values are read as a whole
 *   16-bit register.
 * - 32-bit values (U32, S32) occupy two registers, "_high" first, i.e. big endian; the guideline marks them
 *   "U32 (big endian)". For S32 sensor values the guideline reads only the low register as S16 /10 (27494 buffer
 *   temperature, 31708 volume flow); done the same way here.
 * - "The SF2 / Actual temperature bottom sensor is not always installed and therefore may report a timeout":
 *   optional sensors are read one by one; a timeout or Modbus exception marks them missing until
 *   [RETRY_MISSING_POLLS].
 *
 * Identification: the data point list has no product or serial register. A Hoval WFA heat generator is recognised
 * by plausibility: heat generator status 18722 one of the documented codes, WFA status 18723 ≤ 99, modulation
 * 18726 ≤ 100 %, operating message 18737 0/1.
 *
 * Mapping to [DeviceReading]:
 * - [DeviceReading.loadPowerW]: "Actual electrical power" 25611 (S16, kW, 2 decimals → raw × 10 W).
 * - [DeviceReading.temperatureC]: outdoor temperature AF1 (1477).
 * - [DeviceReading.status]: from the WFA status 18723 (Heating with HP, DHW with HP, Defrosting, Active/Passive
 *   cooling, HP off → Standby, faults → Fault), else from the heat generator status 18722.
 * - today.loadKwh: null — only lifetime energy counters exist.
 *
 * Polling: the gateway relays every request over the CAN bus, so it is polled slowly: ≈ 50 registers in 8
 * requests, a full read only every [REFRESH_POLLS] polls (30 s at 5 s), the cached reading in between.
 *
 * UNVERIFIED: TCP port 502 and Modbus unit id 1 of the gateway (neither file states them); that the register
 * addresses are sent unchanged as protocol addresses (no address base is stated); 0x8000 as "no value" for S16
 * temperatures; 18724 (heat generator setpoint): data point list "Decimal 0", guideline "/10" — /10 is used.
 * 25612 heating power: both files give 0 decimals in kW, used as is. 31708 volume flow: guideline "/10" used.
 */
class HovalTopTronicDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val split = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()
    private var cached: DeviceReading? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(fetch(IDENT_BLOCKS, optional = false))
        if (!img.plausible()) throw IOException("No Hoval TopTronic E heat pump (WFA) found at unit id $unitId (implausible register values)")
        return DeviceInfo(model = "Hoval heat pump (TopTronic E, WFA)", serial = "")
    }

    override suspend fun read(): DeviceReading {
        cached?.let { if (polls % REFRESH_POLLS != 0) { polls++; return it } }
        if (polls % RETRY_MISSING_POLLS == 0) {
            split.clear()
            missing.clear()
        }
        val img = Image(fetch(BLOCKS, optional = false) + fetch(OPTIONAL, optional = true))
        if (!img.plausible()) throw IOException("Unexpected register values: not a Hoval WFA heat generator")
        val reading = parse(img).copy(details = details(img))
        cached = reading
        polls++
        return reading
    }

    /**
     * Reads blocks (start to count), falling back to single registers where the gateway rejects a block. For
     * [optional] sensors a timeout counts as "not installed" too.
     */
    private suspend fun fetch(blocks: List<Pair<Int, Int>>, optional: Boolean): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((start, count) in blocks) {
            if (count > 1 && start !in split) {
                try {
                    out.putAll(reader.read(unitId, start, count).values())
                    continue
                } catch (_: ModbusException) {
                    split += start
                }
            }
            for (a in start until start + count) {
                if (a in missing) continue
                try {
                    out.putAll(reader.read(unitId, a, 1).values())
                } catch (_: ModbusException) {
                    missing += a
                } catch (e: SocketTimeoutException) {
                    if (!optional) throw e
                    missing += a
                }
            }
        }
        return out
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    private fun parse(img: Image): DeviceReading = DeviceReading(
        loadPowerW = img.s16(25611)?.let { it * 10 },
        temperatureC = img.tenths(1477),
        status = img.status(),
        problems = img.problems(),
    )

    private fun details(img: Image): List<DetailSection> {
        val error = img.u16(1534)
        return listOf(
            section(
                "Heat pump",
                "WFA status" to enum(img.u16(18723), WFA_STATUS),
                "Heat generator status" to enum(img.u16(18722), GENERATOR_STATUS),
                "Operating message" to enum(img.u16(18737), mapOf(0 to "Off", 1 to "On")),
                "Operation choice" to enum(img.u16(1561), OPERATION_CHOICE),
                "Modulation" to fmt(img.u16(18726), "%"),
                "Electrical power" to fmt(img.s16(25611)?.let { it / 100.0 }, "kW", 2),
                "Heating power" to fmt(img.s16(25612), "kW"),
                "Current output heating" to fmt(img.u32(18731)?.let { it / 10.0 }, "kW", 1),
                "COP" to fmt(img.u16(27490)?.let { it / 10.0 }, decimals = 1),
                "Total energy efficiency" to fmt(img.u16(27467)?.let { it / 10.0 }, decimals = 1),
                "Volume flow" to fmt(img.tenths(31708), "l/min", 1),
                "Heat pump error" to when (error) {
                    null -> null
                    255 -> "No error"
                    else -> ERRORS[error]?.let { "$it ($error)" } ?: "$error"
                },
            ),
            section(
                "Temperatures",
                "Outdoor (AF1)" to fmt(img.tenths(1477), "°C", 1),
                "Flow" to fmt(img.tenths(18725), "°C", 1),
                "Flow setpoint" to fmt(img.tenths(18724), "°C", 1),
                "Return" to fmt(img.tenths(1535), "°C", 1),
                "Heating circuit 1 flow" to fmt(img.tenths(1513), "°C", 1),
                "Hot water top (SF)" to fmt(img.tenths(1500), "°C", 1),
                "Hot water bottom (SF2)" to fmt(img.tenths(27483), "°C", 1),
                "Hot water setpoint" to fmt(img.tenths(1499), "°C", 1),
                "Buffer (WPF)" to fmt(img.tenths(27494), "°C", 1),
            ),
            section(
                "Circuits",
                "Heating circuit 1" to enum(img.u16(1501), CIRCUIT_STATUS),
                "Hot water" to enum(img.u16(1504), DHW_STATUS),
            ),
            section(
                "Energy",
                "Electrical energy total" to fmt(img.u32(25613), "kWh"),
                "Heat heating total" to fmt(img.u32(27484), "kWh"),
                "Heat hot water total" to fmt(img.u32(27488), "kWh"),
                "Heat cooling total" to fmt(img.u32(27486), "kWh"),
                "COP total (calculated)" to totalCop(img),
                "Operating hours" to fmt(img.u32(1516), "h"),
                "Switching cycles" to img.u32(1518)?.toString(),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    /** (heating + hot water heat) / electrical energy; the totals are MWh with 3 decimals, i.e. kWh. */
    private fun totalCop(img: Image): String? {
        val elec = img.u32(25613)?.takeIf { it > 0 } ?: return null
        val heat = (img.u32(27484) ?: return null) + (img.u32(27488) ?: 0)
        return fmt(heat.toDouble() / elec, decimals = 2)
    }

    internal class Image(private val regs: Map<Int, Int>) {
        fun u16(a: Int): Int? = regs[a]
        fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()?.takeIf { it != Short.MIN_VALUE.toInt() }
        fun tenths(a: Int): Double? = s16(a)?.let { it / 10.0 }

        /** U32, high register first. */
        fun u32(a: Int): Long? {
            val hi = u16(a) ?: return null
            val lo = u16(a + 1) ?: return null
            return (hi.toLong() shl 16) or lo.toLong()
        }

        fun plausible(): Boolean {
            val status = u16(18722) ?: return false
            val wfa = u16(18723) ?: return false
            val modulation = u16(18726) ?: return false
            val message = u16(18737)
            return status in GENERATOR_STATUS && wfa <= 99 && modulation <= 100 && (message == null || message in 0..1)
        }

        /**
         * WFA status faults (11, 12, 49, 55, 99), else heat generator status 15 Alarm / 16 Fault; heating circuit 1
         * status 12 Fault; hot water status 5 Fault / 7 Warning; and the WFA error code 1534 (255 = no error). Error
         * codes are shown with their W/B/E name from the guideline; E codes count as faults, W and B codes as
         * warnings (the guideline gives no texts or severities, the letters are Hoval's message classes).
         */
        fun problems(): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            val wfa = u16(18723)
            WFA_FAULTS[wfa]?.let { out += DeviceProblem(DeviceProblem.Kind.FAULT, "$it (WFA status $wfa)") }
            if (out.isEmpty()) {
                when (val gen = u16(18722)) {
                    15 -> out += DeviceProblem(DeviceProblem.Kind.FAULT, "Heat generator alarm (status $gen)")
                    16 -> out += DeviceProblem(DeviceProblem.Kind.FAULT, "Heat generator fault (status $gen)")
                }
            }
            if (u16(1501) == 12) out += DeviceProblem(DeviceProblem.Kind.FAULT, "Heating circuit 1 fault (status 12)")
            when (u16(1504)) {
                5 -> out += DeviceProblem(DeviceProblem.Kind.FAULT, "Hot water fault (status 5)")
                7 -> out += DeviceProblem(DeviceProblem.Kind.WARNING, "Hot water warning (status 7)")
            }
            val code = u16(1534)
            if (code != null && code != 255 && code != 0) {
                val name = ERROR_CODES[code]
                out += when {
                    name == null -> DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump error code $code")
                    name.startsWith("E") -> DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump error $name (code $code)")
                    else -> DeviceProblem(DeviceProblem.Kind.WARNING, "Heat pump error $name (code $code)")
                }
            }
            return out
        }

        fun status(): String? {
            val wfa = u16(18723)
            when (wfa) {
                1 -> return "Heating"
                4 -> return "Hot water"
                8 -> return "Defrost"
                2 -> return "Cooling"
                9 -> return "Passive cooling"
                11, 12, 55, 49 -> return "Fault"
            }
            when (u16(18722)) {
                15 -> return "Alarm"
                16 -> return "Fault"
                1, 2 -> return "Heating"
                4, 5 -> return "Cooling"
                3, 17, 27 -> return "Blocked"
                0 -> return "Standby"
            }
            return when (wfa) {
                null -> null
                0 -> "Standby"
                3 -> "Blocked"
                else -> WFA_STATUS[wfa] ?: "Status $wfa"
            }
        }
    }

    internal companion object {
        const val REFRESH_POLLS = 6 // full read every 30 s at a 5 s poll interval
        const val RETRY_MISSING_POLLS = 120

        val IDENT_BLOCKS = listOf(18722 to 16)
        /** Start address to register count, all FC03. */
        val BLOCKS = listOf(
            1477 to 1, 1499 to 6, 1513 to 7, 1534 to 2, 1561 to 1,
            18722 to 16, 25611 to 4, 27484 to 7, 27467 to 1,
        )
        /** Sensors that may not be installed (and may time out): read one by one. */
        val OPTIONAL = listOf(27483 to 1, 27494 to 1, 31708 to 1)

        val GENERATOR_STATUS = mapOf(
            0 to "Switched off", 1 to "Heating operation", 2 to "Flow time heating operation", 3 to "Externally blocked",
            4 to "Cooling operation", 5 to "Flow time cooling operation", 15 to "Alarm", 16 to "Fault", 17 to "Blocked",
            21 to "WFmax switch-off", 22 to "WFsetp switch-off", 26 to "Bivalence deactivation", 27 to "DHW charging block",
            28 to "Minimum off time", 29 to "Minimum on time", 32 to "Supplemental operation", 33 to "Delay sequence H-Gen",
            34 to "Operation overtemperature", 42 to "Manual mode heating", 43 to "Manual mode hot water", 44 to "Manual mode cooling",
        )
        val WFA_STATUS = mapOf(
            0 to "HP off", 1 to "Heating with HP", 2 to "Active cooling", 3 to "Block (bivalence, DHW, etc.)",
            4 to "Hot water with HP", 5 to "HP frost protection", 6 to "HP temperature too low", 7 to "HP flow too high",
            8 to "Defrosting", 9 to "Passive cooling", 11 to "HP fault (sensor or switch)", 12 to "Low-pressure fault",
            16 to "Re-engage switching delay", 17 to "Power supply/energy gen. block", 18 to "Flow time primary",
            19 to "Follow-up time primary", 44 to "MOP", 49 to "Unsuccessful defrosting", 51 to "Flow time condenser pump",
            55 to "Inverter fault/Modbus communication", 72 to "Ground water frost protection", 73 to "Flow WQ/GW circuit",
            77 to "Limitation compressor", 96 to "Electrical power limit", 97 to "Preheat compressor oil", 98 to "Coldstart",
            99 to "Machine not configured",
        )
        /** WFA status values that are faults ("HP fault", "Low-pressure fault", …), with display texts. */
        val WFA_FAULTS = mapOf(
            11 to "Heat pump fault (sensor or switch)", 12 to "Low-pressure fault", 49 to "Unsuccessful defrosting",
            55 to "Inverter fault / Modbus communication", 99 to "Machine not configured",
        )

        /**
         * Every code of the guideline's 1534 list (sheet 7), named as there: W = code − 63, B = code − 127,
         * E = code − 191.
         */
        val ERROR_CODES: Map<Int, String> =
            listOf(74, 76, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 100, 101, 102, 103, 104, 106, 121, 122, 126)
                .associateWith { "W" + (it - 63).toString().padStart(2, '0') } +
                listOf(
                    128, 129, 130, 131, 132, 134, 135, 136, 137, 138, 139, 140, 142, 147, 148, 149, 150, 151, 152, 153, 159,
                    160, 161, 162, 163, 164, 171, 172, 173, 174, 175, 176, 177, 178, 181, 182, 183, 184, 185, 188, 189, 190,
                ).associateWith { "B" + (it - 127).toString().padStart(2, '0') } +
                listOf(
                    192, 193, 194, 195, 198, 199, 200, 202, 203, 204, 206, 207, 213, 216, 217, 218, 219, 220, 221, 222, 230,
                    231, 234, 235, 236, 247, 252, 253,
                ).associateWith { "E" + (it - 191).toString().padStart(2, '0') }

        val OPERATION_CHOICE = mapOf(0 to "Heat generator off", 1 to "Automatic", 4 to "Manual heating", 5 to "Manual cooling", 8 to "Manual DHW")
        val CIRCUIT_STATUS = mapOf(
            0 to "Switched off", 1 to "Normal heating", 2 to "Comfort heating", 3 to "Economy heating", 4 to "Frost operation",
            5 to "Forced acceptance", 6 to "Forced throttling", 7 to "Holiday", 8 to "Party", 9 to "Normal cooling",
            10 to "Comfort cooling", 11 to "Economy cooling", 12 to "Fault", 13 to "Manual operation", 14 to "Protection cooling",
            15 to "Party cooling", 16 to "Drying heating-up", 17 to "Drying stationary", 18 to "Drying cooling-down", 19 to "Drying end",
            22 to "Cooling external/constant request", 23 to "Heating external/constant request", 24 to "Absent heating",
            25 to "Absent cooling", 26 to "Smart Grid preferential heating", 27 to "Smart Grid preferential cooling",
        )
        val DHW_STATUS = mapOf(
            0 to "Switched off", 1 to "Normal charging", 2 to "Comfort charging", 3 to "Forced throttling", 4 to "Forced charging",
            5 to "Fault", 6 to "DHW draw-off", 7 to "Warning", 8 to "Reduced charging", 9 to "Frost operation",
            12 to "Smart Grid preferential", 13 to "Smart Grid forced acceptance",
        )
        val ERRORS = mapOf(
            74 to "W11", 76 to "W13", 80 to "W17", 81 to "W18", 82 to "W19", 83 to "W20", 84 to "W21", 85 to "W22", 86 to "W23",
            87 to "W24", 88 to "W25", 89 to "W26", 90 to "W27", 100 to "W37", 101 to "W38", 102 to "W39", 103 to "W40", 104 to "W41",
            106 to "W43", 121 to "W58", 122 to "W59", 126 to "W63",
        )
    }
}
