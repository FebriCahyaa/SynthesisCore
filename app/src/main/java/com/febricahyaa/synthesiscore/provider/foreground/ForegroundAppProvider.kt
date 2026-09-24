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

import android.annotation.SuppressLint
import android.app.ActivityManager

import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * `focused_app <package> <pid> <uid>`.
 *
 * Trigger: `ActivityManager.addOnUidImportanceListener` (@SystemApi, API 26). Every
 * time a UID crosses the foreground importance cutpoint, ActivityManager calls us
 * back over binder and the focused task is re-resolved. The listener is an interface,
 * so a [Proxy] implements it without compile-time access to the hidden type.
 * A 1 s safety poll still covers focus changes that do not change any UID's
 * importance (e.g. moving focus between two visible split-screen apps).
 *
 * A freshly launched app may be focused before its process is listed; the PID is
 * then retried every [PID_RETRY_MS] for up to [PID_RETRY_LIMIT] attempts before
 * "<package> 0 0" is published, so consumers never see a transient bogus PID.
 */
@SuppressLint("PrivateApi")
class ForegroundAppProvider : StateProvider {
    override val name = "foreground"

    private lateinit var ctx: ProviderContext
    private lateinit var activityManager: ActivityManager
    private lateinit var processTracker: ProcessTracker
    private var taskResolver: TaskResolver? = null
    private var importanceListener: Any? = null

    private var pendingPackage: String? = null
    private var pidRetries = 0

    // Package already published as "0 0"; not retried again until focus changes.
    private var unresolvedPackage: String? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        this.ctx = ctx
        activityManager = ctx.context.getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager unavailable")
        processTracker = ProcessTracker(activityManager)
        taskResolver = TaskResolver.create()?.also { Log.i(name, it.describe()) }

        return if (registerImportanceListener()) TriggerMode.EVENT else TriggerMode.POLL
    }

    override fun sample(out: MutableMap<String, String>) {
        when (val result = taskResolver?.resolve() ?: TaskResolver.Result.Unknown) {
            TaskResolver.Result.Unknown -> publish(out, UNKNOWN_APP)
            TaskResolver.Result.NoTask -> publish(out, NONE_APP)
            is TaskResolver.Result.Focused -> sampleFocused(out, result.packageName)
        }
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long = when {
        !interactive -> 5_000L
        mode == TriggerMode.EVENT -> 1_000L
        else -> 500L
    }

    override fun stop() {
        val listener = importanceListener ?: return
        try {
            ActivityManager::class.java
                .getMethod("removeOnUidImportanceListener", listenerClass())
                .invoke(activityManager, listener)
        } catch (t: Throwable) {
            Log.w(name, "Failed to remove UID importance listener: ${t.message}")
        }
        importanceListener = null
    }

    private fun sampleFocused(out: MutableMap<String, String>, packageName: String) {
        val process = processTracker.find(packageName)
        if (process != null) {
            publish(out, "$packageName ${process.pid} ${process.uid}")
            return
        }
        if (packageName == unresolvedPackage) {
            publish(out, "$packageName 0 0", unresolved = packageName)
            return
        }

        // Process not listed yet: retry shortly instead of publishing "0 0".
        if (packageName != pendingPackage) {
            pendingPackage = packageName
            pidRetries = 0
        }
        if (!ctx.oneShot && pidRetries < PID_RETRY_LIMIT) {
            pidRetries++
            ctx.invalidateLater(this, PID_RETRY_MS)
            return
        }

        Log.once("pid_unresolved:$packageName", name, "PID still unresolved for '$packageName', publishing 0 0")
        publish(out, "$packageName 0 0", unresolved = packageName)
    }

    private fun publish(out: MutableMap<String, String>, value: String, unresolved: String? = null) {
        pendingPackage = null
        pidRetries = 0
        unresolvedPackage = unresolved
        out[Protocol.FOCUSED_APP] = value
    }

    private fun listenerClass(): Class<*> = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")

    private fun registerImportanceListener(): Boolean = try {
        val listenerClass = listenerClass()
        val handler = InvocationHandler { proxy, method: Method, args: Array<out Any?>? ->
            when (method.name) {
                "onUidImportance" -> {
                    ctx.invalidate(this)
                    null
                }
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SynthesisCore.UidImportanceListener"
                else -> null
            }
        }
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)

        ActivityManager::class.java
            .getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
            .invoke(activityManager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        importanceListener = listener
        true
    } catch (t: Throwable) {
        Log.w(name, "UID importance listener unavailable, polling instead: ${t.cause?.message ?: t.message}")
        false
    }

    companion object {
        const val UNKNOWN_APP = "unknown 0 0"
        const val NONE_APP = "none 0 0"
        const val PID_RETRY_MS = 50L
        const val PID_RETRY_LIMIT = 10
    }
}
