// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.amslersolar.dashboard.SolarApp
import ch.amslersolar.dashboard.driver.ActivePolling
import ch.amslersolar.dashboard.driver.PlantReader
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
        for (plant in app.repository.plants.first()) {
            if (plant.devices.isEmpty() || ActivePolling.isActive(plant.id)) continue
            try {
                PlantReader(plant.devices).use { app.history.record(plant.id, it.read()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One unreachable plant shouldn't stop the others from being recorded.
            }
        }
        return Result.success()
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
