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

import android.app.ActivityManager

import com.febricahyaa.synthesiscore.core.Log

import java.io.File

/**
 * Maps a package to the PID/UID of its running process.
 *
 * The last match is cached while `/proc/<pid>/cmdline` still names the same process,
 * so [ActivityManager.getRunningAppProcesses] (a binder call that walks every process)
 * only runs when the foreground app changes or its process dies.
 */
class ProcessTracker(private val activityManager: ActivityManager) {
    data class Process(val pid: Int, val uid: Int)

    private var cachedPackage: String? = null
    private var cachedProcessName = ""
    private var cached: Process? = null

    /** The running process of [packageName], or null if it has none (yet). */
    fun find(packageName: String): Process? {
        val hit = cached
        if (packageName == cachedPackage && hit != null && isAlive(hit.pid, cachedProcessName)) return hit
        cachedPackage = null
        cached = null

        val info = try {
            activityManager.runningAppProcesses
                ?.find { it.processName == packageName || it.pkgList?.contains(packageName) == true }
        } catch (e: Exception) {
            Log.once("pid_uid:${e.javaClass.name}", "ProcessTracker", "getRunningAppProcesses failed: ${e.message}")
            null
        } ?: return null
        if (info.pid <= 0) return null

        return Process(info.pid, info.uid).also {
            cachedPackage = packageName
            cachedProcessName = info.processName
            cached = it
        }
    }

    /** True if [pid] is still running as [processName] (guards against PID reuse). */
    private fun isAlive(pid: Int, processName: String): Boolean = try {
        val cmdline = File("/proc/$pid/cmdline").readBytes()
        val end = cmdline.indexOf(0.toByte()).let { if (it < 0) cmdline.size else it }
        String(cmdline, 0, end, Charsets.UTF_8) == processName
    } catch (_: Exception) {
        false
    }
}
