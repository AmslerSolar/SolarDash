// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Engine parts the Inverters2 group needs: address offsets ([SpecTables.addressOffset]), `chars` on identify
 * registers, and `connection.sleeps` (gateway "no answer" exceptions, asleep only after a low-production reading,
 * asked again only after the retry interval).
 */
class SpecReaderInverters2Test {
    private fun spec(sleeps: Boolean) = Spec.parse(
        """
        {"id": "t", "manufacturer": "T", "name": "T", "kind": "inverter",
         "connection": {"sleeps": $sleeps, "limits": {"max_registers": 10}},
         "identify": {"serial": {"table": "input", "address": 10, "type": "str", "length": 3, "chars": "low"},
                      "checks": [{"table": "input", "address": 0, "type": "u16", "equals": 4370}]},
         "points": [
           {"key": "pv_power", "table": "input", "address": 1, "type": "u16", "unit": "W"},
           {"key": "x.far", "table": "input", "address": 40, "type": "u16"}
         ]}
        """.trimIndent(),
    )

    /** Registers by number; [offset] added by the device (a gateway that renumbers); [silence]: what "no answer" throws. */
    private class Fake(private val offset: Int = 0) : RegisterReader {
        val regs = HashMap<Int, Int>()
        var silence: IOException? = null
        var requests = 0

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            requests++
            silence?.let { throw it }
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i - offset] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun device(offset: Int = 0, pv: Int = 120) = Fake(offset).apply {
        regs[0] = 4370; regs[1] = pv; regs[10] = 'A'.code; regs[11] = 'B'.code; regs[12] = 'C'.code; regs[40] = 7
    }

    private fun driver(f: Fake, sleeps: Boolean = true, offset: Int = 0, retryMs: Long = 0) =
        SpecDriver(spec(sleeps), Vocabulary(emptyMap()), SpecTables(f, f, offset), 1, requestGapMs = 0, sleepRetryMs = retryMs)

    @Test
    fun addressOffsetIsAddedToEveryRequest() = runBlocking {
        val f = device(offset = 1) // the device answers register X at address X + 1
        assertTrue(runCatching { driver(f).identify() }.exceptionOrNull() is IOException)
        val d = driver(f, offset = 1)
        assertEquals("ABC", d.identify().serial) // one character per register, in the low byte
        assertEquals(120, d.read().pvPowerW)
    }

    @Test
    fun asleepOnlyAfterLowProduction() = runBlocking {
        for (silence in listOf(SocketTimeoutException("timeout"), ModbusException(0x0B), ModbusException(0x0A))) {
            // No answer before any reading: a failure.
            val first = device().apply { this.silence = silence }
            assertTrue(runCatching { driver(first).read() }.exceptionOrNull() is IOException)
            // Evening (120 W), then silence: asleep; it answers again in the morning.
            val f = device()
            val d = driver(f)
            assertEquals(120, d.read().pvPowerW)
            f.silence = silence
            val night = d.read()
            assertEquals(0, night.pvPowerW)
            assertEquals("Sleeping (no sun)", night.status)
            f.silence = null
            assertEquals(120, d.read().pvPowerW)
            // Silence at 5 kW: a failure.
            val noon = device(pv = 5_000)
            val n = driver(noon)
            n.read()
            noon.silence = silence
            assertTrue(runCatching { n.read() }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun gatewayNoAnswerFailsTheReadWithoutSleeps() = runBlocking {
        val f = device()
        val d = driver(f, sleeps = false)
        d.read()
        f.silence = ModbusException(0x0B)
        f.requests = 0
        val e = runCatching { d.read() }.exceptionOrNull()
        assertTrue(e is ModbusException && e.code == 0x0B)
        assertEquals(1, f.requests) // not asked again point by point
    }

    @Test
    fun asleepDeviceIsAskedAgainOnlyAfterTheRetryInterval() = runBlocking {
        val f = device()
        val d = driver(f, retryMs = 60_000)
        d.read()
        f.silence = SocketTimeoutException("timeout")
        assertEquals("Sleeping (no sun)", d.read().status)
        f.requests = 0
        f.silence = null
        assertEquals("Sleeping (no sun)", d.read().status)
        assertEquals(0, f.requests)
    }
}
