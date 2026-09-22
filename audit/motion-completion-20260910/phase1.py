from edit import read,write,replace
base='composeApp/src/commonMain/kotlin/com/yfuse/'
ds=base+'core/designsystem/'
pl=base+'feature/player/'
write(ds+'ArtworkBlurCache.kt','''package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import kotlin.math.roundToInt

/** Shared by artwork layers on the UI thread; no bitmaps or per-image histories are retained. */
internal class ArtworkBlurCache(private val capacity: Int = 64) {
    private val entries = linkedMapOf<Int, RenderEffect>()
    init { require(capacity > 0) }

    fun effect(radiusPx: Float): RenderEffect? {
        val step = artworkBlurStep(radiusPx)
        if (step == 0) return null
        val existing = entries.remove(step)
        val effect = existing ?: BlurEffect(step * ARTWORK_BLUR_STEP_PX, step * ARTWORK_BLUR_STEP_PX)
        entries[step] = effect
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return effect
    }

    internal val size: Int get() = entries.size
}

/** Half a physical pixel per step: maximum rounding error is a quarter pixel. */
internal const val ARTWORK_BLUR_STEP_PX = 0.5f
internal fun artworkBlurStep(radiusPx: Float): Int =
    if (!radiusPx.isFinite() || radiusPx <= 0f) 0 else (radiusPx / ARTWORK_BLUR_STEP_PX).roundToInt()

internal val artworkBlurCache = ArtworkBlurCache()
''')
p=ds+'Poster.kt';s=read(p).replace('import androidx.compose.ui.graphics.BlurEffect\n','')
s=s.replace('BlurEffect(radius, radius)','artworkBlurCache.effect(radius)')
s=s.replace('private const val ARTWORK_REVEAL_DURATION_MS = 400','private const val ARTWORK_REVEAL_DURATION_MS = Motion.ARTWORK_REVEAL')
s=s.replace('internal const val POSTER_FADE_DURATION_MS = 180','internal const val POSTER_FADE_DURATION_MS = Motion.POSTER_FADE')
write(p,s)
write(pl+'NextUpRingState.kt','''package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

internal class NextUpRingState(remainingMs: Long) {
    private val remaining = Animatable(remainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS).toFloat())
    val value: State<Float> = remaining.asState()

    suspend fun retarget(remainingMs: Long, advancing: Boolean, speed: Float) {
        val current = remainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS).toFloat()
        remaining.snapTo(current)
        if (advancing) {
            // Never run a free-running countdown: one bounded prediction per engine sample.
            remaining.animateTo(
                nextUpPredictedRemaining(current, speed),
                tween(Motion.NEXT_UP_INTERPOLATION, easing = LinearEasing),
            )
        }
    }
}

internal fun nextUpPredictedRemaining(remainingMs: Float, speed: Float): Float =
    (remainingMs - Motion.NEXT_UP_INTERPOLATION * speed.takeIf { it.isFinite() && it > 0f }.orDefaultSpeed())
        .coerceAtLeast(0f)

private fun Float?.orDefaultSpeed(): Float = this ?: 1f

@Composable
internal fun rememberNextUpRemaining(key: Any, remainingMs: Long, advancing: Boolean, speed: Float): State<Float> {
    val motion = remember(key) { NextUpRingState(remainingMs) }
    val animate = advancing && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    LaunchedEffect(motion, remainingMs, animate, speed) { motion.retarget(remainingMs, animate, speed) }
    return motion.value
}
''')
p=pl+'PlayerNextUp.kt';s=read(p)
s=s.replace('    remainingMs: Long,','    remainingMs: Long,\n    playbackKey: Any,\n    advancing: Boolean,\n    speed: Float,')
s=s.replace('    val progress = (remainingMs.toFloat() / NEXT_UP_WINDOW_MS).coerceIn(0f, 1f)', '    val remaining = rememberNextUpRemaining(playbackKey, remainingMs, advancing, speed)')
s=s.replace('            Canvas(Modifier.fillMaxSize()) {','            Canvas(Modifier.fillMaxSize()) {\n                val progress = (remaining.value / NEXT_UP_WINDOW_MS).coerceIn(0f, 1f)')
write(p,s)
p=pl+'PlayerControls.kt';s=read(p)
s=s.replace('                remainingMs = nextUpRemainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS),','''                remainingMs = nextUpRemainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS),
                playbackKey = state.currentIndex to episodes.getOrNull(state.currentIndex),
                advancing = showNextUp && state.playing && !state.buffering && !state.ended && state.error == null,
                speed = state.speed,''')
write(p,s)
p=ds+'Tokens.kt';s=read(p).replace('    const val TAB_SWEEP = 520','''    const val ARTWORK_REVEAL = 400
    const val POSTER_FADE = 180
    const val NEXT_UP_INTERPOLATION = 500
    const val TAB_SWEEP = 520''');write(p,s)
