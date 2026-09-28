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

/**
 * The canonical capability model, which this module owns.
 *
 * `schema/capability_schema_v4.json` is the machine-readable statement of the
 * same thing and is the contract other repositories vendor: Flux carries a copy
 * at `jni/gfx/capability_schema_v4.json` and binds to it in C++. Both sides have
 * a test that fails when their bindings drift from their copy of the descriptor.
 *
 * Ownership here means the model and its interpretation, not its collection: a
 * capability is gathered by whichever subsystem can actually see it. Vulkan and
 * GPU facts come from fluxd's native probe, framework-visible display and
 * composer facts from providers in this process. Every contributor normalises
 * into these domains and keys, and every fact records where it came from.
 *
 * Rules:
 *  - additive within a version; removing or repurposing a key needs a version
 *    bump and a migration note
 *  - an absent key means "unsupported or not observed", never false or zero
 *  - contributors report observable facts; interpretation lives above this layer
 */
object CapabilitySchema {
    /** Keep in step with schema/capability_schema_v4.json and Flux's copy. */
    const val VERSION = 4

    // -- domains ------------------------------------------------------------
    const val DOMAIN_VULKAN = "vulkan"
    const val DOMAIN_GPU = "gpu"
    const val DOMAIN_DISPLAY = "display"
    const val DOMAIN_HWC = "hwc"
    const val DOMAIN_RENDERENGINE = "renderengine"
    const val DOMAIN_RUNTIME = "runtime"

    val DOMAINS = listOf(
        DOMAIN_VULKAN,
        DOMAIN_GPU,
        DOMAIN_DISPLAY,
        DOMAIN_HWC,
        DOMAIN_RENDERENGINE,
        DOMAIN_RUNTIME,
    )

    /** vulkan.* — collected natively by fluxd; this process never loads libvulkan. */
    object Vulkan {
        const val AVAILABLE = "available"
        const val STATUS = "status"
        const val DETAIL = "detail"
        const val LOADER_PRESENT = "loader_present"
        const val INSTANCE_VERSION = "instance_version"
        const val API_VERSION = "api_version"
        const val API_VERSION_MAJOR = "api_version_major"
        const val API_VERSION_MINOR = "api_version_minor"
        const val API_VERSION_PATCH = "api_version_patch"
        const val DRIVER_VERSION_RAW = "driver_version_raw"
        const val DEVICE_COUNT = "device_count"
        const val DEVICE_NAME = "device_name"
        const val DEVICE_TYPE = "device_type"
        const val VENDOR_ID = "vendor_id"
        const val DEVICE_ID = "device_id"
        const val INSTANCE_EXTENSIONS = "instance_extensions"
        const val DEVICE_EXTENSIONS = "device_extensions"
        const val FEATURES_SUPPORTED = "features_supported"

        const val STATUS_OK = "ok"
        const val STATUS_ABSENT = "absent"
        const val STATUS_NO_DEVICE = "no_device"
        const val STATUS_ERROR = "error"

        val KEYS = listOf(
            AVAILABLE, STATUS, DETAIL, LOADER_PRESENT, INSTANCE_VERSION, API_VERSION,
            API_VERSION_MAJOR, API_VERSION_MINOR, API_VERSION_PATCH, DRIVER_VERSION_RAW,
            DEVICE_COUNT, DEVICE_NAME, DEVICE_TYPE, VENDOR_ID, DEVICE_ID,
            INSTANCE_EXTENSIONS, DEVICE_EXTENSIONS, FEATURES_SUPPORTED,
        )
    }

    /** gpu.* — normalised identity, mostly derived from the Vulkan facts. */
    object Gpu {
        const val VENDOR = "vendor"
        const val FAMILY = "family"
        const val MODEL = "model"
        const val VENDOR_ID = "vendor_id"
        const val DEVICE_ID = "device_id"
        const val DRIVER_VERSION = "driver_version"
        const val KGSL_PRESENT = "kgsl_present"
        const val DEVFREQ_PRESENT = "devfreq_present"

