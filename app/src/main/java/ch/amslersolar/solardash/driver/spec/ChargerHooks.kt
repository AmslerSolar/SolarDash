// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.model.DeviceReading
import kotlin.math.roundToInt

/**
 * Charging power for a charger that only has an energy counter rising in steps (Huawei SCharger behind an EMMA: no
 * power register, the total energy 30506 rises by 0.2 kWh at a time, about once a minute at 11 kW).
 *
 * - At a step: the step over the time since the step before.
 * - Between steps: at most one step over the time since the last one, so a stop shows as falling power.
 * - No step for [IDLE_MS] (also since the first reading): 0 W; no car charges below about 1.4 kW, which makes a step at
 *   least every 9 minutes.
 * - Unknown (null) until a step's interval is known: at the start, and at the first step after a pause.
 * The power is that of the counter's last known step, so it lags by up to one step.
 */
class PowerFromEnergySteps(private val clock: () -> Long = System::currentTimeMillis) : SpecHooks {
    private var lastKwh: Double? = null
    private var watchingSince = 0L
    private var lastStepAt: Long? = null
    private var stepKwh: Double? = null
    private var powerW: Int? = null

    override fun adjust(values: Map<String, SpecValue>, reading: DeviceReading): DeviceReading {
        val kwh = values["consumer_energy"]?.takeIf { it.trusted }?.number ?: return reading
        val now = clock()
        val prev = lastKwh
        lastKwh = kwh
        when {
            prev == null || kwh < prev -> { // first reading, or the counter was reset
                watchingSince = now
                lastStepAt = null
                powerW = null
            }
            kwh > prev -> {
                val since = lastStepAt
                powerW = if (since != null && now - since in 1..IDLE_MS) (STEP_W_PER_KWH_MS * (kwh - prev) / (now - since)).roundToInt() else null
                stepKwh = kwh - prev
                lastStepAt = now
            }
        }
        val quietFrom = lastStepAt ?: watchingSince
        val quiet = now - quietFrom
        val power = when {
            quiet >= IDLE_MS -> 0
            powerW == null -> null
            else -> {
                // Between steps: at most one step over the time since the last one.
                val bound = stepKwh?.takeIf { quiet > 0 }?.let { (STEP_W_PER_KWH_MS * it / quiet).roundToInt() }
                if (bound != null) minOf(powerW!!, bound) else powerW
            }
        }
        if (power == null) return reading
        return reading.copy(
            loadPowerW = reading.loadPowerW ?: power,
            status = reading.status ?: if (power > 0) "Charging" else "Not charging",
        )
    }

    companion object {
        /** No step for this long: not charging. */
        const val IDLE_MS = 9 * 60_000L
        /** kWh per millisecond to W. */
        private const val STEP_W_PER_KWH_MS = 3_600_000_000.0
    }
}
