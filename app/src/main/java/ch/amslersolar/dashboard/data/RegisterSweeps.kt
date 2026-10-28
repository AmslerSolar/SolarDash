// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import android.content.Context
import ch.amslersolar.dashboard.driver.modbus.AnswerTimeout
import ch.amslersolar.dashboard.driver.modbus.ModbusConnections
import ch.amslersolar.dashboard.driver.modbus.ModbusDeviceId
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcp
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.modbus.RegisterSweep
import ch.amslersolar.dashboard.driver.spec.SpecLibrary
import ch.amslersolar.dashboard.model.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Register sweeps, recorded as the raw traffic: a text file per sweep in the app's own folder on the shared storage
 * (Android/data/<package>/files/register-sweeps, reachable over USB without root), else in its private files.
 *
 * One line per frame: local time with milliseconds, `>` for a request or `<` for an answer, the frame in hex (RTU
 * frames with their CRC, Modbus TCP frames with their header). `!` lines are requests without an answer, `#` lines
 * comments (the device, its identification, the passes, the end). A request that got no answer and was sent again on a new connection
 * shows as `!` (… sent again), then the same request once more.
 */
object RegisterSweeps {
    /** What a sweep reads, in this order: all passes of function 03, then all of 04, each over all registers. */
    val FUNCTIONS = listOf(ModbusTcp.READ_HOLDING_REGISTERS, ModbusTcp.READ_INPUT_REGISTERS)

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val PASSES = listOf(
        "known registers",
        "blocks of ${RegisterSweep.LARGE}",
        "blocks of ${RegisterSweep.SMALL}",
        "first register of each silent block of ${RegisterSweep.SMALL}",
        "edges of the answered stretches",
    )
    val passCount: Int get() = PASSES.size

    /**
     * Sweeps [device] function by function ([FUNCTIONS] order), each pass by pass, over its own connection (closed
     * afterwards). The file is written as it goes and closed also when stopped (cancelled) or the connection fails:
     * [onSaved] gets it.
     */
    suspend fun run(context: Context, device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, progress: () -> Unit, onSaved: (File) -> Unit) {
        require(sweeps.keys.all { it in FUNCTIONS }) { "Read functions only" }
        val started = Instant.now()
        val file = withContext(Dispatchers.IO) { newFile(context, device, started) }
        val out = withContext(Dispatchers.IO) { file.bufferedWriter() }
        val log = Log(out)
        try {
            withContext(Dispatchers.IO) { sweep(device, sweeps, log, progress) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                log.comment("end; answered: " + sweeps.entries.joinToString { (f, s) -> "%02d: %d registers".format(f, s.values.size) })
                out.close()
                onSaved(file)
            }
        }
    }

