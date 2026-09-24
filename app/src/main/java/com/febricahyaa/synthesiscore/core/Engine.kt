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

import android.content.Context
import android.os.Handler
import android.os.Looper

import java.util.concurrent.Executor

/**
 * Event-driven state engine.
 *
 * Runs every provider on a single [Looper] thread, so providers need no locking:
 *  - framework callbacks call [ProviderContext.invalidate], which re-samples only
 *    that provider;
 *  - each provider also has its own poll timer, whose interval adapts to the screen
 *    state (slow while the screen is off);
 *  - changes are coalesced for [COALESCE_MS] and then handed to [sink] as a fully
 *    rendered status text, only when the text actually changed.
 */
class Engine(
    override val context: Context,
    looper: Looper,
    private val providers: List<StateProvider>,
    override val oneShot: Boolean = false,
    private val sink: (String) -> Unit,
) : ProviderContext {

    /** Per-provider state as reported by [capabilities]. */
    data class ProviderStatus(val name: String, val mode: TriggerMode?, val error: String?)

    override val handler = Handler(looper)
    override val executor = Executor { handler.post(it) }

    private val values = LinkedHashMap<String, String>()
    private val modes = HashMap<StateProvider, TriggerMode>()
    private val errors = HashMap<StateProvider, String>()
    private val pollTasks = HashMap<StateProvider, Runnable>()
    private val sampleTasks = HashMap<StateProvider, Runnable>()

    private var lastRendered = ""
    private var flushScheduled = false
    private var started = false

    private val flushTask = Runnable {
        flushScheduled = false
        flush()
    }

    /** Starts all providers and takes the first sample. Must run on the engine thread. */
    fun start() {
        check(!started) { "Engine already started" }
        started = true

        for (provider in providers) {
            try {
                modes[provider] = provider.start(this)
            } catch (t: Throwable) {
                // A provider that cannot start is dropped; the others keep working.
                errors[provider] = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "Provider '${provider.name}' failed to start", t)
            }
        }

        activeProviders().forEach { sample(it) }
        flush()

        if (!oneShot) activeProviders().forEach { schedulePoll(it) }
    }

    /** Stops all providers and timers. Must run on the engine thread. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        for (provider in activeProviders()) {
            try {
                provider.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Provider '${provider.name}' failed to stop: ${t.message}")
            }
        }
        started = false
    }

    /** The current status text (also what was last handed to the sink). */
    fun render(): String = Protocol.render(values)

    fun capabilities(): List<ProviderStatus> =
        providers.map { ProviderStatus(it.name, modes[it], errors[it]) }

    override fun invalidate(provider: StateProvider) = invalidateLater(provider, 0)

    override fun invalidateLater(provider: StateProvider, delayMs: Long) {
        // Called from any thread: hop to the engine thread before touching state.
        handler.post {
            val task = sampleTasks.getOrPut(provider) { Runnable { sampleAndFlush(provider) } }
            handler.removeCallbacks(task)
            if (delayMs > 0) handler.postDelayed(task, delayMs) else handler.post(task)
        }
    }

    private fun activeProviders() = providers.filter { it in modes }

    private fun sampleAndFlush(provider: StateProvider) {
        if (!started) return
        sample(provider)
        scheduleFlush()
    }

    private fun sample(provider: StateProvider) {
        val out = HashMap<String, String>()
        try {
            provider.sample(out)
        } catch (t: Throwable) {
            Log.once(
                "sample:${provider.name}:${t.javaClass.name}:${t.message}", TAG,
                "Provider '${provider.name}' failed to sample (further identical errors suppressed)", t
            )
            return
        }

        val wasInteractive = isInteractive()
        values.putAll(out)
        if (isInteractive() != wasInteractive && !oneShot) {
            // Screen state changed: re-arm all poll timers with the new intervals.
            activeProviders().forEach { schedulePoll(it) }
        }
    }

    private fun schedulePoll(provider: StateProvider) {
        pollTasks[provider]?.let { handler.removeCallbacks(it) }
        val mode = modes[provider] ?: return
        val interval = provider.pollIntervalMs(mode, isInteractive())
        if (interval <= 0) return

        val task = pollTasks.getOrPut(provider) {
            Runnable {
                sampleAndFlush(provider)
                schedulePoll(provider)
            }
        }
        handler.postDelayed(task, interval)
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed(flushTask, COALESCE_MS)
    }

    private fun flush() {
        val rendered = render()
        if (rendered == lastRendered) return
        try {
            sink(rendered)
            lastRendered = rendered
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish status: ${t.message}", t)
        }
    }

    /** Screen state as last reported by the display provider; assume on until known. */
    private fun isInteractive(): Boolean = values[Protocol.SCREEN_AWAKE] != "0"

    companion object {
        private const val TAG = "Engine"

        /** Window in which bursts of changes are merged into one write. */
        const val COALESCE_MS = 25L
    }
}
