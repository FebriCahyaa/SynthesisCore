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

import android.app.NotificationManager
import android.os.PowerManager

import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

/**
 * `battery_saver` and `zen_mode`, both through public APIs.
 *
 * Their change notifications are broadcast-only (ACTION_POWER_SAVE_MODE_CHANGED,
 * ACTION_INTERRUPTION_FILTER_CHANGED), which app_process cannot receive, so they are
 * polled. Both calls are a single cheap binder transaction.
 */
class PowerProvider : StateProvider {
    override val name = "power"

    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager

    override fun start(ctx: ProviderContext): TriggerMode {
        powerManager = ctx.context.getSystemService(PowerManager::class.java)
            ?: error("PowerManager unavailable")
        notificationManager = ctx.context.getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager unavailable")
        return TriggerMode.POLL
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.BATTERY_SAVER] = Protocol.flag(powerManager.isPowerSaveMode)
        out[Protocol.ZEN_MODE] = zenModeFromInterruptionFilter(notificationManager.currentInterruptionFilter).toString()
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long =
        if (interactive) 2_000L else 10_000L

    companion object {
        /**
         * Maps the public interruption filter to the `zen_mode` values of protocol 1
         * (Settings.Global.ZEN_MODE_*): 0 = off, 1 = priority, 2 = total silence, 3 = alarms.
         */
        fun zenModeFromInterruptionFilter(filter: Int): Int = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 1
            NotificationManager.INTERRUPTION_FILTER_NONE -> 2
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> 3
            else -> 0 // INTERRUPTION_FILTER_ALL / UNKNOWN
        }
    }
}
