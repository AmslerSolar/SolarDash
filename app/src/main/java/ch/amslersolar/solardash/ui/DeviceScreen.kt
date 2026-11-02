// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import ch.amslersolar.solardash.i18n.DetailTexts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import java.util.Locale

/** Everything one device reports, from the plant's live polling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(device: DeviceConfig, status: DeviceStatus?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        val reading = status?.reading
        if (reading == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                if (status?.error == null) CircularProgressIndicator()
                else Text(stringResource(R.string.screens_cant_read_device, status.error), color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(deviceAddress(device), style = MaterialTheme.typography.bodySmall) }
            item { OverviewCard(reading) }
            reading.battery?.let { b ->
                item {
                    InfoCard(stringResource(R.string.screens_battery)) {
                        InfoRow(stringResource(R.string.screens_power), "${formatPower(b.powerW)} · ${batteryDirection(b.powerW)}")
                        b.socPercent?.let { InfoRow(stringResource(R.string.screens_soc), "$it %") }
                        b.voltageV?.let { InfoRow(stringResource(R.string.screens_voltage), "${formatNumber(it)} V") }
                        InfoRow(stringResource(R.string.screens_mode), DetailTexts.tr(b.mode))
                    }
                }
            }
            if (reading.pvStrings.isNotEmpty()) item {
                InfoCard(stringResource(R.string.screens_pv_strings)) {
                    reading.pvStrings.forEach {
                        val values = listOfNotNull("${formatNumber(it.voltageV)} V", "${formatNumber(it.currentA)} A", it.powerW?.let(::formatPower))
                        InfoRow(stringResource(R.string.screens_pv_string, it.index), values.joinToString(" · "))
                    }
                }
            }
            if (reading.phases.isNotEmpty()) item {
                InfoCard(stringResource(R.string.screens_phases)) {
                    reading.phases.forEach {
                        val parts = listOfNotNull(
                            "${formatNumber(it.voltageV)} V",
                            it.currentA?.let { a -> "${formatNumber(a)} A" },
                            it.powerW?.let(::formatPower),
                        )
                        InfoRow(it.name, parts.joinToString(" · "))
                    }
                    val hz = reading.phases.first().frequencyHz
                    if (hz > 0) InfoRow(stringResource(R.string.screens_frequency), "${formatNumber(hz, 2)} Hz")
                }
            }
            item { TodayCard(reading) }
            // Everything else the driver knows, as the device reports it.
            reading.details.filter { it.rows.isNotEmpty() }.forEach { section ->
                item { InfoCard(DetailTexts.tr(section.title)) { section.rows.forEach { (k, v) -> InfoRow(DetailTexts.tr(k), DetailTexts.tr(v)) } } }
            }
        }
    }
}

@Composable
private fun OverviewCard(r: DeviceReading) {
    InfoCard(stringResource(R.string.screens_now)) {
        r.status?.let { InfoRow(stringResource(R.string.screens_status), DetailTexts.tr(it)) }
        r.pvPowerW?.let { InfoRow(stringResource(R.string.screens_solar), formatPower(it)) }
        r.gridPowerW?.let { InfoRow(stringResource(R.string.screens_grid), "${formatPower(it)} · ${gridDirectionLabel(it)}") }
        r.loadPowerW?.let { InfoRow(stringResource(R.string.screens_load), formatPower(it)) }
        r.temperatureC?.let { InfoRow(stringResource(R.string.screens_temperature), "${formatNumber(it)} °C") }
    }
}

@Composable
private fun TodayCard(r: DeviceReading) {
    val t = r.today
    if (listOf(t.pvKwh, t.loadKwh, t.exportKwh, t.importKwh, r.lifetimePvKwh).all { it == null }) return
    InfoCard(stringResource(R.string.screens_today)) {
        t.pvKwh?.let { InfoRow(stringResource(R.string.screens_solar_yield), formatEnergy(it)) }
        t.loadKwh?.let { InfoRow(stringResource(R.string.screens_consumption), formatEnergy(it)) }
        t.exportKwh?.let { InfoRow(stringResource(R.string.screens_exported), formatEnergy(it)) }
        t.importKwh?.let { InfoRow(stringResource(R.string.screens_imported), formatEnergy(it)) }
        r.lifetimePvKwh?.let { InfoRow(stringResource(R.string.screens_lifetime_yield), String.format(Locale.ROOT, "%.0f kWh", it)) }
    }
}
