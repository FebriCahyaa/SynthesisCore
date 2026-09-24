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

/**
 * Minimal stderr logger.
 *
 * SynthesisCore runs under app_process with stderr redirected to a log file by the
 * module's service script, so logcat is not used. [once] de-duplicates errors that
 * would otherwise repeat on every sample.
 */
object Log {
    // Caps the number of distinct keys kept for de-duplication.
    private const val MAX_ONCE_KEYS = 128

    private val onceKeys = HashSet<String>()

    fun i(tag: String, message: String) = emit("INFO", tag, message, null)

    fun w(tag: String, message: String, t: Throwable? = null) = emit("WARN", tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = emit("ERROR", tag, message, t)

    /** Logs a warning only the first time [key] is seen. */
    fun once(key: String, tag: String, message: String, t: Throwable? = null) {
        synchronized(onceKeys) {
            if (onceKeys.size >= MAX_ONCE_KEYS || !onceKeys.add(key)) return
        }
        emit("WARN", tag, message, t)
    }

    private fun emit(level: String, tag: String, message: String, t: Throwable?) {
        System.err.println("$level: [$tag] $message")
        t?.printStackTrace()
    }
}
