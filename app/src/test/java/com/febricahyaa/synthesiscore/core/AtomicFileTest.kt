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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.nio.file.Files

class AtomicFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun write_createsAndReplacesContent() {
        val target = tmp.root.toPath().resolve("sub/status")
        AtomicFile.write(target.toString(), "a 1\n")
        AtomicFile.write(target.toString(), "a 2\n")
        assertEquals("a 2\n", Files.readString(target))
        assertFalse(Files.exists(target.resolveSibling("status.tmp")))
    }

    @Test
    fun write_doesNotFollowPlantedTmpSymlink() {
        val victim = tmp.newFile("victim").toPath()
        Files.writeString(victim, "precious")
        val target = tmp.root.toPath().resolve("status")
        Files.createSymbolicLink(target.resolveSibling("status.tmp"), victim)

        AtomicFile.write(target.toString(), "a 1\n")

        assertEquals("precious", Files.readString(victim))
        assertEquals("a 1\n", Files.readString(target))
    }
}
