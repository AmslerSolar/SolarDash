// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.DeviceKind
import ch.amslersolar.solardash.model.HistoryPeriod
import ch.amslersolar.solardash.model.HistoryRange
import ch.amslersolar.solardash.model.MeterRole

/** Localized names for model values (the model keeps English names for logs and tests). */
@Composable
fun deviceKindName(kind: DeviceKind): String = stringResource(
    when (kind) {
        DeviceKind.INVERTER -> R.string.kind_inverters
        DeviceKind.BATTERY -> R.string.kind_batteries
        DeviceKind.METER -> R.string.kind_meters
        DeviceKind.CONSUMER -> R.string.kind_consumers
    },
)

@Composable
fun meterRoleName(role: MeterRole): String = stringResource(
    when (role) {
        MeterRole.GRID -> R.string.meter_role_grid
        MeterRole.PV -> R.string.meter_role_pv
        MeterRole.CONSUMER -> R.string.meter_role_consumer
    },
)

@Composable
fun historyPeriodName(period: HistoryPeriod): String = stringResource(
    when (period) {
        HistoryPeriod.DAY -> R.string.period_day
        HistoryPeriod.WEEK -> R.string.period_week
        HistoryPeriod.MONTH -> R.string.period_month
        HistoryPeriod.YEAR -> R.string.period_year
        HistoryPeriod.LIFETIME -> R.string.period_lifetime
    },
)

/** The range heading: dates come formatted in the phone's language; "all time" is translated. */
@Composable
fun historyRangeLabel(range: HistoryRange): String =
    if (range.period == HistoryPeriod.LIFETIME) stringResource(R.string.range_all_time) else range.label()
