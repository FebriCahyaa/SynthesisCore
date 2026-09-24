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

import com.febricahyaa.synthesiscore.provider.foreground.PackageNames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-independent logic of the providers. */
class ProviderLogicTest {
    @Test
    fun zenMode_mapsInterruptionFilterToProtocolValues() {
        assertEquals(0, PowerProvider.zenModeFromInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertEquals(1, PowerProvider.zenModeFromInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertEquals(2, PowerProvider.zenModeFromInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertEquals(3, PowerProvider.zenModeFromInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertEquals(0, PowerProvider.zenModeFromInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }

    @Test
    fun headroom_isClampedAndNaNIsUnsupported() {
        assertEquals("0.85", ThermalProvider.formatHeadroom(0.854f))
        assertEquals("1.00", ThermalProvider.formatHeadroom(1.7f))
        assertEquals("0.00", ThermalProvider.formatHeadroom(-0.2f))
        assertEquals(ThermalProvider.UNSUPPORTED, ThermalProvider.formatHeadroom(Float.NaN))
    }

    @Test
    fun batteryTemperature_convertsTenthsAndRejectsGarbage() {
        assertEquals(36.4f, BatteryProvider.parseTenthsCelsius(364))
        assertEquals(-5.0f, BatteryProvider.parseTenthsCelsius(-50))
        assertNull(BatteryProvider.parseTenthsCelsius(9999))
    }

    @Test
    fun gkiKernel_detectedFromRelease() {
        assertTrue(KernelProvider.isGkiKernel("5.15.123-android13-8-00001-gabcdef"))
        assertFalse(KernelProvider.isGkiKernel("4.19.157-perf+"))
        assertFalse(KernelProvider.isGkiKernel(""))
    }

    @Test
    fun packageNames_extractsPackageFromText() {
        assertEquals(
            "com.example.game",
            PackageNames.extract("ComponentInfo{com.example.game/com.example.game.MainActivity}")
        )
        assertEquals("com.example.app", PackageNames.extract("Task: Com.Example.App"))
        assertNull(PackageNames.extract(null))
        assertNull(PackageNames.extract("no package here"))
        assertNull(PackageNames.extract(".leading"))
    }
}
