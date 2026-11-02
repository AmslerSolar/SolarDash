// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash

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
