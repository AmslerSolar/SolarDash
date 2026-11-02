// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui.status

import ch.amslersolar.solardash.model.FaultDetails
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.SolarApp
import ch.amslersolar.solardash.i18n.DetailTexts
import ch.amslersolar.solardash.model.Issue
import ch.amslersolar.solardash.model.LoggedEvent
import ch.amslersolar.solardash.model.Severity
import ch.amslersolar.solardash.ui.theme.SolarTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The plant's fault log: every problem, newest first; a tap shows what the app recorded about it. */
class FaultLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        val plantId = intent.getStringExtra(EXTRA_PLANT) ?: return finish()
        val app = application as SolarApp
        setContent {
            SolarTheme {
                val version by app.history.version.collectAsStateWithLifecycle()
                var events by remember { mutableStateOf<List<LoggedEvent>?>(null) }
                LaunchedEffect(version) { events = app.history.events(plantId) }
                val plant by remember { app.repository.plant(plantId) }.collectAsStateWithLifecycle(initialValue = null)
                var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
                val selected = events?.find { it.id == selectedId }
                if (selected != null) {
                    BackHandler { selectedId = null }
                    FaultDetailScreen(plant?.name.orEmpty(), selected, onBack = { selectedId = null })
                } else {
                    FaultLogScreen(plant?.name.orEmpty(), events, onOpen = { selectedId = it.id }, onBack = ::finish)
                }
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

private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScaffold(title: String, plantName: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (plantName.isNotEmpty()) Text(plantName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
                actions = { actions() },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
private fun FaultLogScreen(plantName: String, events: List<LoggedEvent>?, onOpen: (LoggedEvent) -> Unit, onBack: () -> Unit) {
    LogScaffold(stringResource(R.string.log_title), plantName, onBack) { modifier ->
        when {
            events == null -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            events.isEmpty() -> Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(stringResource(R.string.log_empty)) }
            else -> {
                val zone = ZoneId.systemDefault()
                LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(events, key = { it.id }) { e ->
                        EventRow(e, zone, onClick = { onOpen(e) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventIcon(e: LoggedEvent) {
    val severity = Severity.entries.find { it.name == e.severity } ?: Severity.WARNING
    val info = severity == Severity.INFO
    Icon(
        if (info) Icons.Outlined.Info else severityIcon(severity),
        contentDescription = null,
        tint = if (info) MaterialTheme.colorScheme.onSurfaceVariant else severityColor(severity),
        modifier = Modifier.padding(top = 2.dp).size(22.dp),
    )
}

@Composable
private fun eventTitle(e: LoggedEvent): String = e.issueKind?.let { issueTitle(Issue(it)) } ?: e.kind

@Composable
private fun EventRow(e: LoggedEvent, zone: ZoneId, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        EventIcon(e)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(timeRange(e, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                eventTitle(e) + (e.deviceName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            e.detail?.let { Text(DetailTexts.tr(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** "23.10.26, 14:02 – 14:35 · 33 min", or "23.10.26, 14:02 · still going on". */
@Composable
private fun timeRange(e: LoggedEvent, zone: ZoneId): String {
    val start = Instant.ofEpochSecond(e.started).atZone(zone)
    val end = e.ended?.let { Instant.ofEpochSecond(it).atZone(zone) }
        ?: return start.format(DATE_TIME) + " · " + stringResource(R.string.log_ongoing)
    val endText = if (end.toLocalDate() == start.toLocalDate()) end.format(TIME) else end.format(DATE_TIME)
    return start.format(DATE_TIME) + " – " + endText + " · " + duration(e.ended - e.started)
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

// ---------------------------------------------------------------- details

/** A titled block of (label, value) rows, for the screen and the shared text alike. */
private data class Block(val title: String, val rows: List<Pair<String, String>>)

@Composable
private fun FaultDetailScreen(plantName: String, e: LoggedEvent, onBack: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val title = eventTitle(e)
    val summary = Block(
        title,
        listOfNotNull(
            stringResource(R.string.log_started) to Instant.ofEpochSecond(e.started).atZone(zone).format(DATE_TIME),
            stringResource(R.string.log_ended) to (e.ended?.let { Instant.ofEpochSecond(it).atZone(zone).format(DATE_TIME) } ?: stringResource(R.string.log_ongoing)),
            e.ended?.let { stringResource(R.string.log_duration) to duration(it - e.started) },
            e.deviceName?.let { stringResource(R.string.log_device) to it },
            e.detail?.let { stringResource(R.string.log_reported) to DetailTexts.tr(it) + if (DetailTexts.tr(it) != it) " ($it)" else "" },
        ),
    )
    val start = detailBlocks(e.details, stringResource(R.string.log_at_start))
    val end = if (e.ended != null) detailBlocks(e.endDetails, stringResource(R.string.log_at_end)) else emptyList()
    val blocks = listOf(summary) + start + end
    val shareTitle = "$title – $plantName"
    LogScaffold(
        stringResource(R.string.log_details), plantName, onBack,
        actions = {
            IconButton(onClick = { share(context, shareTitle, blocks) }) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.log_share))
            }
        },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Row { EventIcon(e); Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp)) } }
            items(blocks) { b -> BlockView(b) }
        }
    }
}

@Composable
private fun BlockView(b: Block) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(b.title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        b.rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f).widthIn(min = 100.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f).padding(start = 8.dp))
            }
        }
    }
}

private fun share(context: Context, title: String, blocks: List<Block>) {
    val text = buildString {
        appendLine(title)
        for (b in blocks) {
            appendLine()
            appendLine("== ${b.title} ==")
            b.rows.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun JsonElement?.text(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> booleanOrNull?.toString() ?: content
    else -> toString()
}

/** The recorded situation as readable blocks: device, error, the device's own report and values, plant, network. */
@Composable
private fun detailBlocks(json: String?, prefix: String): List<Block> {
    val root = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return emptyList()
    val yes = stringResource(R.string.log_yes)
    val no = stringResource(R.string.log_no)
    fun yesNo(e: JsonElement?) = when ((e as? JsonPrimitive)?.booleanOrNull) { true -> yes; false -> no; null -> null }
    fun JsonObject.str(k: String) = this[k].text()
    fun JsonObject.w(k: String) = this[k].text()?.let { "$it W" }
    // Measured values: at most three decimals ("38.800000000000004" was stored by earlier versions).
    fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.let { p -> p.doubleOrNull?.let(FaultDetails::short) ?: p.contentOrNull }
    val out = mutableListOf<Block>()

    (root["device"] as? JsonObject)?.let { d ->
        out += Block(
            "$prefix: " + stringResource(R.string.log_device),
            listOfNotNull(
                d.str("name")?.let { stringResource(R.string.log_device) to it },
                d.str("type_name")?.let { stringResource(R.string.log_type) to it },
                d.str("host")?.let { h -> stringResource(R.string.log_address) to (h + (d.str("port")?.let { ":$it" } ?: "")) },
                d.str("unit_id")?.let { stringResource(R.string.log_unit) to it },
                d.str("serial")?.let { stringResource(R.string.log_serial) to it },
            ),
        )
    }
    (root["error"] as? JsonObject)?.let { err ->
        out += Block(
            "$prefix: " + stringResource(R.string.log_error),
            listOfNotNull(
                err.str("kind")?.let { stringResource(R.string.log_error_kind) to it },
                err.str("message")?.let { stringResource(R.string.log_message) to it },
            ),
        )
    }
    (root["reading"] as? JsonObject)?.let { r ->
        val rows = mutableListOf<Pair<String, String>>()
        r.str("status")?.let { rows += stringResource(R.string.log_status) to DetailTexts.tr(it) }
        (r["problems"] as? JsonArray)?.forEach { p ->
            val o = p.jsonObject
            rows += (o.str("kind") ?: "") to (o.str("text")?.let { DetailTexts.tr(it) + if (DetailTexts.tr(it) != it) " ($it)" else "" } ?: "")
        }
        r.w("pv_w")?.let { rows += stringResource(R.string.screens_solar) to it }
        r.w("grid_w")?.let { rows += stringResource(R.string.screens_grid) to it }
        r.w("load_w")?.let { rows += stringResource(R.string.screens_home) to it }
        (r["battery"] as? JsonObject)?.let { b ->
            rows += stringResource(R.string.screens_battery) to listOfNotNull(
                b.w("w"), b.str("soc")?.let { "$it %" }, b.num("v")?.let { "$it V" }, b.str("mode")?.let { DetailTexts.tr(it) },
            ).joinToString(" · ")
        }
        r.num("temperature_c")?.let { rows += stringResource(R.string.screens_temperature) to "$it °C" }
        (r["phases"] as? JsonArray)?.forEach { p ->
            val o = p.jsonObject
            rows += (o.str("name") ?: "") to listOfNotNull(o.num("v")?.let { "$it V" }, o.num("a")?.let { "$it A" }, o.w("w"), o.num("hz")?.let { "$it Hz" }).joinToString(" · ")
        }
        (r["pv_strings"] as? JsonArray)?.forEach { p ->
            val o = p.jsonObject
            rows += "PV${o.str("index")}" to listOfNotNull(o.num("v")?.let { "$it V" }, o.num("a")?.let { "$it A" }, o.w("w")).joinToString(" · ")
        }
        if (rows.isNotEmpty()) out += Block("$prefix: " + stringResource(R.string.log_reported), rows)
        (r["details"] as? JsonArray)?.forEach { s ->
            val o = s.jsonObject
            val sectionRows = o["rows"]?.jsonArray?.map { row ->
                val pair = row.jsonArray
                DetailTexts.tr(pair[0].jsonPrimitive.content) to DetailTexts.tr(pair[1].jsonPrimitive.content)
            }.orEmpty()
            if (sectionRows.isNotEmpty()) out += Block("$prefix: " + DetailTexts.tr(o.str("title").orEmpty()), sectionRows)
        }
    }
    (root["plant"] as? JsonObject)?.let { p ->
        val rows = mutableListOf<Pair<String, String>>()
        p.w("pv_w")?.let { rows += stringResource(R.string.screens_solar) to it }
        p.w("grid_w")?.let { rows += stringResource(R.string.screens_grid) to it }
        p.w("load_w")?.let { rows += stringResource(R.string.screens_home) to it }
        p.w("battery_w")?.let { w -> rows += stringResource(R.string.screens_battery) to (w + (p.str("battery_soc")?.let { " · $it %" } ?: "")) }
        (p["devices"] as? JsonArray)?.forEach { d ->
            val o = d.jsonObject
            val answered = (o["answered"] as? JsonPrimitive)?.booleanOrNull == true
            rows += (o.str("name") ?: "") to (if (answered) stringResource(R.string.log_answered) else stringResource(R.string.log_not_answered) + (o.str("error")?.let { " ($it)" } ?: ""))
        }
        if (rows.isNotEmpty()) out += Block("$prefix: " + stringResource(R.string.log_plant), rows)
    }
    (root["network"] as? JsonObject)?.let { n ->
        out += Block(
            "$prefix: " + stringResource(R.string.log_network),
            listOfNotNull(
                yesNo(n["local_network"])?.let { stringResource(R.string.log_local) to it },
                (n["subnets"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { s -> stringResource(R.string.log_subnets) to s.joinToString(", ") { it.text().orEmpty() } },
                yesNo(n["internet"])?.let { stringResource(R.string.log_internet) to it },
                root.str("app")?.let { stringResource(R.string.log_app) to it },
            ),
        )
    }
    return out
}
