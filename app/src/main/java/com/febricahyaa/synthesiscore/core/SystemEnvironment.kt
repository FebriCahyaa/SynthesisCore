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
import android.content.Context
import android.os.Looper

import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Bootstraps the Android framework inside a bare app_process.
 *
 * app_process gives us the framework classes but no Application, Looper or Context.
 * This recreates the minimum a system_server client needs:
 *  1. hidden API exemptions, so @hide/@SystemApi members can be reached by reflection
 *  2. the main [Looper], which framework listeners and the [Engine] dispatch on
 *  3. a system [Context] from `ActivityThread.systemMain()`, so public managers
 *     (PowerManager, AudioManager, ...) can be obtained the regular way
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object SystemEnvironment {
    private const val TAG = "Env"

    /** False when the hidden API bypass failed; reflection on @hide members may then fail. */
    var hiddenApiExempt = false
        private set

    /**
     * Exempts all hidden APIs. Throwable is caught on purpose: the bypass fails with
     * Errors (UnsatisfiedLinkError, NoSuchMethodError) on unexpected ART builds, and the
     * daemon must degrade instead of crashing.
     */
    fun exemptHiddenApis(): Boolean {
        hiddenApiExempt = try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (t: Throwable) {
            Log.w(TAG, "HiddenApiBypass failed, hidden API features may be unavailable: ${t.message}")
            false
        }
        return hiddenApiExempt
    }

    /** Prepares the main looper and returns the system context, or null on failure. */
    fun bootstrap(): Context? {
        exemptHiddenApis()
        return try {
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getMethod("systemMain").invoke(null)
                ?: activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: error("Both systemMain() and currentActivityThread() returned null")

            activityThreadClass.getMethod("getSystemContext").invoke(thread) as? Context
                ?: error("getSystemContext() returned null")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set up system context", t)
            null
        }
    }
}
