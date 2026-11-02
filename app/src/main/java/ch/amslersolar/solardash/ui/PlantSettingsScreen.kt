// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.Plant

/**
 * Editing a plant: its name (saved with the button; blank or unchanged names can't be saved) and whether it is an
 * off-grid plant (saved at once).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantSettingsScreen(plant: Plant, onRename: (String) -> Unit, onOffGrid: (Boolean) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(plant.id) { mutableStateOf(plant.name) }
    val trimmed = name.trim()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screens_edit_plant)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                name, { name = it },
                label = { Text(stringResource(R.string.forms_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = trimmed.isNotEmpty() && trimmed != plant.name, onClick = { onRename(trimmed); name = trimmed }) {
                Text(stringResource(R.string.forms_save))
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onOffGrid(!plant.offGrid) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.status_off_grid), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.screens_off_grid_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = plant.offGrid, onCheckedChange = onOffGrid)
            }
        }
    }
}
