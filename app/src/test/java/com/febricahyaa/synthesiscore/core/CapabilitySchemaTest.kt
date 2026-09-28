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
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File

/**
 * Pins [CapabilitySchema] to `schema/capability_schema_v4.json`.
 *
 * This module owns the canonical model, and the descriptor is the form other
 * repositories vendor: Flux keeps a copy and binds to it in C++. Both bindings
 * are hand-written, and hand-written bindings drift, so each side tests against
 * its own copy. This is that test for the owning side.
 *
 * The descriptor is parsed with a small hand-rolled reader rather than org.json,
 * which is not on the plain-JVM unit-test classpath.
 */
class CapabilitySchemaTest {

    private fun descriptor(): String {
        // Unit tests run with the module directory as the working directory.
        val candidates = listOf(
            File("../schema/capability_schema_v4.json"),
            File("schema/capability_schema_v4.json"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "capability_schema_v4.json not found; looked in ${candidates.map { it.absolutePath }}",
            found != null,
        )
        return found!!.readText()
    }

    /** Key names inside the object that follows "domains" -> <domain> -> "keys". */
    private fun keysOf(json: String, domain: String): Set<String> {
        val domainsAt = json.indexOf("\"domains\"")
        assertTrue("descriptor has no domains object", domainsAt >= 0)
        val domainAt = json.indexOf("\"$domain\"", domainsAt)
        assertTrue("descriptor has no domain '$domain'", domainAt >= 0)
        val keysAt = json.indexOf("\"keys\"", domainAt)
        assertTrue("domain '$domain' has no keys object", keysAt >= 0)

        val open = json.indexOf('{', keysAt)
        var depth = 0
        var end = open
        while (end < json.length) {
            if (json[end] == '{') depth++
            if (json[end] == '}') {
                depth--
                if (depth == 0) break
            }
            end++
        }
        val body = json.substring(open + 1, end)
        return Regex("\"([a-z][a-z0-9_]*)\"\\s*:").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun stringArray(json: String, name: String): Set<String> {
        val at = json.indexOf("\"$name\"")
        assertTrue("descriptor has no '$name' array", at >= 0)
        val open = json.indexOf('[', at)
        val close = json.indexOf(']', open)
        return Regex("\"([^\"]+)\"").findAll(json.substring(open, close))
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun schemaVersionMatchesDescriptor() {
        val json = descriptor()
        val version = Regex("\"schema_version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
        assertEquals(CapabilitySchema.VERSION, version)
    }

    @Test
    fun everyDomainInTheBindingIsInTheDescriptor() {
        val json = descriptor()
        for (domain in CapabilitySchema.DOMAINS) {
            assertTrue(
                "binding domain '$domain' is not in the canonical descriptor",
                json.contains("\"$domain\""),
            )
        }
    }

    @Test
    fun keysMatchTheDescriptorExactly() {
        val json = descriptor()
        for ((domain, boundKeys) in CapabilitySchema.KEYS_BY_DOMAIN) {
            val descriptorKeys = keysOf(json, domain)
            assertEquals(
                "key set for domain '$domain' has drifted from the canonical descriptor",
                descriptorKeys,
                boundKeys.toSet(),
            )
        }
    }

    @Test
    fun sourceStatusValuesMatchTheDescriptor() {
        assertEquals(
            stringArray(descriptor(), "source_status"),
            CapabilitySchema.SourceStatus.ALL.toSet(),
        )
    }

    @Test
    fun vulkanStatusValuesAreAllowedByTheDescriptor() {
        val allowed = stringArray(descriptor(), "vulkan.status")
        for (status in listOf(
            CapabilitySchema.Vulkan.STATUS_OK,
            CapabilitySchema.Vulkan.STATUS_ABSENT,
            CapabilitySchema.Vulkan.STATUS_NO_DEVICE,
            CapabilitySchema.Vulkan.STATUS_ERROR,
        )) {
            assertTrue("'$status' is not an allowed vulkan.status value", allowed.contains(status))
        }
    }

    @Test
    fun isKnownRecognisesSchemaKeysAndRejectsOthers() {
        assertTrue(CapabilitySchema.isKnown(CapabilitySchema.DOMAIN_VULKAN, CapabilitySchema.Vulkan.AVAILABLE))
        assertTrue(CapabilitySchema.isKnown(CapabilitySchema.DOMAIN_DISPLAY, CapabilitySchema.Display.HDR_TYPES))
        assertFalse(CapabilitySchema.isKnown(CapabilitySchema.DOMAIN_VULKAN, "ray_tracing_tier"))
        assertFalse(CapabilitySchema.isKnown("graphite", "enabled"))
    }

    @Test
    fun keyNamesAreUniqueWithinEachDomain() {
        for ((domain, keys) in CapabilitySchema.KEYS_BY_DOMAIN) {
            assertEquals("domain '$domain' lists a key twice", keys.size, keys.toSet().size)
        }
    }
}
