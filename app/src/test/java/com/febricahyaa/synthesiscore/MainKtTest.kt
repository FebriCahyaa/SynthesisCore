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

package com.febricahyaa.synthesiscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainKtTest {
    @Test
    fun extractPackageName_findsPackageInComponentString() {
        assertEquals(
            "com.example.game",
            MainKt.extractPackageName("ComponentInfo{com.example.game/com.example.game.MainActivity}")
        )
    }

    @Test
    fun extractPackageName_lowercasesInput() {
        assertEquals("com.example.app", MainKt.extractPackageName("Task: Com.Example.App"))
    }

    @Test
    fun extractPackageName_rejectsInputWithoutPackage() {
        assertNull(MainKt.extractPackageName(null))
        assertNull(MainKt.extractPackageName("no package here"))
        assertNull(MainKt.extractPackageName(".leading"))
    }

    @Test
    fun resolveClass_acceptsDollarNotation() {
        assertEquals(Map.Entry::class.java, MainKt.resolveClass("java.util.Map\$Entry"))
    }

    @Test
    fun resolveClass_convertsDottedNestedClass() {
        assertEquals(Map.Entry::class.java, MainKt.resolveClass("java.util.Map.Entry"))
    }

    @Test(expected = ClassNotFoundException::class)
    fun resolveClass_throwsForUnknownClass() {
        MainKt.resolveClass("com.example.DoesNotExist")
    }

    @Test
    fun protocolVersion_isAtLeastResolverVersion() {
        // Flux gates --resolve on synthesis_version >= 2.
        assertTrue(MainKt.PROTOCOL_VERSION >= 2)
    }
}
