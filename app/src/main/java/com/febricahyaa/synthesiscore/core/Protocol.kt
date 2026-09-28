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

import java.util.Locale

/**
 * The status file contract shared with consumers (Flux fluxd and WebUI).
 *
 * Format: one `key value...` pair per line, `synthesis_version` first. Consumers must
 * ignore unknown keys, and a key that is absent means "unsupported on this device".
 */
object Protocol {
    /**
     * Bump whenever a field or CLI mode is added, removed or changes meaning.
     *
     * 1 = initial fields up to kernel_is_gki (implicit; synthesis_version did not exist)
     * 2 = synthesis_version field, --resolve mode
     * 3 = thermal_level, battery_level, battery_temp, call_active; --once and
     *     --capabilities modes; numbers always use '.' as decimal separator
     * 4 = capability_schema and capability_file, announcing the canonical
     *     capability model (CapabilitySchema); --capability-model mode
     *
     * 4 is additive: every key from 1-3 keeps its name, meaning, position and
     * format, so a consumer built against 3 reads a 4 file correctly and simply
     * ignores the two new keys. Flux's native reader is tested against exactly
     * that case in tests/synthesis_core_test.cpp.
     */
    const val VERSION = 4

    const val SYNTHESIS_VERSION = "synthesis_version"
    const val FOCUSED_APP = "focused_app"
    const val SCREEN_AWAKE = "screen_awake"
    const val BATTERY_SAVER = "battery_saver"
    const val ZEN_MODE = "zen_mode"
    const val CHARGING_STATE = "charging_state"
    const val THERMAL_STATUS = "thermal_status"
    const val AUDIO_ACTIVE = "audio_active"
    const val THERMAL_API_AVAILABLE = "thermal_api_available"
    const val KERNEL_IS_GKI = "kernel_is_gki"
    const val THERMAL_LEVEL = "thermal_level"
    const val BATTERY_LEVEL = "battery_level"
    const val BATTERY_TEMP = "battery_temp"
    const val CALL_ACTIVE = "call_active"

    // Protocol 4. Announce the canonical capability model without carrying it:
    // capabilities are nested data that does not fit one key per line, and they
    // change on a different timescale from the per-tick state in this file.
    // Consumers that want them read CAPABILITY_FILE.
    const val CAPABILITY_SCHEMA = "capability_schema"
    const val CAPABILITY_FILE = "capability_file"

    /** Output order. Fields from protocol 1 keep their original order for older parsers. */
    val FIELD_ORDER = listOf(
        SYNTHESIS_VERSION,
        FOCUSED_APP,
        SCREEN_AWAKE,
        BATTERY_SAVER,
        ZEN_MODE,
        CHARGING_STATE,
        THERMAL_STATUS,
        AUDIO_ACTIVE,
        THERMAL_API_AVAILABLE,
        KERNEL_IS_GKI,
        THERMAL_LEVEL,
        BATTERY_LEVEL,
        BATTERY_TEMP,
        CALL_ACTIVE,
        // Protocol 4 keys are appended, so every earlier key keeps its position.
        CAPABILITY_SCHEMA,
        CAPABILITY_FILE,
    )

    private val ORDER_INDEX = FIELD_ORDER.withIndex().associate { it.value to it.index }

    /** Flux reads each line into a 256-byte buffer; keep key + value well below it. */
    private const val MAX_VALUE_LENGTH = 192

    private val KEY_REGEX = Regex("[a-z][a-z0-9_]{0,47}")
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")

    /**
     * Renders [values] in protocol order; unknown keys follow, sorted by name.
     *
     * The file is parsed line by line by native code, so it is kept strictly
     * well-formed: keys that are not `[a-z][a-z0-9_]{0,47}` are dropped, control
     * characters (newlines included) in values become spaces and values are capped at
     * [MAX_VALUE_LENGTH]. A value can therefore never inject an extra line such as a
     * forged `focused_app`, nor overflow a consumer's line buffer into the next key.
     */
    fun render(values: Map<String, String>): String = buildString {
        append(SYNTHESIS_VERSION).append(' ').append(VERSION).append('\n')
        values.keys
            .filter { it != SYNTHESIS_VERSION && KEY_REGEX.matches(it) }
            .sortedWith(compareBy<String>({ ORDER_INDEX[it] ?: Int.MAX_VALUE }, { it }))
            .forEach { key -> append(key).append(' ').append(sanitize(values.getValue(key))).append('\n') }
    }

    fun sanitize(value: String): String = value.replace(CONTROL_CHARS, " ").trim().take(MAX_VALUE_LENGTH)

    fun flag(value: Boolean): String = if (value) "1" else "0"

    /**
     * Locale-independent decimal. app_process takes its default locale from
     * persist.sys.locale, so `"%.2f".format(x)` yields "0,85" on e.g. id-ID devices,
     * which C `sscanf("%f")` and JS `parseFloat` read as 0.
     */
    fun decimal(value: Float, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)
}
