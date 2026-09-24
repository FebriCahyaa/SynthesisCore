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

package com.febricahyaa.synthesiscore

import org.lsposed.hiddenapibypass.HiddenApiBypass

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

// @SuppressLint("StaticFieldLeak") is intentional, this runs as a CLI tool via app_process,
// not inside an Android Activity lifecycle, so there is no real Context leak risk here.

@SuppressLint("StaticFieldLeak", "DiscouragedPrivateApi", "PrivateApi")
object MainKt {
    private const val POLL_INTERVAL_MS = 500L
    private const val PID_RETRY_INTERVAL_MS = 50L
    private const val MIN_SLEEP_MS = PID_RETRY_INTERVAL_MS
    private const val UNKNOWN_APP = "unknown 0 0"
    private const val NONE_APP = "none 0 0"

    // getThermalHeadroom() requires API 31+
    private const val THERMAL_API_MIN_SDK = 31

    /**
     * Version of the output format and CLI contract, written as `synthesis_version`.
     * Bump whenever a field or CLI mode is added, removed or changes meaning so
     * consumers (Flux) can detect a mismatched prebuilt APK.
     *
     * 1 = initial fields up to kernel_is_gki (implicit; the field did not exist yet)
     * 2 = synthesis_version field, --resolve mode
     */
    const val PROTOCOL_VERSION = 2

    private const val RESOLVE_FLAG = "--resolve"
    private const val TRANSACTION_PREFIX = "TRANSACTION_"

    // Caps the number of distinct warnings kept for log de-duplication.
    private const val MAX_LOGGED_WARNINGS = 64

