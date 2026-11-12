// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash

import ch.amslersolar.solardash.model.LastReadings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.content.edit
import ch.amslersolar.solardash.driver.spec.SpecLibrary
import ch.amslersolar.solardash.model.FaultLog
import ch.amslersolar.solardash.net.LocalNetworks
import ch.amslersolar.solardash.driver.net.DeviceNetwork
import ch.amslersolar.solardash.i18n.DetailTexts
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ch.amslersolar.solardash.data.DeviceRegistry
import ch.amslersolar.solardash.data.HistoryRepository
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.discovery.DeviceScanner
import ch.amslersolar.solardash.remote.Collector
import ch.amslersolar.solardash.remote.Observer
import ch.amslersolar.solardash.discovery.MdnsBrowser
import ch.amslersolar.solardash.ui.LivePlants
import ch.amslersolar.solardash.work.HistoryWorker

class SolarApp : Application() {
    val repository by lazy { PlantRepository(this) }
    val history by lazy { HistoryRepository(this) }
    /** When which problem happened (shared by live polling and background recording). */
    val faultLog by lazy { FaultLog(history, appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})") }
    /** Device data follows the device (type + serial), across removing and adding it again. */
    val registry by lazy { DeviceRegistry(repository, history) }
    /** Live polling per plant, shared by the screens and the kiosk service. */
    val live by lazy {
        LivePlants(repository, history, CoroutineScope(SupervisorJob() + Dispatchers.Default), registry, localHosts = { DeviceScanner.localHosts(this) },
            announced = { MdnsBrowser.collect(this, 5_000) },
            faultLog = faultLog, network = { LocalNetworks.get(this).state.value }, collector = collector, observer = observer)
    }
    /** Sends shared plants to their stores. */
    val collector by lazy {
        Collector(repository, history, CoroutineScope(SupervisorJob() + Dispatchers.IO), java.io.File(filesDir, "observer/collector.json"))
    }
    /** Reads observed plants from their stores. */
    val observer by lazy { Observer(repository, history, java.io.File(filesDir, "observer/observer.json")) }

    override fun onCreate() {
        super.onCreate()
        DetailTexts.init(this)
        DeviceNetwork.current = LocalNetworks.get(this) // devices on Wi-Fi without internet, too
        SpecLibrary.current = SpecLibrary { name -> assets.open("modbusspec/$name").bufferedReader().use { it.readText() } }
        HistoryWorker.schedule(this)
        collector.start()
        // Inverters that sleep: known as asleep at night right away, not as "no connection", by their last reading
        // and their size, the highest solar power seen (LastReadings; the highest is kept across restarts).
        val peaks = getSharedPreferences("solar_peaks", MODE_PRIVATE)
        for ((id, w) in peaks.all) (w as? Int)?.let { LastReadings.shared.seedPeak(id, it) }
        LastReadings.shared.onPeak = { id, w -> peaks.edit { putInt(id, w) } }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                for (d in repository.plants.first().flatMap { it.devices }) {
                    history.lastDeviceReading(d.id)?.let { LastReadings.shared.seed(d.id, it) }
                    history.peakDevicePv(d.id)?.let { LastReadings.shared.seedPeak(d.id, it) }
                }
            }
        }
    }
}
