package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.yfuse.tv.player.isTelevisionDevice
import kotlinx.coroutines.delay

/**
 * Where a half-open fold crosses the window, top to bottom in window pixels, while the phone
 * stands on a table (a horizontal hinge, half open). Null on every other device and posture,
 * and until the window has reported its layout.
 *
 * A flip phone standing half-open has its hinge across a portrait screen, which the player's
 * landscape turns into a line down the middle of the picture; while it stands, the window is let
 * follow the phone so that hinge can lie across it again. See [FlipStandOrientation].
 */
@Composable
internal fun rememberTabletopHinge(
    /** 旋转锁 is on: the viewer has pinned the orientation, and a phone standing up leaves it pinned. */
    rotationLocked: Boolean,
    inPictureInPicture: Boolean,
): IntRange? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val folds by produceState(initialValue = HalfOpenFolds(), activity) {
        val target = activity ?: return@produceState
        WindowInfoTracker
            .getOrCreate(target)
            .windowLayoutInfo(target)
            .collect { layout ->
                val halfOpen =
                    layout.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .filter { it.state == FoldingFeature.State.HALF_OPENED }
                value =
                    HalfOpenFolds(
                        across =
                            halfOpen
                                .firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                                ?.bounds
                                ?.let { it.top..it.bottom },
                        down = halfOpen.any { it.orientation == FoldingFeature.Orientation.VERTICAL },
                    )
            }
    }
    val compact = LocalConfiguration.current.smallestScreenWidthDp < FLIP_STAND_MAX_SMALLEST_WIDTH_DP
    val flipStand = remember(activity) { FlipStandOrientation() }
    val halfOpen = folds.across != null || folds.down
    LaunchedEffect(activity, halfOpen, folds.down, compact, rotationLocked, inPictureInPicture) {
        val target = activity ?: return@LaunchedEffect
        // Opening flat or folding shut passes through half open; only a posture that holds is one.
        delay(FLIP_STAND_SETTLE_MS)
        // The system ignores the request in 画中画 and in a window sharing the screen: nothing is
        // borrowed or given back there, and the window picks up where it was once it is whole again.
        if (inPictureInPicture || target.isInMultiWindowMode) return@LaunchedEffect
        flipStand
            .onPosture(
                requested = target.requestedOrientation,
                halfOpen = halfOpen,
                flipHinge = folds.down,
                mayBorrow = compact && !rotationLocked && !isTelevisionDevice(target),
            )?.let { target.requestedOrientation = it }
    }
    DisposableEffect(activity, flipStand) {
        onDispose {
            activity?.let { target ->
                flipStand.onLeave(target.requestedOrientation)?.let { target.requestedOrientation = it }
            }
        }
    }
    return folds.across
}

/** The window's half-open folds: where one crosses it, and whether one runs down it. */
private data class HalfOpenFolds(
    /** Top to bottom in window pixels: the tabletop layout's hinge. */
    val across: IntRange? = null,
    /** A fold from the top of the window to the bottom, as a flip phone's is while the player holds it in landscape. */
    val down: Boolean = false,
)

/**
 * 翻盖折叠屏: the orientation a flip phone standing half-open borrows, and the one it gives back.
 *
 * The player holds a phone in landscape (the manifest's sensorLandscape), and a flip phone's hinge
 * then runs down the middle of the picture. While it stands half-open the window follows the phone
 * instead — FULL_USER, which still honours the system rotation lock — so it can come upright with
 * the hinge across it and the tabletop layout takes over; once the phone opens flat or folds shut,
 * the landscape it had comes back.
 *
 * Borrowed only from that landscape, on a compact screen with 旋转锁 off: a tablet — a book-style
 * foldable's inner screen among them — already follows the device, and a television has no fold.
 * Given back only while FULL_USER is still what is asked for, so 旋转锁 pinning the upright window
 * meanwhile keeps it pinned until it is let go.
 *
 * Every answer is the orientation to request, or null to leave the window's as it is.
 */
internal class FlipStandOrientation {
    /** What the player asked for before it borrowed FULL_USER; null while nothing is borrowed. */
    private var returnTo: Int? = null

    fun onPosture(
        requested: Int,
        halfOpen: Boolean,
        /** A half-open fold runs down the landscape window: the phone's hinge is across its narrow side. */
        flipHinge: Boolean,
        /** A compact screen that is not a television's, with 旋转锁 off. */
        mayBorrow: Boolean,
    ): Int? {
        val previous = returnTo
        if (previous == null) {
            if (!mayBorrow || !flipHinge || requested !in FLIP_STAND_FROM) return null
            returnTo = requested
            return ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        // Still standing, or 旋转锁 holds the window now: nothing to give back yet.
        if (halfOpen || requested != ActivityInfo.SCREEN_ORIENTATION_FULL_USER) return null
        returnTo = null
        return previous
    }

    /** The player is going: what to put back, if FULL_USER is still what is asked for. */
    fun onLeave(requested: Int): Int? {
        val previous = returnTo ?: return null
        returnTo = null
        return previous.takeIf { requested == ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
    }
}

/** The phone's landscapes the player holds, the manifest's and the one 旋转锁 can hand back. */
private val FLIP_STAND_FROM =
    setOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)

/** From this smallest width the player already follows the device (see PlayerActivity), so nothing is borrowed. */
private const val FLIP_STAND_MAX_SMALLEST_WIDTH_DP = 600

/** How long a half-open posture has to hold before the orientation follows it either way. */
private const val FLIP_STAND_SETTLE_MS = 500L

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
