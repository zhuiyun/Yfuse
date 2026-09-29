package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 液态分离 — a key growing out of another one.
 *
 * The reference is the album bar in ColorOS 17's 流畅 film: switching to 创作, the "+" swells out of
 * the capsule's right end, draws a thin neck, and snaps off as a round key of its own. Three places
 * in Yfuse have the same structure — the dock and its 搜索 key, the detail page's play key and its
 * 从头, the player's paused key and the three keys at an item's end — and they share this engine.
 *
 * Every shape is a signed distance along one horizontal axis, and the shapes are joined with a
 * smooth minimum of width k. Two shapes closer than k/2 are connected; at exactly k/2 the neck is
 * zero wide. So the neck is not animated at all: it narrows by itself as the pieces move apart and
 * breaks when the gap reaches k/2, and the two small points left behind withdraw as k decays. The
 * outline is found by walking the axis every half dp and bisecting the half-height at each sample,
 * which gives one closed path per piece; no shader, so it draws the same on every API level.
 *
 * Geometry here is in dp and milliseconds, the units the design was written in. Scenes convert to
 * pixels only when they build a path.
 */

/** One shape of a liquid layer, centred on the layer's axis. */
internal class LiquidBody private constructor(
    private val kind: Int,
    private val center: Float,
    internal val radius: Float,
    private val start: Float,
    private val end: Float,
    private val halfWidth: Float,
    private val halfHeight: Float,
    private val corner: Float,
    private val morph: Float,
    /** Width of the smooth union with every body before it; 0 is a plain union. */
    val blend: Float,
) {
    /** Bodies with a radius vanish below [MIN_RADIUS] instead of blending a speck into their parent. */
    internal val vanished: Boolean get() = kind != BOX && kind != CAPSULE && radius < MIN_RADIUS

    /** Signed distance from ([u], [v]) to this body's edge: negative inside. [v] is measured from the axis. */
    fun distance(
        u: Float,
        v: Float,
    ): Float =
        when (kind) {
            CAPSULE -> hypot(max(max(start - u, 0f), u - end), v) - radius
            BOX -> boxDistance(u, v, halfWidth, halfHeight, corner)
            MORPH -> {
                val drop = hypot(u - center, v) - radius
                if (morph <= 0f) drop else drop + (boxDistance(u, v, halfWidth, halfHeight, corner) - drop) * morph
            }
            else -> hypot(u - center, v) - radius
        }

    private fun boxDistance(
        u: Float,
        v: Float,
        halfWidth: Float,
        halfHeight: Float,
        corner: Float,
    ): Float {
        val qx = abs(u - center) - halfWidth + corner
        val qy = abs(v) - halfHeight + corner
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - corner
    }

    companion object {
        private const val DROP = 0
        private const val CAPSULE = 1
        private const val BOX = 2
        private const val MORPH = 3

        /** A capsule whose two caps are centred at [start] and [end] along the axis. */
        fun capsule(
            start: Float,
            end: Float,
            radius: Float,
            blend: Float = 0f,
        ) = LiquidBody(CAPSULE, 0f, radius, start, max(start, end), 0f, 0f, 0f, 0f, blend)

        /** A round drop. */
        fun drop(
            center: Float,
            radius: Float,
            blend: Float = 0f,
        ) = LiquidBody(DROP, center, radius, 0f, 0f, 0f, 0f, 0f, 0f, blend)

        /** A rounded rectangle, [halfWidth] × [halfHeight] about its centre. */
        fun box(
            center: Float,
            halfWidth: Float,
            halfHeight: Float,
            corner: Float,
            blend: Float = 0f,
        ) = LiquidBody(
            BOX,
            center,
            0f,
            0f,
            0f,
            halfWidth,
            halfHeight,
            corner.coerceIn(0f, min(halfWidth, halfHeight)),
            0f,
            blend,
        )

        /**
         * A drop of [radius] on its way to becoming a rounded rectangle: [amount] 0 is the drop, 1
         * the rectangle, and the distance fields are interpolated in between.
         */
        fun morph(
            center: Float,
            radius: Float,
            halfWidth: Float,
            halfHeight: Float,
            corner: Float,
            amount: Float,
            blend: Float = 0f,
        ) = LiquidBody(
            MORPH,
            center,
            radius,
            0f,
            0f,
            halfWidth,
            halfHeight,
            corner.coerceIn(0f, min(halfWidth, halfHeight)),
            amount.coerceIn(0f, 1f),
            blend,
        )

        /** Below this a drop is gone, not a sub-pixel speck pulling at its parent. */
        internal const val MIN_RADIUS = 0.4f
    }
}

