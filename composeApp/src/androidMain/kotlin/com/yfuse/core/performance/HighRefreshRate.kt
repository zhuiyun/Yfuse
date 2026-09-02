package com.yfuse.core.performance

import android.app.Activity
import android.os.Build
import android.view.Display
import com.yfuse.core.logging.AppLog
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
 */
internal fun Activity.preferHighRefreshRateForUi() {
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
