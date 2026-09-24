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

import android.annotation.SuppressLint
import android.os.IBinder

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Reflection helpers for talking to system services over binder.
 *
 * Everything that touches @hide AIDL interfaces lives here, so the rest of the code
 * base only deals with public framework APIs or with these typed helpers.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object Binders {
    const val TRANSACTION_PREFIX = "TRANSACTION_"

    /** `ServiceManager.getService(name)`, or null when the service is not registered. */
    fun getService(name: String): IBinder? =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder

    /** `<stubClassName>.asInterface(binder)`, e.g. `android.app.IActivityTaskManager$Stub`. */
    fun asInterface(stubClassName: String, binder: IBinder): Any =
        Class.forName(stubClassName)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("asInterface returned null for $stubClassName")

    /**
     * Reads every static `TRANSACTION_*` field of an AIDL stub into a
     * method name -> transaction code map. A method only has such a code when it is a
     * real binder call on this ROM, which makes the map a reliable capability probe.
     * Returns an empty map when the fields cannot be read.
     */
    fun transactionCodes(stubClassName: String): Map<String, Int> = try {
        HiddenApiBypass.getStaticFields(Class.forName(stubClassName))
            .filterIsInstance<Field>()
            .filter { it.name.startsWith(TRANSACTION_PREFIX) && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name.removePrefix(TRANSACTION_PREFIX) to field.getInt(null)
            }
    } catch (t: Throwable) {
        Log.w("Binders", "Failed to read transaction codes from $stubClassName: ${t.message}")
        emptyMap()
    }

    /**
     * Loads [className], accepting dotted notation for nested classes
     * (e.g. "android.os.IPowerManager.Stub" -> "android.os.IPowerManager$Stub").
     * Trailing dots are converted to '$' one at a time until a class is found.
     */
    fun resolveClass(className: String): Class<*> {
        var candidate = className
        while (true) {
            try {
                return Class.forName(candidate)
            } catch (_: ClassNotFoundException) {
                val lastDot = candidate.lastIndexOf('.')
                if (lastDot <= 0) throw ClassNotFoundException(className)
                candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1)
            }
        }
    }

    /** Value of a `static final int` constant, e.g. a `TRANSACTION_*` code. */
    fun staticIntField(className: String, fieldName: String): Int {
        val field = resolveClass(className).getDeclaredField(fieldName)
        require(Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers)) {
            "$fieldName is not a static final constant"
        }
        require(field.type == Int::class.javaPrimitiveType) { "$fieldName is not an int" }
        field.isAccessible = true
        return field.getInt(null)
    }

    fun declaredMethods(cls: Class<*>): List<Method> =
        HiddenApiBypass.getDeclaredMethods(cls).filterIsInstance<Method>()

    fun instanceFields(cls: Class<*>): List<Field> =
        HiddenApiBypass.getInstanceFields(cls).filterIsInstance<Field>()
}
