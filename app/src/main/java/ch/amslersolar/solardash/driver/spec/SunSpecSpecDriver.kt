// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.sunspec.Model
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.driver.sunspec.SunSpecDevice
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver.Companion.METER_MODELS
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver.Meter
import ch.amslersolar.solardash.driver.sunspec.SunSpecProfiles
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceReading
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
    /**
     * A make's SunSpec settings (see [SpecDevices.SUNSPEC_PROFILES]): the manufacturer identify() requires, a meter
     * under its own unit id, the meter's sign. Null: any make, meters in the chain, the sign from the file.
     */
    private val profile: SunSpecInverterDriver.Profile? = null,
    /** The file's hooks, applied to its registers beside the chain (see [SpecDevices.hooks]). */
    private val hooks: SpecHooks = SpecHooks.NONE,
    /**
     * File points read at their fixed addresses although they lie in the chain's address range: a vendor model the
     * device puts after the chain's end marker, where a walk doesn't reach it (APsystems model 114).
     */
    private val fixed: (Spec.Point) -> Boolean = { false },
) : DeviceDriver {
    private val chain = SunSpecDevice(tables.holding, unitId)
    private val importPositive = profile?.meterImportPositive ?: (spec.points.firstOrNull { it.key == "grid_power" }?.positive != "export")
    private val inverter by lazy {
        SunSpecInverterDriver(tables.holding, unitId, profile ?: SunSpecProfiles.GENERIC.copy(meterImportPositive = importPositive))
    }
    private var vendor: SpecDriver? = null
    private var vendorChecked = false

    override suspend fun identify(): DeviceInfo {
        // A make's profile: its Common model must name the make (the inverter model and firmware as model text).
        if (profile != null) return inverter.identify()
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
        // PV inputs from the maker's registers are more specific than the inverter model's DC power; the maker's
        // energy counters win over the SunSpec ones (whose scale is often ambiguous, e.g. Conext CL's WH_SF).
        val vendorInputs = extra.pvStrings.isNotEmpty()
        return DeviceReading(
            pvPowerW = if (vendorInputs) extra.pvPowerW ?: sunspec.pvPowerW else sunspec.pvPowerW ?: extra.pvPowerW,
            pvStrings = if (vendorInputs) extra.pvStrings else sunspec.pvStrings,
            battery = sunspec.battery ?: extra.battery,
            gridPowerW = sunspec.gridPowerW ?: extra.gridPowerW,
            phases = sunspec.phases.ifEmpty { extra.phases },
            loadPowerW = sunspec.loadPowerW ?: extra.loadPowerW,
            temperatureC = sunspec.temperatureC ?: extra.temperatureC,
            status = sunspec.status ?: extra.status,
            today = extra.today.takeIf { it.pvKwh != null } ?: sunspec.today,
            lifetimePvKwh = extra.lifetimePvKwh ?: sunspec.lifetimePvKwh,
            details = sunspec.details + extra.details,
            problems = (sunspec.problems + extra.problems).distinct(),
        )
    }

    /** The file's registers outside the chain (null: it has none, or none answered). */
    private suspend fun vendorReading(): DeviceReading? {
        if (!vendorChecked) {
            // The file gives the chain at its usual base (where its Common model sits); its chain points lie above it.
            val base = SunSpecDevice.BASES.firstOrNull { b -> spec.points.any { it.address in b until b + COMMON_LENGTH } } ?: 40000
            val outside = spec.points.filter { it.address !in base until base + CHAIN_WINDOW || fixed(it) }
            vendor = outside.takeIf { it.isNotEmpty() }?.let { SpecDriver(spec.withPoints(it), vocabulary, tables, unitId, hooks, model = model) }
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
