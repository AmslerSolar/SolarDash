// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import android.content.Context
import ch.amslersolar.dashboard.driver.modbus.ModbusConnections
import ch.amslersolar.dashboard.driver.modbus.ModbusTcp
import ch.amslersolar.dashboard.driver.modbus.RegisterSweep
import ch.amslersolar.dashboard.model.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Register sweeps saved as JSON files in the app's own folder on the shared storage
 * (Android/data/<package>/files/register-sweeps, reachable over USB without root), else in its private files.
 */
object RegisterSweeps {
    const val FORMAT = "register-sweep"
    const val VERSION = 2

    /** What a sweep reads: function 03, then 04, each over all registers. */
    val FUNCTIONS = listOf(ModbusTcp.READ_HOLDING_REGISTERS, ModbusTcp.READ_INPUT_REGISTERS)

    /**
     * Sweeps [device] with each function of [sweeps] in turn, over its own connection (closed afterwards), and
     * saves all of it in one file, also when stopped (cancelled) or the connection fails: [onSaved] gets the file.
     */
    suspend fun run(context: Context, device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, progress: () -> Unit, onSaved: (File) -> Unit) {
        require(sweeps.keys.all { it in FUNCTIONS }) { "Read functions only" }
        val started = Instant.now()
        try {
            ModbusConnections().use { connections ->
                val client = connections.get(device)
                for ((function, sweep) in sweeps) {
                    sweep.run(
                        read = { start, count ->
                            if (function == ModbusTcp.READ_INPUT_REGISTERS) client.readInputRegisters(device.unitId, start, count)
                            else client.readHoldingRegisters(device.unitId, start, count)
                        },
                        progress = progress,
                    )
                }
            }
        } finally {
            withContext(NonCancellable) { onSaved(save(context, device, sweeps, started)) }
        }
    }

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "register-sweeps")

    suspend fun save(context: Context, device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, startedAt: Instant): File =
        withContext(Dispatchers.IO) {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(startedAt)
            val label = device.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "device" }
            val file = File(dir(context).apply { mkdirs() }, "$stamp-$label.json")
            file.writeText(encode(device, sweeps, startedAt).toString())
            file
        }

    fun encode(device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, startedAt: Instant): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("started", startedAt.toString())
        put("finished", Instant.now().toString())
        // What the form had; no password.
        putJsonObject("device") {
            put("name", device.name)
            put("type", device.type.name)
            if (device.specId.isNotEmpty()) put("spec_id", device.specId)
            if (device.model.isNotEmpty()) put("model", device.model)
            if (device.serial.isNotEmpty()) put("serial", device.serial)
            put("host", device.host)
            put("port", device.port)
            put("unit_id", device.unitId)
            put("rtu_over_tcp", device.rtuOverTcp)
        }
        put("complete", sweeps.values.all { it.complete })
        put("functions", JsonArray(sweeps.map { (function, sweep) -> encode(function, sweep) }))
    }

    private fun encode(function: Int, sweep: RegisterSweep): JsonObject = buildJsonObject {
        put("function", function)
        put("from", sweep.from)
        put("to", sweep.to)
        put("done", sweep.done)
        put("complete", sweep.complete)
        sweep.stopped?.let { put("stopped", it) }
        // Protocol addresses (from 0), raw 16-bit values.
        putJsonObject("registers") { sweep.values.forEach { (r, v) -> put(r.toString(), v) } }
        put("exceptions", JsonArray(runs(sweep.exceptions).map { (a, b, code) -> buildJsonObject { put("from", a); put("to", b); put("code", code) } }))
        put("no_answer", JsonArray(runs(sweep.silent.associateWith { 0 }).map { (a, b, _) -> buildJsonObject { put("from", a); put("to", b) } }))
    }

    /** Consecutive registers with the same value as (first, last, value). */
    private fun runs(m: Map<Int, Int>): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for ((r, v) in m.toSortedMap()) {
            val last = out.lastOrNull()
            if (last != null && last.second == r - 1 && last.third == v) out[out.size - 1] = Triple(last.first, r, v)
            else out += Triple(r, r, v)
        }
        return out
    }
}
