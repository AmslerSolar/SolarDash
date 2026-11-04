// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.model.DeviceReading
import java.util.Locale

/**
 * Victron GX system service (unit 100) register images, from the hand-written driver's tests: what that driver read
 * on them is recorded in `src/test/resources/golden/VictronGoldenTest/old-drivers.txt`.
 */
internal object VictronImages {
    /** A register image; reads touching an address not in it fail with [code] (2 = illegal address, 0x0B = gateway). */
    class FakeGx(private val code: Int = 2) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()

        /** A current Venus OS: every system register, reserved ones included, answers (0 unless set). */
        fun fullRange() = apply { for (a in (800..937) + (5400..5407)) regs[a] = 0 }

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun s32(a: Int, v: Int) = apply { regs[a] = (v ushr 16) and 0xFFFF; regs[a + 1] = v and 0xFFFF }
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }
        fun remove(vararg a: Int) = apply { a.forEach { regs.remove(it) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            check(unitId == 100) { "unit $unitId" }
            check(count in 1..125) { "request of $count registers" }
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(code)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    fun identity(fake: FakeGx) = fake.text(800, 6, "c0619ab1f2e3").u16(834, 0x0331).u16(835, 0)

    /**
     * Three-phase ESS: AC-coupled PV 1.5 kW on the output (L1) + 0.9 kW on the input (L2), 2 kW DC-coupled; battery
     * charging 1.2 kW; loads 1.0 kW; exporting 2.2 kW over L1–L3.
     */
    fun threePhase() = identity(FakeGx().fullRange())
        .s32(884, 1_500).s32(892, 900).u16(850, 2_000).u16(851, 385)
        .s32(902, 400).s32(904, 300).s32(906, 300)
        .s32(908, -1_000).s32(910, -700).s32(912, -500)
        .u16(808, 1_500).u16(812, 900).u16(817, 400).u16(818, 300).u16(819, 300)
        .u16(820, -1_000).u16(821, -700).u16(822, -500)
        .u16(840, 532).u16(841, 225).u16(842, 1_200).u16(843, 67).u16(844, 1).u16(845, 123).u16(846, 0)
        .u16(826, 1).u16(930, 3)
        .u16(5400, 1).u16(5402, 1).u16(5406, 1).u16(5407, 80)

    /** VE.Bus fault, no AC input, and a Dynamic ESS error while Dynamic ESS is active. */
    fun faults() = threePhase().u16(930, 2).u16(826, 240).u16(5404, 4)

    /** Inverting from the battery with the grid connected, and a Dynamic ESS error while it's off: no problem. */
    fun inverting() = threePhase().u16(930, 9).u16(5400, 0).u16(5404, 3)

    /**
     * Single phase, older firmware without the 32-bit registers (868–918), 930–937 and the reserved registers; L2/L3
     * and everything else the installation lacks are rejected with a gateway exception. DC PV only, battery
     * discharging, importing 300 W.
     */
    fun singlePhaseOldFirmware() = identity(FakeGx(code = 0x0B))
        .u16(806, 0).u16(807, 0)
        .u16(808, 0).u16(811, 0).u16(817, 1_100).u16(820, 300).u16(826, 1)
        .u16(840, 262).u16(841, (-300).toShort().toInt()).u16(842, -800).u16(843, 45).u16(844, 2)
        .u16(850, 0)

    /** Current firmware, but no battery monitor (battery registers not available) and nothing else measured. */
    fun missingValues() = identity(FakeGx().fullRange()).remove(840, 841, 842, 843, 844, 845, 846)
        .u16(851, 0x8000).u16(5402, 0)
        .s32(908, Int.MIN_VALUE).u16(820, 0x8000).u16(817, 0xFFFF).s32(902, -1)

    val CASES: List<Pair<String, () -> FakeGx>> = listOf(
        "threePhase" to ::threePhase,
        "faults" to ::faults,
        "inverting" to ::inverting,
        "singlePhaseOldFirmware" to ::singlePhaseOldFirmware,
        "missingValues" to ::missingValues,
    )

    /** What has to match: the energy flow, the battery, the status and the kinds of problems. */
    fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent, it.voltageV?.let { v -> "%.1f".format(Locale.ROOT, v) }) },
        "phases" to r.phases.size,
        "status" to r.status,
        "problemKinds" to r.problems.map { it.kind }.sorted(),
    ).joinToString("\n")
}
