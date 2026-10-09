package com.yfuse.core.performance

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.yfuse.core.logging.AppLog
import java.util.WeakHashMap
import kotlin.math.abs

private const val HIGH_REFRESH_RATE_THRESHOLD_HZ = 90f
private const val REFRESH_RATE_EPSILON_HZ = 0.1f

/** Small platform-neutral snapshot so the selection rule stays deterministic and testable. */
internal data class UiDisplayMode(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * Prefer the highest refresh-rate mode that keeps the panel at the current native resolution.
 *
 * Resolution changes are deliberately rejected: the UI should gain temporal smoothness without
 * making text/images softer. Devices whose panel tops out below 90 Hz simply keep the system mode.
 */
internal fun selectHighRefreshRateMode(
    currentWidth: Int,
    currentHeight: Int,
    modes: List<UiDisplayMode>,
): UiDisplayMode? =
    modes
        .asSequence()
        .filter { mode ->
            mode.width == currentWidth &&
                mode.height == currentHeight &&
                mode.refreshRate >= HIGH_REFRESH_RATE_THRESHOLD_HZ
        }.maxByOrNull(UiDisplayMode::refreshRate)

/**
 * Gives normal app UI access to 90/120/144 Hz panels without hard-coding one vendor mode id.
 *
 * This is intentionally used by [com.yfuse.MainActivity], not the fullscreen player activity.
 * Video playback owns its own Surface frame-rate matching so 24/25/30/50/60 fps content can still
 * request a cadence-friendly display mode instead of being pinned to the UI preference.
 *
 * From Android 15 the window is not pinned to a mode at all: a pinned mode kept an LTPO panel at
 * its top rate on a still page too. The Compose host instead asks for the high rate while it is
 * drawing frame after frame — an animation, a scroll, a fling — and hands the choice back to the
 * platform once it has been still for a moment (see [MotionFrameRateVoter]).
 */
internal fun Activity.preferHighRefreshRateForUi() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        installMotionFrameRateVoter(window.decorView)
        return
    }
    val targetDisplay: Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

    val currentMode = targetDisplay.mode
    val selected =
        selectHighRefreshRateMode(
            currentWidth = currentMode.physicalWidth,
            currentHeight = currentMode.physicalHeight,
            modes =
                targetDisplay.supportedModes.map { mode ->
                    UiDisplayMode(
                        modeId = mode.modeId,
                        width = mode.physicalWidth,
                        height = mode.physicalHeight,
                        refreshRate = mode.refreshRate,
                    )
                },
        ) ?: return

    val attributes = window.attributes
    if (
        attributes.preferredDisplayModeId == selected.modeId &&
        abs(attributes.preferredRefreshRate - selected.refreshRate) <= REFRESH_RATE_EPSILON_HZ
    ) {
        return
    }

    @Suppress("DEPRECATION")
    run {
        attributes.preferredDisplayModeId = selected.modeId
        attributes.preferredRefreshRate = selected.refreshRate
    }
    window.attributes = attributes

    AppLog.info(
        category = "performance.ui",
        event = "high_refresh_rate_requested",
        message = "Main UI requested the highest refresh-rate mode at the current resolution",
        attributes =
            mapOf(
                "modeId" to selected.modeId.toString(),
                "refreshRate" to selected.refreshRate.toString(),
                "resolution" to "${selected.width}x${selected.height}",
            ),
    )
}

/** One voter per window, however many times the activity resumes. Main thread only. */
private val motionFrameRateVoters = WeakHashMap<View, ViewTreeObserver.OnDrawListener>()

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun installMotionFrameRateVoter(decor: View) {
    if (motionFrameRateVoters.containsKey(decor)) return
    val voter = MotionFrameRateVoter(decor)
    decor.viewTreeObserver.addOnDrawListener(voter)
    motionFrameRateVoters[decor] = voter
    AppLog.info(
        category = "performance.ui",
        event = "frame_rate_category_voting",
        message = "Main UI asks for the high frame-rate category only while it animates or scrolls",
    )
}

/**
 * API 35+: votes [View.REQUESTED_FRAME_RATE_CATEGORY_HIGH] for the Compose host while it draws frame
 * after frame, and gives the rate back to the platform ([View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT])
 * once it has been still for [MOTION_IDLE_MS]. A lone redraw — a caret, a toast arriving — is not
 * motion and changes nothing.
 *
 * A vote counts only while its own view is being invalidated, and the view Compose invalidates is the
 * one inside setContent's ComposeView, so that is where the vote goes, not the window's decor.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private class MotionFrameRateVoter(
    private val decor: View,
) : ViewTreeObserver.OnDrawListener,
    Runnable {
    private var host: View? = null
    private var lastDrawNanos = 0L
    private var drawn = false
    private var high = false

    override fun onDraw() {
        val now = System.nanoTime()
        val continuous = drawn && now - lastDrawNanos <= MOTION_FRAME_GAP_NANOS
        drawn = true
        lastDrawNanos = now
        if (!continuous || high) return
        val view = composeHost() ?: return
        high = true
        view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        decor.postDelayed(this, MOTION_IDLE_MS)
    }

    /** Checks back once the burst may be over, and only then lets the rate go. */
    override fun run() {
        val stillMs = (System.nanoTime() - lastDrawNanos) / NANOS_PER_MILLI
        if (stillMs < MOTION_IDLE_MS) {
            decor.postDelayed(this, MOTION_IDLE_MS - stillMs)
            return
        }
        high = false
        host?.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }

    private fun composeHost(): View? {
        host?.takeIf { it.isAttachedToWindow }?.let { return it }
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val composeView = content.getChildAt(0) ?: return null
        return ((composeView as? ViewGroup)?.getChildAt(0) ?: composeView).also { host = it }
    }
}

/** Two draws closer than this are one motion: a 30 Hz animation still counts. */
private const val MOTION_FRAME_GAP_NANOS = 50_000_000L

/** How long the host is to be still before the high rate is given back. */
private const val MOTION_IDLE_MS = 300L

private const val NANOS_PER_MILLI = 1_000_000L
