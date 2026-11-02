// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Measured values in the fault details: no binary rounding noise. */
class FaultDetailsShortTest {
    @Test
    fun valuesKeepAtMostThreeDecimals() {
        assertEquals("38.8", FaultDetails.short(38.800000000000004))
        assertEquals("1.2", FaultDetails.short(1.2000000000000002))
        assertEquals("237.6", FaultDetails.short(237.60000000000002))
        assertEquals("49.99", FaultDetails.short(49.99))
        assertEquals("414.3", FaultDetails.short(414.3))
        assertEquals("230", FaultDetails.short(230.0))
        assertEquals("0.125", FaultDetails.short(0.1249999))
    }
}
