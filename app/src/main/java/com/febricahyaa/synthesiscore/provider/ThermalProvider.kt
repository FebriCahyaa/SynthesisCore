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

import android.os.Build
import android.os.PowerManager

import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

/**
 * `thermal_status` (headroom), `thermal_level` and `thermal_api_available`.
 *
 * - thermal_level: [PowerManager.getCurrentThermalStatus] (API 29), pushed by
 *   [PowerManager.addThermalStatusListener]. 0 = none ... 6 = shutdown. This is the
 *   fallback when the vendor thermal HAL provides no headroom.
 * - thermal_status: [PowerManager.getThermalHeadroom] (API 30) with a 1 s forecast,
 *   normalised to [0.00, 1.00] where 1.00 = cool; -1.00 when unsupported/NaN. There is
 *   no headroom callback, so it is polled; PowerManager caches it client-side for
 *   500 ms anyway, so polling faster than that gains nothing.
 */
class ThermalProvider : StateProvider {
    override val name = "thermal"

    private lateinit var powerManager: PowerManager
    private var statusListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        powerManager = ctx.context.getSystemService(PowerManager::class.java)
            ?: error("PowerManager unavailable")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TriggerMode.POLL

        return try {
            val listener = PowerManager.OnThermalStatusChangedListener { ctx.invalidate(this) }
            powerManager.addThermalStatusListener(ctx.executor, listener)
            statusListener = listener
            TriggerMode.EVENT
        } catch (t: Throwable) {
            Log.w(name, "Thermal status listener unavailable, polling instead: ${t.message}")
            TriggerMode.POLL
        }
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.THERMAL_API_AVAILABLE] = Protocol.flag(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        out[Protocol.THERMAL_STATUS] = readHeadroom()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out[Protocol.THERMAL_LEVEL] = powerManager.currentThermalStatus.toString()
        }
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long =
        // Headroom has no callback: poll it every second while the screen is on.
        if (interactive) 1_000L else 10_000L

    override fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            statusListener?.let { powerManager.removeThermalStatusListener(it) }
        }
        statusListener = null
    }

    private fun readHeadroom(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return UNSUPPORTED
        return formatHeadroom(powerManager.getThermalHeadroom(FORECAST_SECONDS))
    }

    companion object {
        private const val FORECAST_SECONDS = 1
        const val UNSUPPORTED = "-1.00"

        /** Clamps to [0, 1]; NaN (no HAL support) becomes [UNSUPPORTED]. */
        fun formatHeadroom(headroom: Float): String =
            if (headroom.isNaN()) UNSUPPORTED else Protocol.decimal(headroom.coerceIn(0f, 1f), 2)
    }
}
