// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the installer needs to find the cause of a logged problem later: the situation when it started (and when it
 * ended), stored as JSON with the fault log entry. Everything the app knew at that moment, in English and raw
 * units, so it reads the same whatever the tablet's language:
 * - the device: name, type, address, port, unit id, serial number;
 * - for a failed read: the kind of failure and the exact error message;
 * - for a device-reported problem: the device's status, all its fault and warning texts, its main values and every
 *   detail row (fault registers, states, temperatures …) as the device showed them;
 * - the plant: solar, grid, load, battery, and which devices answered;
 * - the tablet: local network and its address range, internet, app version.
 * The device password is never stored.
 */
object FaultDetails {
    fun build(plant: Plant, snapshot: PlantSnapshot?, issue: Issue?, net: NetworkState, appVersion: String): JsonObject = buildJsonObject {
        put("time", snapshot?.readAt?.epochSecond)
        put("app", appVersion)
        val device = issue?.device
        if (device != null) {
            putJsonObject("device") {
                put("name", device.name)
                put("type", device.type.name)
                put("type_name", ch.amslersolar.solardash.driver.spec.SpecDevices.typeName(device.type, device.specId))
                if (device.specId.isNotBlank()) put("modbusspec", device.specId)
                if (device.type.needsHost) {
                    put("host", device.host)
                    put("port", device.port)
                }
                if (device.type.modbus) put("unit_id", device.unitId)
                if (device.rtuOverTcp) put("rtu_over_tcp", true)
                if (device.serial.isNotBlank()) put("serial", device.serial)
                if (device.type.kind == DeviceKind.METER) put("meter_role", device.meterRole.name)
            }
            val status = snapshot?.devices?.find { it.device.id == device.id }
            if (status?.error != null) {
                putJsonObject("error") {
                    put("kind", (status.errorKind ?: ConnectionError.OTHER).name)
                    put("message", status.error)
                }
            }
            status?.reading?.let { put("reading", reading(it)) }
        }
        if (snapshot != null) {
            putJsonObject("plant") {
                put("off_grid", plant.offGrid)
                put("pv_w", snapshot.pvPowerW)
                put("grid_w", snapshot.gridPowerW)
                put("load_w", snapshot.loadPowerW)
                snapshot.battery?.let { b ->
                    put("battery_w", b.powerW)
                    put("battery_soc", b.socPercent)
                }
                putJsonArray("devices") {
                    for (s in snapshot.devices) add(
                        buildJsonObject {
                            put("name", s.device.name)
                            put("answered", s.reading != null)
                            s.errorKind?.let { put("error", it.name) }
                        },
                    )
                }
            }
        }
        putJsonObject("network") {
            put("local_network", net.localNetwork)
            put("internet", net.internet)
            putJsonArray("subnets") { net.subnets.forEach { add(JsonPrimitive("${it.address.hostAddress}/${it.prefixLength}")) } }
        }
    }

    /** A measured value with at most three decimals, without trailing zeros: 38.800000000000004 → "38.8". */
    fun short(d: Double): String =
        if (!d.isFinite()) d.toString() else java.math.BigDecimal(d).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun r3(d: Double?): Double? = d?.let { if (it.isFinite()) Math.round(it * 1000) / 1000.0 else it }

    private fun reading(r: DeviceReading): JsonObject = buildJsonObject {
        put("status", r.status)
        if (r.problems.isNotEmpty()) putJsonArray("problems") {
            r.problems.forEach { p -> add(buildJsonObject { put("kind", p.kind.name); put("text", p.text) }) }
        }
        put("pv_w", r.pvPowerW)
        put("grid_w", r.gridPowerW)
        put("load_w", r.loadPowerW)
        put("temperature_c", r3(r.temperatureC))
        r.battery?.let { b ->
            putJsonObject("battery") {
                put("w", b.powerW); put("soc", b.socPercent); put("v", r3(b.voltageV)); put("mode", b.mode)
            }
        }
        if (r.phases.isNotEmpty()) putJsonArray("phases") {
            r.phases.forEach { p ->
                add(buildJsonObject { put("name", p.name); put("v", r3(p.voltageV)); put("a", r3(p.currentA)); put("hz", r3(p.frequencyHz)); put("w", p.powerW) })
            }
        }
        if (r.pvStrings.isNotEmpty()) putJsonArray("pv_strings") {
            r.pvStrings.forEach { s -> add(buildJsonObject { put("index", s.index); put("v", r3(s.voltageV)); put("a", r3(s.currentA)); put("w", s.powerW) }) }
        }
        if (r.details.isNotEmpty()) putJsonArray("details") {
            r.details.forEach { section ->
                add(
                    buildJsonObject {
                        put("title", section.title)
                        put("rows", JsonArray(section.rows.map { (k, v) -> buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) } }))
                    },
                )
            }
        }
    }
}
