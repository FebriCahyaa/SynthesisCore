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
import org.junit.Test

import java.util.Locale

class ProtocolTest {
    @Test
    fun render_putsVersionFirstAndKeepsProtocolOrder() {
        val values = linkedMapOf(
            Protocol.KERNEL_IS_GKI to "1",
            "zz_custom" to "x",
            Protocol.THERMAL_STATUS to "0.85",
            Protocol.FOCUSED_APP to "com.example.game 42 10001",
            Protocol.SCREEN_AWAKE to "1",
        )
        assertEquals(
            """
            synthesis_version ${Protocol.VERSION}
            focused_app com.example.game 42 10001
            screen_awake 1
            thermal_status 0.85
            kernel_is_gki 1
            zz_custom x

            """.trimIndent(),
            Protocol.render(values)
        )
    }

    @Test
    fun render_ignoresCallerSuppliedVersion() {
        val rendered = Protocol.render(mapOf(Protocol.SYNTHESIS_VERSION to "999"))
        assertEquals("synthesis_version ${Protocol.VERSION}\n", rendered)
    }

    @Test
    fun decimal_usesDotRegardlessOfDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            // id-ID formats decimals with a comma ("0,85"), which C sscanf reads as 0.
            Locale.setDefault(Locale.forLanguageTag("id-ID"))
            assertEquals("0.85", Protocol.decimal(0.85f, 2))
            assertEquals("36.4", Protocol.decimal(36.4f, 1))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun flag_mapsBooleanToDigit() {
        assertEquals("1", Protocol.flag(true))
        assertEquals("0", Protocol.flag(false))
    }

    @Test
    fun render_cannotBeInjectedWithExtraLines() {
        val rendered = Protocol.render(
            mapOf(
                Protocol.FOCUSED_APP to "com.evil 1 2\nfocused_app com.forged 3 4",
                "Bad-Key" to "x",
                "bad\nkey" to "y",
            )
        )
        assertEquals("synthesis_version ${Protocol.VERSION}\nfocused_app com.evil 1 2 focused_app com.forged 3 4\n", rendered)
    }

    @Test
    fun sanitize_capsValueLength() {
        assertEquals(192, Protocol.sanitize("x".repeat(1000)).length)
    }
}
