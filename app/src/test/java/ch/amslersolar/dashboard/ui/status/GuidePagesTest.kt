// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui.status

import ch.amslersolar.dashboard.model.Guide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePagesTest {
    @Test
    fun parsesTheGuideMarkup() {
        val blocks = GuidePages.parse("# Title\n\nSome text\ncontinued.\n\n## What to do\n\n1. First **step**.\n2. Second.\n- A point\n! Danger")
        assertEquals(
            listOf(
                GuideBlock.Title("Title"),
                GuideBlock.Paragraph("Some text continued."),
                GuideBlock.Heading("What to do"),
                GuideBlock.Step("1", "First **step**."),
                GuideBlock.Step("2", "Second."),
                GuideBlock.Point("A point"),
                GuideBlock.Note("Danger"),
            ),
            blocks,
        )
        assertEquals("First step.", GuidePages.inline("First **step**.").text)
    }

    @Test
    fun everyGuideExistsInEveryLanguageThatHasGuides() {
        val root = File("src/main/assets/guide")
        val languages = root.listFiles()!!.filter { it.isDirectory }.map { it.name }
        assertTrue("en" in languages)
        for (lang in languages) for (g in Guide.entries) {
            val f = File(root, "$lang/${g.id}.md")
            assertTrue("missing $f", f.isFile)
            assertTrue("$f starts with a title", f.readText().startsWith("# "))
        }
    }
}
