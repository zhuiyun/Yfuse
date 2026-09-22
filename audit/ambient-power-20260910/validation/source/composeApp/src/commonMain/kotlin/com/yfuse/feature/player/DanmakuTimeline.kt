package com.yfuse.feature.player

import kotlin.math.max

private const val RECOVERY_CATCH_UP_TOLERANCE_MS = 250L
private const val RECOVERY_FORWARD_DISARM_MS = 1_000L
private const val BACKWARD_SEEK_RESET_THRESHOLD_MS = 3_000L
private const val MAX_INTERPOLATED_LEAD_MS = 1_500L

/** State for keeping already-consumed danmaku monotonic across an internal player restart. */
internal data class DanmakuRecoveryFenceState(
    val floorMs: Long? = null,
    val rollbackObserved: Boolean = false,
) {
    val active: Boolean get() = floorMs != null
}

internal data class DanmakuRecoveryFenceUpdate(
    val state: DanmakuRecoveryFenceState,
    val holdAtMs: Long? = null,
    val resumeAtMs: Long? = null,
)

internal fun armDanmakuRecoveryFence(
    renderedPositionMs: Long,
    reportedPositionMs: Long,
): DanmakuRecoveryFenceState =
    DanmakuRecoveryFenceState(
        floorMs = max(renderedPositionMs, reportedPositionMs),
    )

/**
 * Advances the recovery fence without ever asking the overlay to move backwards.
 *
 * The runtime fault signal is emitted before retry() can reopen the backend. Therefore the first
 * sample after arming is commonly still the old position and must not disarm the fence. A real
 * rollback is detected only when the backend later reports behind the armed floor. From that point
 * the floor is locked to the highest danmaku position reached until media catches up again.
 */
internal fun updateDanmakuRecoveryFence(
    state: DanmakuRecoveryFenceState,
    reportedPositionMs: Long,
    renderedPositionMs: Long,
): DanmakuRecoveryFenceUpdate {
    val floor = state.floorMs ?: return DanmakuRecoveryFenceUpdate(state)
    return when {
        reportedPositionMs + RECOVERY_CATCH_UP_TOLERANCE_MS < floor -> {
            val highWater = max(floor, renderedPositionMs)
            DanmakuRecoveryFenceUpdate(
                state =
                    DanmakuRecoveryFenceState(
                        floorMs = highWater,
                        rollbackObserved = true,
                    ),
                holdAtMs = highWater,
            )
        }

        state.rollbackObserved ->
            DanmakuRecoveryFenceUpdate(
                state = DanmakuRecoveryFenceState(),
                resumeAtMs = max(renderedPositionMs, reportedPositionMs),
            )

        reportedPositionMs > floor + RECOVERY_FORWARD_DISARM_MS ->
            DanmakuRecoveryFenceUpdate(DanmakuRecoveryFenceState())

        else -> DanmakuRecoveryFenceUpdate(state)
    }
}

/** A backward reset is a seek only when the backend's own reported clock actually moves back. */
internal fun isDanmakuBackwardSeek(
    previousReportedPositionMs: Long,
    reportedPositionMs: Long,
): Boolean = reportedPositionMs + BACKWARD_SEEK_RESET_THRESHOLD_MS < previousReportedPositionMs

/**
 * Smooths coarse engine ticks but caps how far danmaku may run ahead of a stalled media clock.
 * The max() is deliberate: when the clock stalls, the overlay freezes at its current high-water
 * mark instead of snapping backwards to the last reported engine position.
 */
internal fun advanceDanmakuInterpolatedPosition(
    renderedPositionMs: Long,
    reportedPositionMs: Long,
    elapsedMs: Long,
    playbackRate: Float,
): Long {
    if (elapsedMs <= 0L || !playbackRate.isFinite() || playbackRate <= 0f) return renderedPositionMs
    val advanced = renderedPositionMs + (elapsedMs * playbackRate).toLong()
    val leadCeiling = reportedPositionMs + MAX_INTERPOLATED_LEAD_MS
    return max(renderedPositionMs, minOf(advanced, leadCeiling))
}
