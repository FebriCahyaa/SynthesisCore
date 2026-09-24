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
 * Reads `Class::FIELD` lines and emits `Class::FIELD <code>` for every static int field
 * that could be resolved. Blank lines and `#` comments are ignored; failures are
 * reported on [err] and omitted from the output.
 */
object BinderResolver {
    const val EXIT_OK = 0
    const val EXIT_PARTIAL = 1
    const val EXIT_IO_ERROR = 2

    data class Result(val output: String, val failures: Int)

    /** Parses `Class::FIELD`, or returns null if the line is malformed. */
    fun parseEntry(line: String): Pair<String, String>? {
        val parts = line.split("::")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }

    fun resolve(lines: Sequence<String>, err: PrintStream = System.err): Result {
        val output = StringBuilder()
        var failures = 0

        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { entry ->
                val parsed = parseEntry(entry)
                if (parsed == null) {
                    err.println("ERROR: Invalid format '$entry'. Use Class::TRANSACTION_name")
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
