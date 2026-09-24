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

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Implements a framework listener interface that is @hide, @SystemApi or flagged,
 * and therefore cannot be referenced at compile time against the public SDK.
 */
object ListenerProxy {
    /**
     * Returns an instance of [interfaceName] whose [callbackName] method runs
     * [onCallback]. Object methods behave by identity; other methods are no-ops.
     */
    fun create(interfaceName: String, callbackName: String, onCallback: () -> Unit): Any {
        val listenerClass = Class.forName(interfaceName)
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                callbackName -> {
                    onCallback()
                    null
                }
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SynthesisCore.$callbackName"
                else -> null
            }
        }
        return Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
    }
}
