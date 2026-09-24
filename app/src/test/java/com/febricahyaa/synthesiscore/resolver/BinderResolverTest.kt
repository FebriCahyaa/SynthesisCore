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

package com.febricahyaa.synthesiscore.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import java.io.ByteArrayOutputStream
import java.io.PrintStream

class BinderResolverTest {
    @Test
    fun parseEntry_splitsClassAndField() {
        assertEquals("a.B" to "C", BinderResolver.parseEntry("a.B::C"))
        assertEquals("a.B\$Stub" to "TRANSACTION_x", BinderResolver.parseEntry("a.B\$Stub::TRANSACTION_x"))
        assertNull(BinderResolver.parseEntry("no separator"))
        assertNull(BinderResolver.parseEntry("::C"))
        assertNull(BinderResolver.parseEntry("a.B::"))
        assertNull(BinderResolver.parseEntry("a::b::c"))
    }

    @Test
    fun parseEntry_rejectsNonIdentifiers() {
        assertNull(BinderResolver.parseEntry("NoPackage::C"))
        assertNull(BinderResolver.parseEntry("a.B::C;rm -rf /"))
        assertNull(BinderResolver.parseEntry("a..B::C"))
        assertNull(BinderResolver.parseEntry("a.B::1C"))
        assertNull(BinderResolver.parseEntry("a/b.C::D"))
        assertNull(BinderResolver.parseEntry("a.B::" + "C".repeat(BinderResolver.MAX_LINE_LENGTH)))
    }

    @Test
    fun resolve_capsNumberOfEntries() {
        val lines = generateSequence { "java.lang.Integer::MAX_VALUE" }.take(BinderResolver.MAX_ENTRIES + 50)
        val result = BinderResolver.resolve(lines, PrintStream(ByteArrayOutputStream(), true))
        assertEquals(BinderResolver.MAX_ENTRIES, result.output.lines().count { it.isNotEmpty() })
        assertEquals(1, result.failures)
    }

    @Test
    fun resolve_emitsResolvedEntriesAndCountsFailures() {
        val errors = ByteArrayOutputStream()
        val result = BinderResolver.resolve(
            sequenceOf(
                "# comment",
                "",
                "  java.lang.Integer::MAX_VALUE  ",
                "java.lang.Integer::DOES_NOT_EXIST",
                "malformed",
            ),
            PrintStream(errors, true)
        )

        assertEquals("java.lang.Integer::MAX_VALUE ${Integer.MAX_VALUE}\n", result.output)
        assertEquals(2, result.failures)
        assertEquals(2, errors.toString().lines().count { it.startsWith("ERROR:") })
    }
}
