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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.model.Guide
import ch.amslersolar.dashboard.ui.theme.SolarTheme
import java.util.Locale

/**
 * A built-in help page: what a status means and what to do, in the phone's language. Works without internet;
 * the pages are assets/guide/<language>/<id>.md (English when a language has none).
 */
class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true) // opened from the kiosk, also over the lock screen
        val id = intent.getStringExtra(EXTRA_GUIDE) ?: Guide.NOT_ANSWERING.id
        val blocks = GuidePages.parse(GuidePages.load(this, id))
        setContent { SolarTheme { GuideScreen(blocks, onBack = ::finish) } }
    }

    companion object {
        private const val EXTRA_GUIDE = "guide"

        fun open(context: Context, guide: Guide) {
            context.startActivity(Intent(context, GuideActivity::class.java).putExtra(EXTRA_GUIDE, guide.id))
        }
    }
}

/** The small Markdown subset the guides use: "# " title, "## " heading, "1. " step, "- " point, "! " safety note. */
sealed interface GuideBlock {
    data class Title(val text: String) : GuideBlock
    data class Heading(val text: String) : GuideBlock
    data class Step(val number: String, val text: String) : GuideBlock
    data class Point(val text: String) : GuideBlock
    data class Note(val text: String) : GuideBlock
    data class Paragraph(val text: String) : GuideBlock
}

object GuidePages {
    fun load(context: Context, id: String): String {
        val language = Locale.getDefault().language
        for (dir in listOf(language, "en")) {
            runCatching { return context.assets.open("guide/$dir/$id.md").bufferedReader().use { it.readText() } }
        }
        return "# $id"
    }

    fun parse(text: String): List<GuideBlock> {
        val out = mutableListOf<GuideBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += GuideBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        val step = Regex("""^(\d+)\.\s+(.*)$""")
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val m = step.matchEntire(line)
            when {
                line.isBlank() -> flush()
                line.startsWith("## ") -> { flush(); out += GuideBlock.Heading(line.removePrefix("## ")) }
                line.startsWith("# ") -> { flush(); out += GuideBlock.Title(line.removePrefix("# ")) }
                line.startsWith("! ") -> { flush(); out += GuideBlock.Note(line.removePrefix("! ")) }
                line.startsWith("- ") -> { flush(); out += GuideBlock.Point(line.removePrefix("- ")) }
                m != null -> { flush(); out += GuideBlock.Step(m.groupValues[1], m.groupValues[2]) }
                else -> paragraph.append(line).append(' ')
            }
        }
        flush()
        return out
    }

    /** "**bold**" inside a line. */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        text.split("**").forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideScreen(blocks: List<GuideBlock>, onBack: () -> Unit) {
    val title = (blocks.firstOrNull() as? GuideBlock.Title)?.text.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.status_guide)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val type = MaterialTheme.typography
            if (title.isNotEmpty()) Text(title, style = type.headlineMedium)
            for (b in blocks) when (b) {
                is GuideBlock.Title -> Unit
                is GuideBlock.Heading -> Text(b.text, style = type.titleLarge, modifier = Modifier.padding(top = 8.dp))
                is GuideBlock.Paragraph -> Text(GuidePages.inline(b.text), style = type.bodyLarge)
                is GuideBlock.Step -> Row(verticalAlignment = Alignment.Top) {
                    Text("${b.number}.", style = type.titleMedium, modifier = Modifier.widthIn(min = 28.dp))
                    Text(GuidePages.inline(b.text), style = type.bodyLarge)
                }
                is GuideBlock.Point -> Row(verticalAlignment = Alignment.Top) {
                    Text("•", style = type.titleMedium, modifier = Modifier.widthIn(min = 28.dp))
                    Text(GuidePages.inline(b.text), style = type.bodyLarge)
                }
                is GuideBlock.Note -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(GuidePages.inline(b.text), style = type.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
