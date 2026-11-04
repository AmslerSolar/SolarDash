// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.discovery.FoundDevice
import ch.amslersolar.solardash.discovery.ScanEvent
import ch.amslersolar.solardash.driver.spec.SpecDevices
import ch.amslersolar.solardash.model.DeviceConfig
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Searches the network as soon as it opens and adds every recognised device that isn't configured yet the moment it
 * is identified. Devices needing a password the search couldn't work out are listed and left to the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverDevicesScreen(
    onScan: () -> Flow<ScanEvent>,
    onAdd: (DeviceConfig) -> Unit,
    onAddByHand: () -> Unit,
    onBack: () -> Unit,
) {
    // Kept across searches: what was added once is configured, so the next search only reports it as such.
    val added = remember { mutableStateListOf<FoundDevice>() }
    val needsPassword = remember { mutableStateListOf<FoundDevice>() }
    var progress by remember { mutableStateOf<ScanEvent.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        scanning = true
        needsPassword.clear()
        error = null
        progress = null
        onScan().collect { e ->
            when (e) {
                is ScanEvent.Progress -> progress = e
                is ScanEvent.Found -> {
                    val d = e.device
                    val type = d.type
                    if (type != null && d.alreadyIn == null && added.none { it.key == d.key }) {
                        needsPassword.removeAll { it.key == d.key }
                        if (type.needsPassword && d.password.isEmpty()) needsPassword += d
                        else { onAdd(toConfig(d)); added += d }
                    }
                }
                is ScanEvent.Failed -> error = e.message
            }
        }
        scanning = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screens_discover_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scanning) {
                val p = progress
                Text(
                    if (p == null) stringResource(R.string.screens_discover_searching)
                    else scanProgressText(p),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { p?.let { it.done.toFloat() / it.total.coerceAtLeast(1) } ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (added.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.screens_discover_added, added.size, added.size), style = MaterialTheme.typography.titleMedium)
            } else if (!scanning && needsPassword.isEmpty()) {
                Text(stringResource(R.string.screens_discover_none))
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(added + needsPassword, key = { it.key }) { d ->
                    val ok = d in added
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Lock, contentDescription = null,
                                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                            Column {
                                Text(d.model, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        d.type?.let { SpecDevices.typeName(it, d.specId) },
                                        "${d.host}:${d.port}",
                                        if (d.serial.isNotBlank()) stringResource(R.string.forms_found_serial, d.serial) else null,
                                        if (!ok) stringResource(R.string.screens_discover_needs_password) else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) { Text(stringResource(R.string.screens_done)) }
                if (!scanning) OutlinedButton(onClick = { round++ }) { Text(stringResource(R.string.forms_search_network)) }
                TextButton(onClick = onAddByHand) { Text(stringResource(R.string.screens_add_by_hand)) }
            }
        }
    }
}

private fun toConfig(d: FoundDevice): DeviceConfig {
    val type = requireNotNull(d.type)
    return DeviceConfig(
        id = UUID.randomUUID().toString(),
        name = d.model.ifBlank { SpecDevices.typeName(type, d.specId) },
        type = type,
        host = d.host,
        port = d.port,
        unitId = d.unitId,
        password = if (type.needsPassword) d.password else "",
        serial = d.serial,
        specId = d.specId,
        rtuOverTcp = type.modbus && d.rtuOverTcp,
    )
}
