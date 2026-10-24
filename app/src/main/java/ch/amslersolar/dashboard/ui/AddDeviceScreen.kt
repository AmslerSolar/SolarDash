// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui

import androidx.compose.material3.TextButton
import ch.amslersolar.dashboard.driver.spec.SpecIndexEntry
import ch.amslersolar.dashboard.driver.spec.SpecLibrary
import ch.amslersolar.dashboard.driver.spec.SpecDevices
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceKind
import ch.amslersolar.dashboard.model.DeviceType
import ch.amslersolar.dashboard.model.MeterRole
import ch.amslersolar.dashboard.discovery.FoundDevice
import ch.amslersolar.dashboard.discovery.ScanEvent
import ch.amslersolar.dashboard.discovery.ScanPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    plantName: String,
    onTest: suspend (DeviceConfig) -> ConnectionTest,
    /** The network search; extended: all ports devices use, not only 502. */
    onScan: (extended: Boolean) -> Flow<ScanEvent>,
    onSave: (DeviceConfig) -> Unit,
    onBack: () -> Unit,
    /** Set when editing: the form starts from it, and saving keeps its id (history and chart channels stay). */
    existing: DeviceConfig? = null,
) {
    val initialType = existing?.type ?: DeviceType.GOODWE_ET
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: initialType.defaultPort).toString()) }
    var unitId by rememberSaveable { mutableStateOf((existing?.unitId ?: initialType.defaultUnitId).toString()) }
    // Kept to find the device again if its IP changes; replaced by what Test connection or the search reports.
    var serial by rememberSaveable { mutableStateOf(existing?.serial.orEmpty()) }
    var invertGrid by rememberSaveable { mutableStateOf(existing?.invertGrid ?: false) }
    var meterRole by rememberSaveable { mutableStateOf(existing?.meterRole ?: MeterRole.GRID) }
    var model by rememberSaveable { mutableStateOf(existing?.model.orEmpty()) }
    var specId by rememberSaveable { mutableStateOf(existing?.specId.orEmpty()) }
    var browsing by rememberSaveable { mutableStateOf<DeviceKind?>(null) }
    val models = remember(type, specId) { SpecDevices.userModels(type, specId) }
    val generic = type in SpecDevices.GENERIC
    // Deliberately not saveable: kept out of the saved instance state.
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var testResult by rememberSaveable(stateSaver = ConnectionTestSaver) { mutableStateOf<ConnectionTest?>(null) }
    var testing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var scan by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<ScanEvent.Progress?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<FoundDevice>() }

    fun startScan(extended: Boolean) {
        scan?.cancel()
        found.clear()
        scanError = null
        progress = null
        scan = scope.launch {
            onScan(extended).collect { e ->
                when (e) {
                    is ScanEvent.Progress -> progress = e
                    is ScanEvent.Found -> {
                        // A later event for the same host:port (identified, or not) replaces the "port open" entry.
                        val i = found.indexOfFirst { it.key == e.device.key }
                        if (i >= 0) found[i] = e.device else found += e.device
                    }
                    is ScanEvent.Failed -> scanError = e.message
                }
            }
            scan = null
        }
    }

    fun pick(d: FoundDevice) {
        val t = d.type ?: return
        type = t
        host = d.host
        port = d.port.toString()
        unitId = d.unitId.toString()
        serial = d.serial
        specId = d.specId
        if (name.isBlank()) name = d.model
        testResult = null
    }

    fun build() = DeviceConfig(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { SpecDevices.typeName(type, specId) },
        type = type,
        host = host.trim(),
        port = port.toIntOrNull() ?: type.defaultPort,
        unitId = unitId.toIntOrNull() ?: type.defaultUnitId,
        password = if (type.needsPassword) password else "",
        invertGrid = (type.gridSignOption || type.kind == DeviceKind.METER) && invertGrid,
        meterRole = if (type.kind == DeviceKind.METER) meterRole else MeterRole.GRID,
        model = if (models.any { it.first == model }) model else "",
        specId = if (generic) specId else "",
        // A changed type or unit id may be another device: drop a serial that came with the old settings.
        serial = if (existing != null && (type != existing.type || unitId != existing.unitId.toString()) && serial == existing.serial) "" else serial,
    )
    val valid = !type.needsHost ||
        (host.isNotBlank() && port.toIntOrNull() in 1..65535 && (!type.modbus || unitId.toIntOrNull() in 0..255) &&
            (!type.needsPassword || password.isNotEmpty()))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) stringResource(R.string.forms_edit_device_title, existing.name) else stringResource(R.string.forms_add_device_title, plantName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.forms_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScanSection(
                scanning = scan != null,
                progress = progress,
                error = scanError,
                found = found.sortedWith(compareBy<FoundDevice> { it.type == null }.thenBy { it.alreadyIn != null }.thenBy { it.identifying }.thenBy { it.host }.thenBy { it.port }),
                onScan = ::startScan,
                onStop = { scan?.cancel(); scan = null },
                onPick = ::pick,
            )

            OutlinedTextField(
                name, { name = it },
                label = { Text(stringResource(R.string.forms_name)) }, placeholder = { Text(stringResource(R.string.forms_device_name_placeholder)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            // Kind as chips, the device as a field opening a searchable list (built-in drivers and ModbusSpec files).
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceKind.entries.forEach { k ->
                    FilterChip(selected = type.kind == k, onClick = { browsing = k }, label = { Text(deviceKindName(k)) })
                }
            }
            val chosen = if (generic) SpecLibrary.current?.entry(specId) else null
            Box {
                OutlinedTextField(
                    SpecDevices.typeName(type, specId), {}, readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.forms_device)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    supportingText = if (chosen != null && !chosen.documented) ({ Text(stringResource(R.string.forms_spec_unverified)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A read-only field swallows taps: this layer opens the list.
                Spacer(Modifier.matchParentSize().clickable { browsing = type.kind })
            }
            browsing?.let { kind ->
                DevicePicker(
                    kind = kind,
                    onPickType = { option ->
                        type = option
                        specId = ""
                        model = ""
                        serial = ""
                        port = option.defaultPort.toString()
                        unitId = option.defaultUnitId.toString()
                        testResult = null
                        browsing = null
                    },
                    onPickSpec = { e ->
                        type = SpecDevices.genericType(e.kind)
                        specId = e.id
                        model = ""
                        serial = ""
                        port = e.port.toString()
                        unitId = e.unit.toString()
                        if (name.isBlank() || existing == null) name = e.title
                        testResult = null
                        browsing = null
                    },
                    onDismiss = { browsing = null },
                )
            }

            when (type.kind) {
                DeviceKind.BATTERY -> Hint(stringResource(R.string.forms_hint_kind_battery))
                DeviceKind.METER -> Hint(stringResource(R.string.forms_hint_kind_meter))
                DeviceKind.CONSUMER -> Hint(stringResource(R.string.forms_hint_kind_consumer))
                else -> {}
            }
            if (type == DeviceType.HUAWEI) {
                Hint(stringResource(R.string.forms_hint_huawei))
            }

            if (type == DeviceType.POWER_ONE) {
                Hint(stringResource(R.string.forms_hint_power_one))
            }
            if (type == DeviceType.SUNGROW) {
                Hint(stringResource(R.string.forms_hint_sungrow))
            }
            if (type == DeviceType.SMA) {
                Hint(stringResource(R.string.forms_hint_sma))
            }
            if (type == DeviceType.VOLTRONIC) {
                Hint(stringResource(R.string.forms_hint_voltronic))
            }
            when (type) {
                DeviceType.KACO_NX3 -> Hint(stringResource(R.string.forms_hint_kaco_nx3))
                DeviceType.FIMER -> Hint(stringResource(R.string.forms_hint_fimer))
                DeviceType.APSYSTEMS_ECU -> Hint(stringResource(R.string.forms_hint_apsystems_ecu))
                DeviceType.CONEXT_CL -> Hint(stringResource(R.string.forms_hint_conext_cl))
                DeviceType.CONEXT_CL60 -> Hint(stringResource(R.string.forms_hint_conext_cl60))
                DeviceType.CONEXT_GATEWAY -> Hint(stringResource(R.string.forms_hint_conext_gateway))
                DeviceType.KOSTAL_PIKO_CI -> Hint(stringResource(R.string.forms_hint_kostal_piko_ci))
                DeviceType.INGETEAM -> Hint(stringResource(R.string.forms_hint_ingeteam))
                DeviceType.CPS -> Hint(stringResource(R.string.forms_hint_cps))
                DeviceType.RIELLO_CENTRAL -> Hint(stringResource(R.string.forms_hint_riello_central))
                DeviceType.CARLO_GAVAZZI -> Hint(stringResource(R.string.forms_hint_carlo_gavazzi))
                DeviceType.EASTRON -> Hint(stringResource(R.string.forms_hint_eastron))
                DeviceType.INEPRO_PRO380 -> Hint(stringResource(R.string.forms_hint_inepro_pro380))
                DeviceType.ABB_B2X -> Hint(stringResource(R.string.forms_hint_abb_b2x))
                DeviceType.JANITZA_UMG -> Hint(stringResource(R.string.forms_hint_janitza_umg))
                DeviceType.SCHNEIDER_IEM -> Hint(stringResource(R.string.forms_hint_schneider_iem))
                DeviceType.SIEMENS_PAC -> Hint(stringResource(R.string.forms_hint_siemens_pac))
                DeviceType.KOSTAL_KSEM -> Hint(stringResource(R.string.forms_hint_kostal_ksem))
                DeviceType.SHELLY_PRO_3EM -> Hint(stringResource(R.string.forms_hint_shelly_pro_3em))
                DeviceType.KEBA_P30 -> Hint(stringResource(R.string.forms_hint_keba_p30))
                DeviceType.ALFEN_EVE -> Hint(stringResource(R.string.forms_hint_alfen_eve))
                DeviceType.MENNEKES_AMTRON -> Hint(stringResource(R.string.forms_hint_mennekes_amtron))
                DeviceType.GO_E -> Hint(stringResource(R.string.forms_hint_go_e))
                DeviceType.MY_PV -> Hint(stringResource(R.string.forms_hint_my_pv))
                DeviceType.STIEBEL_ELTRON_ISG -> Hint(stringResource(R.string.forms_hint_stiebel_eltron_isg))
                DeviceType.NIBE_S -> Hint(stringResource(R.string.forms_hint_nibe_s))
                DeviceType.HOVAL_TOPTRONIC -> Hint(stringResource(R.string.forms_hint_hoval_toptronic))
                else -> {}
            }
            if (type == DeviceType.FENECON) {
                Hint(stringResource(R.string.forms_hint_fenecon))
            }
            if (type == DeviceType.VICTRON) {
                Hint(stringResource(R.string.forms_hint_victron))
            }
            if (type == DeviceType.GROWATT) {
                Hint(stringResource(R.string.forms_hint_growatt))
            }
            if (type == DeviceType.SOLAREDGE) {
                Hint(stringResource(R.string.forms_hint_solaredge))
            }
            if (type == DeviceType.SUNSPEC) {
                Hint(stringResource(R.string.forms_hint_sunspec))
            }
            if (type == DeviceType.FRONIUS) {
                Hint(stringResource(R.string.forms_hint_fronius))
            }
            if (type == DeviceType.KOSTAL_PLENTICORE) {
                Hint(stringResource(R.string.forms_hint_kostal_plenticore))
            }

            if (type.needsHost) {
                OutlinedTextField(
                    host, { host = it; testResult = null },
                    label = { Text(stringResource(R.string.forms_ip_address)) }, placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit); testResult = null },
                        label = { Text(stringResource(R.string.forms_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    if (type.modbus) {
                        OutlinedTextField(
                            unitId, { unitId = it.filter(Char::isDigit); testResult = null },
                            label = { Text(stringResource(R.string.forms_unit_id)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (type.modbus) Hint(stringResource(R.string.forms_hint_shared_gateway))
                if (type.needsPassword) {
                    OutlinedTextField(
                        password, { password = it; testResult = null },
                        label = { Text(if (type == DeviceType.ENPHASE) stringResource(R.string.forms_access_token) else stringResource(R.string.forms_device_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(
                        if (type == DeviceType.ENPHASE) {
                            stringResource(R.string.forms_hint_enphase_token)
                        } else {
                            stringResource(R.string.forms_hint_mac_password)
                        },
                    )
                }
            }

            if (models.isNotEmpty()) {
                // The device can't tell its model over Modbus: the installer picks it (ModbusSpec model_choice: user).
                Text(stringResource(R.string.forms_model), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.forms_hint_model))
                Column(Modifier.selectableGroup()) {
                    models.forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = model == id, role = Role.RadioButton) { model = id; testResult = null }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model == id, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            if (type.kind == DeviceKind.METER) {
                Text(stringResource(R.string.forms_meter_measures), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    MeterRole.entries.forEach { role ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = meterRole == role, role = Role.RadioButton) { meterRole = role }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = meterRole == role, onClick = null)
                            Text(meterRoleName(role), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            if (type.gridSignOption || type.kind == DeviceKind.METER) {
                Row(
                    Modifier.fillMaxWidth().toggleable(invertGrid, role = Role.Checkbox) { invertGrid = it; testResult = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = invertGrid, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(if (type.kind == DeviceKind.METER) stringResource(R.string.forms_invert_meter) else stringResource(R.string.forms_invert_grid))
                        Hint(
                            if (type.kind == DeviceKind.METER) {
                                stringResource(R.string.forms_hint_invert_meter)
                            } else {
                                stringResource(R.string.forms_hint_invert_grid)
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = valid && !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = onTest(build())
                            testResult = result
                            result.serial?.let { serial = it }
                            testing = false
                        }
                    },
                ) { Text(if (testing) stringResource(R.string.forms_testing) else stringResource(R.string.forms_test_connection)) }
                Button(enabled = valid, onClick = { onSave(build()) }) { Text(stringResource(R.string.forms_save)) }
            }
            testResult?.let { r ->
                Text(
                    when (r) {
                        is ConnectionTest.Connected -> stringResource(R.string.forms_test_connected, r.model, r.reportedSerial)
                        is ConnectionTest.Failed -> stringResource(R.string.forms_test_failed, r.error)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** "Search network" and its results; tapping a recognised device fills in the form below. */
@Composable
private fun ScanSection(
    scanning: Boolean,
    progress: ScanEvent.Progress?,
    error: String?,
    found: List<FoundDevice>,
    onScan: (extended: Boolean) -> Unit,
    onStop: () -> Unit,
    onPick: (FoundDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scanning) OutlinedButton(onClick = onStop) { Text(stringResource(R.string.forms_stop_search)) }
            else {
                OutlinedButton(onClick = { onScan(false) }) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Text(stringResource(R.string.forms_search_network), modifier = Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { onScan(true) }) { Text(stringResource(R.string.forms_search_extended)) }
            }
            if (scanning && progress != null) Text(
                stringResource(
                    if (progress.phase == ScanPhase.PORTS) R.string.forms_scan_progress_ports else R.string.forms_scan_progress_identify,
                    progress.done, progress.total,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            else if (!scanning && progress != null) Text(found.count { it.type != null }.let { pluralStringResource(R.plurals.forms_scan_found, it, it) }, style = MaterialTheme.typography.bodySmall)
        }
        if (scanning) {
            LinearProgressIndicator(
                progress = { progress?.let { it.done.toFloat() / it.total.coerceAtLeast(1) } ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (progress == null && !scanning) {
            Hint(stringResource(R.string.forms_hint_scan))
        }
        found.forEach { d ->
            val usable = d.type != null
            Card(
                Modifier.fillMaxWidth().clickable(enabled = usable) { onPick(d) },
                colors = if (usable) CardDefaults.cardColors() else CardDefaults.outlinedCardColors(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        when {
                            d.alreadyIn != null || usable -> d.model
                            d.identifying -> stringResource(R.string.forms_found_identifying)
                            else -> stringResource(R.string.forms_found_unrecognised)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val where = "${d.host}:${d.port}"
                    val details = when {
                        d.alreadyIn != null -> listOf(where, stringResource(R.string.forms_found_already_in, d.alreadyIn))
                        d.type != null -> listOfNotNull(
                            SpecDevices.typeName(d.type, d.specId),
                            where,
                            stringResource(R.string.forms_found_unit, d.unitId),
                            if (d.serial.isNotBlank()) stringResource(R.string.forms_found_serial, d.serial) else null,
                        )
                        else -> listOf(where)
                    }
                    val mdns = if (d.viaMdns) stringResource(R.string.forms_found_via_mdns) else null
                    Text(
                        (details + listOfNotNull(mdns)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Keeps the last connection test across configuration changes (as plain strings). */
private val ConnectionTestSaver = Saver<ConnectionTest?, ArrayList<String>>(
    save = { t ->
        when (t) {
            null -> null
            is ConnectionTest.Connected -> arrayListOf("ok", t.model, t.reportedSerial)
            is ConnectionTest.Failed -> arrayListOf("failed", t.error)
        }
    },
    restore = { if (it[0] == "ok") ConnectionTest.Connected(it[1], it[2]) else ConnectionTest.Failed(it[1]) },
)

/** One line of the device list: a built-in driver or a ModbusSpec file. */
private class DeviceOption(val title: String, val enabled: Boolean, val unverified: Boolean, val pick: () -> Unit)

/** The devices of one kind (built-in drivers and ModbusSpec files), searchable by maker and name. */
@Composable
private fun DevicePicker(kind: DeviceKind, onPickType: (DeviceType) -> Unit, onPickSpec: (SpecIndexEntry) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val comingSoon = stringResource(R.string.forms_type_coming_soon, "%s")
    val all = remember(kind) {
        val named = DeviceType.entries.filter { it.kind == kind && it !in SpecDevices.GENERIC }.map { t ->
            DeviceOption(if (t.supported) t.displayName else comingSoon.replace("%s", t.displayName), t.supported, false) { onPickType(t) }
        }
        // Files behind a built-in driver are listed as that driver.
        val covered = SpecDevices.SPEC_IDS.values.flatten().toSet()
        val specs = SpecLibrary.current?.index.orEmpty()
            .filter { SpecDevices.genericType(it.kind).kind == kind && it.id !in covered }
            .map { e -> DeviceOption(e.title, true, !e.documented) { onPickSpec(e) } }
        (named + specs).sortedWith(compareBy<DeviceOption> { !it.enabled }.thenBy { it.title.lowercase() })
    }
    val shown = remember(all, query) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        all.filter { o -> words.all { w -> o.title.lowercase().contains(w) } }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(deviceKindName(kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.forms_spec_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
                Text(pluralStringResource(R.plurals.forms_spec_count, shown.size, shown.size), style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(shown.size) { i ->
                        val o = shown[i]
                        Column(Modifier.fillMaxWidth().clickable(enabled = o.enabled) { o.pick() }.padding(vertical = 8.dp)) {
                            Text(
                                o.title, style = MaterialTheme.typography.bodyMedium,
                                color = if (o.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            )
                            if (o.unverified) Text(stringResource(R.string.forms_spec_unverified_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.forms_cancel)) } },
    )
}
