// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import org.junit.Assert.assertFalse
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
    fun uploadsSayWhatTheyAre() {
        assertTrue(LogUpload.userAgent().startsWith("AmslerSolarDashboard/"))
        assertTrue(LogUpload.KIND.startsWith("register-sweep-log/"))
    }
}
