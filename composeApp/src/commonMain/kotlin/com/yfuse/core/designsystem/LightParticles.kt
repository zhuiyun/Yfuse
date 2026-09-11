package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The mode is persisted independently of the existing search/navigation motion setting. */
enum class ParticleLight(
    val label: String,
) {
    Off("关闭"),
    Gentle("轻柔"),
    Enhanced("增强"),
}

internal enum class LightEffect {
    Trail,
    Converge,
    Edge,
    Dissolve,
    Node,
    Dust,
}

internal val LocalParticleLight = staticCompositionLocalOf { ParticleLight.Gentle }
internal val LocalParticleLimit = staticCompositionLocalOf { 64 }
internal val LocalParticleActive = staticCompositionLocalOf { true }
internal val LocalParticleBudget = staticCompositionLocalOf { LightParticleBudget() }

/** Shared by the window, including its dialog subtree. No particle can bypass this cap. */
internal class LightParticleBudget {
    var active: Int = 0
        private set

    fun acquire(limit: Int): Boolean {
        if (active >= limit) return false
        active++
        return true
    }

    fun release() {
        check(active > 0)
        active--
    }
}

/** Fixed storage allocated on first use, not one object or composable per particle/frame. */
internal class LightParticlePool(
    private val budget: LightParticleBudget,
    private val limit: Int,
    val capacity: Int = 24,
) {
    private val age = FloatArray(capacity) { -1f }
    private val lifetime = FloatArray(capacity)
    private val startX = FloatArray(capacity)
    private val startY = FloatArray(capacity)
    private val endX = FloatArray(capacity)
    private val endY = FloatArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val alpha = FloatArray(capacity)
    val radius = FloatArray(capacity)
    var active: Int = 0
        private set
    private var elapsed = 0f
    private var lastEmission = -1f
    private var seed = 0

    fun emit(
        effect: LightEffect,
        anchorX: Float,
        anchorY: Float,
        width: Float,
        height: Float,
        density: Float,
        enhanced: Boolean,
        directionX: Float = 0f,
        directionY: Float = 0f,
    ): Boolean {
        if (width <= 0f ||
            height <= 0f ||
            !width.isFinite() ||
            !height.isFinite() ||
            !anchorX.isFinite() ||
            !anchorY.isFinite() ||
            !density.isFinite() ||
            density <= 0f
        ) {
            return false
        }
        // Continuous input is coalesced. A held endpoint cannot build an unbounded queue.
        if (active > 0 && elapsed - lastEmission < 0.048f) return false
        val count = if (enhanced) 12 else 6
        val distance =
            when (effect) {
                LightEffect.Node -> 6f
                LightEffect.Trail -> 10f
                LightEffect.Edge -> 5f
                LightEffect.Dissolve -> 18f
                LightEffect.Dust -> 9f
                LightEffect.Converge -> 13f
            } * density
        val ax = anchorX.coerceIn(0f, width)
        val ay = anchorY.coerceIn(0f, height)
        var emitted = 0
        for (i in 0 until capacity) {
            if (emitted >= count) break
            if (age[i] >= 0f) continue
            if (!budget.acquire(limit)) break
            val angle = ((emitted + seed % 7 * 0.13f) / count * 2f * PI).toFloat()
            val spread = distance * (0.55f + ((seed + emitted * 7) % 9) / 20f)
            val dx = cos(angle) * spread
            val dy = sin(angle) * spread
            val edge = effect == LightEffect.Edge || effect == LightEffect.Dust
            val ox = if (edge) width * (emitted + 0.5f) / count else ax
            val oy =
                if (edge) {
                    if (emitted % 2 == 0) {
                        density
                    } else {
                        height - density
                    }
                } else {
                    ay
                }
            if (effect == LightEffect.Converge) {
                startX[i] = ax + dx
                startY[i] = ay + dy
                endX[i] = ax
                endY[i] = ay
            } else {
                startX[i] = ox
                startY[i] = oy
                endX[i] = ox + dx - directionX.coerceIn(-1f, 1f) * distance
                endY[i] = oy + dy - directionY.coerceIn(-1f, 1f) * distance +
                    if (effect == LightEffect.Dissolve) 8f * density else 0f
            }
            age[i] = 0f
            lifetime[i] = (if (effect == LightEffect.Dust) 0.32f else 0.24f) + (emitted % 4) * 0.04f
            radius[i] = (0.65f + emitted % 3 * 0.22f) * density
            x[i] = startX[i]
            y[i] = startY[i]
            alpha[i] = 0.15f
            active++
            emitted++
        }
        if (emitted > 0) {
            lastEmission = elapsed
            seed = (seed + 1) % 997
        }
        return emitted > 0
    }

    fun advance(seconds: Float) {
        val delta = if (seconds.isFinite()) seconds.coerceAtLeast(0f) else 1f
        elapsed += delta
        for (i in 0 until capacity) {
            if (age[i] < 0f) continue
            age[i] += delta
            val t = age[i] / lifetime[i]
            if (t >= 1f) {
                age[i] = -1f
                alpha[i] = 0f
                active--
                budget.release()
                continue
            }
            val eased = 1f - (1f - t) * (1f - t)
            x[i] = startX[i] + (endX[i] - startX[i]) * eased
            y[i] = startY[i] + (endY[i] - startY[i]) * eased
            alpha[i] = sin(t * PI.toFloat()).coerceAtLeast(0f) * (1f - t) * 0.8f
        }
    }

    fun clear() {
        for (i in 0 until capacity) {
            if (age[i] >= 0f) budget.release()
            age[i] = -1f
            alpha[i] = 0f
        }
        active = 0
        elapsed = 0f
        lastEmission = -1f
    }
}

