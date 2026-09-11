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
import androidx.compose.ui.graphics.lerp
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

/**
 * How the light moves. [ParticleLight] decides how much of it there is; this decides its shape,
 * and the two are chosen separately so a quiet 轻柔 星轨 is as valid as a full 增强 星尘.
 *
 * Entries only ever append — the choice persists by name.
 */
enum class ParticleStyle(
    val label: String,
    val description: String,
) {
    /** 光粒从四周被吸向目标 — the closest to the feedback the app already had. */
    Stardust("星尘", "光粒从四周汇聚到目标，像被磁吸"),

    /** 光粒绕目标环流 — rings of light that tighten or open around the control. */
    Orbit("星轨", "光粒绕目标环流一圈，带一颗拖尾亮点"),

    /** 光粒顺丝带路径流过 — curved lanes coloured from the mark's lavender and ice blue. */
    Flow("流光", "光粒顺一条丝带流过，取自标志的薰衣草与冰蓝"),
}

enum class LightEffect {
    Trail,
    Converge,
    Edge,
    Dissolve,
    Node,
    Dust,
}

internal val LocalParticleLight = staticCompositionLocalOf { ParticleLight.Gentle }
internal val LocalParticleStyle = staticCompositionLocalOf { ParticleStyle.Stardust }
internal val LocalParticleLimit = staticCompositionLocalOf { 64 }
internal val LocalParticleActive = staticCompositionLocalOf { true }
internal val LocalParticleBudget = staticCompositionLocalOf { LightParticleBudget() }

/**
 * 流光's two ends, from the 「Yfuse 水火 Logo」 lavender band and ice-blue band. Both are pastel
 * by design: on the dark page they read as light, so on the light page they are pulled most of
 * the way to the ink before they are drawn — see [flowLightColors].
 */
internal val FlowLightLavender = Color(0xFFC3B1F5)
internal val FlowLightIce = Color(0xFF9FD6F3)

internal fun flowLightColors(
    ink: Color,
    dark: Boolean,
): Pair<Color, Color> =
    if (dark) {
        FlowLightLavender to FlowLightIce
    } else {
        lerp(FlowLightLavender, ink, 0.55f) to lerp(FlowLightIce, ink, 0.55f)
    }

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

    // 星轨 keeps its two radii here; 流光 keeps its bend point. A linear particle ignores both.
    private val controlX = FloatArray(capacity)
    private val controlY = FloatArray(capacity)
    private val angle = FloatArray(capacity)
    private val spin = FloatArray(capacity)
    val motion = IntArray(capacity)
    val tint = FloatArray(capacity)
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
        style: ParticleStyle = ParticleStyle.Stardust,
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
            } * density * style.spreadScale
        val ax = anchorX.coerceIn(0f, width)
        val ay = anchorY.coerceIn(0f, height)
        val edge = effect == LightEffect.Edge || effect == LightEffect.Dust
        val dirX = directionX.coerceIn(-1f, 1f)
        val dirY = directionY.coerceIn(-1f, 1f)
        val kind =
            when {
                edge -> MOTION_LINEAR
                style == ParticleStyle.Orbit -> MOTION_ORBIT
                style == ParticleStyle.Flow -> MOTION_FLOW
                else -> MOTION_LINEAR
            }
        var emitted = 0
        for (i in 0 until capacity) {
            if (emitted >= count) break
            if (age[i] >= 0f) continue
            if (!budget.acquire(limit)) break
            val theta = ((emitted + seed % 7 * 0.13f) / count * 2f * PI).toFloat()
            val spread = distance * (0.55f + ((seed + emitted * 7) % 9) / 20f)
            val dx = cos(theta) * spread
            val dy = sin(theta) * spread
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
            val sink = if (effect == LightEffect.Dissolve) 8f * density else 0f
            motion[i] = kind
            when (kind) {
                MOTION_ORBIT -> {
                    // The centre travels the way a linear particle would; the light rides a
                    // ring around it whose radius opens or closes with the effect's meaning.
                    startX[i] = ax
                    startY[i] = ay
                    endX[i] = ax - dirX * distance
                    endY[i] = ay - dirY * distance + sink
                    val (from, to) =
                        when (effect) {
                            LightEffect.Converge -> spread to spread * 0.12f
                            LightEffect.Dissolve -> spread * 0.3f to spread * 1.3f
                            LightEffect.Trail -> spread * 0.5f to spread * 0.95f
                            else -> spread * 0.8f to spread * 1.05f
                        }
                    controlX[i] = from
                    controlY[i] = to
                    angle[i] = theta
                    spin[i] = (if (emitted % 2 == 0) 1f else -1f) * (0.9f + emitted % 3 * 0.25f)
                }
                else -> {
                    if (effect == LightEffect.Converge) {
                        startX[i] = ax + dx
                        startY[i] = ay + dy
                        endX[i] = ax
                        endY[i] = ay
                    } else {
                        startX[i] = ox
                        startY[i] = oy
                        endX[i] = ox + dx - dirX * distance
                        endY[i] = oy + dy - dirY * distance + sink
                    }
                    if (kind == MOTION_FLOW) {
                        // Bend each lane sideways so the batch reads as a ribbon rather than
                        // a burst; alternating sides keep the ribbon from becoming an arc.
                        val vx = endX[i] - startX[i]
                        val vy = endY[i] - startY[i]
                        val side = if (emitted % 2 == 0) 0.6f else -0.6f
                        controlX[i] = (startX[i] + endX[i]) / 2f - vy * side
                        controlY[i] = (startY[i] + endY[i]) / 2f + vx * side
                    }
                }
            }
            age[i] = 0f
            lifetime[i] =
                (if (effect == LightEffect.Dust) 0.32f else 0.24f) +
                (emitted % 4) * 0.04f +
                (if (kind == MOTION_ORBIT) 0.04f else 0f)
            radius[i] = (0.65f + emitted % 3 * 0.22f) * density * style.sizeScale
            tint[i] = if (count > 1) emitted.toFloat() / (count - 1) else 0f
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
            when (motion[i]) {
                MOTION_ORBIT -> {
                    val cx = startX[i] + (endX[i] - startX[i]) * eased
                    val cy = startY[i] + (endY[i] - startY[i]) * eased
                    val r = controlX[i] + (controlY[i] - controlX[i]) * eased
                    val a = angle[i] + spin[i] * TWO_PI * eased
                    x[i] = cx + cos(a) * r
                    y[i] = cy + sin(a) * r
                }
                MOTION_FLOW -> {
                    val u = 1f - eased
                    x[i] = u * u * startX[i] + 2f * u * eased * controlX[i] + eased * eased * endX[i]
                    y[i] = u * u * startY[i] + 2f * u * eased * controlY[i] + eased * eased * endY[i]
                }
                else -> {
                    x[i] = startX[i] + (endX[i] - startX[i]) * eased
                    y[i] = startY[i] + (endY[i] - startY[i]) * eased
                }
            }
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

    companion object {
        const val MOTION_LINEAR = 0
        const val MOTION_ORBIT = 1
        const val MOTION_FLOW = 2
        private const val TWO_PI = (2.0 * PI).toFloat()
    }
}

