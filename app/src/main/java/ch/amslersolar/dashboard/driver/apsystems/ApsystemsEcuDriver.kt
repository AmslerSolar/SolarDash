// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.apsystems

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.Model
import ch.amslersolar.dashboard.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.driver.sunspec.SunSpecDevice
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.roundToInt

/**
 * One APsystems microinverter behind an ECU-R / ECU-C, over the ECU's SunSpec Modbus TCP server (port 502, unit id
 * = the microinverter's Modbus address set in EMA Manager; one device per microinverter). Spec: "SunSpec Modbus
 * Rev 3.3" (docs/specs/apsystems/); profile and addressing in [MoreSunSpecProfiles.APSYSTEMS].
 *
 * The SunSpec part (Common 1, inverter 101/103 + float 111/113, controls 123) is read by [SunSpecInverterDriver]:
 * AC power, lifetime energy (WH), state, cabinet temperature, phase voltage (single-phase models; the three-phase
 * models implement only the line-to-line voltages). The inverter models leave DC power unimplemented, so the PV
 * inputs come from the vendor "DC Data (float)" model 114 (length 48): DCV1–8 at offsets 2–17, DCA1–8 at 18–33,
 * DCW1–8 at 34–49 (float32 each; channels 1–2 "supported", 3–8 "depends on model"). Solar = sum of DCW.
 * - Model 114 is read at the spec's fixed address ([DC_MODEL_ADDRESS] = 40212, data 40214–40261), not through the
 *   chain: in the spec's table the chain ends after model 123 (40209), so a chain walk doesn't reach it. A register
 *   dump of an ECU-R (firmware 1.3.23, published with the ha-sunspec2 project) shows the same: end marker at
 *   40210, header 40212 reading 0, DC values at 40214 ff. Only if that read fails is model 114 looked for in the
 *   chain.
 * - No grid meter and no battery: grid and home come from another device of the plant.
 * - UNVERIFIED: what an unimplemented float reads as (NaN as in SunSpec, or 0; both are skipped). The same dump
 *   suggests WH counts today's energy (reset each night) rather than lifetime; the spec only says "AC Energy".
 */
class ApsystemsEcuDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val inner = SunSpecInverterDriver(reader, unitId, MoreSunSpecProfiles.APSYSTEMS)
    private val device = SunSpecDevice(reader, unitId)

    override suspend fun identify(): DeviceInfo = inner.identify()

    override suspend fun read(): DeviceReading {
        val base = inner.read()
        val dc = dcModel() ?: return base
        val inputs = inputs(dc)
        if (inputs.isEmpty()) return base
        return base.copy(
            pvPowerW = inputs.sumOf { it.w ?: 0.0 }.roundToInt(),
            pvStrings = inputs.filter { (it.v ?: 0.0) > 0 || (it.w ?: 0.0) > 0 }
                .map { PvString(it.index, it.v ?: 0.0, it.a ?: 0.0, it.w?.roundToInt()) },
            details = base.details + details(inputs),
        )
    }

    /** Model 114 at its fixed address, else from the chain; null if neither answers. */
    private suspend fun dcModel(): Model? =
        orNull { Model(reader.read(unitId, DC_MODEL_ADDRESS + 2, DC_LENGTH), DC_MODEL_ADDRESS, DC_LENGTH) }
            ?: orNull { device.model(DC_MODEL) }

    private suspend fun <T> orNull(read: suspend () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    internal data class Input(val index: Int, val v: Double?, val a: Double?, val w: Double?)

    internal companion object {
        const val DC_MODEL = 114
        /** Model 114's ID register in the spec's table (its values start 2 registers later). */
        const val DC_MODEL_ADDRESS = 40212
        private const val DC_LENGTH = 48
        private const val CHANNELS = 8

        /** Channels with at least one implemented value. */
        fun inputs(m: Model): List<Input> = (0 until CHANNELS).mapNotNull { k ->
            val v = m.f32(2 + 2 * k)?.toDouble()
            val a = m.f32(18 + 2 * k)?.toDouble()
            val w = m.f32(34 + 2 * k)?.toDouble()
            if (v == null && a == null && w == null) null else Input(k + 1, v, a, w)
        }

        fun details(inputs: List<Input>) = DetailSection(
            "DC inputs (APsystems model 114)",
            inputs.flatMap { i ->
                listOfNotNull(
                    fmt(i.w, "W", 1)?.let { "DC${i.index} power" to it },
                    fmt(i.v, "V", 1)?.let { "DC${i.index} voltage" to it },
                    fmt(i.a, "A", 2)?.let { "DC${i.index} current" to it },
                )
            },
        )
    }
}
