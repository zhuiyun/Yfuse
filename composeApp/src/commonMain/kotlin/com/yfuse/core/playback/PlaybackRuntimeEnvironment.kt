package com.yfuse.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class PlaybackResourcePressure(
    val label: String,
) {
    Normal("正常"),
    BatteryLow("低电量"),
    SystemPowerSaver("系统省电"),
    Thermal("设备过热"),
}

data class PlaybackRuntimeEnvironment(
    val pressure: PlaybackResourcePressure,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
) {
    val diagnosticLabel: String
        get() =
            buildString {
                append(pressure.label)
                batteryPercent?.let { append(" · 电量 $it%") }
                if (charging) append(" · 充电中")
            }

    companion object {
        fun normal() = PlaybackRuntimeEnvironment(PlaybackResourcePressure.Normal)
    }
}

interface PlaybackRuntimeEnvironmentProvider {
    fun current(): PlaybackRuntimeEnvironment

    fun revisions(): Flow<Long> = emptyFlow()
}

data class ResolvedPlaybackOptimization(
    val mode: PlaybackOptimizationMode,
    val reason: String? = null,
)

/** System safety may lower cost, but never silently turns a compatibility request into Exo-only. */
fun resolvePlaybackOptimization(
    requested: PlaybackOptimizationMode,
    environment: PlaybackRuntimeEnvironment,
): ResolvedPlaybackOptimization =
    when {
        requested == PlaybackOptimizationMode.PowerSaver ->
            ResolvedPlaybackOptimization(requested)
        environment.pressure == PlaybackResourcePressure.Thermal ->
            ResolvedPlaybackOptimization(
                mode = PlaybackOptimizationMode.PowerSaver,
                reason = "设备温度过高，YCore 临时启用省电管线",
            )
        environment.pressure in
            setOf(
                PlaybackResourcePressure.SystemPowerSaver,
                PlaybackResourcePressure.BatteryLow,
            ) &&
            requested != PlaybackOptimizationMode.Compatibility ->
            ResolvedPlaybackOptimization(
                mode = PlaybackOptimizationMode.PowerSaver,
                reason = "${environment.pressure.label}，YCore 临时启用省电管线",
            )
        else -> ResolvedPlaybackOptimization(requested)
    }

internal expect fun createPlaybackRuntimeEnvironmentProvider(): PlaybackRuntimeEnvironmentProvider
