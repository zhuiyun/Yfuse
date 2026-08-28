package com.yfuse.core2.android

import android.os.Build
import com.yfuse.core2.render.MIN_ANDROID_HARDWARE_BUFFER_API
import com.yfuse.core2.render.NATIVE_GPU_API_VERSION
import com.yfuse.core2.render.YNativeGpuRuntimeProbe

/**
 * Fail-closed bridge for the native Vulkan/AHardwareBuffer probe.
 *
 * Loading or probing never makes Vulkan a playback route. The common gate additionally requires a
 * real decoded frame, swapchain presentation, and measured output evidence.
 */
internal object AndroidYCoreGpuRuntime {
    fun probe(): YNativeGpuRuntimeProbe {
        if (Build.VERSION.SDK_INT < MIN_ANDROID_HARDWARE_BUFFER_API) return unavailableProbe()
        val apiVersion = AndroidYCoreGpuNativeBridge.apiVersionOrZero()
        val features =
            if (apiVersion == NATIVE_GPU_API_VERSION) {
                AndroidYCoreGpuNativeBridge.probeFeaturesOrZero()
            } else {
                0L
            }
        return YNativeGpuRuntimeProbe(
            platformApiLevel = Build.VERSION.SDK_INT,
            nativeApiVersion = apiVersion,
            featureMask = features,
        )
    }

    private fun unavailableProbe() =
        YNativeGpuRuntimeProbe(
            platformApiLevel = Build.VERSION.SDK_INT,
            nativeApiVersion = 0,
            featureMask = 0L,
        )
}

private object AndroidYCoreGpuNativeBridge {
    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary(YCORE_NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    fun apiVersionOrZero(): Int = if (libraryLoaded) runCatching(::nativeGpuApiVersion).getOrDefault(0) else 0

    fun probeFeaturesOrZero(): Long = if (libraryLoaded) runCatching(::nativeProbeGpuFeatures).getOrDefault(0L) else 0L

    private external fun nativeGpuApiVersion(): Int

    private external fun nativeProbeGpuFeatures(): Long
}

private const val YCORE_NATIVE_LIBRARY = "ycore_demux"
