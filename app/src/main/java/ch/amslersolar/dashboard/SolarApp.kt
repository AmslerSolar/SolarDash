// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard

import ch.amslersolar.dashboard.driver.spec.SpecLibrary
import ch.amslersolar.dashboard.model.FaultLog
import ch.amslersolar.dashboard.net.LocalNetworks
import ch.amslersolar.dashboard.driver.net.DeviceNetwork
import ch.amslersolar.dashboard.i18n.DetailTexts
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ch.amslersolar.dashboard.data.DeviceRegistry
import ch.amslersolar.dashboard.data.HistoryRepository
import ch.amslersolar.dashboard.data.PlantRepository
import ch.amslersolar.dashboard.discovery.DeviceScanner
import ch.amslersolar.dashboard.discovery.MdnsBrowser
import ch.amslersolar.dashboard.ui.LivePlants
import ch.amslersolar.dashboard.work.HistoryWorker

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
            faultLog = faultLog, network = { LocalNetworks.get(this).state.value })
    }

    override fun onCreate() {
        super.onCreate()
        DetailTexts.init(this)
        DeviceNetwork.current = LocalNetworks.get(this) // devices on Wi-Fi without internet, too
        SpecLibrary.current = SpecLibrary { name -> assets.open("modbusspec/$name").bufferedReader().use { it.readText() } }
        HistoryWorker.schedule(this)
    }
}
