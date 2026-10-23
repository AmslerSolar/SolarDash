// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.goodwe

import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class GoodWeProblemsTest {
    /** A register block of [count] registers from [start], zero except [values]. */
    private fun block(start: Int, count: Int, vararg values: Pair<Int, Int>): RegisterBlock {
        val data = ByteArray(count * 2)
        for ((a, v) in values) {
            data[2 * (a - start)] = (v shr 8).toByte()
            data[2 * (a - start) + 1] = v.toByte()
        }
        return RegisterBlock(start, data)
    }

    private fun running(vararg values: Pair<Int, Int>) =
        block(GoodWeEtDriver.RUNNING_DATA, GoodWeEtDriver.RUNNING_DATA_COUNT, 35136 to 1, 35187 to 1, *values)

    private fun bms(vararg values: Pair<Int, Int>) = block(GoodWeEtDriver.BMS_DATA, GoodWeEtDriver.BMS_DATA_COUNT, *values)

    @Test
    fun onGridWithoutErrorsHasNoProblems() {
        assertEquals(emptyList<DeviceProblem>(), GoodWeEtDriver.problems(running(), bms()))
        // Waiting (night) with the grid disconnected isn't a problem either.
        assertEquals(emptyList<DeviceProblem>(), GoodWeEtDriver.problems(running(35187 to 0, 35136 to 0), null))
    }

    @Test
    fun errorBitsWorkModeWarningAndBmsCodesAreReported() {
        // 35189 high word bit 18 (isolation), low word bit 9 (utility loss) and bit 2 (TBD); off-grid mode.
        val r = running(35189 to 0x0004, 35190 to 0x0204, 35187 to 2, 35185 to 0x0012)
        assertEquals(
            listOf(
                DeviceProblem(Kind.FAULT, "Isolation failure (error bit 18)"),
                DeviceProblem(Kind.MAINS_LOST, "Utility loss (error bit 9)"),
                DeviceProblem(Kind.FAULT, "Error (error bit 2)"),
                DeviceProblem(Kind.BACKUP_MODE, "Off-grid mode (work mode 2)"),
                DeviceProblem(Kind.WARNING, "Warning code 0x0012"),
                DeviceProblem(Kind.FAULT, "BMS error code 0x00010020"),
                DeviceProblem(Kind.WARNING, "BMS warning code 0x00000008"),
            ),
            GoodWeEtDriver.problems(r, bms(37006 to 0x0020, 37012 to 0x0001, 37010 to 0x0008)),
        )
        // Off-grid and fault mode without error bits.
        assertEquals(
            listOf(DeviceProblem(Kind.BACKUP_MODE, "Off-grid mode (work mode 2)")),
            GoodWeEtDriver.problems(running(35187 to 2), null),
        )
        assertEquals(
            listOf(DeviceProblem(Kind.FAULT, "Grid fault (grid mode 2)")),
            GoodWeEtDriver.problems(running(35187 to 3, 35136 to 2), null),
        )
        assertEquals(
            listOf(DeviceProblem(Kind.FAULT, "Fault mode (work mode 3)")),
            GoodWeEtDriver.problems(running(35187 to 3), null),
        )
    }
}
