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

package com.febricahyaa.synthesiscore.provider.foreground

object PackageNames {
    private val SANITIZE_REGEX = Regex("[^a-z0-9._-]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PACKAGE_NAME_REGEX = Regex("[a-z0-9]+(\\.[a-z0-9]+)+")

    /** Finds the first package-like token (e.g. "com.example.game") in free text. */
    fun extract(input: String?): String? {
        if (input == null || input.indexOf('.') <= 0) return null
        val normalized = input.lowercase().replace(SANITIZE_REGEX, " ")
        return normalized.split(WHITESPACE_REGEX).find { it.contains(".") && it.matches(PACKAGE_NAME_REGEX) }
    }
}
