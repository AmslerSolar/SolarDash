// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** The sweep finds the answered registers between refused and silent ones, and gives up on a gone device. */
class RegisterSweepTest {
    /** A device with registers 0..9 and 40..44 (value = address + 1000); 20..29 time out; the rest is exception 2. */
    private fun device(requests: MutableList<Pair<Int, Int>> = mutableListOf()): suspend (Int, Int) -> RegisterBlock = { start, count ->
        requests += start to count
        val range = start until start + count
        when {
            range.all { it in 0..9 || it in 40..44 } -> RegisterBlock(start, ByteArray(count * 2) { i -> ((start + i / 2 + 1000) shr if (i % 2 == 0) 8 else 0).toByte() })
            range.any { it in 20..29 } -> throw SocketTimeoutException("Read timed out")
            else -> throw ModbusException(2)
        }
    }

    @Test
    fun findsRegistersBetweenGaps() = runBlocking {
        val sweep = RegisterSweep(0, 63)
        sweep.run(device(), pauseMs = 0)
        assertTrue(sweep.complete)
        assertNull(sweep.stopped)
        assertEquals((0..9).toList() + (40..44).toList(), sweep.values.keys.toList())
        assertEquals(1042, sweep.values[42])
        assertEquals((20..29).toSet(), sweep.silent.toSet())
        assertEquals(64 - 15 - 10, sweep.exceptions.size)
        assertEquals(2, sweep.exceptions[10])
    }

    @Test
    fun anAnsweringBlockIsOneRequest() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        RegisterSweep(0, 9).run(device(requests), pauseMs = 0)
        assertEquals(listOf(0 to 10), requests)
    }

    @Test
    fun givesUpOnAGoneDevice() = runBlocking {
        val sweep = RegisterSweep(0, 999)
        sweep.run({ _, _ -> throw SocketTimeoutException("connect timed out") }, pauseMs = 0)
        assertFalse(sweep.complete)
        assertEquals("connect timed out", sweep.stopped)
        assertTrue(sweep.values.isEmpty())
    }
}
