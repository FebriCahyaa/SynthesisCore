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

package com.febricahyaa.synthesiscore.provider

import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

/** `kernel_is_gki`: resolved once, the kernel does not change at runtime. */
class KernelProvider : StateProvider {
    override val name = "kernel"

    private var isGki = false

    override fun start(ctx: ProviderContext): TriggerMode {
        isGki = isGkiKernel(System.getProperty("os.version").orEmpty())
        return TriggerMode.STATIC
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.KERNEL_IS_GKI] = Protocol.flag(isGki)
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean) = 0L

    companion object {
        private val GKI_KERNEL_REGEX = Regex("-android\\d+-")

        /**
         * GKI kernels carry an "-androidXX-" segment in `uname -r`
         * (e.g. "5.15.123-android13-8-00001-gabcdef"); vendor kernels do not.
         */
        fun isGkiKernel(release: String): Boolean = GKI_KERNEL_REGEX.containsMatchIn(release)
    }
}
