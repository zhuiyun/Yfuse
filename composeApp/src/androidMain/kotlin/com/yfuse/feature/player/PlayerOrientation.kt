package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.yfuse.tv.player.isTelevisionDevice
import kotlin.math.abs

/** Android 16, from which a large screen ignores the orientation an app asks for. */
private const val ANDROID_16 = 36

/** The smallest width from which Android 16 treats a screen as large. */
private const val LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP = 600

// Pure: the sensor readings 开幕's gate waits for.

/**
 * The [android.view.OrientationEventListener] readings at which the phone faces the way the player
 * does: the one that matches [playerRotation] (the window's `Display.rotation`), or — when the
 * player follows the phone into [bothLandscapes] — either landscape, since the window turns with
 * the phone to whichever it is held in.
 */
internal fun orientationGateTargets(
    playerRotation: Int,
    bothLandscapes: Boolean,
): List<Int> =
    if (bothLandscapes && playerRotation.mod(2) == 1) {
        listOf(90, 270)
    } else {
        listOf((360 - 90 * playerRotation).mod(360))
    }

/** Whether the sensor's [orientation] is within [withinDegrees] of any of [targets]. */
internal fun turnedToward(
    orientation: Int,
    targets: List<Int>,
    withinDegrees: Int,
): Boolean = targets.any { target -> abs(orientation - target).let { minOf(it, 360 - it) } <= withinDegrees }

/**
 * 竖屏视频: the orientation a phone's player asks for once the picture's size is known, or null to
 * leave [current] alone.
 *
 * A picture taller than it is wide turns the player upright, following the phone either way up,
 * and a wide or square one turns it back to landscape; a portrait clip letterboxed into landscape
 * used to fill a third of the screen. Only an orientation the player chose itself is changed: 旋转锁
 * (LOCKED) pins whatever the screen shows, and a tablet's FULL_USER already follows the hand.
 */
internal fun phonePlayerOrientation(
    videoWidth: Int,
    videoHeight: Int,
    current: Int,
): Int? {
    if (videoWidth <= 0 || videoHeight <= 0) return null
    if (current != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
        current != ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ) {
        return null
    }
    return if (videoHeight > videoWidth) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

// End of pure.

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** The player turns with the phone into either landscape — the manifest's sensorLandscape — and has not been pinned. */
internal fun Context.playerFollowsBothLandscapes(): Boolean =
    when (findActivity()?.requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
        else -> false
    }

/**
 * 旋转锁: pins the orientation the screen has now — whichever landscape, since the player follows
 * the phone into both — until it is unlocked, when the Activity gets back what it asked for
 * before: sensorLandscape on a phone, FULL_USER on a tablet. Kept for this player session only;
 * it never touches the system's own rotation setting.
 *
 * Null where there is nothing to pin: a television; a window sharing the screen, where the system
 * ignores the request; and a large screen from Android 16, which does the same.
 */
@Composable
internal fun rememberPlayerRotationLock(): PlayerRotationLock? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    var locked by remember { mutableStateOf(false) }
    var released by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    DisposableEffect(activity) {
        onDispose { if (locked) activity?.requestedOrientation = released }
    }
    val lockable =
        activity != null &&
            !isTelevisionDevice(activity) &&
            !activity.isInMultiWindowMode &&
            !(
                Build.VERSION.SDK_INT >= ANDROID_16 &&
                    configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP
            )
    if (!lockable || activity == null) return null
    return remember(activity, locked) {
        PlayerRotationLock(
            locked = locked,
            onToggle = {
                if (locked) {
                    activity.requestedOrientation = released
                    locked = false
                } else {
                    released = activity.requestedOrientation
                    // LOCKED is "the rotation it has now, whatever that is": the half turn included.
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    locked = true
                }
            },
        )
    }
}