        val KEYS = listOf(VENDOR, FAMILY, MODEL, VENDOR_ID, DEVICE_ID, DRIVER_VERSION, KGSL_PRESENT, DEVFREQ_PRESENT)
    }

    /** display.* — SurfaceFlinger is the authority, so a framework provider owns these. */
    object Display {
        const val WIDTH_PX = "width_px"
        const val HEIGHT_PX = "height_px"
        const val DENSITY_DPI = "density_dpi"
        const val REFRESH_RATE_HZ = "refresh_rate_hz"
        const val MIN_REFRESH_RATE_HZ = "min_refresh_rate_hz"
        const val PEAK_REFRESH_RATE_HZ = "peak_refresh_rate_hz"
        const val SUPPORTED_MODES = "supported_modes"
        const val HDR_TYPES = "hdr_types"
        const val WIDE_COLOR_GAMUT = "wide_color_gamut"

        val KEYS = listOf(
            WIDTH_PX, HEIGHT_PX, DENSITY_DPI, REFRESH_RATE_HZ, MIN_REFRESH_RATE_HZ,
            PEAK_REFRESH_RATE_HZ, SUPPORTED_MODES, HDR_TYPES, WIDE_COLOR_GAMUT,
        )
    }

    /** hwc.* — presence is observable natively; the version detail is not. */
    object Hwc {
        const val SERVICE_PRESENT = "service_present"
        const val INTERFACE = "interface"
        const val COMPOSER_VERSION = "composer_version"
        const val VSYNC_PERIOD_NS = "vsync_period_ns"

        const val INTERFACE_HIDL = "hidl"
        const val INTERFACE_AIDL = "aidl"
        const val INTERFACE_UNKNOWN = "unknown"

        val KEYS = listOf(SERVICE_PRESENT, INTERFACE, COMPOSER_VERSION, VSYNC_PERIOD_NS)
    }

    /** renderengine.* — what the device is set to, never what it should be set to. */
    object RenderEngine {
        const val BACKEND = "backend"
        const val HWUI_RENDERER = "hwui_renderer"
        const val GPU_COMPOSITION = "gpu_composition"

        val KEYS = listOf(BACKEND, HWUI_RENDERER, GPU_COMPOSITION)
    }

    /** runtime.* — platform and kernel facts that gate what may be attempted. */
    object Runtime {
        const val ANDROID_SDK = "android_sdk"
        const val KERNEL_IS_GKI = "kernel_is_gki"
        const val SOC_MODEL = "soc_model"
        const val SOC_MANUFACTURER = "soc_manufacturer"
        const val ABI = "abi"
        const val PAGE_SIZE = "page_size"

        val KEYS = listOf(ANDROID_SDK, KERNEL_IS_GKI, SOC_MODEL, SOC_MANUFACTURER, ABI, PAGE_SIZE)
    }

    /** The outcome a contributor reports for its own run. */
    object SourceStatus {
        const val OK = "ok"
        const val PARTIAL = "partial"
        const val UNAVAILABLE = "unavailable"
        const val ERROR = "error"

        val ALL = listOf(OK, PARTIAL, UNAVAILABLE, ERROR)
    }

    /** Every domain's key list, for validation and for the drift test. */
    val KEYS_BY_DOMAIN: Map<String, List<String>> = mapOf(
        DOMAIN_VULKAN to Vulkan.KEYS,
        DOMAIN_GPU to Gpu.KEYS,
        DOMAIN_DISPLAY to Display.KEYS,
        DOMAIN_HWC to Hwc.KEYS,
        DOMAIN_RENDERENGINE to RenderEngine.KEYS,
        DOMAIN_RUNTIME to Runtime.KEYS,
    )

    /** True when [domain] and [key] are both part of this schema version. */
    fun isKnown(domain: String, key: String): Boolean = KEYS_BY_DOMAIN[domain]?.contains(key) == true
}
