// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A register sweep pauses the other traffic while it runs, and only then. */
class TrafficPauseTest {
    @Test
    fun theOtherTrafficWaitsWhileASweepRuns() = runBlocking {
        assertFalse(TrafficPause.isPaused)
        val sweepGo = CompletableDeferred<Unit>()
        val poll = async { TrafficPause.pauseWithin(5_000) } // a poll waiting for its next round
        val sweep = launch { TrafficPause.during { sweepGo.await() } }
        assertTrue("the waiting poll stops at once", withTimeout(1_000) { poll.await() })
        assertTrue(TrafficPause.isPaused)
        val resumed = async { TrafficPause.awaitResumed(); "resumed" }
        sweepGo.complete(Unit)
        sweep.join()
        assertEquals("resumed", withTimeout(1_000) { resumed.await() })
        assertFalse(TrafficPause.isPaused)
    }

    @Test
    fun aFailedOrStoppedSweepResumesTheTraffic() = runBlocking {
        runCatching { TrafficPause.during { error("connection lost") } }
        assertFalse(TrafficPause.isPaused)
        val sweep = launch { TrafficPause.during { CompletableDeferred<Unit>().await() } }
        withTimeout(1_000) { while (!TrafficPause.isPaused) kotlinx.coroutines.yield() }
        sweep.cancel() // "Stop sweep"
        sweep.join()
        assertFalse(TrafficPause.isPaused)
        assertFalse(TrafficPause.pauseWithin(50))
    }
}
