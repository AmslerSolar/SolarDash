// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.i18n.DetailTexts
import ch.amslersolar.dashboard.model.Guide
import ch.amslersolar.dashboard.model.Issue
import ch.amslersolar.dashboard.model.IssueKind
import ch.amslersolar.dashboard.model.LastReadings
import ch.amslersolar.dashboard.model.Plant
import ch.amslersolar.dashboard.model.PlantSnapshot
import ch.amslersolar.dashboard.model.PlantStatus
import ch.amslersolar.dashboard.model.PlantStatusRules
import ch.amslersolar.dashboard.model.Severity
import ch.amslersolar.dashboard.net.LocalNetworks

/** The plant's status from its latest snapshot and the tablet's network. */
@Composable
fun rememberPlantStatus(plant: Plant, snapshot: PlantSnapshot?): PlantStatus {
    val context = LocalContext.current
    val net by remember { LocalNetworks.get(context).state }.collectAsStateWithLifecycle()
    val last = remember(plant.id) { LastReadings() }
    return remember(plant, snapshot, net) {
        PlantStatusRules.evaluate(plant, snapshot, net, last).also { last.update(snapshot) }
    }
}

private val OK_GREEN = Color(0xFF2E7D32)
private val WARNING_ORANGE = Color(0xFFE65100)

@Composable
fun severityColor(severity: Severity?): Color = when (severity) {
    Severity.ALARM -> MaterialTheme.colorScheme.error
    Severity.WARNING -> WARNING_ORANGE
    Severity.INFO, null -> OK_GREEN
}

internal fun severityIcon(severity: Severity?): ImageVector = when (severity) {
    Severity.ALARM -> Icons.Outlined.Error
    Severity.WARNING -> Icons.Outlined.Warning
    Severity.INFO, null -> Icons.Outlined.CheckCircle
}

/** "Everything OK", "Please check" or "Needs attention". */
@Composable
fun statusHeadline(status: PlantStatus): String = stringResource(
    when {
        status.ok -> R.string.status_ok
        status.severity == Severity.ALARM -> R.string.status_alarm
        else -> R.string.status_warning
    },
)

/** Status card on the plant's dashboard: headline, then every issue with a link to its guide. */
@Composable
fun StatusCard(status: PlantStatus, onGuide: (Guide) -> Unit, modifier: Modifier = Modifier) {
    val color = severityColor(if (status.ok) null else status.severity)
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(severityIcon(if (status.ok) null else status.severity), contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
                Text(
                    statusHeadline(status),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            IssueList(status.issues, onGuide)
        }
    }
}

/** Every issue: title, explanation, the device's own text and "How to fix". */
@Composable
fun IssueList(issues: List<Issue>, onGuide: (Guide) -> Unit, large: Boolean = false) {
    issues.forEachIndexed { i, issue ->
        if (i > 0) HorizontalDivider()
        IssueRow(issue, onGuide, large)
    }
}

@Composable
private fun IssueRow(issue: Issue, onGuide: (Guide) -> Unit, large: Boolean) {
    val type = MaterialTheme.typography
    val info = issue.severity == Severity.INFO
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (info) Icons.Outlined.Info else severityIcon(issue.severity),
            contentDescription = null,
            tint = if (info) MaterialTheme.colorScheme.onSurfaceVariant else severityColor(issue.severity),
            modifier = Modifier.padding(top = 2.dp).size(if (large) 28.dp else 20.dp),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(issueTitle(issue), style = (if (large) type.titleLarge else type.titleMedium).copy(fontWeight = FontWeight.SemiBold))
            Text(issueText(issue), style = if (large) type.titleMedium else type.bodyMedium)
            issue.detail?.let {
                Text(DetailTexts.tr(it), style = if (large) type.bodyLarge else type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onGuide(issue.kind.guide) }, modifier = Modifier.padding(start = 0.dp)) {
                Text(stringResource(R.string.status_how_to_fix), style = if (large) type.titleMedium else type.labelLarge)
            }
        }
    }
}

