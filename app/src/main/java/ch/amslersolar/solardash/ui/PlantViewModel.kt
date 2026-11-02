// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/** A plant's live state for its screens; the polling itself is shared through [LivePlants]. */
class PlantViewModel(plantId: String, live: LivePlants) : ViewModel() {
    val state: StateFlow<PlantState> = live.state(plantId)
}