/** Polynomial smooth minimum: [a] and [b] joined by a fillet [k] wide; below 0.01 it is a plain min. */
internal fun liquidSmoothMin(
    a: Float,
    b: Float,
    k: Float,
): Float {
    if (!(k > 0.01f)) return min(a, b)
    val h = max(k - abs(a - b), 0f) / k
    return min(a, b) - h * h * k * 0.25f
}

/** The layer's distance field: the first body, then every later one joined with its own [LiquidBody.blend]. */
internal fun liquidField(
    bodies: List<LiquidBody>,
    u: Float,
    v: Float,
): Float {
    var distance = bodies[0].distance(u, v)
    for (index in 1 until bodies.size) {
        val body = bodies[index]
        if (body.vanished) continue
        distance = liquidSmoothMin(distance, body.distance(u, v), body.blend)
    }
    return distance
}

/**
 * One separate piece of a liquid layer: samples along the axis, from its left tip to its right tip,
 * with the half-height at each. The tips have zero height.
 */
internal class LiquidSegment(
    val u: FloatArray,
    val halfHeight: FloatArray,
) {
    val start: Float get() = u.first()
    val end: Float get() = u.last()
    val center: Float get() = (start + end) / 2f

    /** The tallest half-height along the piece. */
    val peak: Float get() = halfHeight.max()

    /** Whether [position] on the axis falls inside this piece. */
    operator fun contains(position: Float): Boolean = position in start..end
}

/**
 * The pieces of [bodies] between [from] and [to] on the axis, left to right.
 *
 * The axis is walked every [LIQUID_STEP] to find where the field crosses zero, each crossing is
 * bisected, and each piece is then sampled with cosine spacing — dense at the tips, where the
 * outline turns fastest — bisecting the half-height below [maxHalfHeight] at every sample.
 */
internal fun liquidOutline(
    bodies: List<LiquidBody>,
    from: Float,
    to: Float,
    maxHalfHeight: Float,
): List<LiquidSegment> {
    if (bodies.isEmpty() || !(to > from)) return emptyList()

    fun inside(u: Float) = liquidField(bodies, u, 0f) <= 0f

    // Where the field crosses zero between two samples on either side of an edge.
    fun crossing(
        before: Float,
        after: Float,
    ): Float {
        var a = before
        var b = after
        val aInside = inside(a)
        repeat(EDGE_BISECTIONS) {
            val middle = (a + b) / 2f
            if (inside(middle) == aInside) a = middle else b = middle
        }
        return (a + b) / 2f
    }
    val spans = ArrayList<FloatArray>(3)
    var wasInside = inside(from)
    var spanStart = from
    var previous = from
    var u = from + LIQUID_STEP
    while (u <= to + 1e-4f) {
        val now = inside(u)
        if (now != wasInside) {
            val edge = crossing(previous, u)
            if (now) spanStart = edge else spans += floatArrayOf(spanStart, edge)
            wasInside = now
        }
        previous = u
        u += LIQUID_STEP
    }
    if (wasInside) spans += floatArrayOf(spanStart, to)
    return spans.map { (a, b) ->
        val count = ((b - a) * SAMPLES_PER_DP).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val positions = FloatArray(count + 1)
        val heights = FloatArray(count + 1)
        for (index in 0..count) {
            val position = a + (b - a) * (1f - cos(PI.toFloat() * index / count)) / 2f
            positions[index] = position
            if (index == 0 || index == count) continue
            var low = 0f
            var high = maxHalfHeight
            repeat(HEIGHT_BISECTIONS) {
                val middle = (low + high) / 2f
                if (liquidField(bodies, position, middle) <= 0f) low = middle else high = middle
            }
            heights[index] = low
        }
        LiquidSegment(positions, heights)
    }
}

