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
 * - No grid meter and no battery: grid and home come from another device of the plant.
 * - UNVERIFIED: what an unimplemented float reads as (NaN as in SunSpec, or 0; both are skipped), and the two
 *   registers 40210–40211 between model 123 (ends 40209) and model 114 (40212) in the spec's table; the chain
 *   walk follows whatever headers the ECU reports.
 */
class ApsystemsEcuDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val inner = SunSpecInverterDriver(reader, unitId, MoreSunSpecProfiles.APSYSTEMS)
    private val device = SunSpecDevice(reader, unitId)

    override suspend fun identify(): DeviceInfo = inner.identify()

    override suspend fun read(): DeviceReading {
        val base = inner.read()
        val dc = try {
            device.model(DC_MODEL)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } ?: return base
        val inputs = inputs(dc)
        if (inputs.isEmpty()) return base
        return base.copy(
            pvPowerW = inputs.sumOf { it.w ?: 0.0 }.roundToInt(),
            pvStrings = inputs.filter { (it.v ?: 0.0) > 0 || (it.w ?: 0.0) > 0 }
                .map { PvString(it.index, it.v ?: 0.0, it.a ?: 0.0, it.w?.roundToInt()) },
            details = base.details + details(inputs),
        )
    }

    internal data class Input(val index: Int, val v: Double?, val a: Double?, val w: Double?)

    internal companion object {
        const val DC_MODEL = 114
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
