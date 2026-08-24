package com.yfuse.feature.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackResourcePressure
import com.yfuse.core.playback.PlaybackRuntimeEnvironment

internal data class DolbyFelPerformanceFacts(
    val sdkInt: Int,
    val mediaPerformanceClass: Int,
    val lowRamDevice: Boolean,
    val totalMemoryBytes: Long,
    val requiredGlEsVersion: Int,
    val resourcePressure: PlaybackResourcePressure,
)

/** Conservative device gate for decoding two P7 layers plus libplacebo composition at UHD. */
internal fun supportsFullDolbyFelProcessing(facts: DolbyFelPerformanceFacts): Boolean {
    if (facts.sdkInt < 29 || facts.lowRamDevice) return false
    if (facts.resourcePressure != PlaybackResourcePressure.Normal) return false
    val performanceClassDevice = facts.mediaPerformanceClass >= 31
    val legacyHighMemoryDevice =
        facts.totalMemoryBytes >= MIN_FULL_FEL_MEMORY_BYTES &&
            facts.requiredGlEsVersion >= MIN_FULL_FEL_GLES_VERSION
    return performanceClassDevice || legacyHighMemoryDevice
}

internal fun dolbyVisionRuntimeCapabilities(
    context: Context,
    environment: PlaybackRuntimeEnvironment,
    native: MpvNativeBuildCapabilities = installedMpvNativeBuildCapabilities,
): PlaybackDolbyVisionRuntimeCapabilities {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val mediaPerformanceClass =
        if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
    val facts =
        DolbyFelPerformanceFacts(
            sdkInt = Build.VERSION.SDK_INT,
            mediaPerformanceClass = mediaPerformanceClass,
            lowRamDevice = activityManager.isLowRamDevice,
            totalMemoryBytes = memory.totalMem,
            requiredGlEsVersion = activityManager.deviceConfigurationInfo.reqGlEsVersion,
            resourcePressure = environment.pressure,
        )
    val verified = native.pinnedYfuseDolbyVisionArtifact
    return PlaybackDolbyVisionRuntimeCapabilities(
        verifiedMpvRpu = verified && native.dolbyVisionRpu,
        verifiedMpvFel = verified && native.dolbyVisionFel,
        fullFelGpuCapable = verified && supportsFullDolbyFelProcessing(facts),
    )
}

private const val MIN_FULL_FEL_MEMORY_BYTES = 6L * 1024L * 1024L * 1024L
private const val MIN_FULL_FEL_GLES_VERSION = 0x0003_0000
