// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.lg

import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against responses recorded from a real unit (pyess test cassettes, Oct 2019). */
class LgEssParserTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private val home = json(
        """{"statistics":{"pcs_pv_total_power":"1712","batconv_power":"848","bat_use":"1","bat_status":"1",
        "bat_user_soc":"52.87357","load_power":"314","load_today":"0.0","grid_power":"550"},
        "direction":{"is_direct_consuming_":"1","is_battery_charging_":"1","is_battery_discharging_":"0",
        "is_grid_selling_":"1","is_grid_buying_":"0","is_charging_from_grid_":"0"},
        "operation":{"status":"start","mode":"1"},"pcs_fault":{"pcs_status":"pcs_ok"}}""",
    )
    private val common = json(
        """{"PV":{"pv1_voltage":"219.800003","pv2_voltage":"317.899994","pv1_power":"129","pv2_power":"1252",
        "pv1_current":"0.580000","pv2_current":"3.930000","today_pv_generation_sum":"4238"},
        "GRID":{"active_power":"-371.769989","a_phase":"234.199997","freq":"50.000000",
        "today_grid_feed_in_energy":"104","today_grid_power_purchase_energy":"2140"},
        "LOAD":{"today_load_consumption_sum":"13040"}}""",
    )

    @Test
    fun liveValuesAndSigns() {
        val r = LgEssParser.parse(home, null)
        assertEquals(1712, r.pvPowerW)
        assertEquals(-848, r.battery?.powerW)
        assertEquals(53, r.battery?.socPercent)
        assertEquals(550, r.gridPowerW)
        assertEquals(314, r.loadPowerW)
        assertEquals("Running", r.status)
        // The snapshot balances: PV = battery charging + home + export
        assertEquals(r.loadPowerW, r.pvPowerW!! + r.battery!!.powerW - r.gridPowerW!!)
    }

    @Test
    fun detailsAndDailyTotals() {
        val r = LgEssParser.parse(home, common)
        assertEquals(listOf(129, 1252), r.pvStrings.map { it.powerW })
        // One phase voltage, and only a total power: no per-phase current or power is made up.
        assertEquals("L1", r.phases.single().name)
        assertEquals(234.2, r.phases.single().voltageV, 1e-3)
        assertNull(r.phases.single().powerW)
        assertNull(r.phases.single().currentA)
        val grid = LgEssParser.details(home, common, null).first { it.title == "Details · GRID" }.rows.toMap()
        assertEquals("234.199997", grid["L1 voltage (a_phase)"])
        assertEquals(4.238, r.today.pvKwh!!, 1e-9)
        assertEquals(0.104, r.today.exportKwh!!, 1e-9)
        assertEquals(2.140, r.today.importKwh!!, 1e-9)
        assertEquals(13.040, r.today.loadKwh!!, 1e-9)
    }

    @Test
    fun faultAndMissingValues() {
        val r = LgEssParser.parse(json("""{"statistics":{"bat_use":"0"},"pcs_fault":{"pcs_status":"pcs_fault_3"}}"""), null)
        assertNull(r.battery)
        assertNull(r.pvPowerW)
        assertTrue(r.status!!.startsWith("Fault"))
    }

    @Test
    fun problems() {
        assertEquals(emptyList<DeviceProblem>(), LgEssParser.parse(home, common).problems)
        val r = LgEssParser.parse(json("""{"pcs_fault":{"pcs_status":"pcs_fault_3"},"wintermode":{"backupmode":"on"}}"""), null)
        assertEquals(
            listOf(DeviceProblem(Kind.FAULT, "PCS fault (pcs_fault_3)"), DeviceProblem(Kind.BACKUP_MODE, "Backup (off-grid) mode (on)")),
            r.problems,
        )
        assertEquals(emptyList<DeviceProblem>(), LgEssParser.parse(json("""{"wintermode":{"backupmode":""}}"""), null).problems)
        assertEquals(
            listOf(DeviceProblem(Kind.FAULT, "Overload fault (backup)")),
            LgEssParser.overloadProblems(json("""{"mustViewOverLoadFaultPopUp":"1","overLoadFaultKind":"backup"}""")),
        )
        assertEquals(emptyList<DeviceProblem>(), LgEssParser.overloadProblems(json("""{"mustViewOverLoadFaultPopUp":"0"}""")))
    }
}
