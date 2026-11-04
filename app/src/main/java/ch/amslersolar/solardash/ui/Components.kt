// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R

@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfirmRemoveDialog(
    name: String,
    detail: String,
    onConfirm: (deleteData: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Label of an "also delete the recorded data" checkbox (unchecked by default); none when null. */
    deleteDataOption: String? = null,
) {
    var deleteData by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forms_remove_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail)
                if (deleteDataOption != null) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(deleteData, role = Role.Checkbox) { deleteData = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteData, onCheckedChange = null)
                        Text(deleteDataOption, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteData) }) { Text(stringResource(R.string.forms_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.forms_cancel)) } },
    )
}

/** Removing a device: its recorded data is kept when its serial is known (it comes back when added again). */
@Composable
fun ConfirmRemoveDeviceDialog(device: ch.amslersolar.solardash.model.DeviceConfig, onConfirm: (deleteData: Boolean) -> Unit, onDismiss: () -> Unit) {
    ConfirmRemoveDialog(
        name = device.name,
        detail = if (device.serial.isNotBlank()) {
            stringResource(R.string.screens_remove_keeps_data, device.serial)
        } else {
            stringResource(R.string.screens_remove_deletes_data)
        },
        deleteDataOption = if (device.serial.isNotBlank()) stringResource(R.string.screens_remove_delete_data) else null,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Asks for a new name, prefilled with the current one. Blank or unchanged names can't be saved; spaces are trimmed. */
@Composable
fun RenameDialog(title: String, current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.forms_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed != current, onClick = { onRename(trimmed) }) { Text(stringResource(R.string.forms_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.forms_cancel)) } },
    )
}
