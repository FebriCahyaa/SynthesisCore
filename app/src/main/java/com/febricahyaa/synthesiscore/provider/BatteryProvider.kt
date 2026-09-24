/*
 * Copyright (C) 2026 FebriCahyaa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.febricahyaa.synthesiscore.provider

import android.os.BatteryManager

import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

import java.io.File

/**
 * `charging_state`, `battery_level` and `battery_temp`.
 *
 * The sticky ACTION_BATTERY_CHANGED intent is out of reach from app_process
 * (registerReceiver needs an app record), so charging and capacity come from
 * [BatteryManager] and temperature from the power_supply sysfs node that backs it.
 */
class BatteryProvider : StateProvider {
    override val name = "battery"

    private lateinit var batteryManager: BatteryManager
    private var tempNode: File? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        batteryManager = ctx.context.getSystemService(BatteryManager::class.java)
            ?: error("BatteryManager unavailable")
        tempNode = TEMP_NODES.map(::File).firstOrNull { it.canRead() }
        return TriggerMode.POLL
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.CHARGING_STATE] = Protocol.flag(batteryManager.isCharging)

        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity in 0..100) out[Protocol.BATTERY_LEVEL] = capacity.toString()

        readTemperature()?.let { out[Protocol.BATTERY_TEMP] = Protocol.decimal(it, 1) }
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long =
        if (interactive) 2_000L else 30_000L

    /** Battery temperature in °C; the node reports tenths of a degree. */
    private fun readTemperature(): Float? {
        val raw = try {
            tempNode?.readText()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        } ?: return null
        return parseTenthsCelsius(raw)
    }

    companion object {
        private val TEMP_NODES = listOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/Battery/temp",
        )

        /** Converts tenths of °C to °C, rejecting values outside a plausible range. */
        fun parseTenthsCelsius(raw: Int): Float? {
            val celsius = raw / 10f
            return if (celsius in -40f..120f) celsius else null
        }
    }
}
