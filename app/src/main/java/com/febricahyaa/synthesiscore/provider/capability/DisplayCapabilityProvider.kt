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

package com.febricahyaa.synthesiscore.provider.capability

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

import com.febricahyaa.synthesiscore.core.CapabilityModel
import com.febricahyaa.synthesiscore.core.CapabilityProvider
import com.febricahyaa.synthesiscore.core.CapabilitySchema
import com.febricahyaa.synthesiscore.core.CapabilitySourceInfo

/**
 * `display.*` from the framework.
 *
 * This is the half of the display domain fluxd cannot see. A native collector
 * can read the ROM's advertised refresh range out of system properties, but the
 * mode list, the panel geometry and the HDR types come from SurfaceFlinger, and
 * only a process holding a Context can ask. That division is the point of the
 * model: whichever subsystem can actually observe a fact collects it, and both
 * normalise into the same keys.
 */
class DisplayCapabilityProvider : CapabilityProvider {
    override val id = "synthesiscore.display"
    override val domains = listOf(CapabilitySchema.DOMAIN_DISPLAY)

    override fun collect(context: Context, out: CapabilityModel): CapabilitySourceInfo {
        val manager = context.getSystemService(DisplayManager::class.java)
            ?: return unavailable("DisplayManager unavailable")
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return unavailable("no default display")

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        out.set(CapabilitySchema.DOMAIN_DISPLAY, CapabilitySchema.Display.WIDTH_PX, metrics.widthPixels.toLong(), id)
        out.set(CapabilitySchema.DOMAIN_DISPLAY, CapabilitySchema.Display.HEIGHT_PX, metrics.heightPixels.toLong(), id)
        out.set(
            CapabilitySchema.DOMAIN_DISPLAY,
            CapabilitySchema.Display.DENSITY_DPI,
            metrics.densityDpi.toLong(),
            id,
        )

        out.set(
            CapabilitySchema.DOMAIN_DISPLAY,
            CapabilitySchema.Display.REFRESH_RATE_HZ,
            display.refreshRate.toDouble(),
            id,
        )

        // The supported mode list is what a refresh-rate policy will key off in a
        // later phase. Reported verbatim and unranked.
        val modes = display.supportedModes
        if (modes != null && modes.isNotEmpty()) {
            out.set(
                CapabilitySchema.DOMAIN_DISPLAY,
                CapabilitySchema.Display.SUPPORTED_MODES,
                modes.map { formatMode(it.physicalWidth, it.physicalHeight, it.refreshRate) },
                id,
            )
            val rates = modes.map { it.refreshRate.toDouble() }
            out.set(CapabilitySchema.DOMAIN_DISPLAY, CapabilitySchema.Display.MIN_REFRESH_RATE_HZ, rates.min(), id)
            out.set(CapabilitySchema.DOMAIN_DISPLAY, CapabilitySchema.Display.PEAK_REFRESH_RATE_HZ, rates.max(), id)
        }

        val hdr = display.hdrCapabilities?.supportedHdrTypes
        if (hdr != null && hdr.isNotEmpty()) {
            out.set(
                CapabilitySchema.DOMAIN_DISPLAY,
                CapabilitySchema.Display.HDR_TYPES,
                hdr.map { hdrTypeName(it) },
                id,
            )
        }

        out.set(
            CapabilitySchema.DOMAIN_DISPLAY,
            CapabilitySchema.Display.WIDE_COLOR_GAMUT,
            display.isWideColorGamut,
            id,
        )

        return CapabilitySourceInfo(
            id = id,
            domains = domains,
            status = CapabilitySchema.SourceStatus.OK,
            collectedAtMs = System.currentTimeMillis(),
        )
    }

    private fun unavailable(detail: String) = CapabilitySourceInfo(
        id = id,
        domains = domains,
        status = CapabilitySchema.SourceStatus.UNAVAILABLE,
        detail = detail,
        collectedAtMs = System.currentTimeMillis(),
    )

    companion object {
        /** "1080x2400@120.00", the form the model stores modes in. */
        fun formatMode(width: Int, height: Int, refreshRate: Float): String =
            String.format(java.util.Locale.ROOT, "%dx%d@%.2f", width, height, refreshRate)

        /** Names from android.view.Display.HdrCapabilities, which are stable constants. */
        fun hdrTypeName(type: Int): String = when (type) {
            1 -> "dolby_vision"
            2 -> "hdr10"
            3 -> "hlg"
            4 -> "hdr10_plus"
            else -> "unknown_$type"
        }
    }
}
