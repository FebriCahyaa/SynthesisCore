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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol 4 is an additive change, and this pins what "additive" has to mean.
 *
 * Consumers in the field were built against 3. They read the status file line by
 * line and ignore keys they do not recognise, so 4 is safe only as long as every
 * earlier key keeps its name, its meaning, its position and its format. The
 * matching test on the consumer side is Flux's tests/synthesis_core_test.cpp,
 * which feeds a v4 file to the v3 native parser.
 */
class ProtocolV4CompatTest {

    /** The fourteen keys of protocol 3, in the order 3 emitted them. */
    private val v3Order = listOf(
        "synthesis_version",
        "focused_app",
        "screen_awake",
        "battery_saver",
        "zen_mode",
        "charging_state",
        "thermal_status",
        "audio_active",
        "thermal_api_available",
        "kernel_is_gki",
        "thermal_level",
        "battery_level",
        "battery_temp",
        "call_active",
    )

    @Test
    fun versionIsFour() {
        assertEquals(4, Protocol.VERSION)
    }

    @Test
    fun everyProtocol3KeyKeepsItsNameAndPosition() {
        assertEquals(v3Order, Protocol.FIELD_ORDER.take(v3Order.size))
    }

    @Test
    fun protocol4KeysAreAppendedNotInserted() {
        val added = Protocol.FIELD_ORDER.drop(v3Order.size)
        assertEquals(listOf(Protocol.CAPABILITY_SCHEMA, Protocol.CAPABILITY_FILE), added)
    }

    @Test
    fun renderedFileKeepsProtocol3LinesInPlace() {
        val values = linkedMapOf(
            Protocol.FOCUSED_APP to "com.example.game 42 10001",
            Protocol.SCREEN_AWAKE to "1",
            Protocol.THERMAL_STATUS to "0.85",
            Protocol.BATTERY_LEVEL to "76",
            Protocol.CALL_ACTIVE to "0",
            Protocol.CAPABILITY_SCHEMA to "4",
            Protocol.CAPABILITY_FILE to "/data/adb/.config/flux/capabilities.json",
        )
        val lines = Protocol.render(values).trim().lines()

        assertEquals("synthesis_version 4", lines[0])
        assertEquals("focused_app com.example.game 42 10001", lines[1])
        assertEquals("screen_awake 1", lines[2])
        assertEquals("thermal_status 0.85", lines[3])
        assertEquals("battery_level 76", lines[4])
        assertEquals("call_active 0", lines[5])

        // The new keys come last, where a v3 parser walks past them.
        assertEquals("capability_schema 4", lines[6])
        assertEquals("capability_file /data/adb/.config/flux/capabilities.json", lines[7])
    }

    @Test
    fun aProtocol3ConsumerCanStillFindEveryFieldItKnows() {
        // Simulates the native reader: scan for "<key> " at the start of a line.
        val values = linkedMapOf(
            Protocol.FOCUSED_APP to "com.example.game 42 10001",
            Protocol.SCREEN_AWAKE to "1",
            Protocol.BATTERY_SAVER to "0",
            Protocol.ZEN_MODE to "0",
            Protocol.CHARGING_STATE to "1",
            Protocol.THERMAL_STATUS to "0.85",
            Protocol.AUDIO_ACTIVE to "1",
            Protocol.THERMAL_API_AVAILABLE to "1",
            Protocol.KERNEL_IS_GKI to "1",
            Protocol.THERMAL_LEVEL to "2",
            Protocol.BATTERY_LEVEL to "76",
            Protocol.BATTERY_TEMP to "38.50",
            Protocol.CALL_ACTIVE to "0",
            Protocol.CAPABILITY_SCHEMA to "4",
        )
        val lines = Protocol.render(values).trim().lines()

        for (key in v3Order) {
            assertTrue("a v3 consumer would not find '$key'", lines.any { it.startsWith("$key ") })
        }
    }

    @Test
    fun newKeysAreStillSanitisedLikeEveryOther() {
        // A capability path is a value like any other: it must not be able to
        // inject a second line and forge a field.
        val values = mapOf(Protocol.CAPABILITY_FILE to "/tmp/x\nfocused_app com.evil.app 1 1")
        val lines = Protocol.render(values).trim().lines()

        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("capability_file "))
        assertTrue(lines.none { it.startsWith("focused_app ") })
    }

    @Test
    fun capabilitySchemaKeyAnnouncesTheModelVersion() {
        assertEquals(CapabilitySchema.VERSION.toString(), "4")
        assertEquals("capability_schema", Protocol.CAPABILITY_SCHEMA)
    }
}
