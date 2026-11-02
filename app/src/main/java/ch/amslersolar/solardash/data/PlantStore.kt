// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.Plant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The saved plant configuration, readable across app updates (and back to an older APK):
 * - stored as `{"version": n, "plants": [...]}`; version 0 is the original bare list. Older versions are
 *   converted step by step by [MIGRATIONS], so a later format change adds one step, never a break.
 * - each plant and device is read on its own: a device this version can't read (an unknown type, say) is kept
 *   as it was in [Plant.unreadableDevices] and written back, instead of the whole list being dropped.
 * - unknown fields are ignored and unknown enum values fall back to their default, so newer data stays readable.
 * - stored enum names are part of the format: renaming a [ch.amslersolar.solardash.model.DeviceType] needs an
 *   entry in [RENAMED_TYPES] (StoredFormatTest guards the names).
 */
object PlantStore {
    const val VERSION = 1

    /** Old → new [ch.amslersolar.solardash.model.DeviceType] names, applied when reading. */
    val RENAMED_TYPES: Map<String, String> = emptyMap()

    /** Step from version n to n + 1, on the whole stored object. */
    private val MIGRATIONS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        0 to { it }, // 0 → 1: only the wrapper (added in readRoot)
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    class Unreadable(cause: Throwable) : Exception("Saved plants can't be read", cause)

    /** Empty or missing text is no plants; text that isn't JSON at all throws [Unreadable] (nothing may overwrite it). */
    fun decode(raw: String?): List<Plant> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = try { json.parseToJsonElement(raw) } catch (e: Exception) { throw Unreadable(e) }
        var obj = when (root) {
            is JsonArray -> buildJsonObject { put("version", 0); put("plants", root) }
            is JsonObject -> root
            else -> throw Unreadable(IllegalArgumentException("not a plant list"))
        }
        var version = obj["version"]?.jsonPrimitive?.intOrNull ?: 0
        while (version < VERSION) {
            obj = MIGRATIONS[version]?.invoke(obj) ?: break
            version++
        }
        val plants = (obj["plants"] as? JsonArray).orEmpty()
        return plants.mapNotNull { (it as? JsonObject)?.let(::plant) }
    }

    fun encode(plants: List<Plant>): String = buildJsonObject {
        put("version", VERSION)
        put("plants", JsonArray(plants.map(::encodePlant)))
    }.toString()

    private fun plant(obj: JsonObject): Plant? {
        val devices = ArrayList<DeviceConfig>()
        val unreadable = ArrayList<JsonObject>()
        for (d in (obj["devices"] as? JsonArray).orEmpty()) {
            val device = d as? JsonObject ?: continue
            val renamed = renameType(device)
            runCatching { json.decodeFromJsonElement<DeviceConfig>(renamed) }
                .onSuccess { devices += it }.onFailure { unreadable += device }
        }
        val rest = JsonObject(obj - "devices")
        return runCatching { json.decodeFromJsonElement<Plant>(rest) }.getOrNull()
            ?.copy(devices = devices, unreadableDevices = unreadable)
    }

    private fun renameType(device: JsonObject): JsonObject {
        val type = (device["type"] as? JsonPrimitive)?.content ?: return device
        val to = RENAMED_TYPES[type] ?: return device
        return JsonObject(device + ("type" to JsonPrimitive(to)))
    }

    private fun encodePlant(plant: Plant): JsonElement {
        val obj = json.encodeToJsonElement(plant).jsonObject
        val devices = obj["devices"]?.jsonArray.orEmpty() + plant.unreadableDevices
        return JsonObject(obj + ("devices" to JsonArray(devices)))
    }
}
