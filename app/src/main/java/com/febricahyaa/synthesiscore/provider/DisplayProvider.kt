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

import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display

import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

/**
 * `screen_awake`: [PowerManager.isInteractive], triggered by display state changes.
 *
 * Screen on/off broadcasts cannot be received from app_process (the process has no
 * app record in ActivityManager, so registerReceiver silently returns null).
 * [DisplayManager.DisplayListener] is a binder callback and works without one.
 */
class DisplayProvider : StateProvider, DisplayManager.DisplayListener {
    override val name = "display"

    private lateinit var ctx: ProviderContext
    private lateinit var powerManager: PowerManager
    private var displayManager: DisplayManager? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        this.ctx = ctx
        powerManager = ctx.context.getSystemService(PowerManager::class.java)
            ?: error("PowerManager unavailable")

        return try {
            displayManager = ctx.context.getSystemService(DisplayManager::class.java)
                ?: error("DisplayManager unavailable")
            displayManager!!.registerDisplayListener(this, ctx.handler)
            TriggerMode.EVENT
        } catch (t: Throwable) {
            Log.w(name, "Display listener unavailable, polling instead: ${t.message}")
            displayManager = null
            TriggerMode.POLL
        }
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.SCREEN_AWAKE] = Protocol.flag(powerManager.isInteractive)
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long = when (mode) {
        TriggerMode.EVENT -> 5_000L
        else -> 1_000L
    }

    override fun stop() {
        displayManager?.unregisterDisplayListener(this)
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) ctx.invalidate(this)
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit
}
