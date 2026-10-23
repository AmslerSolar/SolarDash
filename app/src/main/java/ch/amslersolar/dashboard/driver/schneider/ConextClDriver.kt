// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Schneider Electric Conext CL 18 / 20 / 25 (NA and E), PV only, over SunSpec Modbus TCP with the shared
 * [SunSpecInverterDriver] ([MoreSunSpecProfiles.CONEXT_CL]: port 502, unit id 10 by default). Spec: "Modbus
 * Registers Map – Conext CL", 503-0243-01-01 rev 1.5 (docs/specs/schneider/); all reads are function code 03.
 *
 * Two things the map needs on top of plain SunSpec:
 * - Common model length: 0x9C43 says 65, but Common ends with a pad register (0x9C85) and model 103 starts at
 *   0x9C86, i.e. 66 registers. A chain walk with 65 would land on the pad register. When 0x9C43 reads 65 and 0x9C86
 *   holds 103, the length is passed on as 66.
 * - Resolution and energy: model 103 has W / VA / VAr with SF 2 (100 W steps) and no DC power (DC voltage and power
 *   are "NA"); WH_SF is listed as "1 (multiply by 1)", ambiguous. Solar is therefore the sum of the two MPPT inputs
 *   of model 160 (DCW_SF 0, 1 W), and yield comes from the vendor registers: 0x0802 lifetime and 0x0804 today
 *   (U32, high word first, kWh/10), 0x0806 yesterday, 0x081E operating hours. Unused vendor addresses read 0xFF.
 * - Model 160's input IDs are 161 / 162 in the map ("01 / 02 (example)"); strings are numbered 1, 2 here.
 * No grid meter: grid and home come from another device of the plant.
 */
class ConextClDriver(reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fixed = CommonLengthFix(reader)
    private val inner = SunSpecInverterDriver(fixed, unitId, MoreSunSpecProfiles.CONEXT_CL)
    private val fetcher = RegisterFetcher(reader, unitId)

    override suspend fun identify(): DeviceInfo = inner.identify()

    override suspend fun read(): DeviceReading {
        val base = inner.read()
        val vendor = try {
            Registers(fetcher.fetch(VENDOR_FIELDS), lowWordFirst = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        val strings = base.pvStrings.mapIndexed { i, s -> s.copy(index = i + 1) }
        val today = vendor?.let { energy(it, 0x0804) }
        return base.copy(
            pvStrings = strings,
            today = if (today != null) EnergyTotals(pvKwh = today) else base.today,
            lifetimePvKwh = vendor?.let { energy(it, 0x0802) } ?: base.lifetimePvKwh,
            details = base.details + listOfNotNull(vendor?.let(::vendorDetails)?.takeIf { it.rows.isNotEmpty() }),
        )
    }

    /** Passes 66 on as the Common model's length where the device says 65 but lays out 66 registers (see class doc). */
    internal class CommonLengthFix(private val raw: RegisterReader) : RegisterReader {
        private val padded = HashMap<Int, Boolean>()

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            val block = raw.read(unitId, register, count)
            if (COMMON_LENGTH !in register until register + count || block.u16(COMMON_LENGTH) != 65) return block
            val fix = padded.getOrPut(unitId) {
                try {
                    raw.read(unitId, FIRST_MODEL, 1).u16(FIRST_MODEL) == 103
                } catch (_: ModbusException) {
                    false
                }
            }
            if (!fix) return block
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = if (register + i == COMMON_LENGTH) 66 else block.u16(register + i)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    internal companion object {
        const val COMMON_LENGTH = 0x9C43
        const val FIRST_MODEL = 0x9C86
        val VENDOR_FIELDS = listOf(0x0802 to 2, 0x0804 to 2, 0x0806 to 2, 0x081E to 2)

        /** U32 kWh/10; 0xFFFFFFFF (unused) → null. */
        fun energy(img: Registers, a: Int): Double? = img.u32(a)?.takeIf { it != 0xFFFFFFFFL }?.div(10.0)

        fun vendorDetails(img: Registers): DetailSection = section(
            "Energy (Conext CL registers)",
            "Yield today" to fmt(energy(img, 0x0804), "kWh", 1),
            "Yield yesterday" to fmt(energy(img, 0x0806), "kWh", 1),
            "Yield total" to fmt(energy(img, 0x0802), "kWh", 1),
            "Operating hours" to fmt(img.u32(0x081E)?.takeIf { it != 0xFFFFFFFFL }, "h"),
        )
    }
}