    private suspend fun sweep(device: DeviceConfig, sweeps: Map<Int, RegisterSweep>, log: Log, progress: () -> Unit) {
        log.comment("register sweep of ${device.name}: ${device.host}:${device.port}, unit ${device.unitId}, " +
            if (device.rtuOverTcp) "Modbus RTU over TCP" else "Modbus TCP")
        log.comment("read only (functions 43/14, ${sweeps.keys.joinToString { "%02d".format(it) }}); nothing is written")
        // identified (the form names its device file): only that file's registers, else those of every file
        val own = device.specId.isNotEmpty() && SpecLibrary.current?.entry(device.specId) != null
        val known = withContext(Dispatchers.Default) {
            SpecLibrary.current?.let { if (own) it.knownRegisters(listOf(device.specId)) else it.knownRegisters() }.orEmpty()
        }
        log.comment("known registers from " + if (own) "its device file ${device.specId}" else "all device files")
        // other traffic is paused (TrafficPause): a gap only where the device's file asks for one
        val gap = if (!own) 0L else withContext(Dispatchers.Default) {
            runCatching { SpecLibrary.current?.spec(device.specId)?.connection?.minIntervalMs }.getOrNull() ?: 0L
        }.coerceAtMost(RegisterSweep.MAX_GAP_MS)
        log.comment("gap between requests: $gap ms")
        val timeout = AnswerTimeout()
        ModbusConnections(timeoutMs = RegisterSweep.TIMEOUT_MS).use { connections ->
            val client = connections.get(device)
            client.traffic = { outgoing, frame -> log.frame(outgoing, frame) }
            client.retrying = { why -> log.noAnswer("$why; sent again") }
            client.answered = { ms -> client.answerTimeoutMs = timeout.answered(ms) }
            identify(client, device.unitId, log)
            fun reader(function: Int): suspend (Int, Int) -> RegisterBlock = { start, count ->
                try {
                    if (function == ModbusTcp.READ_INPUT_REGISTERS) client.readInputRegisters(device.unitId, start, count)
                    else client.readHoldingRegisters(device.unitId, start, count)
                } catch (e: IOException) {
                    if (e !is ModbusException) log.noAnswer(e.message ?: e::class.simpleName.orEmpty())
                    throw e
                }
            }
            for (function in FUNCTIONS) {
                val sweep = sweeps[function] ?: continue
                val read = reader(function)
                for ((i, name) in PASSES.withIndex()) {
                    if (sweep.stopped != null) break
                    sweep.pass = i + 1
                    log.comment("pass ${i + 1}: $name, function %02d; timeout %d ms".format(function, timeout.ms))
                    when (i) {
                        0 -> sweep.known(known[if (function == ModbusTcp.READ_INPUT_REGISTERS) "input" else "holding"].orEmpty(), read, split = own, gap, progress)
                        1 -> sweep.blocks(RegisterSweep.LARGE, read, gap, progress)
                        2 -> sweep.blocks(RegisterSweep.SMALL, read, gap, progress)
                        3 -> sweep.probes(read, gap, progress)
                        else -> sweep.edges(read, gap, progress)
                    }
                    sweep.stopped?.let { log.comment("function %02d stopped: %s".format(function, it)) }
                }
            }
        }
    }

    /** Function 43/14 before the passes: what the device says it is, as far as it answers (read only). */
    private suspend fun identify(client: ModbusTcpClient, unitId: Int, log: Log) {
        log.comment("device identification (function 43/14)")
        val id = ModbusDeviceId.readAll { code, objectId ->
            try {
                client.readDeviceIdentification(unitId, code, objectId)
            } catch (e: IOException) {
                if (e !is ModbusException) log.noAnswer(e.message ?: e::class.simpleName.orEmpty())
                throw e
            }
        }
        if (id.conformity == 0) return log.comment("device identification: none")
        log.comment("device identification: conformity level %02X".format(id.conformity))
        for ((objectId, value) in id.objects) {
            log.comment("  %02X %s: %s".format(objectId, ModbusDeviceId.NAMES[objectId] ?: "object", ModbusDeviceId.text(value)))
        }
    }

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "register-sweeps")

    private fun newFile(context: Context, device: DeviceConfig, startedAt: Instant): File {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(startedAt)
        val label = device.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "device" }
        return File(dir(context).apply { mkdirs() }, "$stamp-$label.txt")
    }

    /** The lines of a sweep file; each written at once (flushed), so a crash keeps what was read. */
    class Log(private val out: Writer, private val now: () -> OffsetDateTime = { OffsetDateTime.now() }) {
        @Synchronized
        fun frame(outgoing: Boolean, frame: ByteArray) =
            line("${TIME.format(now())} ${if (outgoing) ">" else "<"} ${frame.joinToString(" ") { "%02X".format(it) }}")

        @Synchronized
        fun noAnswer(why: String) = line("${TIME.format(now())} ! $why")

        @Synchronized
        fun comment(text: String) = line("# $text")

        private fun line(text: String) {
            out.write(text)
            out.write("\n")
            out.flush()
        }
    }
}