/**
 * Appends [segment] to [path] as a closed outline: along the top from tip to tip, back along the
 * bottom. [scale] turns dp into pixels; the axis sits at [axisY] and the layer's left edge at
 * [originX], both in pixels.
 */
internal fun Path.addLiquidSegment(
    segment: LiquidSegment,
    scale: Float,
    axisY: Float,
    originX: Float = 0f,
) {
    val u = segment.u
    val h = segment.halfHeight
    val last = u.lastIndex
    val vertical = abs(scale)
    moveTo(originX + u[0] * scale, axisY)
    for (index in 1..last) lineTo(originX + u[index] * scale, axisY - h[index] * vertical)
    for (index in last - 1 downTo 1) lineTo(originX + u[index] * scale, axisY + h[index] * vertical)
    close()
}

/**
 * A piece's bounds in pixels, for brushes that are sized to the surface they paint. A negative
 * [scale] lays the axis out from [originX] leftwards, for right-to-left layouts.
 */
internal fun LiquidSegment.bounds(
    scale: Float,
    axisY: Float,
    originX: Float = 0f,
): Rect {
    val half = peak * abs(scale)
    val a = originX + start * scale
    val b = originX + end * scale
    return Rect(min(a, b), axisY - half, max(a, b), axisY + half)
}

/**
 * One frame of a liquid layer in pixels: the whole outline, and each piece on its own in a box of
 * its own, so brushes that are sized to a surface — a rim, a sheen — paint each piece as if it
 * were one. The paths are reused from frame to frame.
 */
internal class LiquidPaths {
    /** Every piece, in the drawing node's coordinates. */
    val outline = Path()

    private val pool = ArrayList<LiquidPiece>(3)
    private var count = 0

    /** The pieces, left to right. */
    val pieces: List<LiquidPiece> get() = pool.subList(0, count)

    /** Rebuilds the paths from [segments], laid on an axis at [axisY] with the layer's left edge at [originX]. */
    fun update(
        segments: List<LiquidSegment>,
        scale: Float,
        axisY: Float,
        originX: Float = 0f,
    ) {
        outline.rewind()
        while (pool.size < segments.size) pool += LiquidPiece()
        count = segments.size
        segments.forEachIndexed { index, segment ->
            outline.addLiquidSegment(segment, scale, axisY, originX)
            pool[index].update(segment, scale, axisY, originX)
        }
    }
}

/** One piece of a [LiquidPaths] frame. */
internal class LiquidPiece {
    /** The piece's outline, relative to the top-left corner of [bounds]. */
    val path = Path()

    /** Where the piece sits in the drawing node, in pixels. */
    var bounds: Rect = Rect.Zero
        private set

    /** The piece along the axis, in dp. */
    var segment: LiquidSegment = LiquidSegment(FloatArray(2), FloatArray(2))
        private set

    internal fun update(
        segment: LiquidSegment,
        scale: Float,
        axisY: Float,
        originX: Float,
    ) {
        this.segment = segment
        bounds = segment.bounds(scale, axisY, originX)
        path.rewind()
        path.addLiquidSegment(segment, scale, axisY - bounds.top, originX - bounds.left)
    }
}

/** Sampling pitch along the axis. */
internal const val LIQUID_STEP = 0.5f
private const val SAMPLES_PER_DP = 0.8f
private const val MIN_SAMPLES = 24
private const val MAX_SAMPLES = 220
private const val EDGE_BISECTIONS = 26
private const val HEIGHT_BISECTIONS = 22

// ---------------------------------------------------------------- timing

/**
 * The curves a liquid move is written with, as pure functions of milliseconds so a frame can be
 * drawn — and tested — from the clock alone.
 */
internal object LiquidMotion {
    /**
     * Step response of a spring with damping ratio [damping] and natural frequency [hz], [ms] after
     * it was let go: 0 until then, 1 at rest. Compose's `spring(stiffness = (2πf)²)` is the same curve.
     */
    fun spring(
        ms: Float,
        damping: Float,
        hz: Float,
    ): Float {
        if (ms <= 0f) return 0f
        val omega = 2f * PI.toFloat() * hz
        val t = ms / 1000f
        if (damping >= 1f) return 1f - exp(-omega * t) * (1f + omega * t)
        val damped = omega * sqrt(1f - damping * damping)
        return 1f - exp(-damping * omega * t) * (cos(damped * t) + damping * omega / damped * sin(damped * t))
    }

