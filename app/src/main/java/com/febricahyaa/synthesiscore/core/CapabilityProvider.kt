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

/**
 * A source of capability facts this process can see through the framework.
 *
 * The sibling of [StateProvider], for a different kind of data. A StateProvider
 * feeds the per-tick status file with volatile state — which app is focused, how
 * hot the device is. A CapabilityProvider answers the slow question of what the
 * device *can* do, which is asked once and written to the capability model.
 *
 * The contract matches the native collector contract in Flux, deliberately:
 *  - [collect] writes observable facts only. It must not rank, score or
 *    recommend; interpretation happens above this layer.
 *  - It must not throw. A device that cannot answer is an ordinary outcome,
 *    reported as `unavailable` with the facts left absent.
 *  - A fact that was not observed is omitted, never defaulted. Absent means
 *    "unsupported or not observed"; it never means false or zero.
 */
interface CapabilityProvider {
    /** Stable id recorded as provenance, e.g. "synthesiscore.display". */
    val id: String

    /** Domains this provider may contribute to. */
    val domains: List<String>

    /**
     * Observes the device and writes facts into [out].
     * @return the run's outcome, recorded in the model's sources array.
     */
    fun collect(context: Context, out: CapabilityModel): CapabilitySourceInfo
}

/**
 * Runs [providers] in order, folding each one's facts into [out].
 *
 * Every provider runs even if an earlier one failed, and a provider that throws
 * is recorded as an `error` source rather than taking the caller down — one
 * broken provider degrades the model, it does not stop capability collection.
 */
fun runCapabilityProviders(
    context: Context,
    providers: List<CapabilityProvider>,
    out: CapabilityModel,
) {
    if (out.generatedAtMs == 0L) out.generatedAtMs = System.currentTimeMillis()

    for (provider in providers) {
        val info = try {
            provider.collect(context, out)
        } catch (t: Throwable) {
            CapabilitySourceInfo(
                id = provider.id,
                domains = provider.domains,
                status = CapabilitySchema.SourceStatus.ERROR,
                detail = "provider threw: ${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                collectedAtMs = System.currentTimeMillis(),
            )
        }
        out.addSource(info)
    }
}
