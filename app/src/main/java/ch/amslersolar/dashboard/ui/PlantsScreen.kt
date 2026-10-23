// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.dashboard.BuildConfig
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.model.Plant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantsScreen(
    plants: List<Plant>?,
    onOpen: (Plant) -> Unit,
    onAdd: (name: String) -> Unit,
    onDelete: (Plant, deleteDeviceData: Boolean) -> Unit,
    onRename: (Plant, String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Plant?>(null) }
    var renaming by remember { mutableStateOf<Plant?>(null) }
    var about by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forms_plants_title)) },
                actions = {
                    IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.forms_about)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.forms_add_plant)) }
        },
    ) { padding ->
        when {
            plants == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            plants.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                Text(stringResource(R.string.forms_plants_empty))
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(plants, key = { it.id }) { plant ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(plant) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SolarPower, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                plant.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                            )
                            IconButton(onClick = { renaming = plant }) {
                                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.forms_rename_item, plant.name))
                            }
                            IconButton(onClick = { pendingDelete = plant }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.forms_remove_item, plant.name))
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddPlantDialog(onAdd = { onAdd(it); adding = false }, onDismiss = { adding = false })

    renaming?.let { plant ->
        RenameDialog(stringResource(R.string.forms_rename_plant), plant.name, onRename = { onRename(plant, it); renaming = null }, onDismiss = { renaming = null })
    }

    if (about) AboutDialog(onDismiss = { about = false })
    pendingDelete?.let { plant ->
        ConfirmRemoveDialog(
            name = plant.name,
            detail = stringResource(R.string.forms_remove_plant_detail),
            deleteDataOption = stringResource(R.string.forms_remove_plant_delete_data),
            onConfirm = { deleteData -> onDelete(plant, deleteData); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun AddPlantDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forms_new_plant)) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.forms_name)) }, placeholder = { Text(stringResource(R.string.forms_plant_name_placeholder)) }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name.trim()) }) { Text(stringResource(R.string.forms_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.forms_cancel)) } },
    )
}

/** App name, version and build time, so it's clear which build a tablet runs. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.forms_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                Text(stringResource(R.string.forms_about_built, BuildConfig.BUILD_TIME))
                Text("Copyright (C) 2026 Amsler Solar GmbH", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.forms_about_license), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.forms_about_close)) } },
    )
}
