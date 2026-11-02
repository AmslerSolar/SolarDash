// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Colors for energy series (dashboard tiles and history charts). Each mode has its own steps, validated as
 * sets against the chart surface: the day chart's four lines and the energy chart's four bars each clear
 * colorblind (≥ 8 ΔE) and normal-vision (≥ 15 ΔE) separation on all pairs. Light solar and dark
 * consumption sit below 3:1 contrast, so series always carry a visible text label too.
 */
@Immutable
data class SeriesColors(
    val solar: Color,
    val consumption: Color,
    val battery: Color,
    /** Grid power and exported energy. */
    val grid: Color,
    val import: Color,
    /** Colour slots for channels added to the history chart (validated against the series above). */
    val channels: List<Color>,
)

private val LightSeries = SeriesColors(
    solar = Color(0xFFD99500),
    consumption = Color(0xFFAB2B2B),
    battery = Color(0xFF17A195),
    grid = Color(0xFF2F7FD1),
    import = Color(0xFFDC188B),
    channels = listOf(Color(0xFF2F2FEE), Color(0xFFF04C5C), Color(0xFF7D32AE)),
)

private val DarkSeries = SeriesColors(
    solar = Color(0xFFC98500),
    consumption = Color(0xFFBB231B),
    battery = Color(0xFF18AA99),
    grid = Color(0xFF3E8BE0),
    import = Color(0xFFD7428C),
    channels = listOf(Color(0xFF6A13EC), Color(0xFFD926B5), Color(0xFF8C2C96)),
)

val LocalSeriesColors = staticCompositionLocalOf { LightSeries }

private val LightColors = lightColorScheme(primary = Color(0xFF8A5A00), secondary = Color(0xFF2F7FD1))
private val DarkColors = darkColorScheme(primary = Color(0xFFF2A900), secondary = Color(0xFF3E8BE0))

@Composable
fun SolarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalSeriesColors provides if (dark) DarkSeries else LightSeries) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