/** 星尘 is the big, magnetic one; the other two are read by their path, not their size. */
private val ParticleStyle.sizeScale: Float
    get() =
        when (this) {
            ParticleStyle.Stardust -> 1.4f
            ParticleStyle.Orbit -> 1.15f
            ParticleStyle.Flow -> 1.2f
        }

private val ParticleStyle.spreadScale: Float
    get() =
        when (this) {
            ParticleStyle.Stardust -> 1.5f
            ParticleStyle.Orbit -> 1f
            ParticleStyle.Flow -> 1.35f
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
    private val style: ParticleStyle = ParticleStyle.Stardust,
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
        if (particles.emit(effect, px, py, width, height, density, enhanced, directionX, directionY, style)) {
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
        flowStart: Color = color,
        flowEnd: Color = color,
    ) {
        revision.intValue // Read only in drawing: particles never recompose the containing page.
        val particles = pool ?: return
        with(scope) {
            for (i in 0 until particles.capacity) {
                val a = particles.alpha[i]
                if (a <= 0f) continue
                val center = Offset(particles.x[i], particles.y[i])
                val ink =
                    if (particles.motion[i] == LightParticlePool.MOTION_FLOW) {
                        lerp(flowStart, flowEnd, particles.tint[i])
                    } else {
                        color
                    }
                drawCircle(ink.copy(alpha = a * 0.16f), particles.radius[i] * 2.8f, center)
                drawCircle(ink.copy(alpha = a), particles.radius[i], center)
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
    val style = LocalParticleStyle.current
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
        remember(active, level, style, budget, limit, density) {
            LightFeedbackState(bounds, budget, limit, density, level == ParticleLight.Enhanced, active, style)
        }
    LaunchedEffect(state) { if (active) state.run() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/** Draw locally. Does not alter layout, semantics, pointer handling or video surfaces. */
@Composable
internal fun Modifier.lightFeedback(state: LightFeedbackState): Modifier {
    val palette = LocalPalette.current
    val color = palette.text
    val (flowStart, flowEnd) = flowLightColors(color, palette.isDark)
    if (!state.enabled) return this
    return onSizeChanged { state.resize(it.width, it.height) }
        .drawWithContent {
            drawContent()
            state.draw(this, color, flowStart, flowEnd)
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

internal class LightAppearanceGate {
    private var consumed = false

    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

/** For newly created notices/previews, never for recycled media rows. */
@Composable
internal fun Modifier.lightOnAppear(
    effect: LightEffect = LightEffect.Edge,
    enabled: Boolean = true,
    enhancedOnly: Boolean = false,
): Modifier {
    val light = rememberLightFeedback(enabled, enhancedOnly)
    val gate = remember { LightAppearanceGate() }
    return lightFeedback(light).onSizeChanged { size ->
        light.resize(size.width, size.height)
        if (gate.consume()) light.emit(effect)
    }
}
