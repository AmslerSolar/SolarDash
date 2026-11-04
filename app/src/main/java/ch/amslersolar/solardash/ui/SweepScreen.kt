// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.driver.modbus.RegisterSweep
import ch.amslersolar.solardash.model.DeviceConfig

/** The register sweep of a configured device, from its detail screen (see [SweepSection]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SweepScreen(
    device: DeviceConfig,
    onSweep: suspend (device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, progress: () -> Unit, onSaved: (String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forms_sweep_title_device, device.name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 600.dp)) {
            SweepSection(enabled = true, onSweep = { sweeps, progress, onSaved -> onSweep(device, sweeps, progress, onSaved) }, folded = false)
        }
    }
}
