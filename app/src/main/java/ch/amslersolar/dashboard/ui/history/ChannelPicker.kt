// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui.history

import ch.amslersolar.dashboard.i18n.channelName
import ch.amslersolar.dashboard.i18n.DetailTexts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.model.Channels
import ch.amslersolar.dashboard.model.DeviceConfig

/**
 * Picks recorded channels of the plant's devices for the history chart, grouped like the device detail view:
 * live values first, then each detail section. Channels already on the chart are shown checked and disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelPicker(
    devices: List<DeviceConfig>,
    added: Set<Pair<String, String>>,
    catalog: suspend (String) -> List<Triple<String, String, String>>,
    onAdd: (List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    var deviceId by remember { mutableStateOf(devices.firstOrNull()?.id) }
    var channels by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    val free = MAX_CHANNELS - added.size

    LaunchedEffect(deviceId) {
        channels = null
        channels = deviceId?.let { catalog(it) }.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { d ->
                        FilterChip(selected = d.id == deviceId, onClick = { deviceId = d.id }, label = { Text(d.name) })
                    }
                }
                OutlinedTextField(
                    search, { search = it },
                    label = { Text(stringResource(R.string.history_picker_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    pluralStringResource(R.plurals.history_picker_status, selection.size, selection.size, free, MAX_CHANNELS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = channels
                when {
                    list == null -> Text(stringResource(R.string.history_picker_loading))
                    list.isEmpty() -> Text(stringResource(R.string.history_picker_empty))
                    else -> {
                        val query = search.trim().lowercase()
                        val sections = list
                            .filter { (key, label, _) -> query.isEmpty() || label.lowercase().contains(query) || channelName(key, label).lowercase().contains(query) || key.lowercase().contains(query) }
                            .groupBy { Channels.section(it.first) }
                            .entries.sortedBy { if (it.key == LIVE_SECTION) 0 else 1 } // stable: detail sections keep their order
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            sections.forEach { (section, rows) ->
                                item(key = "s:$section") {
                                    Text(
                                        if (section == LIVE_SECTION) stringResource(R.string.history_section_live) else DetailTexts.tr(section),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    )
                                }
                                items(rows, key = { it.first }) { (key, label, unit) ->
                                    val id = deviceId!! to key
                                    val already = id in added
                                    val checked = already || id in selection
                                    val enabled = !already && (id in selection || selection.size < free)
                                    val shownUnit = unit.takeIf { it.isNotEmpty() }?.let { Channels.displayUnit(it).first }
                                    Row(
                                        Modifier.fillMaxWidth().clickable(enabled = enabled) {
                                            selection = if (id in selection) selection - id else selection + id
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                                        Text(
                                            channelName(key, label) + (shownUnit?.let { " ($it)" } ?: ""),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (enabled || already) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selection.isNotEmpty(), onClick = { onAdd(selection) }) { Text(stringResource(R.string.history_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) } },
    )
}


/** Section name [Channels.section] gives plant-level live values (device sections are driver data, shown as is). */
private val LIVE_SECTION = Channels.section("")