/** One line for the kiosk header, tappable to see the issues. */
@Composable
fun KioskStatusLine(status: PlantStatus, onClick: () -> Unit, iconSize: Dp = 36.dp) {
    val severity = if (status.ok) null else status.severity
    val color = severityColor(severity)
    val text = if (status.ok) statusHeadline(status)
    else status.issues.first().let { issueTitle(it) + (it.device?.let { d -> " · " + d.name } ?: "") }
    Row(
        Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(severityIcon(severity), contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(start = 12.dp),
        )
        val more = status.issues.count { it.severity != Severity.INFO } - 1
        if (!status.ok && more > 0) {
            Text(" +$more", style = MaterialTheme.typography.headlineSmall, color = color)
        }
    }
}

@Composable
fun issueTitle(issue: Issue): String = stringResource(
    when (issue.kind) {
        IssueKind.NO_LOCAL_NETWORK -> R.string.status_no_wifi
        IssueKind.WRONG_NETWORK -> R.string.status_wrong_network
        IssueKind.NOT_ANSWERING -> R.string.status_not_answering
        IssueKind.ASLEEP -> R.string.status_asleep
        IssueKind.CONNECTION_REFUSED -> R.string.status_refused
        IssueKind.LOGIN_FAILED -> R.string.status_login
        IssueKind.DEVICE_SETTINGS -> R.string.status_device_settings
        IssueKind.NOT_READY -> R.string.status_not_ready
        IssueKind.DEVICE_FAULT -> R.string.status_fault
        IssueKind.DEVICE_WARNING -> R.string.status_device_warning
        IssueKind.MAINS_LOST -> R.string.status_mains_lost
        IssueKind.NO_AC_INPUT -> R.string.status_no_ac_input
        IssueKind.BACKUP_MODE -> R.string.status_backup_mode
        IssueKind.OFF_GRID_MODE -> R.string.status_off_grid_mode
        IssueKind.BATTERY_LOW -> R.string.status_battery_low
        IssueKind.BATTERY_EMPTY -> R.string.status_battery_empty
        IssueKind.NO_INTERNET -> R.string.status_no_internet
    },
)

@Composable
fun issueText(issue: Issue): String = issueText(issue.kind, issue.device?.name.orEmpty(), issue.percent)

/** The explanation for an issue kind; also for fault log entries, whose device may be gone. */
@Composable
fun issueText(kind: IssueKind, name: String, percent: Int?): String = when (kind) {
    IssueKind.NO_LOCAL_NETWORK -> stringResource(R.string.status_no_wifi_text)
    IssueKind.WRONG_NETWORK -> stringResource(R.string.status_wrong_network_text, name)
    IssueKind.NOT_ANSWERING -> stringResource(R.string.status_not_answering_text, name)
    IssueKind.ASLEEP -> stringResource(R.string.status_asleep_text, name)
    IssueKind.CONNECTION_REFUSED -> stringResource(R.string.status_refused_text, name)
    IssueKind.LOGIN_FAILED -> stringResource(R.string.status_login_text, name)
    IssueKind.DEVICE_SETTINGS -> stringResource(R.string.status_device_settings_text, name)
    IssueKind.NOT_READY -> stringResource(R.string.status_not_ready_text, name)
    IssueKind.DEVICE_FAULT -> stringResource(R.string.status_fault_text, name)
    IssueKind.DEVICE_WARNING -> stringResource(R.string.status_device_warning_text, name)
    IssueKind.MAINS_LOST -> stringResource(R.string.status_mains_lost_text, name)
    IssueKind.NO_AC_INPUT -> stringResource(R.string.status_no_ac_input_text)
    IssueKind.BACKUP_MODE -> stringResource(R.string.status_backup_mode_text, name)
    IssueKind.OFF_GRID_MODE -> stringResource(R.string.status_off_grid_mode_text, name)
    IssueKind.BATTERY_LOW -> stringResource(R.string.status_battery_low_text, percent ?: 0)
    IssueKind.BATTERY_EMPTY -> stringResource(R.string.status_battery_empty_text, percent ?: 0)
    IssueKind.NO_INTERNET -> stringResource(R.string.status_no_internet_text)
}
