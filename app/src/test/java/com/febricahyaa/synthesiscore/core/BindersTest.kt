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

class BindersTest {
    class Holder {
        @JvmField
        var x = 1

        companion object {
            @JvmField
            var mutableStatic = 2
        }
    }

    @Test
    fun resolveClass_acceptsDollarNotation() {
        assertEquals(Map.Entry::class.java, Binders.resolveClass("java.util.Map\$Entry"))
    }

    @Test
    fun resolveClass_convertsDottedNestedClass() {
        assertEquals(Map.Entry::class.java, Binders.resolveClass("java.util.Map.Entry"))
    }

    @Test(expected = ClassNotFoundException::class)
    fun resolveClass_throwsForUnknownClass() {
        Binders.resolveClass("com.example.DoesNotExist")
    }

    @Test
    fun staticIntField_readsConstant() {
        assertEquals(Integer.MAX_VALUE, Binders.staticIntField("java.lang.Integer", "MAX_VALUE"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun staticIntField_rejectsInstanceField() {
        Binders.staticIntField(Holder::class.java.name, "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun staticIntField_rejectsMutableStatic() {
        Binders.staticIntField(Holder::class.java.name, "mutableStatic")
    }

    @Test(expected = IllegalArgumentException::class)
    fun staticIntField_rejectsNonIntConstant() {
        Binders.staticIntField("java.lang.Integer", "TYPE")
    }
}
