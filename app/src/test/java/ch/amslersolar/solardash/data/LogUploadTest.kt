// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sweep logs go only to https addresses. */
class LogUploadTest {
    @Test
    fun onlyHttpsAddressesAreAccepted() {
        assertTrue(LogUpload.valid(LogUpload.DEFAULT_URL))
        assertTrue(LogUpload.valid(" https://example.org/upload/sweeps "))
        for (bad in listOf("http://amsler-solar.ch/", "amsler-solar.ch", "", "https://", "ftp://x.ch/", "https:///path")) {
            assertFalse(bad, LogUpload.valid(bad))
        }
    }

    @Test
    fun onlyTheReceiversConfirmationCounts() {
        assertEquals("20261028-091800-a1b2c3d4-meter.txt", LogUpload.storedName("stored 20261028-091800-a1b2c3d4-meter.txt\n"))
        assertNull(LogUpload.storedName("<!DOCTYPE html><html>…"))
        assertNull(LogUpload.storedName("stored "))
        assertNull(LogUpload.storedName(""))
    }

    @Test
    fun uploadsSayWhatTheyAre() {
        assertTrue(LogUpload.userAgent().startsWith("SolarDash/"))
        assertTrue(LogUpload.KIND.startsWith("register-sweep-log/"))
    }
}
