package com.yfuse.feature.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import kotlin.math.roundToInt

/**
 * The level is handed back as a [State] rather than a Float on purpose.
 *
 * A vertical drag writes it on every pointer sample. Read at the top of the player's runtime
 * scope that invalidated ~2800 lines per sample; as a [State] the read happens where the level
 * is actually drawn — the slider, the gesture HUD — and the rest of the tree stays put.
 */
@Composable
internal fun rememberWindowBrightness(
    /**
     * True while the player's window sits over something else, as in picture-in-picture: its
     * override would hold the whole screen at the player's level there, so it is lifted meanwhile.
     */
    followSystem: Boolean = false,
): Pair<State<Float>, (Float) -> Unit> {
    val activity = LocalActivity.current
    val level =
        remember(activity) {
            val current = activity?.window?.attributes?.screenBrightness ?: -1f
            // -1 is the window's default, "follow the system". A drag used to start from a made-up
            // midpoint instead, so the first nudge on a phone dimmed to 10% for the night threw it
            // to half brightness. The system's own level is where the finger actually is.
            mutableFloatStateOf(if (current in 0f..1f) current else systemBrightnessFraction(activity))
        }
    // Whether the window carries a level of its own, set by the viewer. Until then it follows the system.
    val adjusted =
        remember(activity) {
            mutableStateOf((activity?.window?.attributes?.screenBrightness ?: -1f) in 0f..1f)
        }
    DisposableEffect(activity) {
        // Auto-brightness or the notification shade may move the system level while the player is
        // open; the next first drag starts from where it is then, not from where it was.
        val resolver = activity?.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    if (!adjusted.value) level.floatValue = systemBrightnessFraction(activity)
                }
            }
        runCatching {
            resolver?.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                observer,
            )
        }
        // The override belongs to this player. The window goes back to following the system as the
        // player's composition ends rather than whenever the window itself happens to be torn down.
        onDispose {
            runCatching { resolver?.unregisterContentObserver(observer) }
            activity?.setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }
    LaunchedEffect(activity, followSystem) {
        if (!adjusted.value) return@LaunchedEffect
        activity?.setWindowBrightness(
            if (followSystem) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level.floatValue,
        )
    }
    return level to { target: Float ->
        val clamped = target.coerceIn(MIN_WINDOW_BRIGHTNESS, 1f)
        level.floatValue = clamped
        adjusted.value = true
        if (!followSystem) {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = clamped }
            }
        }
    }
}

private fun Activity.setWindowBrightness(value: Float) {
    runCatching { window.attributes = window.attributes.apply { screenBrightness = value } }
}

/** The system's SCREEN_BRIGHTNESS as a window level, or the old midpoint when it cannot be read. */
private fun systemBrightnessFraction(context: Context?): Float {
    val resolver = context?.contentResolver ?: return windowBrightnessForSystemSetting(null)
    val setting = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
    return windowBrightnessForSystemSetting(setting, systemBrightnessMaximum())
}

/**
 * The top of SCREEN_BRIGHTNESS on this device: 255 on AOSP, but 1023, 2047 or 4095 on several
 * vendors' builds, which only the framework's own configuration says.
 */
@SuppressLint("DiscouragedApi")
private fun systemBrightnessMaximum(): Int =
    runCatching {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) resources.getInteger(id) else null
    }.getOrNull()?.takeIf { it > 0 } ?: SYSTEM_BRIGHTNESS_MAX

/**
 * SCREEN_BRIGHTNESS over its [maximum] as a window level, floored where a brightness drag stops.
 * Null, or a reading past the maximum — a vendor scale the configuration did not report — keeps
 * the midpoint the player always used: clamped, 30% on a 0..2047 scale became full brightness.
 */
internal fun windowBrightnessForSystemSetting(
    setting: Int?,
    maximum: Int = SYSTEM_BRIGHTNESS_MAX,
): Float =
    if (setting == null || maximum <= 0 || setting > maximum) {
        0.5f
    } else {
        (setting.toFloat() / maximum).coerceIn(MIN_WINDOW_BRIGHTNESS, 1f)
    }

private const val SYSTEM_BRIGHTNESS_MAX = 255

/** Where a brightness drag stops: a fully black backlight reads as the screen switching off. */
private const val MIN_WINDOW_BRIGHTNESS = 0.02f

/** Reads and writes STREAM_MUSIC so the player's level chip reflects real system volume. */
@Composable
internal fun rememberSystemVolume(): Pair<State<Float>, (Float) -> Unit> {
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
    val level =
        remember(audio, min, max) {
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
                    level.floatValue =
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
        level.floatValue =
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
    val activity = LocalActivity.current as? ComponentActivity
    val interactive = menuActive && ActiveDiscNavigation.status.interactiveMenuReady

    DisposableEffect(activity, interactive) {
        if (activity == null || !interactive) {
            return@DisposableEffect onDispose { }
        }

        val backCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (ActiveDiscNavigation.routeActiveMenuCommand(PlaybackDiscMenuCommand.Back)) return
                    // Provider disappeared between state publication and the back gesture. Finish
                    // directly: re-entering the dispatcher from its callback breaks predictive back.
                    isEnabled = false
                    activity.finish()
                }
            }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        val keyListener =
            ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
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
