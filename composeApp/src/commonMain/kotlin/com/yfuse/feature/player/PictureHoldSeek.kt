package com.yfuse.feature.player

import kotlinx.coroutines.delay
import kotlin.math.abs

/** Each seeking side occupies 40% of the picture; the centre retains temporary speed boost. */
internal enum class PictureHoldAction { Rewind, SpeedBoost, Forward, None }

internal fun pictureHoldAction(
    x: Float,
    width: Int,
): PictureHoldAction =
    when {
        width <= 0 || !x.isFinite() || x < 0f || x > width -> PictureHoldAction.None
        x < width * 0.4f -> PictureHoldAction.Rewind
        x > width * 0.6f -> PictureHoldAction.Forward
        else -> PictureHoldAction.SpeedBoost
    }

/** Slower than the seek command debounce, so a held finger actually advances the engine. */
internal const val PICTURE_HOLD_SEEK_INTERVAL_MS = 500L

/** Accumulates targets ahead of a slow engine instead of repeatedly seeking from its stale position. */
internal class PictureHoldSeek(
    val direction: Int,
    positionMs: Long,
    durationMs: Long,
) {
    init {
        require(direction == -1 || direction == 1)
        require(durationMs > 0L)
    }

    private val originMs = positionMs.coerceIn(0L, durationMs)
    var targetMs: Long = originMs
        private set

    val movedMs: Long get() = abs(targetMs - originMs)

    /** Null at a boundary or once the timeline disappears: no repeated no-op engine commands. */
    fun advance(
        stepMs: Long,
        durationMs: Long,
    ): Long? {
        if (durationMs <= 0L || stepMs <= 0L) return null
        val current = targetMs.coerceIn(0L, durationMs)
        val available = if (direction > 0) durationMs - current else current
        val moved = stepMs.coerceAtMost(available)
        if (moved == 0L) return null
        targetMs = current + direction * moved
        return targetMs
    }
}

/** The caller's live gate ends a hold on release, locking, replacement, or lost playback control. */
internal suspend fun PictureHoldSeek.deliverSeeks(
    canContinue: () -> Boolean,
    stepMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    while (canContinue()) {
        val target = advance(stepMs(), durationMs()) ?: return
        onSeek(target)
        delay(PICTURE_HOLD_SEEK_INTERVAL_MS)
    }
}
