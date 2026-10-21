package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.yfuse.core.designsystem.Motion

/** A finite, per-item camera move. It owns neither the decoder nor its playback clock. */
@Stable
internal class CinemaPlaybackEntrance(
    eligible: Boolean,
) {
    private enum class Phase { Push, Hold, Reveal, Finished, Skipped }

    private var phase by mutableStateOf(if (eligible) Phase.Push else Phase.Skipped)
    private var pushMillis by mutableFloatStateOf(0f)
    private var revealMillis by mutableFloatStateOf(0f)
    private var revealScale by mutableFloatStateOf(1f)

    val needsFrames: Boolean get() = phase == Phase.Push || phase == Phase.Reveal
    val choreographed: Boolean get() = phase != Phase.Skipped
    val revealing: Boolean get() = phase == Phase.Reveal || phase == Phase.Finished
    val revealProgress: Float get() = Motion.Curve.transform(revealMillis / REVEAL_MS)

    val scale: Float
        get() =
            when (phase) {
                Phase.Push, Phase.Hold -> 1f + PUSH_DISTANCE * Motion.Curve.transform(pushMillis / PUSH_MS)
                Phase.Reveal, Phase.Finished -> revealScale + (FINAL_SCALE - revealScale) * revealProgress
                Phase.Skipped -> 1f
            }

    val artworkDim: Float
        get() = if (choreographed) 0.34f - (scale - 1f) * 0.65f else 0.34f

    /**
     * Only verified video readiness may open the cover. Error, audio-only, PiP and accessibility
     * changes cancel it. A retry after readiness uses the ordinary cover, without replaying entry.
     */
    fun update(
        awaitingPicture: Boolean,
        pictureReady: Boolean,
        allowed: Boolean,
    ) {
        if (phase == Phase.Skipped) return
        when {
            !allowed -> skip()
            awaitingPicture && revealing -> skip()
            !awaitingPicture && !pictureReady -> skip()
            !awaitingPicture && (phase == Phase.Push || phase == Phase.Hold) -> {
                revealScale = scale
                phase = Phase.Reveal
            }
        }
    }

    /** No synthetic 60 Hz clock: the caller advances this only by elapsed display-frame time. */
    fun advance(elapsedMillis: Float) {
        if (!elapsedMillis.isFinite() || elapsedMillis <= 0f) return
        when (phase) {
            Phase.Push -> {
                pushMillis = (pushMillis + elapsedMillis).coerceAtMost(PUSH_MS.toFloat())
                if (pushMillis >= PUSH_MS) phase = Phase.Hold
            }
            Phase.Reveal -> {
                revealMillis = (revealMillis + elapsedMillis).coerceAtMost(REVEAL_MS.toFloat())
                if (revealMillis >= REVEAL_MS) phase = Phase.Finished
            }
            else -> Unit
        }
    }

    private fun skip() {
        phase = Phase.Skipped
        pushMillis = 0f
        revealMillis = 0f
    }

    companion object {
        const val PUSH_MS = 720
        const val REVEAL_MS = 360
        private const val PUSH_DISTANCE = 0.14f
        private const val FINAL_SCALE = 1.24f
    }
}

/** The live frame opens sideways through the artwork; nothing resizes the video Surface. */
internal fun cinemaPlaybackAperture(
    size: Size,
    progress: Float,
): Rect {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return Rect.Zero
    }
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val width = size.width * fraction
    val height = size.height * (fraction * 1.45f).coerceAtMost(1f)
    return Rect(
        left = (size.width - width) / 2f,
        top = (size.height - height) / 2f,
        right = (size.width + width) / 2f,
        bottom = (size.height + height) / 2f,
    )
}