    /** Smoothstep from [from] to [to]. */
    fun smooth(
        from: Float,
        to: Float,
        x: Float,
    ): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun easeIn(t: Float): Float = t.coerceIn(0f, 1f).pow(2.2f)

    fun inOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    fun lerp(
        from: Float,
        to: Float,
        fraction: Float,
    ): Float = from + (to - from) * fraction

    /**
     * How viscous a split is, [ms] after it starts, as a fraction of the full k: three tenths for the
     * first 60 ms so the swelling end does not get fat, full from 110 to 250 so the neck draws out
     * long, then gone by 520 so the points left at the break withdraw.
     */
    fun splitViscosity(ms: Float): Float =
        smooth(0f, 60f, ms) * (0.3f + 0.7f * smooth(110f, 250f, ms)) * (1f - smooth(250f, 520f, ms))

    /** The width of the smooth union, k, in dp: long enough to draw a neck, short enough to break it. */
    const val VISCOSITY = 40f

    /**
     * [VISCOSITY] for shapes [height] tall, in proportion to the 62 dp dock it was tuned on — the dock
     * grows under 大号文字, and a neck drawn with a fixed k would get relatively shorter.
     */
    fun viscosityFor(height: Float): Float = height * VISCOSITY / DOCK_HEIGHT

    private const val DOCK_HEIGHT = 62f
}

/**
 * Whether the liquid moves play at all.
 *
 * They ride the 搜索与导航动效 switch; 静息 and 减弱动态效果 keep the plain fades each surface
 * already had.
 */
@Composable
@ReadOnlyComposable
internal fun liquidMotionEnabled(): Boolean =
    LocalPulseSweepEnabled.current && !LocalAccessibilityOptions.current.reduceMotion && !calmMotion()

/**
 * The clock of one liquid layer.
 *
 * [move] is what is playing, or null once the layer has come to rest and the surfaces it drew
 * have taken their own glass back; composition reads it to hand the drawing over. [elapsed] is
 * read while drawing, so a running move invalidates nothing but the layer.
 */
@Stable
internal class LiquidClock<M : Any>(
    armed: M? = null,
) {
    private val time = Animatable(0f)

    /** Bumped by every move, so a move that was cut short does not clear the one that replaced it. */
    private var generation = 0

    var move: M? by mutableStateOf(armed)
        private set

    /** Milliseconds into [move]. */
    val elapsed: Float get() = time.value

    /**
     * Holds [move] at its first frame — or at [atMs] — without starting it: a key waiting for its
     * page to arrive, or a move picked up partway through by [runTo].
     */
    suspend fun arm(
        move: M,
        atMs: Float = 0f,
    ) {
        generation++
        this.move = move
        time.snapTo(atMs)
    }

    /**
     * Runs the move that is on from where it is to [toMs] at its own pace — backwards when [toMs] is
     * behind it, so a move that changes its mind goes back the way it came — then rests, or with
     * [holdLastFrame] stays there. Cancelled on the way, it leaves the move where it got to, for
     * whatever cancelled it to carry on from.
     */
    suspend fun runTo(
        toMs: Float,
        holdLastFrame: Boolean = false,
    ) {
        val mine = ++generation
        val distance = abs(toMs - time.value).roundToInt()
        time.animateTo(toMs, tween(distance, easing = LinearEasing))
        if (generation == mine && !holdLastFrame) move = null
    }

    /**
     * Plays [move] from its first frame for [durationMs], then rests — or, with [holdLastFrame], stays
     * on its last frame: a move that ends with the layer gone must not hand the surfaces back.
     */
    suspend fun play(
        move: M,
        durationMs: Int,
        holdLastFrame: Boolean = false,
    ) {
        val mine = ++generation
        this.move = move
        try {
            time.snapTo(0f)
            time.animateTo(durationMs.toFloat(), tween(durationMs, easing = LinearEasing))
        } finally {
            if (generation == mine && !holdLastFrame) this.move = null
        }
    }

    /** Drops whatever is playing; the surfaces draw themselves again. */
    suspend fun rest() {
        generation++
        move = null
        time.stop()
    }
}