internal class LightBounds {
    var width = 0f
    var height = 0f
}

internal class LightFeedbackState(
    private val bounds: LightBounds,
    private val budget: LightParticleBudget,
    private val limit: Int,
    private val density: Float,
    private val enhanced: Boolean,
    val enabled: Boolean,
) {
    private var pool: LightParticlePool? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val revision = mutableIntStateOf(0)
    private var disposed = false

    fun resize(
        width: Int,
        height: Int,
    ) {
        if (bounds.width != width.toFloat() || bounds.height != height.toFloat()) clear()
        bounds.width = width.toFloat()
        bounds.height = height.toFloat()
    }

    fun emit(
        effect: LightEffect = LightEffect.Node,
        at: Offset = Offset.Unspecified,
        fractionX: Float = 0.5f,
        fractionY: Float = 0.5f,
        directionX: Float = 0f,
        directionY: Float = 0f,
    ) {
        val width = bounds.width
        val height = bounds.height
        if (!enabled || disposed || width <= 0f || height <= 0f) return
        val particles = pool ?: LightParticlePool(budget, limit).also { pool = it }
        val px = if (at.isSpecified) at.x else width * fractionX.coerceIn(0f, 1f)
        val py = if (at.isSpecified) at.y else height * fractionY.coerceIn(0f, 1f)
        if (particles.emit(effect, px, py, width, height, density, enhanced, directionX, directionY)) {
            revision.intValue++
            wake.trySend(Unit)
        }
    }

    suspend fun run() {
        try {
            for (ignored in wake) {
                val particles = pool ?: continue
                var previous = withFrameNanos { it }
                while (particles.active > 0) {
                    withFrameNanos { now ->
                        particles.advance(((now - previous).coerceAtLeast(0L) / 1_000_000_000.0).toFloat())
                        previous = now
                        revision.intValue++
                    }
                    // Requests only wake an idle loop; they never create parallel animation jobs.
                    while (wake.tryReceive().isSuccess) Unit
                }
            }
        } finally {
            clear()
        }
    }

    fun draw(
        scope: DrawScope,
        color: Color,
    ) {
        revision.intValue // Read only in drawing: particles never recompose the containing page.
        val particles = pool ?: return
        with(scope) {
            for (i in 0 until particles.capacity) {
                val a = particles.alpha[i]
                if (a <= 0f) continue
                val center = Offset(particles.x[i], particles.y[i])
                drawCircle(color.copy(alpha = a * 0.14f), particles.radius[i] * 2.5f, center)
                drawCircle(color.copy(alpha = a), particles.radius[i], center)
            }
        }
    }

    fun clear() {
        pool?.clear()
        while (wake.tryReceive().isSuccess) Unit
        revision.intValue++
    }

    fun dispose() {
        disposed = true
        clear()
        wake.close()
    }
}

@Composable
internal fun rememberLightFeedback(
    enabled: Boolean = true,
    enhancedOnly: Boolean = false,
): LightFeedbackState {
    val level = LocalParticleLight.current
    val budget = LocalParticleBudget.current
    val limit = LocalParticleLimit.current
    val density = LocalDensity.current.density
    val active =
        enabled &&
            LocalParticleActive.current &&
            LocalRouteVisible.current &&
            LocalWindowInfo.current.isWindowFocused &&
            !LocalAccessibilityOptions.current.reduceMotion &&
            level != ParticleLight.Off &&
            (!enhancedOnly || level == ParticleLight.Enhanced)
    val bounds = remember { LightBounds() }
    val state =
        remember(active, level, budget, limit, density) {
            LightFeedbackState(bounds, budget, limit, density, level == ParticleLight.Enhanced, active)
        }
    LaunchedEffect(state) { if (active) state.run() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/** Draw locally. Does not alter layout, semantics, pointer handling or video surfaces. */
@Composable
internal fun Modifier.lightFeedback(state: LightFeedbackState): Modifier {
    val color = LocalPalette.current.text
    if (!state.enabled) return this
    return onSizeChanged { state.resize(it.width, it.height) }
        .drawWithContent {
            drawContent()
            state.draw(this, color)
        }
}

/** State feedback is silent on first composition and after visibility/policy restoration. */
@Composable
internal fun Modifier.lightOnChange(
    value: Any?,
    effect: LightEffect = LightEffect.Node,
    enabled: Boolean = true,
    enhancedOnly: Boolean = false,
    fractionX: Float = 0.5f,
    fractionY: Float = 0.5f,
    emitWhen: Boolean = true,
): Modifier {
    val light = rememberLightFeedback(enabled, enhancedOnly)
    val previous = remember(light) { arrayOf(value) }
    val currentEffect by rememberUpdatedState(effect)
    val currentX by rememberUpdatedState(fractionX)
    val currentY by rememberUpdatedState(fractionY)
    LaunchedEffect(value, light) {
        val changed = previous[0] != value
        previous[0] = value
        if (changed && emitWhen) light.emit(currentEffect, fractionX = currentX, fractionY = currentY)
    }
    return lightFeedback(light)
}