    private val GKI_KERNEL_REGEX = Regex("-android\\d+-")
    private val PACKAGE_SANITIZE_REGEX = Regex("[^a-z0-9._-]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PACKAGE_NAME_REGEX = Regex("[a-z0-9]+(\\.[a-z0-9]+)+")

    private val FOREGROUND_METHOD_CANDIDATES = listOf(
        "getFocusedRootTaskInfo",
        "getFocusedRootTask",
        "getFocusedTaskInfo",
        "getFocusedStackInfo",
        "getTopActivity",
        "getTasks",
        "getRunningTasks"
    )

    private val COMPONENT_NAME_FIELDS = listOf(
        "topActivity",
        "topActivityComponent",
        "realActivity",
        "baseActivity",
        "origActivity",
        "activity"
    )

    private var systemContext: Context? = null

    private var activityTaskManager: Any? = null
    private var foregroundMethod: Method? = null
    private var powerManager: PowerManager? = null
    private var activityManager: ActivityManager? = null
    private var audioManager: AudioManager? = null
    private var batteryManager: BatteryManager? = null
    private var notificationManager: Any? = null
    private var getZenModeMethod: Method? = null

    // getThermalHeadroom() is available from API 31+; resolved once at init.
    private var getThermalHeadroomMethod: Method? = null

    private var bruteForceCandidates: List<Method>? = null

    // TRANSACTION_* codes exposed by the ATM stub, keyed by method name.
    // Empty when the stub fields could not be read; callers then skip filtering.
    private var atmTransactionCodes: Map<String, Int> = emptyMap()

    // Capabilities that never change during the process lifetime, resolved once at init.
    private var thermalApiAvailable = 0
    private var kernelIsGki = 0

    // Last resolved foreground process, reused while it is still alive.
    private var cachedProcessPkg: String? = null
    private var cachedProcessName = ""
    private var cachedPid = 0
    private var cachedUid = 0

    private val loggedWarnings = HashSet<String>()

    @Volatile
    private var lastStatus = ""

    private var outputPath = ""
    private var lockFilePath: String? = null

    @JvmStatic
    fun main(args: Array<String>) {
        // Usage: app_process / com.febricahyaa.synthesiscore.MainKt <output_path> [lock_file_path]
        //        app_process / com.febricahyaa.synthesiscore.MainKt --resolve [output_path]
        if (args.firstOrNull() == RESOLVE_FLAG) {
            exitProcess(runResolver(args.getOrNull(1)))
        }

        if (args.isEmpty()) {
            System.err.println("Usage: <output_path> [lock_file_path]")
            System.err.println("       $RESOLVE_FLAG [output_path] < Class::TRANSACTION_name lines")
            System.err.println("ERROR: output path is required.")
            return
        }
        outputPath = args[0]

        if (args.size >= 2) {
            lockFilePath = args[1]
        }

        bypassHiddenApiRestrictions()
        setupSystemContext()

        if (systemContext == null) {
            System.err.println("ERROR: System context is null.")
            return
        }

        if (!initializeServices()) {
            System.err.println("ERROR: Failed to initialize services, exiting.")
            return
        }

        val lockChannel = acquireLock()

        val monitorThread = Thread.currentThread()
        Runtime.getRuntime().addShutdownHook(Thread {
            lockChannel?.close()
            monitorThread.interrupt()
        })

        runMonitorLoop()
    }

    private fun acquireLock(): FileChannel? {
        val path = lockFilePath ?: return null
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()

            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )

            val lock: FileLock? = channel.tryLock()
            if (lock == null) {
                System.err.println("ERROR: Another instance holds the lock at '$path'.")
                channel.close()
                System.exit(1)
                null
            } else {
                channel
            }
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to acquire lock at '$path': ${e.message}")
            System.exit(1)
            null
        }
    }

    private fun runMonitorLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                writeStatus()
                // waitForValidFocusedApp() may already have spent part of the interval,
                // so only sleep for what remains to keep the cadence near POLL_INTERVAL_MS.
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Thread.sleep((POLL_INTERVAL_MS - elapsed).coerceAtLeast(MIN_SLEEP_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun setupSystemContext() {
        try {
            val looperClass = Class.forName("android.os.Looper")
            if (looperClass.getMethod("getMainLooper").invoke(null) == null) {
                looperClass.getMethod("prepareMainLooper").invoke(null)
            }

            val activityThreadClass = Class.forName("android.app.ActivityThread")

            val thread = activityThreadClass.getMethod("systemMain").invoke(null)
                ?: activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: error("Both systemMain() and currentActivityThread() returned null")

            systemContext =
                activityThreadClass.getMethod("getSystemContext").invoke(thread) as? Context
                    ?: error("getSystemContext() returned null")

        } catch (e: Exception) {
            System.err.println("ERROR: Failed to set up system context:")
            e.printStackTrace()
        }
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (e: Throwable) {
            // Catch Throwable: native failures (UnsatisfiedLinkError, NoSuchMethodError)
            // are Errors and would otherwise crash the daemon before the watchdog can help.
            // Hidden API bypass failed — features relying on private APIs
            // (zen mode, ATM foreground detection) will degrade gracefully.
            System.err.println("WARN: HiddenApiBypass failed, some features may be unavailable: ${e.message}")
        }
    }

    private fun initializeServices(): Boolean {
        return try {
            val ctx = systemContext ?: return false
            powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            initActivityTaskManager()
            initNotificationManager()
            initThermalHeadroomMethod()
            thermalApiAvailable = if (getThermalHeadroomMethod != null) 1 else 0
            kernelIsGki = if (isGkiKernel()) 1 else 0
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    private fun initActivityTaskManager() {
        val binder = getSystemService(resolveAtmServiceName())
            ?: error("ServiceManager returned null binder for '${resolveAtmServiceName()}'")
        val stubClassName = "${resolveAtmInterfaceName()}\$Stub"
        val atm = bindInterface(stubClassName, binder)
        activityTaskManager = atm
        atmTransactionCodes = readTransactionCodes(stubClassName)
        foregroundMethod = findForegroundMethod(atm)
        logForegroundCapabilities()
    }

    /**
     * Reads every static TRANSACTION_* field of [stubClassName] into a name -> code map.
     *
     * A method only has a TRANSACTION_* code when it is a real binder call served by
     * system_server, so this is used to decide deterministically which ATM methods exist
     * on this ROM instead of guessing from method names alone.
     */
    private fun readTransactionCodes(stubClassName: String): Map<String, Int> {
        return try {
            HiddenApiBypass.getStaticFields(Class.forName(stubClassName))
                .filterIsInstance<Field>()
                .filter { it.name.startsWith(TRANSACTION_PREFIX) && it.type == Int::class.javaPrimitiveType }
                .associate { field ->
                    field.isAccessible = true
                    field.name.removePrefix(TRANSACTION_PREFIX) to field.getInt(null)
                }
        } catch (e: Throwable) {
            System.err.println("WARN: Failed to read transaction codes from $stubClassName: ${e.message}")
            emptyMap()
        }
    }

    private fun hasTransaction(methodName: String): Boolean =
        atmTransactionCodes.isEmpty() || methodName in atmTransactionCodes

    private fun logForegroundCapabilities() {
        val available = FOREGROUND_METHOD_CANDIDATES.filter { it in atmTransactionCodes }
        System.err.println(
            "INFO: ATM transaction codes=${atmTransactionCodes.size}, " +
                    "foreground candidates=$available, " +
                    "selected=${foregroundMethod?.name ?: "none (brute force)"}"
        )
    }

    private fun initNotificationManager() {
        val binder = getSystemService(Context.NOTIFICATION_SERVICE)
            ?: error("ServiceManager returned null binder for notification service")
        notificationManager = bindInterface("android.app.INotificationManager\$Stub", binder)
        notificationManager?.let { manager ->
            getDeclaredMethods(manager.javaClass).forEach { member ->
                if (member.name == "getZenMode" && member.parameterTypes.isEmpty()) {
                    getZenModeMethod = member
                }
            }
        }
    }

    /**
     * Resolve getThermalHeadroom() once at startup.
     * Available on API 31+; silently skipped on older versions.
     */
    private fun initThermalHeadroomMethod() {
        if (Build.VERSION.SDK_INT < THERMAL_API_MIN_SDK) return
        try {
            getThermalHeadroomMethod = PowerManager::class.java
                .getMethod("getThermalHeadroom", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            System.err.println("WARN: getThermalHeadroom() not available on this build: ${e.message}")
        }
    }
    
    /**
     * Returns true if running on a GKI (Generic Kernel Image) kernel.
     *
     * GKI kernels are identified by the "-androidXX-" segment in `uname -r`,
     * e.g. "5.15.123-android13-8-00001-gabcdef". Vendor/OEM kernels carry
     * device-specific suffixes instead (e.g. "-perf+", "-qcom-le") and
     * return false.
     */
    private fun isGkiKernel(): Boolean {
        return try {
            val kernelVersion = System.getProperty("os.version") ?: ""
            kernelVersion.contains(GKI_KERNEL_REGEX)
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveAtmServiceName() =
        if (Build.VERSION.SDK_INT >= 29) "activity_task" else Context.ACTIVITY_SERVICE

    private fun resolveAtmInterfaceName() =
        if (Build.VERSION.SDK_INT >= 29) "android.app.IActivityTaskManager" else "android.app.IActivityManager"

    private fun getSystemService(name: String): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        return serviceManager.getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    }

    private fun bindInterface(stubClassName: String, binder: IBinder): Any {
        return Class.forName(stubClassName)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("asInterface returned null for $stubClassName")
    }

    private fun findForegroundMethod(atm: Any): Method? {
        val methods = getDeclaredMethods(atm.javaClass).associateBy { it.name }

        return FOREGROUND_METHOD_CANDIDATES
            .filter { hasTransaction(it) }
            .mapNotNull { candidate -> methods[candidate] }
            .find { method ->
                method.parameterTypes.isEmpty() ||
                        (method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.java) ||
                        method.name == "getTasks" || method.name == "getRunningTasks"
            }
            ?.apply { isAccessible = true }
    }

    private fun writeStatus() {
        // Resolve the focused app, retrying if the PID is not yet available.
        // Returns null only if interrupted; after the retry timeout the app is still
        // written with "0 0" as PID/UID (see waitForValidFocusedApp).
        val focusedApp = waitForValidFocusedApp() ?: return

        val currentStatus = buildStatus(focusedApp)
        if (currentStatus == lastStatus) return

        try {
            writeFileAtomically(outputPath, currentStatus)
            lastStatus = currentStatus
        } catch (e: Exception) {
            System.err.println("ERROR: writeStatus failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun writeFileAtomically(path: String, content: String) {
        val targetFile = File(path)
        targetFile.parentFile?.mkdirs()

        // Write atomically: write to a .tmp sibling then rename.
        // This prevents the C++ daemon from reading a partial file if we are
        // interrupted mid-write (e.g. OOM-killed or process restart).
        // Note: the rename raises IN_MOVED_TO (not IN_CLOSE_WRITE) on the target
        // name, so inotify watchers must listen for IN_MOVED_TO as well.
        val tmpFile = File("$path.tmp")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }

        if (!tmpFile.renameTo(targetFile)) {
            // renameTo can fail across filesystems (shouldn't happen here, but be safe).
            // Fall back to direct overwrite so the C++ side isn't starved of updates.
            System.err.println("WARN: atomic rename failed for $path, falling back to direct write")
            FileOutputStream(targetFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
        }
    }

    /**
     * Returns the focused-app string once its PID is known.
     *
     * When the foreground app was just launched it may not yet be visible to
     * [ActivityManager.getRunningAppProcesses], causing [getPidUid] to return "0 0".
     * Rather than writing that bogus value immediately, we poll every [PID_RETRY_INTERVAL_MS] ms
     * until the process shows up or [POLL_INTERVAL_MS] elapses.
     * If the timeout expires and the PID is still unknown, the app string with "0 0" is returned
     * so the output file is still updated rather than silently skipped.
     * Returns null only if interrupted.
     */
    private fun waitForValidFocusedApp(): String? {
        var focusedApp = getFocusedAppInfo()
        if (!hasMissingPid(focusedApp)) return focusedApp

        val deadline = System.currentTimeMillis() + POLL_INTERVAL_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(PID_RETRY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            focusedApp = getFocusedAppInfo()
            if (!hasMissingPid(focusedApp)) return focusedApp
        }

        // Timed out, write the app with 0 0 as PID/UID anyway.
        logOnce("pid_unresolved:$focusedApp", "WARN: PID still unresolved after ${POLL_INTERVAL_MS}ms for '$focusedApp'.")
        return focusedApp
    }

    /**
     * Returns true when [appInfo] represents a real foreground app whose PID could not
     * yet be resolved (i.e. ends with " 0 0" but is not the sentinel [NONE_APP] value).
     */
    private fun hasMissingPid(appInfo: String): Boolean =
        appInfo != NONE_APP && appInfo.endsWith(" 0 0")

    private fun buildStatus(focusedApp: String): String {
        val screenAwake = if (powerManager?.isInteractive == true) 1 else 0
        val batterySaver = if (powerManager?.isPowerSaveMode == true) 1 else 0
        val zenMode = getZenMode()
        val chargingState = getChargingState()
        val thermalStatus = getThermalStatus()
        val audioActive = if (isAudioActive()) 1 else 0

        return buildString {
            appendLine("synthesis_version $PROTOCOL_VERSION")
            appendLine("focused_app $focusedApp")
            appendLine("screen_awake $screenAwake")
            appendLine("battery_saver $batterySaver")
            appendLine("zen_mode $zenMode")
            appendLine("charging_state $chargingState")
            appendLine("thermal_status $thermalStatus")
            appendLine("audio_active $audioActive")
            appendLine("thermal_api_available $thermalApiAvailable")
            appendLine("kernel_is_gki $kernelIsGki")
        }
    }

    /**
     * Returns the current charging state as an integer:
     *   0 = not charging / discharging
     *   1 = charging (AC, USB, or wireless)
     *
     * Uses [BatteryManager.isCharging] which is available from API 23+.
     * Falls back to 0 gracefully if the service is unavailable.
     */
    private fun getChargingState(): Int {
        return try {
            if (batteryManager?.isCharging == true) 1 else 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Returns true if any audio stream is currently active (music, game audio, etc.).
     *
     * Checks [AudioManager.isMusicActive] which covers MediaPlayer/ExoPlayer/AudioTrack
     * usage — the most common audio streams in mobile games.
     * Falls back to false if the audio service is unavailable.
     */
    private fun isAudioActive(): Boolean {
        return try {
            audioManager?.isMusicActive == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns a normalised thermal headroom value clamped to [0.0, 1.0].
     *
     * 1.0 = no thermal pressure (cool)
     * 0.0 = device is at thermal limit (hot)
     *
     * Uses [PowerManager.getThermalHeadroom] with a 1-second forecast window,
     * available from API 31+. Returns -1.00 on unsupported devices or on error.
     */
    private fun getThermalStatus(): String {
        if (Build.VERSION.SDK_INT < THERMAL_API_MIN_SDK) return "-1.00"
        return try {
            val method = getThermalHeadroomMethod ?: return "-1.00"
            val headroom = method.invoke(powerManager, 1) as? Float ?: return "-1.00"
            // getThermalHeadroom() may return NaN on devices whose thermal HAL
            // does not provide a valid headroom value even when the API is present.
            // NaN.coerceIn() stays NaN, so we must guard explicitly.
            if (headroom.isNaN()) return "-1.00"
            val clamped = headroom.coerceIn(0f, 1f)
            "%.2f".format(clamped)
        } catch (_: Exception) {
            "-1.00"
        }
    }

    private fun getZenMode(): Int {
        return try {
            getZenModeMethod?.invoke(notificationManager) as? Int ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun getFocusedAppInfo(): String {
        return try {
            val result = invokeForegroundMethod() ?: return UNKNOWN_APP
            if (result is List<*>) {
                getFocusedAppFromList(result)
            } else {
                resolveAppInfoFromObject(result)
            }
        } catch (e: Throwable) {
            logOnce(
                "focused_app:${e.javaClass.name}:${e.message}",
                "WARN: getFocusedAppInfo failed (further identical errors suppressed)", e
            )
            UNKNOWN_APP
        }
    }

    private fun getFocusedAppFromList(list: List<*>): String {
        if (list.isEmpty()) return NONE_APP
        list.forEach { element ->
            extractComponentName(element)?.let { return buildAppInfo(it.packageName) }
        }
        return resolveAppInfoFromObject(list[0]!!)
    }

    private fun resolveAppInfoFromObject(obj: Any): String {
        extractComponentName(obj)?.let { return buildAppInfo(it.packageName) }
        return findPackageLikeString(obj)?.let { buildAppInfo(it) } ?: UNKNOWN_APP
    }

    private fun invokeForegroundMethod(): Any? {
        val method = foregroundMethod ?: return bruteForceForegroundMethod()
        return tryInvokeForegroundMethod(method) ?: bruteForceForegroundMethod()
    }

    private fun tryInvokeForegroundMethod(method: Method): Any? {
        val name = method.name
        return try {
            when {
                name == "getTasks" || name == "getRunningTasks" -> {
                    tryInvokeWithArgs(
                        method,
                        activityTaskManager!!,
                        arrayOf(1),
                        arrayOf(1, 0),
                        arrayOf(1, false, false)
                    )
                }

                method.parameterTypes.isEmpty() -> method.invoke(activityTaskManager)
                else -> tryInvokeWithArgs(method, activityTaskManager!!, arrayOf(0))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryInvokeWithArgs(method: Method, target: Any, vararg argSets: Array<Any>): Any? {
        for (args in argSets) {
            try {
                return method.invoke(target, *args)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Last-resort foreground lookup when no known candidate method works.
     *
     * Only read-only getters ("get*") that are real binder calls (have a TRANSACTION_* code)
     * are tried, in a stable order. Without this restriction, methods such as removeTask(int)
     * or startSystemLockTaskMode(int) would match the name filter and could be invoked.
     */
    private fun bruteForceForegroundMethod(): Any? {
        return try {
            val candidates =
                bruteForceCandidates ?: getDeclaredMethods(activityTaskManager!!.javaClass)
                    .filter {
                        val name = it.name.lowercase()
                        name.startsWith("get") &&
                                (name.contains("focus") || name.contains("top") || name.contains("task")) &&
                                hasTransaction(it.name)
                    }
                    .sortedWith(compareBy<Method>({ it.name }, { it.parameterTypes.size }))
                    .onEach { it.isAccessible = true }
                    .also { bruteForceCandidates = it }

            candidates.firstNotNullOfOrNull { method ->
                when {
                    method.parameterTypes.isEmpty() ->
                        tryInvokeQuietly { method.invoke(activityTaskManager) }

                    method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.java ->
                        tryInvokeQuietly { method.invoke(activityTaskManager, 1) }

                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private inline fun tryInvokeQuietly(block: () -> Any?): Any? {
        return try {
            block()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractComponentName(obj: Any?): ComponentName? {
        if (obj == null) return null
        if (obj is ComponentName) return obj

        COMPONENT_NAME_FIELDS.forEach { fieldName ->
            getComponentNameFromField(obj, obj.javaClass, fieldName)?.let { return it }
        }

        return scanHierarchyForComponentName(obj)
    }

    private fun getComponentNameFromField(
        obj: Any,
        cls: Class<*>,
        fieldName: String
    ): ComponentName? {
        return try {
            val field = cls.getDeclaredField(fieldName).apply { isAccessible = true }
            field.get(obj) as? ComponentName
        } catch (_: Exception) {
            null
        }
    }

    private fun scanHierarchyForComponentName(obj: Any): ComponentName? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            getInstanceFields(cls).forEach { field ->
                try {
                    field.isAccessible = true
                    val value = field.get(obj)
                    if (value is ComponentName) return value
                } catch (_: Exception) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findPackageLikeString(obj: Any?): String? {
        if (obj == null) return null
        extractPackageName(obj.toString())?.let { return it }

        getInstanceFields(obj.javaClass).forEach { field ->
            if (field.type == String::class.java) {
                try {
                    field.isAccessible = true
                    (field.get(obj) as? String)?.let { str ->
                        extractPackageName(str)?.let { return it }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    internal fun extractPackageName(input: String?): String? {
        if (input == null || input.indexOf('.') <= 0) return null
        val normalized = input.lowercase().replace(PACKAGE_SANITIZE_REGEX, " ")
        return normalized.split(WHITESPACE_REGEX).find {
            it.contains(".") && it.matches(PACKAGE_NAME_REGEX)
        }
    }

    private fun buildAppInfo(pkg: String): String {
        val pidUid = getPidUid(pkg)
        return "$pkg $pidUid"
    }

    /**
     * Returns "<pid> <uid>" for [pkg].
     *
     * The last resolved process is reused while it is still alive, so the expensive
     * getRunningAppProcesses() binder call only happens when the foreground app changes
     * or its process dies.
     */
    private fun getPidUid(pkg: String): String {
        if (pkg == cachedProcessPkg && isProcessAlive(cachedPid, cachedProcessName)) {
            return "$cachedPid $cachedUid"
        }
        cachedProcessPkg = null

        return try {
            val process = activityManager?.runningAppProcesses
                ?.find { it.processName == pkg || it.pkgList?.contains(pkg) == true }
                ?: return "0 0"
            if (process.pid > 0) {
                cachedProcessPkg = pkg
                cachedProcessName = process.processName
                cachedPid = process.pid
                cachedUid = process.uid
            }
            "${process.pid} ${process.uid}"
        } catch (e: Exception) {
            logOnce("pid_uid:${e.javaClass.name}", "WARN: getPidUid failed for '$pkg': ${e.message}")
            "0 0"
        }
    }

    /**
     * Returns true if [pid] is still running as [processName].
     * Comparing /proc/<pid>/cmdline guards against the PID being reused by another process.
     */
    private fun isProcessAlive(pid: Int, processName: String): Boolean {
        if (pid <= 0) return false
        return try {
            val cmdline = File("/proc/$pid/cmdline").readBytes()
            val end = cmdline.indexOf(0.toByte()).let { if (it < 0) cmdline.size else it }
            String(cmdline, 0, end, Charsets.UTF_8) == processName
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Logs [message] only the first time [key] is seen, so an error that repeats on
     * every poll does not flood the log. At most [MAX_LOGGED_WARNINGS] keys are tracked.
     */
    private fun logOnce(key: String, message: String, t: Throwable? = null) {
        if (loggedWarnings.size >= MAX_LOGGED_WARNINGS || !loggedWarnings.add(key)) return
        System.err.println(message)
        t?.printStackTrace()
    }

    private fun getDeclaredMethods(cls: Class<*>): List<Method> {
        return HiddenApiBypass.getDeclaredMethods(cls).filterIsInstance<Method>()
    }

    private fun getInstanceFields(cls: Class<*>): List<Field> {
        return HiddenApiBypass.getInstanceFields(cls).filterIsInstance<Field>()
    }

    /**
     * One-shot resolver mode: reads `Class::TRANSACTION_name` lines from stdin and
     * prints `Class::TRANSACTION_name <code>` for each field that could be resolved.
     *
     * Native code can use the resolved codes to issue binder transactions directly,
     * since the codes differ between Android versions and ROMs. Blank lines and lines
     * starting with '#' are ignored. Unresolved entries are reported on stderr and
     * omitted from the output.
     *
     * @param outputPath File to write atomically, or null to print to stdout.
     * @return 0 if every entry resolved, 1 if any entry failed, 2 if the output could not be written.
     */
    private fun runResolver(outputPath: String?): Int {
        bypassHiddenApiRestrictions()

        val output = StringBuilder()
        var failures = 0

        BufferedReader(InputStreamReader(System.`in`)).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { entry ->
                    val parts = entry.split("::")
                    if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                        System.err.println("ERROR: Invalid format '$entry'. Use Class::TRANSACTION_name")
                        failures++
                        return@forEach
                    }
                    try {
                        val code = resolveStaticIntField(parts[0], parts[1])
                        output.append(entry).append(' ').append(code).append('\n')
                    } catch (t: Throwable) {
                        System.err.println("ERROR: Failed to resolve $entry -> ${t.javaClass.simpleName}: ${t.message}")
                        failures++
                    }
                }
        }

        try {
            if (outputPath == null) {
                print(output)
                System.out.flush()
            } else {
                writeFileAtomically(outputPath, output.toString())
            }
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to write resolver output: ${e.message}")
            return 2
        }

        return if (failures == 0) 0 else 1
    }

    private fun resolveStaticIntField(className: String, fieldName: String): Int {
        val field = resolveClass(className).getDeclaredField(fieldName)
        require(Modifier.isStatic(field.modifiers)) { "$fieldName is not static" }
        field.isAccessible = true
        return field.getInt(null)
    }

    /**
     * Loads [className], accepting dotted notation for nested classes
     * (e.g. "android.os.IPowerManager.Stub" -> "android.os.IPowerManager$Stub").
     * Trailing dots are converted to '$' one at a time until a class is found.
     */
    internal fun resolveClass(className: String): Class<*> {
        var candidate = className
        while (true) {
            try {
                return Class.forName(candidate)
            } catch (e: ClassNotFoundException) {
                val lastDot = candidate.lastIndexOf('.')
                if (lastDot <= 0) throw ClassNotFoundException(className)
                candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1)
            }
        }
    }
}
