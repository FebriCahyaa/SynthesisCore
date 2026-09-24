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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainKtTest {
    @Test
    fun isSafePath_acceptsAbsoluteNormalisedPaths() {
        assertTrue(MainKt.isSafePath("/data/adb/.config/flux/synthesis_core.json"))
    }

    @Test
    fun isSafePath_rejectsRelativeTraversalAndControlChars() {
        assertFalse(MainKt.isSafePath("synthesis_core.json"))
        assertFalse(MainKt.isSafePath("--resolve"))
        assertFalse(MainKt.isSafePath("/data/adb/../../system/bin/sh"))
        assertFalse(MainKt.isSafePath("/data/./adb/x"))
        assertFalse(MainKt.isSafePath("/data/adb/x\nfocused_app y"))
    }
}
