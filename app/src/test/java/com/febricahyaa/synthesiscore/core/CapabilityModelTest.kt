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

package com.febricahyaa.synthesiscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.util.Locale

class CapabilityModelTest {

    @Test
    fun typedAccessorsDoNotCoerceAcrossTypes() {
        val m = CapabilityModel()
        m.set("vulkan", "available", true, "probe")
        m.set("vulkan", "device_count", 2L, "probe")
        m.set("display", "refresh_rate_hz", 120.5, "probe")
        m.set("gpu", "model", "Adreno (TM) 730", "probe")
        m.set("vulkan", "device_extensions", listOf("VK_KHR_swapchain"), "probe")

        assertEquals(true, m.getBool("vulkan", "available"))
        assertEquals(2L, m.getInt("vulkan", "device_count"))
        assertEquals(120.5, m.getDouble("display", "refresh_rate_hz")!!, 0.0001)
        assertEquals("Adreno (TM) 730", m.getString("gpu", "model"))
        assertEquals(1, m.getList("vulkan", "device_extensions")!!.size)
        assertEquals(5, m.factCount())

        assertNull(m.getInt("vulkan", "available"))
        assertNull(m.getString("vulkan", "device_count"))
        assertNull(m.getBool("gpu", "model"))
    }

    @Test
    fun absentMeansAbsentNotFalseOrZero() {
        val m = CapabilityModel()
        assertFalse(m.has("vulkan", "available"))
        assertNull(m.getBool("vulkan", "available"))
        assertNull(m.getInt("vulkan", "device_count"))
        assertNull(m.sourceOf("vulkan", "available"))
        assertEquals(0, m.factCount())
    }

    @Test
    fun wholeNumberedRateReadsBackAsADouble() {
        val m = CapabilityModel()
        m.set("display", "refresh_rate_hz", 60L, "probe")
        assertEquals(60.0, m.getDouble("display", "refresh_rate_hz")!!, 0.0001)
    }

    @Test
    fun provenanceTracksPerKeyOverridesWithinADomain() {
        val m = CapabilityModel()
        m.set("gpu", "vendor", "qualcomm", "fluxd.vulkan")
        m.set("gpu", "kgsl_present", true, "fluxd.gpu_sysfs")

        assertEquals("fluxd.vulkan", m.sourceOf("gpu", "vendor"))
        assertEquals("fluxd.gpu_sysfs", m.sourceOf("gpu", "kgsl_present"))
    }

    @Test
    fun mergeKeepsTheFirstObservation() {
        val primary = CapabilityModel()
        primary.set("gpu", "vendor", "qualcomm", "fluxd.vulkan")

        val secondary = CapabilityModel()
        secondary.set("gpu", "vendor", "unknown", "fluxd.gpu_sysfs")
        secondary.set("gpu", "kgsl_present", true, "fluxd.gpu_sysfs")
        secondary.addSource(CapabilitySourceInfo("fluxd.gpu_sysfs", listOf("gpu")))

        val taken = primary.merge(secondary)

        // The weaker guess must not overwrite the stronger observation.
        assertEquals(1, taken)
        assertEquals("qualcomm", primary.getString("gpu", "vendor"))
        assertEquals(true, primary.getBool("gpu", "kgsl_present"))
        assertEquals(1, primary.sourceList().size)
    }

    @Test
    fun addSourceReplacesByIdRatherThanAppending() {
        val m = CapabilityModel()
        m.addSource(CapabilitySourceInfo("fluxd.vulkan", listOf("vulkan"), "unavailable", "no loader", 1))
        m.addSource(CapabilitySourceInfo("fluxd.vulkan", listOf("vulkan", "gpu"), "ok", "", 2))

        assertEquals(1, m.sourceList().size)
        assertEquals("ok", m.sourceList()[0].status)
        assertEquals(2, m.sourceList()[0].domains.size)
    }

    @Test
    fun jsonHasTheCanonicalShape() {
        val m = CapabilityModel(generatedAtMs = 1730000000000L)
        m.set("vulkan", "available", true, "fluxd.vulkan")
        m.set("vulkan", "device_count", 1L, "fluxd.vulkan")
        m.addSource(CapabilitySourceInfo("fluxd.vulkan", listOf("vulkan"), "ok", "", 1730000000001L))

        val json = m.toJson()
        assertTrue(json.contains("\"schema_version\": 4"))
        assertTrue(json.contains("\"generated_at_ms\": 1730000000000"))
        assertTrue(json.contains("\"capabilities\""))
        assertTrue(json.contains("\"provenance\""))
        assertTrue(json.contains("\"sources\""))
        assertTrue(json.contains("\"_source\": \"fluxd.vulkan\""))
        assertTrue(json.contains("\"available\": true"))
        assertTrue(json.contains("\"device_count\": 1"))
    }

    @Test
    fun emptyModelStillRendersValidShape() {
        val json = CapabilityModel().toJson()
        assertTrue(json.contains("\"capabilities\": {}"))
        assertTrue(json.contains("\"provenance\": {}"))
        assertTrue(json.contains("\"sources\": []"))
    }

    @Test
    fun outputIsDeterministic() {
        // Keys inserted in different orders must serialise identically, or the
        // model stops being diffable between runs.
        val a = CapabilityModel(generatedAtMs = 7)
        a.set("vulkan", "device_count", 1L, "s")
        a.set("vulkan", "available", true, "s")
        a.set("gpu", "vendor", "arm", "s")

        val b = CapabilityModel(generatedAtMs = 7)
        b.set("gpu", "vendor", "arm", "s")
        b.set("vulkan", "available", true, "s")
        b.set("vulkan", "device_count", 1L, "s")

        assertEquals(a.toJson(), b.toJson())
    }

    @Test
    fun stringsAreEscapedSoADriverNameCannotBreakTheJson() {
        val m = CapabilityModel()
        m.set("gpu", "model", "Weird\"GPU\\\n\tName", "probe")
        val json = m.toJson()

        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        // A raw newline inside the value would split the line and corrupt the file.
        assertFalse(json.contains("Weird\"GPU"))
    }

    @Test
    fun controlCharactersAreEscapedAsUnicode() {
        assertEquals("\"a\\u0001b\"", CapabilityModel.quote("a\u0001b"))
    }

    @Test
    fun doublesUseADotEvenUnderACommaLocale() {
        // app_process inherits its locale from persist.sys.locale. Under id-ID a
        // naive format would emit "120,5" and every JSON parser would reject it.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("id-ID"))
            val m = CapabilityModel()
            m.set("display", "refresh_rate_hz", 120.5, "probe")
            val json = m.toJson()
            assertTrue("locale leaked into the JSON: $json", json.contains("120.5"))
            assertFalse(json.contains("120,5"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun listsRenderAsJsonArrays() {
        val m = CapabilityModel()
        m.set("vulkan", "device_extensions", listOf("VK_KHR_swapchain", "VK_EXT_hdr_metadata"), "probe")
        assertTrue(m.toJson().contains("[\"VK_KHR_swapchain\", \"VK_EXT_hdr_metadata\"]"))
    }
}
