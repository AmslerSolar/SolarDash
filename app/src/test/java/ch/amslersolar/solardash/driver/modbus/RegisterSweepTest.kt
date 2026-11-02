// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import ch.amslersolar.solardash.data.RegisterSweeps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.net.SocketTimeoutException
import java.time.OffsetDateTime

/** The sweep goes from coarse to fine, finds the answered stretches cheaply, and gives up on a gone device. */
class RegisterSweepTest {
    /**
     * A device answering [answers] (value = address + 1000); a read reaching outside them gets exception 2, or no
     * answer with [silentGaps].
     */
    private fun device(
        answers: Set<Int>,
        silentGaps: Boolean = false,
        requests: MutableList<Pair<Int, Int>> = mutableListOf(),
    ): suspend (Int, Int) -> RegisterBlock = { start, count ->
        requests += start to count
        if ((start until start + count).all { it in answers }) {
            RegisterBlock(start, ByteArray(count * 2) { i -> ((start + i / 2 + 1000) shr if (i % 2 == 0) 8 else 0).toByte() })
        } else if (silentGaps) {
            throw SocketTimeoutException("Read timed out")
        } else {
            throw ModbusException(2)
        }
    }

    private suspend fun RegisterSweep.all(read: suspend (Int, Int) -> RegisterBlock, known: List<IntRange> = emptyList()) {
        known(known, read, pauseMs = 0)
        blocks(RegisterSweep.LARGE, read, pauseMs = 0)
        blocks(RegisterSweep.SMALL, read)
        probes(read, pauseMs = 0)
        edges(read, pauseMs = 0)
    }

    @Test
    fun theKnownRegistersComeFirstMergedIntoBlocks() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val sweep = RegisterSweep(0, 999)
        sweep.known(listOf(10..11, 12..13, 14..14, 500..501), device((10..14).toSet() + (500..501), requests = requests), pauseMs = 0)
        assertEquals(listOf(10 to 5, 500 to 2), requests)
        assertEquals((10..14).toList() + (500..501).toList(), sweep.values.keys.toList())
    }

    @Test
    fun theDevicesOwnFileIsReadRangeByRangeWhereABlockIsRefused() = runBlocking {
        // the file documents 10..11, 12..13 and 14..15; the device lacks 14..15, so the merged block 10..15 is refused
        val requests = mutableListOf<Pair<Int, Int>>()
        val sweep = RegisterSweep(0, 999)
        sweep.known(listOf(10..11, 12..13, 14..15), device((10..13).toSet(), requests = requests), split = true, pauseMs = 0)
        assertEquals(listOf(10 to 6, 10 to 2, 12 to 2, 14 to 2), requests)
        assertEquals((10..13).toList(), sweep.values.keys.toList())
    }

    @Test
    fun findsEveryStretchFromCoarseToFine() = runBlocking {
        // 0..9 and 40..44 are partly known; 100..140 is unknown and crosses block edges
        val answers = (0..9).toSet() + (40..44) + (100..140)
        val sweep = RegisterSweep(0, 255)
        sweep.all(device(answers), known = listOf(0..3, 40..41))
        assertNull(sweep.stopped)
        assertEquals(answers.sorted(), sweep.values.keys.toList())
        assertEquals(1042, sweep.values[42])
    }

    @Test
    fun theTimeoutFollowsTheAnswerTimesWithinItsBounds() {
        val t = AnswerTimeout()
        assertEquals(RegisterSweep.TIMEOUT_MS, t.ms)  // until the first answer
        assertEquals(AnswerTimeout.MIN_MS, t.answered(20))
        assertEquals(450, t.answered(150))
        assertEquals(RegisterSweep.TIMEOUT_MS, t.answered(900))
        repeat(AnswerTimeout.WINDOW) { t.answered(50) }  // the slow answer has left the window
        assertEquals(AnswerTimeout.MIN_MS, t.ms)
    }

    @Test
    fun aDeviceSilentInItsGapsStillShowsItsStretches() = runBlocking {
        val answers = (100..140).toSet()
        val sweep = RegisterSweep(0, 255)
        sweep.all(device(answers, silentGaps = true))
        assertEquals(answers.sorted(), sweep.values.keys.toList())
    }

    @Test
    fun aMeterShapedLikeTheDtsu666H() = runBlocking {
        // holding map of a real DTSU666-H: 0..55, 2000..2029, 2102..2265 answer, everything else is exception 2
        val answers = (0..55).toSet() + (2000..2029) + (2102..2265)
        val requests = mutableListOf<Pair<Int, Int>>()
        val sweep = RegisterSweep()
        sweep.all(device(answers, requests = requests))
        assertEquals(answers.sorted(), sweep.values.keys.toList())
        // one request per register (65536) before; now 64-blocks, 16-blocks and one probe where nothing answered
        assertTrue("${requests.size} requests", requests.size < 1024 + 4096 * 2 + 100)
    }

    @Test
    fun givesUpOnAnUnreachableDevice() = runBlocking {
        val sweep = RegisterSweep()
        val requests = mutableListOf<Pair<Int, Int>>()
        sweep.all({ s, c -> requests += s to c; throw SocketTimeoutException("connect timed out") })
        assertEquals("connect timed out", sweep.stopped)
        assertEquals(RegisterSweep.MAX_SILENT, requests.size)
        assertTrue(sweep.values.isEmpty())
    }

    @Test
    fun givesUpWhenTheDeviceGoesAwayAfterAnswering() = runBlocking {
        var gone = false
        val answers = (0..63).toSet()
        val sweep = RegisterSweep()
        sweep.all({ start, count ->
            if (gone) throw SocketTimeoutException("Read timed out")
            if (start > 63) gone = true  // answers its first block, then nothing at all
            device(answers)(start, count)
        })
        assertEquals("Read timed out", sweep.stopped)
        assertEquals(answers.toList(), sweep.values.keys.toList())
    }

    @Test
    fun theLogHasOneLinePerFrameWithTimeAndHex() {
        val out = StringWriter()
        val log = RegisterSweeps.Log(out) { OffsetDateTime.parse("2026-10-26T12:00:00.123+01:00") }
        log.comment("pass 1")
        log.frame(true, byteArrayOf(0x0B, 0x03, 0x08, 0x36, 0x00, 0x02, 0x27, 0x6B))
        log.frame(false, byteArrayOf(0x0B, 0x83.toByte(), 0x02))
        log.noAnswer("Read timed out")
        assertEquals(
            "# pass 1\n" +
                "2026-10-26T12:00:00.123+01:00 > 0B 03 08 36 00 02 27 6B\n" +
                "2026-10-26T12:00:00.123+01:00 < 0B 83 02\n" +
                "2026-10-26T12:00:00.123+01:00 ! Read timed out\n",
            out.toString(),
        )
        assertFalse(out.toString().contains("{"))
    }
}
