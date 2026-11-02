// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.sunspec

/** Per-make SunSpec settings; see [SunSpecInverterDriver]. Fronius has its own driver class (separate meter unit). */
object SunSpecProfiles {
    /**
     * SolarEdge: inverter and up to three meters in one model chain on unit id 1 (port 1502). The meter sign is
     * commonly reported as + = export (feed-in) for a meter at the grid connection; the SolarEdge technical note
     * couldn't be downloaded here, so this is unverified, and the device form can invert it.
     */
    val SOLAREDGE = SunSpecInverterDriver.Profile(maker = "SolarEdge", meterUnitId = null, meterImportPositive = false)

    /** Any other SunSpec device: meters in its own chain, + = drawing from the grid (as Fronius documents it). */
    val GENERIC = SunSpecInverterDriver.Profile(maker = null, meterUnitId = null, meterImportPositive = true)
}
