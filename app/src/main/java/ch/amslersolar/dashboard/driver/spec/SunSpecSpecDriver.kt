// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.sunspec.Model
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.driver.sunspec.SunSpecDevice
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver.Companion.METER_MODELS
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver.Meter
import ch.amslersolar.dashboard.driver.sunspec.SunSpecProfiles
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * A ModbusSpec device marked `sunspec: true`: its SunSpec models are read by walking the device's own chain
 * ([SunSpecDevice], wherever the device puts it and whatever models come first), and the file adds what SunSpec
 * doesn't say:
 * - identification (its `identify` checks, else the Common model);
 * - the meaning of the grid power sign (`positive` of its grid_power point; SunSpec doesn't define it);
 * - its vendor registers outside the chain, read as in any ModbusSpec file; their values fill in what the SunSpec
 *   models left empty and are listed with the details.
 * The file's points inside the chain aren't read at their fixed addresses: on another chain layout they don't fit.
 */
class SunSpecSpecDriver(
    private val spec: Spec,
    private val vocabulary: Vocabulary,
    private val tables: SpecTables,
    private val unitId: Int,
    private val model: String? = null,
) : DeviceDriver {
    private val chain = SunSpecDevice(tables.holding, unitId)
    private val importPositive = spec.points.firstOrNull { it.key == "grid_power" }?.positive != "export"
    private val inverter by lazy {
        SunSpecInverterDriver(tables.holding, unitId, SunSpecProfiles.GENERIC.copy(meterImportPositive = importPositive))
    }
    private var vendor: SpecDriver? = null
    private var vendorChecked = false

    override suspend fun identify(): DeviceInfo {
        if (spec.identify != null && (spec.identify.checks.isNotEmpty() || spec.identify.any.isNotEmpty())) {
            return SpecDriver(spec, vocabulary, tables, unitId, model = model).identify()
        }
        val common = chain.model(1) ?: throw IOException("SunSpec Common model (1) missing")
        val maker = common.text(2, 16)
        val name = common.text(18, 16)
        return DeviceInfo(
            model = listOf(maker.ifEmpty { spec.manufacturer }, name.ifEmpty { spec.name }).joinToString(" "),
            serial = common.text(50, 16),
        )
    }

    override suspend fun read(): DeviceReading {
        val sunspec = when (spec.kind) {
            "meter" -> {
                val (id, m) = chain.firstModel(*METER_MODELS) ?: throw IOException("No SunSpec meter model (201–214)")
                val meter = Meter(id, m)
                SunSpecInverterDriver.parseMeter(meter, importPositive)
                    .copy(details = listOf(SunSpecInverterDriver.meterDetails(meter, "Energy meter", importPositive)))
            }
            "battery" -> {
                val storage = optional { chain.model(124) }
                val bank = optional { chain.model(802) }
                val battery = SunSpecInverterDriver.battery(storage, bank) ?: throw IOException("No SunSpec battery model (124, 802)")
                DeviceReading(
                    battery = battery,
                    details = listOfNotNull(storage?.let(SunSpecInverterDriver::storageDetails), bank?.let(SunSpecInverterDriver::bankDetails)),
                )
            }
            else -> inverter.read()
        }
        val extra = vendorReading() ?: return sunspec
        return DeviceReading(
            pvPowerW = sunspec.pvPowerW ?: extra.pvPowerW,
            pvStrings = sunspec.pvStrings.ifEmpty { extra.pvStrings },
            battery = sunspec.battery ?: extra.battery,
            gridPowerW = sunspec.gridPowerW ?: extra.gridPowerW,
            phases = sunspec.phases.ifEmpty { extra.phases },
            loadPowerW = sunspec.loadPowerW ?: extra.loadPowerW,
            temperatureC = sunspec.temperatureC ?: extra.temperatureC,
            status = sunspec.status ?: extra.status,
            today = sunspec.today.takeIf { it.pvKwh != null } ?: extra.today,
            lifetimePvKwh = sunspec.lifetimePvKwh ?: extra.lifetimePvKwh,
            details = sunspec.details + extra.details,
            problems = (sunspec.problems + extra.problems).distinct(),
        )
    }

    /** The file's registers outside the chain (null: it has none, or none answered). */
    private suspend fun vendorReading(): DeviceReading? {
        if (!vendorChecked) {
            // The file gives the chain at its usual base (where its Common model sits); its chain points lie above it.
            val base = SunSpecDevice.BASES.firstOrNull { b -> spec.points.any { it.address in b until b + COMMON_LENGTH } } ?: 40000
            val outside = spec.points.filter { it.address !in base until base + CHAIN_WINDOW }
            vendor = outside.takeIf { it.isNotEmpty() }?.let { SpecDriver(spec.withPoints(it), vocabulary, tables, unitId, model = model) }
            vendorChecked = true
        }
        val v = vendor ?: return null
        return optional { v.read() }
    }

    private companion object {
        /** "SunS" and the Common model (1): 2 + 2 + 66 registers. */
        const val COMMON_LENGTH = 70
        /** A chain stays well within this many registers; vendor registers are elsewhere (Conext CL: 2050, 6184). */
        const val CHAIN_WINDOW = 10_000
    }

    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }
}
