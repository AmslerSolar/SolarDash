// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.DetailSection
import java.util.Locale
import kotlin.math.abs

/** Building the detail view's sections: format values, skip what the device doesn't provide. */
object Details {
    fun fmt(value: Number?, unit: String = "", decimals: Int? = null): String? {
        value ?: return null
        val text = when (value) {
            is Double, is Float -> {
                val v = value.toDouble()
                val d = decimals ?: if (v == Math.floor(v) && abs(v) >= 100) 0 else if (abs(v) < 10) 2 else 1
                String.format(Locale.ROOT, "%.${d}f", v)
            }
            else -> value.toString()
        }
        return "$text $unit".trim()
    }

    fun enum(value: Int?, names: Map<Int, String>): String? = value?.let { v -> names[v]?.let { "$it ($v)" } ?: "$v" }

    fun bits(value: Long?, width: Int = 8): String? = value?.let { "0x" + it.toString(16).uppercase().padStart(width, '0') }

    fun section(title: String, vararg rows: Pair<String, String?>): DetailSection =
        DetailSection(title, rows.mapNotNull { (k, v) -> if (v.isNullOrEmpty()) null else k to v })
}
