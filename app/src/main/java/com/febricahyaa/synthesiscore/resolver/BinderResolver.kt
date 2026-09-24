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

import com.febricahyaa.synthesiscore.core.AtomicFile
import com.febricahyaa.synthesiscore.core.Binders

import java.io.PrintStream

/**
 * `--resolve` mode: resolves binder transaction codes for native callers.
 *
 * Reads `Class::FIELD` lines and emits `Class::FIELD <code>` for every `static final int`
 * field that could be resolved. Blank lines and `#` comments are ignored; failures are
 * reported on [err] and omitted from the output.
 *
 * Input is untrusted: only well-formed Java identifiers are accepted, and the number
 * and length of lines are capped, so the root process cannot be steered into loading
 * arbitrary resources or reading an unbounded stream.
 */
object BinderResolver {
    const val EXIT_OK = 0
    const val EXIT_PARTIAL = 1
    const val EXIT_IO_ERROR = 2

    const val MAX_ENTRIES = 1024
    const val MAX_LINE_LENGTH = 256

    private val CLASS_NAME = Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")
    private val FIELD_NAME = Regex("[A-Za-z_$][\\w$]*")

    data class Result(val output: String, val failures: Int)

    /** Parses `Class::FIELD`, or returns null unless both parts are valid identifiers. */
    fun parseEntry(line: String): Pair<String, String>? {
        if (line.length > MAX_LINE_LENGTH) return null
        val parts = line.split("::")
        if (parts.size != 2) return null
        val (className, fieldName) = parts
        if (!CLASS_NAME.matches(className) || !FIELD_NAME.matches(fieldName)) return null
        return className to fieldName
    }

    fun resolve(lines: Sequence<String>, err: PrintStream = System.err): Result {
        val output = StringBuilder()
        var failures = 0
        var entries = 0

        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(MAX_ENTRIES + 1) // stop reading once the cap is exceeded
            .forEach { entry ->
                if (++entries > MAX_ENTRIES) {
                    err.println("ERROR: more than $MAX_ENTRIES entries, ignoring the rest")
                    failures++
                    return@forEach
                }
                val parsed = parseEntry(entry)
                if (parsed == null) {
                    err.println("ERROR: Invalid entry '${entry.take(MAX_LINE_LENGTH)}'. Use fully.qualified.Class::FIELD_NAME")
                    failures++
                    return@forEach
                }
                try {
                    val code = Binders.staticIntField(parsed.first, parsed.second)
                    output.append(entry).append(' ').append(code).append('\n')
                } catch (t: Throwable) {
                    err.println("ERROR: Failed to resolve $entry -> ${t.javaClass.simpleName}: ${t.message}")
                    failures++
                }
            }

        return Result(output.toString(), failures)
    }

    /** Runs the mode end to end and returns the process exit code. */
    fun run(outputPath: String?): Int {
        val result = System.`in`.bufferedReader().useLines { resolve(it) }
        try {
            if (outputPath == null) {
                print(result.output)
                System.out.flush()
            } else {
                AtomicFile.write(outputPath, result.output)
            }
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to write resolver output: ${e.message}")
            return EXIT_IO_ERROR
        }
        return if (result.failures == 0) EXIT_OK else EXIT_PARTIAL
    }
}
