package com.yfuse.core2.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaFormat
import android.media.Spatializer
import android.os.Build
import androidx.annotation.RequiresApi

/** Format-specific system spatializer evidence for the active PCM sink. */
internal data class AndroidSpatialAudioState(
    val active: Boolean = false,
    val headTrackerAvailable: Boolean = false,
)

internal class AndroidSpatialAudioProbe(
    context: Context,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    fun current(format: MediaFormat): AndroidSpatialAudioState {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val encoding =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        val channelMask =
            if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
            } else {
                channelMaskForCount(channelCount)
            }
        return current(sampleRate, channelMask, encoding)
    }

    fun current(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
    ): AndroidSpatialAudioState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return AndroidSpatialAudioState()
        if (sampleRate <= 0 || channelMask == 0) return AndroidSpatialAudioState()
        val manager = audioManager ?: return AndroidSpatialAudioState()
        return manager.querySpatialAudioStateApi32(sampleRate, channelMask, encoding)
    }
}

internal interface AndroidSpatialAudioStateMonitor {
    fun release()
}

internal fun createAndroidSpatialAudioStateMonitor(
    context: Context,
    onChanged: () -> Unit,
): AndroidSpatialAudioStateMonitor? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return null
    val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return null
    return AndroidSpatialAudioStateMonitorApi32(context, manager, onChanged)
}

@RequiresApi(Build.VERSION_CODES.S_V2)
private class AndroidSpatialAudioStateMonitorApi32(
    context: Context,
    audioManager: AudioManager,
    private val onChanged: () -> Unit,
) : AndroidSpatialAudioStateMonitor {
    private val output = audioManager.spatializer
    private val headTrackerMonitor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AndroidHeadTrackerStateMonitorApi33(context, output, onChanged)
        } else {
            null
        }
    private val listener =
        object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerAvailableChanged(
                spatializer: Spatializer,
                available: Boolean,
            ) = onChanged()

            override fun onSpatializerEnabledChanged(
                spatializer: Spatializer,
                enabled: Boolean,
            ) = onChanged()
        }

    init {
        output.addOnSpatializerStateChangedListener(context.mainExecutor, listener)
    }

    override fun release() {
        output.removeOnSpatializerStateChangedListener(listener)
        headTrackerMonitor?.release()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidHeadTrackerStateMonitorApi33(
    context: Context,
    private val output: Spatializer,
    private val onChanged: () -> Unit,
) : AndroidSpatialAudioStateMonitor {
    private val listener =
        object : Spatializer.OnHeadTrackerAvailableListener {
            override fun onHeadTrackerAvailableChanged(
                spatializer: Spatializer,
                available: Boolean,
            ) = onChanged()
        }

    init {
        output.addOnHeadTrackerAvailableListener(context.mainExecutor, listener)
    }

    override fun release() {
        output.removeOnHeadTrackerAvailableListener(listener)
    }
}

@RequiresApi(Build.VERSION_CODES.S_V2)
private fun AudioManager.querySpatialAudioStateApi32(
    sampleRate: Int,
    channelMask: Int,
    encoding: Int,
): AndroidSpatialAudioState {
    val output = spatializer
    if (
        output.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL ||
        !output.isAvailable ||
        !output.isEnabled
    ) {
        return AndroidSpatialAudioState()
    }
    val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    val audioFormat =
        AudioFormat
            .Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
    val active = runCatching { output.canBeSpatialized(attributes, audioFormat) }.getOrDefault(false)
    return AndroidSpatialAudioState(
        active = active,
        headTrackerAvailable =
            active &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                output.isHeadTrackerAvailable,
    )
}
