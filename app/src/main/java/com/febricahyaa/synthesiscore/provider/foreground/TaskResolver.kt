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

import android.content.ComponentName
import android.os.Build

import com.febricahyaa.synthesiscore.core.Binders
import com.febricahyaa.synthesiscore.core.Log

import java.lang.reflect.Method

/**
 * Resolves the package of the focused task through IActivityTaskManager.
 *
 * There is no public API for "which app is in front" from outside an app, so this
 * talks to the @hide ATM interface. To stay robust across Android versions and ROMs:
 *  - the method is picked from [CANDIDATES] in order, but only if the ROM really
 *    serves it (has a `TRANSACTION_*` code);
 *  - the returned object (RootTaskInfo, StackInfo, RunningTaskInfo, ...) is mined for
 *    a [ComponentName] by known field names, then by scanning its fields;
 *  - as a last resort, read-only `get*` binder methods are tried in a stable order.
 */
class TaskResolver {
    sealed interface Result {
        data class Focused(val packageName: String) : Result
        data object NoTask : Result
        data object Unknown : Result
    }

    private val atm: Any
    private val transactionCodes: Map<String, Int>
    private val primaryMethod: Method?
    private var fallbackMethods: List<Method>? = null

    init {
        val binder = Binders.getService(SERVICE_NAME)
            ?: error("ServiceManager returned null binder for '$SERVICE_NAME'")
        atm = Binders.asInterface("$INTERFACE_NAME\$Stub", binder)
        transactionCodes = Binders.transactionCodes("$INTERFACE_NAME\$Stub")
        primaryMethod = findPrimaryMethod()
    }

    /** Human-readable description of what was detected, for the capability report. */
    fun describe(): String {
        val available = CANDIDATES.filter { it in transactionCodes }
        return "transactions=${transactionCodes.size} candidates=$available " +
                "selected=${primaryMethod?.name ?: "none (fallback scan)"}"
    }

    fun resolve(): Result {
        val result = primaryMethod?.let(::invokeCandidate) ?: invokeFallbacks() ?: return Result.Unknown
        return if (result is List<*>) fromList(result) else fromObject(result)
    }

    private fun hasTransaction(methodName: String) =
        transactionCodes.isEmpty() || methodName in transactionCodes

    private fun findPrimaryMethod(): Method? {
        val methods = Binders.declaredMethods(atm.javaClass).associateBy { it.name }
        return CANDIDATES
            .filter(::hasTransaction)
            .mapNotNull { methods[it] }
            .firstOrNull { method ->
                method.parameterTypes.isEmpty() ||
                        (method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.javaPrimitiveType) ||
                        method.name in LIST_METHODS
            }
            ?.apply { isAccessible = true }
    }

    private fun invokeCandidate(method: Method): Any? = try {
        when {
            method.name in LIST_METHODS -> invokeWithArgs(method, *LIST_METHOD_ARGS)
            method.parameterTypes.isEmpty() -> method.invoke(atm)
            else -> invokeWithArgs(method, arrayOf(0))
        }
    } catch (_: Exception) {
        null
    }

    private fun invokeWithArgs(method: Method, vararg argSets: Array<Any>): Any? {
        for (args in argSets) {
            try {
                return method.invoke(atm, *args)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Only read-only getters that are real binder calls are tried, in a stable order.
     * Without this restriction, methods such as removeTask(int) or
     * startSystemLockTaskMode(int) would match the name filter and be invoked.
     */
    private fun invokeFallbacks(): Any? {
        val methods = fallbackMethods ?: Binders.declaredMethods(atm.javaClass)
            .filter {
                val name = it.name.lowercase()
                name.startsWith("get") &&
                        (name.contains("focus") || name.contains("top") || name.contains("task")) &&
                        hasTransaction(it.name)
            }
            .sortedWith(compareBy<Method>({ it.name }, { it.parameterTypes.size }))
            .onEach { it.isAccessible = true }
            .also { fallbackMethods = it }

        return methods.firstNotNullOfOrNull { method ->
            try {
                when {
                    method.parameterTypes.isEmpty() -> method.invoke(atm)
                    method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.javaPrimitiveType ->
                        method.invoke(atm, 1)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fromList(list: List<*>): Result {
        if (list.isEmpty()) return Result.NoTask
        list.forEach { element -> componentOf(element)?.let { return Result.Focused(it.packageName) } }
        return list.firstNotNullOfOrNull { it }?.let(::fromObject) ?: Result.Unknown
    }

    private fun fromObject(obj: Any): Result {
        componentOf(obj)?.let { return Result.Focused(it.packageName) }
        return packageLikeString(obj)?.let { Result.Focused(it) } ?: Result.Unknown
    }

    private fun componentOf(obj: Any?): ComponentName? {
        if (obj == null) return null
        if (obj is ComponentName) return obj

        for (fieldName in COMPONENT_FIELDS) {
            try {
                val field = obj.javaClass.getField(fieldName).apply { isAccessible = true }
                (field.get(obj) as? ComponentName)?.let { return it }
            } catch (_: Exception) {
            }
        }

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in safeInstanceFields(cls)) {
                try {
                    field.isAccessible = true
                    (field.get(obj) as? ComponentName)?.let { return it }
                } catch (_: Exception) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun packageLikeString(obj: Any): String? {
        PackageNames.extract(obj.toString())?.let { return it }
        for (field in safeInstanceFields(obj.javaClass)) {
            if (field.type != String::class.java) continue
            try {
                field.isAccessible = true
                PackageNames.extract(field.get(obj) as? String)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Field listing can be refused (hidden API policy, a failed bypass); treat that as
     * "no fields" so one inaccessible class does not fail the whole lookup.
     */
    private fun safeInstanceFields(cls: Class<*>) = try {
        Binders.instanceFields(cls)
    } catch (t: Throwable) {
        Log.once("fields:${cls.name}", "TaskResolver", "Cannot list fields of ${cls.name}: ${t.message}")
        emptyList()
    }

    companion object {
        // IActivityTaskManager was split out of IActivityManager in Android 10 (API 29).
        private val SERVICE_NAME = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "activity_task" else "activity"
        private val INTERFACE_NAME =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android.app.IActivityTaskManager" else "android.app.IActivityManager"

        /** Newest first: RootTaskInfo (API 31+), StackInfo (API 29-30), then task lists. */
        private val CANDIDATES = listOf(
            "getFocusedRootTaskInfo",
            "getFocusedRootTask",
            "getFocusedTaskInfo",
            "getFocusedStackInfo",
            "getTopActivity",
            "getTasks",
            "getRunningTasks",
        )

        private val LIST_METHODS = setOf("getTasks", "getRunningTasks")

        /**
         * Argument sets tried for the task list methods, oldest signature first:
         * getTasks(max), (max, flags), (max, filterOnlyVisibleRecents, keepIntentExtra),
         * and (…, displayId) on Android 16+ (API 36/37).
         */
        private val LIST_METHOD_ARGS = arrayOf<Array<Any>>(
            arrayOf(1),
            arrayOf(1, 0),
            arrayOf(1, false, false),
            arrayOf(1, false, false, 0),
        )

        private val COMPONENT_FIELDS = listOf(
            "topActivity",
            "topActivityComponent",
            "realActivity",
            "baseActivity",
            "origActivity",
            "activity",
        )

        fun create(): TaskResolver? = try {
            TaskResolver()
        } catch (t: Throwable) {
            Log.e("TaskResolver", "Failed to bind activity task manager", t)
            null
        }
    }
}
