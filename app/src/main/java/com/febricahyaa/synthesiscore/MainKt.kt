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

import android.content.Context
import android.os.Looper

import com.febricahyaa.synthesiscore.core.AtomicFile
import com.febricahyaa.synthesiscore.core.Engine
import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.SystemEnvironment
import com.febricahyaa.synthesiscore.provider.AudioProvider
import com.febricahyaa.synthesiscore.provider.BatteryProvider
import com.febricahyaa.synthesiscore.provider.DisplayProvider
import com.febricahyaa.synthesiscore.provider.KernelProvider
import com.febricahyaa.synthesiscore.provider.PowerProvider
import com.febricahyaa.synthesiscore.provider.ThermalProvider
import com.febricahyaa.synthesiscore.provider.foreground.ForegroundAppProvider
import com.febricahyaa.synthesiscore.resolver.BinderResolver

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * Command-line entry point, launched through app_process:
 *
 * ```
 * app_process -Djava.class.path=<apk> / com.febricahyaa.synthesiscore.MainKt <mode>
 *
 *   <output_path> [lock_file_path]   run the monitor daemon (default mode)
 *   --resolve [output_path]          resolve Class::TRANSACTION_x lines from stdin
 *   --once                           print one status snapshot and exit
 *   --capabilities                   print how each provider runs on this device
 *   --version                        print the protocol version
 * ```
 */
object MainKt {
    private const val TAG = "Main"

    private const val EXIT_FAILURE = 1

    @JvmStatic
    fun main(args: Array<String>) {
        when (val mode = args.firstOrNull()) {
            null, "-h", "--help" -> {
                printUsage()
                if (mode == null) exitProcess(EXIT_FAILURE)
            }
            "--version" -> println(Protocol.VERSION)
            "--resolve" -> {
                val outputPath = args.getOrNull(1)?.let(::requireSafePath)
                SystemEnvironment.exemptHiddenApis()
                exitProcess(BinderResolver.run(outputPath))
            }
            "--once" -> exitProcess(runOnce(printCapabilities = false))
            "--capabilities" -> exitProcess(runOnce(printCapabilities = true))
            else -> exitProcess(
                runMonitor(outputPath = requireSafePath(mode), lockPath = args.getOrNull(1)?.let(::requireSafePath))
            )
        }
    }

    /** The provider set, in the order they are started. */
    fun createProviders(): List<StateProvider> = listOf(
        ForegroundAppProvider(),
        DisplayProvider(),
        PowerProvider(),
        BatteryProvider(),
        ThermalProvider(),
        AudioProvider(),
        KernelProvider(),
    )

    private fun runMonitor(outputPath: String, lockPath: String?): Int {
        val context = SystemEnvironment.bootstrap() ?: return EXIT_FAILURE
        val lock = lockPath?.let(::acquireLock)

        val engine = Engine(context, Looper.getMainLooper(), createProviders()) { status ->
            AtomicFile.write(outputPath, status)
        }
        engine.start()
        logCapabilities(engine)

        // Binder death notifications unregister our listeners server-side when the
        // process dies, so only the lock needs explicit cleanup.
        Runtime.getRuntime().addShutdownHook(Thread { lock?.close() })

        Looper.loop()
        return 0
    }

    private fun runOnce(printCapabilities: Boolean): Int {
        val context: Context = SystemEnvironment.bootstrap() ?: return EXIT_FAILURE
        val engine = Engine(context, Looper.getMainLooper(), createProviders(), oneShot = true) {}
        engine.start()

        if (printCapabilities) {
            println("protocol ${Protocol.VERSION}")
            println("hidden_api_exempt ${Protocol.flag(SystemEnvironment.hiddenApiExempt)}")
            engine.capabilities().forEach { status ->
                println("provider ${status.name} ${status.mode ?: "FAILED"}${status.error?.let { " ($it)" } ?: ""}")
            }
        } else {
            print(engine.render())
        }
        System.out.flush()
        engine.stop()
        return 0
    }

    private fun logCapabilities(engine: Engine) {
        engine.capabilities().forEach { status ->
            val detail = status.error?.let { " error=$it" } ?: ""
            Log.i(TAG, "provider=${status.name} mode=${status.mode ?: "FAILED"}$detail")
        }
    }

    /** Takes an exclusive lock so only one monitor runs per lock file; exits if held. */
    private fun acquireLock(path: String): FileChannel {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            if (channel.tryLock() == null) {
                Log.e(TAG, "Another instance holds the lock at '$path'.")
                channel.close()
                exitProcess(EXIT_FAILURE)
            }
            return channel
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire lock at '$path': ${e.message}")
            exitProcess(EXIT_FAILURE)
        }
    }

    /**
     * Accepts only absolute, normalised paths without control characters. This root
     * process writes wherever it is told, so relative paths (resolved against an
     * unknown working directory), `..` segments and embedded newlines are refused.
     */
    fun isSafePath(path: String): Boolean =
        path.startsWith("/") &&
                path.none { it.isISOControl() } &&
                path.split('/').none { it == ".." || it == "." }

    private fun requireSafePath(path: String): String {
        if (!isSafePath(path)) {
            Log.e(TAG, "Refusing unsafe path '${path.filterNot { it.isISOControl() }}': use an absolute path")
            printUsage()
            exitProcess(EXIT_FAILURE)
        }
        return path
    }

    private fun printUsage() {
        System.err.println(
            """
            Usage: MainKt <output_path> [lock_file_path]   run the monitor daemon
                   MainKt --resolve [output_path]          resolve Class::TRANSACTION_x lines from stdin
                   MainKt --once                           print one status snapshot
                   MainKt --capabilities                   print provider trigger modes
                   MainKt --version                        print the protocol version
            """.trimIndent()
        )
    }
}
