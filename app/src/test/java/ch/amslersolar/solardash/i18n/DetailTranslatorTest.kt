// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTranslatorTest {
    private val t = DetailTranslator(
        mapOf(
            "Battery voltage" to "Batteriespannung",
            "Battery bank (SunSpec {n})" to "Batteriebank (SunSpec {n})",
            "Active energy drawn ({n}.{n}.{n})" to "Bezogene Wirkenergie ({n}.{n}.{n})",
            "MPPT {x} power" to "Leistung MPPT {x}",
            "{x} power" to "Leistung {x}",
            "Meter: {x}" to "Zähler: {x}",
            "Grid" to "Netz",
            "Charging" to "Lädt",
        ),
    )

    @Test
    fun exactNumberAndTextTemplates() {
        assertEquals("Batteriespannung", t.translate("Battery voltage"))
        assertEquals("Batteriebank (SunSpec 802)", t.translate("Battery bank (SunSpec 802)"))
        assertEquals("Bezogene Wirkenergie (1.8.0)", t.translate("Active energy drawn (1.8.0)"))
        assertEquals("Leistung MPPT 2", t.translate("MPPT 2 power")) // the more specific template wins
        assertEquals("Leistung L1", t.translate("L1 power"))
        assertEquals("Zähler: Netz", t.translate("Meter: Grid")) // the inserted text is translated too
    }

    @Test
    fun suffixValuesAndUnknownTextsStayReadable() {
        assertEquals("Lädt (4)", t.translate("Charging (4)"))
        assertEquals("238.4 V", t.translate("238.4 V"))
        assertEquals("-12 W", t.translate("-12 W"))
        assertEquals("Something new", t.translate("Something new"))
        assertEquals("Lädt, Netz, Unknown", t.translate("Charging, Grid, Unknown")) // flags: each on its own
        assertEquals("Anything", DetailTranslator(emptyMap()).translate("Anything"))
    }

    @Test
    fun shippedDictionariesKeepPlaceholders() {
        val dir = File("src/main/assets/i18n")
        for (lang in listOf("de", "fr", "it", "es")) {
            val entries = DetailTranslator.parse(File(dir, "details_$lang.tsv").readText())
            assertTrue("$lang has entries", entries.size > 1_500)
            for ((en, tr) in entries) {
                assertEquals("$lang {n}: $en", en.split("{n}").size, tr.split("{n}").size)
                assertEquals("$lang {x}: $en", en.split("{x}").size, tr.split("{x}").size)
            }
            assertEquals(entries["Battery voltage"], DetailTranslator(entries).translate("Battery voltage"))
        }
    }

    @Test
    fun abbCddTextsInGerman() {
        val de = DetailTranslator(DetailTranslator.parse(File("src/main/assets/i18n/details_de.tsv").readText()))
        assertEquals("Kommunikationsfehler (E501): Mikrowechselrichter 709997", de.translate("Comm Fault (E501): microinverter 709997"))
        assertEquals("Keine Kommunikation: Mikrowechselrichter xxxxxx", de.translate("No communication: microinverter xxxxxx"))
        assertEquals("Anlauf, nie gemeldet", de.translate("START-UP, never reported"))
        assertEquals("Mikrowechselrichter 709994", de.translate("Microinverter 709994"))
    }
}
