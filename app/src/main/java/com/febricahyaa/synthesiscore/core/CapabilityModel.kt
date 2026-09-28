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
 * One observed fact. The five kinds are the value types the canonical schema
 * admits; a fact that was not observed is not stored at all.
 */
sealed interface CapabilityValue {
    data class BoolValue(val value: Boolean) : CapabilityValue
    data class IntValue(val value: Long) : CapabilityValue
    data class DoubleValue(val value: Double) : CapabilityValue
    data class StringValue(val value: String) : CapabilityValue
    data class ListValue(val value: List<String>) : CapabilityValue
}

/** A contributor's run: what it covered and how it went. */
data class CapabilitySourceInfo(
    val id: String,
    val domains: List<String>,
    val status: String = CapabilitySchema.SourceStatus.OK,
    val detail: String = "",
    val collectedAtMs: Long = 0L,
)

/**
 * The canonical capability model (schema v4).
 *
 * Holds facts and their provenance and nothing else: it never ranks, scores or
 * selects. The JSON it renders is the interchange format between this process
 * and fluxd, and matches byte for byte what Flux's C++ implementation produces
 * for the same facts — ordered containers throughout, so the output is stable
 * and diffable.
 */
class CapabilityModel(
    var schemaVersion: Int = CapabilitySchema.VERSION,
    var generatedAtMs: Long = 0L,
) {
    private val domains = sortedMapOf<String, MutableMap<String, CapabilityValue>>()
    private val domainSource = mutableMapOf<String, String>()
    private val keySource = mutableMapOf<String, MutableMap<String, String>>()
    private val sources = mutableListOf<CapabilitySourceInfo>()

    // -- writing ------------------------------------------------------------

    fun set(domain: String, key: String, value: CapabilityValue, source: String) {
        domains.getOrPut(domain) { sortedMapOf() }[key] = value

        val existing = domainSource[domain]
        if (existing == null) {
            domainSource[domain] = source
            keySource[domain]?.remove(key)
        } else if (existing == source) {
            keySource[domain]?.remove(key)
        } else {
            keySource.getOrPut(domain) { mutableMapOf() }[key] = source
        }
    }

    fun set(domain: String, key: String, value: Boolean, source: String) =
        set(domain, key, CapabilityValue.BoolValue(value), source)

    fun set(domain: String, key: String, value: Long, source: String) =
        set(domain, key, CapabilityValue.IntValue(value), source)

    fun set(domain: String, key: String, value: Double, source: String) =
        set(domain, key, CapabilityValue.DoubleValue(value), source)

    fun set(domain: String, key: String, value: String, source: String) =
        set(domain, key, CapabilityValue.StringValue(value), source)

    fun set(domain: String, key: String, value: List<String>, source: String) =
        set(domain, key, CapabilityValue.ListValue(value), source)

    /** Records a contributor's run, replacing an earlier entry with the same id. */
    fun addSource(info: CapabilitySourceInfo) {
        val at = sources.indexOfFirst { it.id == info.id }
        if (at >= 0) sources[at] = info else sources.add(info)
    }

    // -- reading ------------------------------------------------------------

    fun has(domain: String, key: String): Boolean = domains[domain]?.containsKey(key) == true

    fun get(domain: String, key: String): CapabilityValue? = domains[domain]?.get(key)

    fun getBool(domain: String, key: String): Boolean? = (get(domain, key) as? CapabilityValue.BoolValue)?.value
    fun getInt(domain: String, key: String): Long? = (get(domain, key) as? CapabilityValue.IntValue)?.value
    fun getString(domain: String, key: String): String? = (get(domain, key) as? CapabilityValue.StringValue)?.value
    fun getList(domain: String, key: String): List<String>? = (get(domain, key) as? CapabilityValue.ListValue)?.value

    fun getDouble(domain: String, key: String): Double? = when (val v = get(domain, key)) {
        is CapabilityValue.DoubleValue -> v.value
        // JSON has one number type, so a whole-numbered rate reads back as an int.
        is CapabilityValue.IntValue -> v.value.toDouble()
        else -> null
    }

    /** The contributor credited with a key, or null when the key is absent. */
    fun sourceOf(domain: String, key: String): String? {
        if (!has(domain, key)) return null
        return keySource[domain]?.get(key) ?: domainSource[domain]
    }

    fun domainNames(): Set<String> = domains.keys
    fun keysIn(domain: String): Set<String> = domains[domain]?.keys.orEmpty()
    fun sourceList(): List<CapabilitySourceInfo> = sources.toList()
    fun factCount(): Int = domains.values.sumOf { it.size }

    /**
     * Folds [other] into this model. Facts already present are kept, so the first
     * contributor to observe a key owns it and a later one cannot quietly
     * overwrite a better observation.
     */
    fun merge(other: CapabilityModel): Int {
        var taken = 0
        for (domain in other.domainNames()) {
            for (key in other.keysIn(domain)) {
                if (has(domain, key)) continue
                val value = other.get(domain, key) ?: continue
                set(domain, key, value, other.sourceOf(domain, key).orEmpty())
                taken++
            }
        }
        other.sourceList().forEach { addSource(it) }
        return taken
    }

    // -- serialisation ------------------------------------------------------

    /**
     * Renders the canonical JSON. Hand-written rather than via org.json so the
     * model stays a plain-JVM object that unit tests can exercise without an
     * Android runtime, and so number formatting is locale-independent.
     */
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schema_version\": ").append(schemaVersion).append(",\n")
        append("  \"generated_at_ms\": ").append(generatedAtMs).append(",\n")

        append("  \"capabilities\": {")
        appendDomains(this)
        append("},\n")

        append("  \"provenance\": {")
        appendProvenance(this)
        append("},\n")

        append("  \"sources\": [")
        appendSources(this)
        append("]\n")
        append("}")
    }

    private fun appendDomains(sb: StringBuilder) = with(sb) {
        if (domains.isEmpty()) return@with
        append('\n')
        domains.entries.forEachIndexed { i, (domain, values) ->
            append("    ").append(quote(domain)).append(": {")
            if (values.isNotEmpty()) {
                append('\n')
                values.entries.forEachIndexed { j, (key, value) ->
                    append("      ").append(quote(key)).append(": ").append(render(value))
                    if (j < values.size - 1) append(',')
                    append('\n')
                }
                append("    ")
            }
            append('}')
            if (i < domains.size - 1) append(',')
            append('\n')
        }
        append("  ")
    }

    private fun appendProvenance(sb: StringBuilder) = with(sb) {
        if (domains.isEmpty()) return@with
        append('\n')
        domains.keys.forEachIndexed { i, domain ->
            append("    ").append(quote(domain)).append(": {\n")
            append("      \"_source\": ").append(quote(domainSource[domain].orEmpty()))
            val overrides = keySource[domain]?.toSortedMap().orEmpty()
            overrides.forEach { (key, source) ->
                append(",\n      ").append(quote(key)).append(": ").append(quote(source))
            }
            append("\n    }")
            if (i < domains.size - 1) append(',')
            append('\n')
        }
        append("  ")
    }

    private fun appendSources(sb: StringBuilder) = with(sb) {
        if (sources.isEmpty()) return@with
        append('\n')
        sources.forEachIndexed { i, s ->
            append("    {\n")
            append("      \"id\": ").append(quote(s.id)).append(",\n")
            append("      \"domains\": [")
            append(s.domains.joinToString(", ") { quote(it) })
            append("],\n")
            append("      \"status\": ").append(quote(s.status)).append(",\n")
            append("      \"detail\": ").append(quote(s.detail)).append(",\n")
            append("      \"collected_at_ms\": ").append(s.collectedAtMs).append('\n')
            append("    }")
            if (i < sources.size - 1) append(',')
            append('\n')
        }
        append("  ")
    }

    private fun render(value: CapabilityValue): String = when (value) {
        is CapabilityValue.BoolValue -> value.value.toString()
        is CapabilityValue.IntValue -> value.value.toString()
        // app_process takes its locale from persist.sys.locale, so an id-ID device
        // would otherwise emit "120,5" and every JSON parser would reject it.
        is CapabilityValue.DoubleValue -> String.format(Locale.ROOT, "%s", value.value)
        is CapabilityValue.StringValue -> quote(value.value)
        is CapabilityValue.ListValue -> value.value.joinToString(", ", "[", "]") { quote(it) }
    }

    companion object {
        /** JSON string escaping, including the control characters a driver name could carry. */
        fun quote(raw: String): String = buildString {
            append('"')
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else ->
                        if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code))
                        else append(c)
                }
            }
            append('"')
        }
    }
}
