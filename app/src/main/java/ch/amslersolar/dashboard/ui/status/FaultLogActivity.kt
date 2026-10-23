// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui.status

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.SolarApp
import ch.amslersolar.dashboard.i18n.DetailTexts
import ch.amslersolar.dashboard.model.Issue
import ch.amslersolar.dashboard.model.LoggedEvent
import ch.amslersolar.dashboard.model.Severity
import ch.amslersolar.dashboard.ui.theme.SolarTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The plant's fault log: what happened when, newest first, with a link to each guide. */
class FaultLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true) // opened from the kiosk, also over the lock screen
        val plantId = intent.getStringExtra(EXTRA_PLANT) ?: return finish()
        val app = application as SolarApp
        setContent {
            SolarTheme {
                val version by app.history.version.collectAsStateWithLifecycle()
                var events by remember { mutableStateOf<List<LoggedEvent>?>(null) }
                LaunchedEffect(version) { events = app.history.events(plantId) }
                val plant by remember { app.repository.plant(plantId) }.collectAsStateWithLifecycle(initialValue = null)
                FaultLogScreen(plant?.name.orEmpty(), events, onBack = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_PLANT = "plant"

        fun open(context: Context, plantId: String) {
            context.startActivity(Intent(context, FaultLogActivity::class.java).putExtra(EXTRA_PLANT, plantId))
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaultLogScreen(plantName: String, events: List<LoggedEvent>?, onBack: () -> Unit) {
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.log_title))
                        if (plantName.isNotEmpty()) Text(plantName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !problemsOnly, onClick = { problemsOnly = false }, label = { Text(stringResource(R.string.log_filter_all)) })
                FilterChip(selected = problemsOnly, onClick = { problemsOnly = true }, label = { Text(stringResource(R.string.log_filter_alarms)) })
            }
            when {
                events == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val shown = events.filter { !problemsOnly || it.severity == Severity.ALARM.name }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(stringResource(R.string.log_empty)) }
                    } else {
                        val zone = ZoneId.systemDefault()
                        val byDay = shown.groupBy { Instant.ofEpochSecond(it.started).atZone(zone).toLocalDate() }
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            byDay.forEach { (day, list) ->
                                item(key = "d$day") { DayHeader(day) }
                                items(list, key = { it.id }) { EventRow(it, zone) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate) {
    Text(
        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun EventRow(e: LoggedEvent, zone: ZoneId) {
    val context = LocalContext.current
    val kind = e.issueKind
    val severity = Severity.entries.find { it.name == e.severity } ?: Severity.WARNING
    val info = severity == Severity.INFO
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (info) Icons.Outlined.Info else severityIcon(severity),
            contentDescription = null,
            tint = if (info) MaterialTheme.colorScheme.onSurfaceVariant else severityColor(severity),
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kind?.let { issueTitle(Issue(it)) } ?: e.kind,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(timeRange(e, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind != null) Text(issueText(kind, e.deviceName.orEmpty(), e.percent), style = MaterialTheme.typography.bodyMedium)
            e.detail?.let { Text(DetailTexts.tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (kind != null) {
                TextButton(onClick = { GuideActivity.open(context, kind.guide) }) { Text(stringResource(R.string.status_how_to_fix)) }
            }
        }
    }
}

/** "14:02 – 14:35 · 33 min", "since 14:02", or with the end date when it ended on another day. */
@Composable
private fun timeRange(e: LoggedEvent, zone: ZoneId): String {
    val start = Instant.ofEpochSecond(e.started).atZone(zone)
    val end = e.ended?.let { Instant.ofEpochSecond(it).atZone(zone) }
        ?: return stringResource(R.string.log_since, start.format(TIME))
    val endText = if (end.toLocalDate() == start.toLocalDate()) end.format(TIME)
    else end.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    return start.format(TIME) + " – " + endText + " · " + duration(e.ended - e.started)
}

@Composable
private fun duration(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return when {
        minutes < 60 -> stringResource(R.string.log_minutes, minutes.toInt())
        minutes < 24 * 60 -> stringResource(R.string.log_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        else -> stringResource(R.string.log_days, (minutes / (24 * 60)).toInt(), ((minutes / 60) % 24).toInt())
    }
}
