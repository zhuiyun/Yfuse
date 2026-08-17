package com.yfuse.feature.player

import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PlaybackDiscMenuCommand
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

/**
 * Installs platform input only while a verified HDMV/BD-J menu is active.
 *
 * Ordinary playback keeps the Activity's normal key/back behavior, including predictive-back. A
 * menu runtime gets remote D-pad/enter keys plus system back, and a runtime failure immediately falls
 * through to the Activity instead of trapping the viewer inside a dead menu.
 */
@Composable
internal fun DiscNavigationPlatformInputEffect(menuActive: Boolean) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val interactive = menuActive && ActiveDiscNavigation.status.interactiveMenuReady

    DisposableEffect(activity, lifecycleOwner, interactive) {
        if (activity == null || !interactive) {
            return@DisposableEffect onDispose { }
        }

        val backCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (ActiveDiscNavigation.routeActiveMenuCommand(PlaybackDiscMenuCommand.Back)) return
                    // Provider disappeared between state publication and the back gesture. Do not
                    // strand the user: remove this interception and continue the normal dispatcher.
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
        activity.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)

        val keyListener =
            androidx.core.view.OnUnhandledKeyEventListenerCompat { _, event ->
                if (event.action != KeyEvent.ACTION_DOWN || !ActiveDiscNavigation.menuActive) {
                    false
                } else {
                    discMenuCommandForAndroidKey(event.keyCode)
                        ?.let(ActiveDiscNavigation::routeActiveMenuCommand)
                        ?: false
                }
            }
        val decor = activity.window.decorView
        ViewCompat.addOnUnhandledKeyEventListener(decor, keyListener)

        onDispose {
            backCallback.remove()
            ViewCompat.removeOnUnhandledKeyEventListener(decor, keyListener)
        }
    }
}

internal fun discMenuCommandForAndroidKey(keyCode: Int): PlaybackDiscMenuCommand? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> PlaybackDiscMenuCommand.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> PlaybackDiscMenuCommand.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> PlaybackDiscMenuCommand.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> PlaybackDiscMenuCommand.Right
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> PlaybackDiscMenuCommand.Select
        KeyEvent.KEYCODE_MENU -> PlaybackDiscMenuCommand.ShowMenu
        else -> null
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
