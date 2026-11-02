// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import java.util.Locale
import kotlin.math.abs

fun formatPower(watts: Int): String =
    if (abs(watts) >= 1000) String.format(Locale.ROOT, "%.2f kW", watts / 1000.0) else "$watts W"

fun formatEnergy(kwh: Double?): String = kwh?.let { String.format(Locale.ROOT, "%.1f kWh", it) } ?: "–"

fun formatNumber(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%.${decimals}f", value)
