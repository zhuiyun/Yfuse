package com.yfuse.feature.player

import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.logging.AppLog
import kotlin.math.roundToInt

@Composable
internal fun rememberWindowBrightness(): Pair<Float, (Float) -> Unit> {
    val activity = LocalActivity.current
    var level by remember(activity) {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (current in 0f..1f) current else 0.5f)
    }
    return level to { target: Float ->
        val clamped = target.coerceIn(0.02f, 1f)
        level = clamped
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply { screenBrightness = clamped }
        }
    }
}

/** Reads and writes STREAM_MUSIC so the player's level chip reflects real system volume. */
@Composable
internal fun rememberSystemVolume(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val min =
        remember(audio) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
        }
    var level by remember(audio, min, max) {
        mutableFloatStateOf(
            streamVolumeFraction(
                current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                min = min,
                max = max,
            ),
        )
    }
    DisposableEffect(context, audio, min, max) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    level =
                        streamVolumeFraction(
                            current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                            min = min,
                            max = max,
                        )
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer,
        )
        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return level to { target: Float ->
        val requested = streamVolumeForFraction(target, min, max)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
        }.onFailure {
            AppLog.warning(
                category = "player.audio",
                event = "volume_change_failed",
                message = "System media volume could not be changed",
                throwable = it,
                attributes = mapOf("requestedLevel" to requested.toString()),
            )
        }
        level =
            streamVolumeFraction(
                current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                min = min,
                max = max,
            )
    }
}

internal fun streamVolumeFraction(
    current: Int,
    min: Int,
    max: Int,
): Float {
    if (max <= min) return 0f
    return (current.coerceIn(min, max) - min).toFloat() / (max - min)
}

internal fun streamVolumeForFraction(
    fraction: Float,
    min: Int,
    max: Int,
): Int {
    if (max <= min) return min
    return (min + fraction.coerceIn(0f, 1f) * (max - min))
        .roundToInt()
        .coerceIn(min, max)
}
