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

import java.util.concurrent.Executor

/**
 * How a provider learns that its state may have changed.
 */
enum class TriggerMode {
    /** A framework listener/callback notifies it; polling is only a safety net. */
    EVENT,

    /** No listener is available on this device/API level; the value is polled. */
    POLL,

    /** The value never changes during the process lifetime. */
    STATIC,
}

/**
 * Services the [Engine] offers to providers. All methods are thread-safe, so
 * framework callbacks arriving on binder threads may call them directly.
 */
interface ProviderContext {
    val context: Context

    /** Handler on the engine thread; pass it to framework APIs that take a Handler. */
    val handler: Handler

    /** Executor on the engine thread; pass it to framework APIs that take an Executor. */
    val executor: Executor

    /** True in `--once` mode: providers must return final values without deferring. */
    val oneShot: Boolean

    /** Requests a new [StateProvider.sample] of [provider] on the engine thread. */
    fun invalidate(provider: StateProvider)

    /** Like [invalidate], after [delayMs]. */
    fun invalidateLater(provider: StateProvider, delayMs: Long)
}

/**
 * A source of one or more status fields.
 *
 * Lifecycle, always on the engine thread:
 *  1. [start] obtains services and registers framework listeners, returning how the
 *     provider is triggered on this device.
 *  2. [sample] is called after start, after every [ProviderContext.invalidate] and on
 *     every poll tick. It writes the current value of its fields into `out`; a field
 *     left out keeps its previous value, and a field never written is omitted from
 *     the status file (meaning "unsupported").
 *  3. [stop] unregisters listeners.
 */
interface StateProvider {
    val name: String

    fun start(ctx: ProviderContext): TriggerMode

    fun sample(out: MutableMap<String, String>)

    /**
     * Poll interval in ms given the screen state and the trigger mode returned by
     * [start], or 0 to never poll. EVENT providers should still return a slow safety
     * interval so a missed or unsupported callback cannot freeze a value forever.
     */
    fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long

    fun stop() {}
}
