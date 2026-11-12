// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.work

import ch.amslersolar.solardash.net.LocalNetworks
import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.amslersolar.solardash.SolarApp
import ch.amslersolar.solardash.driver.ActivePolling
import ch.amslersolar.solardash.driver.PlantReader
import ch.amslersolar.solardash.driver.TrafficPause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Records one sample per plant roughly every 15 minutes while the app isn't open (Android's minimum
 * for periodic work; the system may defer it further in battery saver or Doze).
 */
class HistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SolarApp
        if (TrafficPause.isPaused) return Result.success() // a register sweep is running: the next run records
        for (plant in app.repository.plants.first()) {
            // Observed plants: what's new on their stores; shared ones: what the stores don't have yet.
            if (plant.observed != null) {
                try { app.observer.sync(plant.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                continue
            }
            if (plant.sharing != null) {
                try { app.collector.sync(plant.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            repairOnce(app, plant)
            if (plant.devices.isEmpty() || ActivePolling.isActive(plant.id)) continue
            try {
                PlantReader(plant.devices).use {
                    val snapshot = it.read()
                    app.history.record(plant.id, snapshot)
                    app.faultLog.update(plant, snapshot, LocalNetworks.get(app).state.value, continuous = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One unreachable plant shouldn't stop the others from being recorded.
            }
        }
        return Result.success()
    }

    /** Repairs of recorded history that run once per plant (see HistoryRepository.repairSolarDays). */
    private suspend fun repairOnce(app: SolarApp, plant: ch.amslersolar.solardash.model.Plant) {
        val prefs = app.getSharedPreferences("history_repairs", Context.MODE_PRIVATE)
        val key = "solar_days_1:${plant.id}"
        if (prefs.getBoolean(key, false)) return
        try {
            app.history.repairSolarDays(plant)
            prefs.edit { putBoolean(key, true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Tried again with the next run.
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "history",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistoryWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
