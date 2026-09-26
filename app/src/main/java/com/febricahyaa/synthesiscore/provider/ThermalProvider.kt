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

import com.febricahyaa.synthesiscore.core.ListenerProxy
import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

import java.util.concurrent.Executor

/**
 * `thermal_status` (headroom), `thermal_level` and `thermal_api_available`.
 *
 * - thermal_level: [PowerManager.getCurrentThermalStatus] (API 29), pushed by
 *   [PowerManager.addThermalStatusListener]. 0 = none ... 6 = shutdown. This is the
 *   fallback when the vendor thermal HAL provides no headroom.
 * - thermal_status: [PowerManager.getThermalHeadroom] (API 30) with a 1 s forecast,
 *   normalised to [0.00, 1.00] where 1.00 = cool; -1.00 when unsupported/NaN.
 *   Android 16+ pushes headroom changes through `addThermalHeadroomListener`; it is a
 *   flagged API (android.os.allow_thermal_thresholds_callback) that ROMs may disable,
 *   so it is bound by reflection and headroom falls back to a 1 s poll without it.
 */
class ThermalProvider : StateProvider {
    override val name = "thermal"

    private lateinit var powerManager: PowerManager
    private var statusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var headroomListener: Any? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        powerManager = ctx.context.getSystemService(PowerManager::class.java)
            ?: error("PowerManager unavailable")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TriggerMode.POLL

        try {
            val listener = PowerManager.OnThermalStatusChangedListener { ctx.invalidate(this) }
            powerManager.addThermalStatusListener(ctx.executor, listener)
            statusListener = listener
        } catch (t: Throwable) {
            Log.w(name, "Thermal status listener unavailable, polling instead: ${t.message}")
            return TriggerMode.POLL
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            headroomListener = registerHeadroomListener(ctx)
        }
        return TriggerMode.EVENT
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.THERMAL_API_AVAILABLE] = Protocol.flag(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        out[Protocol.THERMAL_STATUS] = readHeadroom()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out[Protocol.THERMAL_LEVEL] = powerManager.currentThermalStatus.toString()
        }
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long = when {
        !interactive -> 10_000L
        headroomListener != null -> 5_000L // safety net; changes arrive by callback
        else -> 1_000L // no headroom callback: poll it every second
    }

    override fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            statusListener?.let { powerManager.removeThermalStatusListener(it) }
        }
        headroomListener?.let { listener ->
            try {
                PowerManager::class.java
                    .getMethod("removeThermalHeadroomListener", Class.forName(HEADROOM_LISTENER))
                    .invoke(powerManager, listener)
            } catch (t: Throwable) {
                Log.w(name, "Failed to remove headroom listener: ${t.message}")
            }
        }
        statusListener = null
        headroomListener = null
    }

    /** Binds `addThermalHeadroomListener(Executor, listener)`; null when unavailable. */
    private fun registerHeadroomListener(ctx: ProviderContext): Any? = try {
        val listener = ListenerProxy.create(HEADROOM_LISTENER, "onThermalHeadroomChanged") { ctx.invalidate(this) }
        PowerManager::class.java
            .getMethod("addThermalHeadroomListener", Executor::class.java, Class.forName(HEADROOM_LISTENER))
            .invoke(powerManager, ctx.executor, listener)
        listener
    } catch (t: Throwable) {
        Log.w(name, "Thermal headroom listener unavailable, polling headroom: ${t.cause?.message ?: t.message}")
        null
    }

    private fun readHeadroom(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return UNSUPPORTED
        return formatHeadroom(powerManager.getThermalHeadroom(FORECAST_SECONDS))
    }

    companion object {
        private const val FORECAST_SECONDS = 1
        private const val HEADROOM_LISTENER = "android.os.PowerManager\$OnThermalHeadroomChangedListener"
        const val UNSUPPORTED = "-1.00"

        /**
         * Headroom left, 1.00 = cool. getThermalHeadroom() grows with heat (1.0 = SEVERE
         * throttling, above 1.0 past it), so it is inverted after clamping to [0, 1];
         * NaN (no HAL support) becomes [UNSUPPORTED].
         */
        fun formatHeadroom(headroom: Float): String =
            if (headroom.isNaN()) UNSUPPORTED else Protocol.decimal(1f - headroom.coerceIn(0f, 1f), 2)
    }
}
