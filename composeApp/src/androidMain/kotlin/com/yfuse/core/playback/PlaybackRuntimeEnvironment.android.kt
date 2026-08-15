package com.yfuse.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun createPlaybackRuntimeEnvironmentProvider(): PlaybackRuntimeEnvironmentProvider {
    val context = androidAppContext
    return if (context == null) {
        object : PlaybackRuntimeEnvironmentProvider {
            override fun current(): PlaybackRuntimeEnvironment = PlaybackRuntimeEnvironment.normal()
        }
    } else {
        AndroidPlaybackRuntimeEnvironmentProvider(context.applicationContext)
    }
}

private class AndroidPlaybackRuntimeEnvironmentProvider(
    private val context: Context,
) : PlaybackRuntimeEnvironmentProvider {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val revision = MutableStateFlow(0L)
    private var thermalStatus =
        if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else THERMAL_STATUS_NONE
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                revision.value = revision.value + 1L
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager.addThermalStatusListener { status ->
                if (thermalStatus != status) {
                    thermalStatus = status
                    revision.value = revision.value + 1L
                }
            }
        }
    }

    override fun current(): PlaybackRuntimeEnvironment {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent =
            if (level >= 0 && scale > 0) {
                (level * 100 / scale).coerceIn(0, 100)
            } else {
                null
            }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0
        val pressure =
            when {
                Build.VERSION.SDK_INT >= 29 && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                    PlaybackResourcePressure.Thermal
                powerManager.isPowerSaveMode -> PlaybackResourcePressure.SystemPowerSaver
                percent != null && percent <= LOW_BATTERY_PERCENT && !charging ->
                    PlaybackResourcePressure.BatteryLow
                else -> PlaybackResourcePressure.Normal
            }
        return PlaybackRuntimeEnvironment(
            pressure = pressure,
            batteryPercent = percent,
            charging = charging,
        )
    }

    override fun revisions(): Flow<Long> = revision.asStateFlow()
}

private const val THERMAL_STATUS_NONE = 0
private const val LOW_BATTERY_PERCENT = 15
