package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * 氛围光: the picture's own edge colours, leaked into the letterbox around it.
 *
 * The player reads a 32×18 thumbnail at an adaptive interval. The two pixel rows hugging each
 * edge are averaged into buckets — five down each side, eight along the top and bottom — and
 * the whole frame into [mean]. Those colours are then painted into the black bars outside the
 * picture, brightest at the picture's edge and falling to true black at the screen's edge, and
 * [mean] tints the chrome scrims and the seek bar's accent. The picture itself is never touched.
 *
 * The bucket lists are ordered along their edge: [left]/[right] top to bottom, [top]/[bottom]
 * start to end. Lists are always the sizes named here, so buckets can be interpolated pairwise.
 */
data class AmbientLight(
    val left: List<Color>,
    val right: List<Color>,
    val top: List<Color>,
    val bottom: List<Color>,
    val mean: Color,
    /** Harmonize each sampled target once; intermediate frames interpolate these final colours. */
    val accent: Color = harmonizeArtworkAccent(mean, darkTheme = true),
    val accentWeight: Float = if (mean == Color.Black) 0f else 1f,
) {
    init {
        require(left.size == SIDE_BUCKETS && right.size == SIDE_BUCKETS) { "side buckets must be $SIDE_BUCKETS" }
        require(top.size == EDGE_BUCKETS && bottom.size == EDGE_BUCKETS) { "edge buckets must be $EDGE_BUCKETS" }
    }

    /** True when the light has nothing to paint: every bucket is black. */
    val isDark: Boolean
        get() =
            mean == Color.Black &&
                left.all { it == Color.Black } &&
                right.all { it == Color.Black } &&
                top.all { it == Color.Black } &&
                bottom.all { it == Color.Black }

    companion object {
        const val SIDE_BUCKETS = 5
        const val EDGE_BUCKETS = 8

        /** One colour everywhere — the poster-derived fallback when frames cannot be read. */
        fun uniform(color: Color): AmbientLight =
            AmbientLight(
                left = List(SIDE_BUCKETS) { color },
                right = List(SIDE_BUCKETS) { color },
                top = List(EDGE_BUCKETS) { color },
                bottom = List(EDGE_BUCKETS) { color },
                mean = color,
            )

        val Off: AmbientLight = uniform(Color.Black)
    }
}

/** How often a playing picture is read. Paused playback reads once and stops. */
const val AMBIENT_LIGHT_SAMPLE_MS = 500L

/** At most about 30 published animation frames/s, independent of a 60/120Hz display clock. */
internal const val AMBIENT_LIGHT_FRAME_MS = 34L

/** Every bucket slides from its old colour to its new one over this long, on [Motion.Curve]. */
const val AMBIENT_LIGHT_FADE_MS = Motion.AMBIENT_LIGHT_FADE

/** Below this HSL lightness a bucket is kept black: 片头片尾 and night scenes must not glow grey. */
private const val AMBIENT_BLACK_FLOOR = 0.06f

/** A bucket brighter than this is dimmed to it; light in the bars must never compete with the picture. */
private const val AMBIENT_LIGHTNESS_CAP = 0.42f

/** Chroma is boosted only when a hue is really there; true greys stay grey. */
private const val AMBIENT_NEUTRAL_GUARD = 0.08f
private const val AMBIENT_SATURATION_BOOST = 1.25f

/**
 * Turns an averaged frame colour into a light that can sit in the letterbox.
 *
 * Pale colours (a daytime sky) get darker and richer rather than staying near-white: reducing
 * lightness while raising saturation is what makes a cream sky read as amber light instead of a
 * grey slab. Near-black stays black, and neutral greys are dimmed but not given a hue.
 */
fun toneAmbientLight(sampled: Color): Color {
    val (hue, saturation, lightness) = sampled.toHsl()
    if (lightness < AMBIENT_BLACK_FLOOR) return Color.Black
    val richer =
        if (saturation > AMBIENT_NEUTRAL_GUARD) minOf(1f, saturation * AMBIENT_SATURATION_BOOST) else saturation
    val dimmer = minOf(lightness, AMBIENT_LIGHTNESS_CAP)
    return Color.hsl(hue, richer, dimmer)
}

/** Bucket-wise sRGB interpolation, so a single cut fades every bucket on the same clock. */
fun lerpAmbientLight(
    start: AmbientLight,
    end: AmbientLight,
    fraction: Float,
): AmbientLight {
    fun mix(
        a: List<Color>,
        b: List<Color>,
    ) = List(a.size) { index -> interpolateArtworkPageColor(a[index], b[index], fraction) }
    val progress = fraction.coerceIn(0f, 1f)
    return AmbientLight(
        left = mix(start.left, end.left),
        right = mix(start.right, end.right),
        top = mix(start.top, end.top),
        bottom = mix(start.bottom, end.bottom),
        mean = interpolateArtworkPageColor(start.mean, end.mean, fraction),
        accent = interpolateArtworkPageColor(start.accent, end.accent, fraction),
        accentWeight = start.accentWeight + (end.accentWeight - start.accentWeight) * progress,
    )
}

/**
 * Whether a fresh sample is worth retargeting for. Sensor-level noise from grain and dithering
 * would otherwise keep restarting the fade and the light would never settle.
 */
fun ambientLightDiffers(
    current: AmbientLight,
    next: AmbientLight,
    threshold: Float = 6f / 255f,
): Boolean {
    fun far(
        a: Color,
        b: Color,
    ): Boolean {
        val from = a.convert(ColorSpaces.Srgb)
        val to = b.convert(ColorSpaces.Srgb)
        return abs(from.red - to.red) + abs(from.green - to.green) + abs(from.blue - to.blue) > threshold
    }

    fun edgeDiffers(
        a: List<Color>,
        b: List<Color>,
    ): Boolean {
        for (index in a.indices) if (far(a[index], b[index])) return true
        return false
    }
    return far(current.mean, next.mean) ||
        edgeDiffers(current.left, next.left) ||
        edgeDiffers(current.right, next.right) ||
        edgeDiffers(current.top, next.top) ||
        edgeDiffers(current.bottom, next.bottom)
}

/**
 * The seek bar's accent while the light is on: the frame mean, harmonised into the same band
 * every artwork accent lives in. Null when the frame is dark, so the caller keeps its own accent
 * instead of a lifted grey.
 */
fun ambientLightAccent(light: AmbientLight): Color? = light.accent.takeIf { light.accentWeight > 0f }

/** Dark frames fade back to the artwork accent without starting another animation clock. */
fun ambientSeekAccent(
    light: AmbientLight?,
    fallback: Color,
): Color = if (light == null) fallback else interpolateArtworkPageColor(fallback, light.accent, light.accentWeight)

/** How much of the light the chrome scrims take: 30% of the mean over their black base. */
const val AMBIENT_SCRIM_TINT = 0.30f

/**
 * [target] eased into place, bucket by bucket. Only the draw nodes that read the returned state
 * observe the frame clock; the host composable retargets and never recomposes per frame. Same
 * shape as [rememberCarouselPageColor]. A null target means the light is off and paints nothing.
 */
@Composable
fun rememberAmbientLight(
    target: AmbientLight?,
    active: Boolean = true,
    animate: Boolean = true,
): State<AmbientLight> {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val visible = LocalRouteVisible.current
    val targetLight = if (active) target ?: AmbientLight.Off else AmbientLight.Off
    val previousOutput = remember { arrayOfNulls<State<AmbientLight>>(1) }
    val transition =
        remember(targetLight, reduceMotion, visible, active, animate) {
            // Retarget-time read: not a subscription that would recompose the host every frame.
            val start = Snapshot.withoutReadObservation { previousOutput[0]?.value ?: targetLight }
            AmbientLightTransition(
                start,
                targetLight,
                active && animate && !reduceMotion && visible && start != targetLight,
            )
        }
    val output =
        remember(transition) {
            mutableStateOf(if (transition.animate) transition.start else transition.target)
        }
    LaunchedEffect(transition) {
        if (transition.animate) {
            val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            val started = TimeSource.Monotonic.markNow()
            runAmbientLightTransition(
                start = transition.start,
                target = transition.target,
                durationMs = (AMBIENT_LIGHT_FADE_MS * durationScale).toLong(),
                elapsedMs = { started.elapsedNow().inWholeMilliseconds },
                publish = { output.value = it },
            )
        }
    }
    SideEffect { previousOutput[0] = output }
    return output
}

/** Delay between colour publications instead of subscribing to every display frame. */
internal suspend fun runAmbientLightTransition(
    start: AmbientLight,
    target: AmbientLight,
    durationMs: Long,
    elapsedMs: () -> Long,
    publish: (AmbientLight) -> Unit,
) {
    while (durationMs > 0L) {
        val elapsed = elapsedMs().coerceAtLeast(0L)
        if (elapsed >= durationMs) break
        val fraction = Motion.Curve.transform(elapsed.toFloat() / durationMs)
        publish(lerpAmbientLight(start, target, fraction))
        delay(minOf(AMBIENT_LIGHT_FRAME_MS, durationMs - elapsed))
    }
    publish(target)
}

private data class AmbientLightTransition(
    val start: AmbientLight,
    val target: AmbientLight,
    val animate: Boolean,
)

/**
 * Paints [light] into the bars around [picture] inside [bounds], never over the picture.
 *
 * Each bar is a band of its edge's bucket colours laid along the picture's edge, then a black
 * falloff from transparent at the picture to opaque at the screen edge (alpha 0 → .35 → .75 → 1).
 * Two gradients per bar, no blur, no extra layer. The exclusion is grown by [guard] so a rect that
 * is off by a pixel leaves a hairline of black rather than a hairline of light on the picture.
 */
fun DrawScope.drawAmbientLight(
    light: AmbientLight,
    picture: Rect,
    bounds: Size = size,
    guard: Float = 0f,
    falloffs: List<Brush>? = null,
) {
    if (light.isDark) return
    val fades = falloffs ?: ambientLightFalloffBrushes(picture, bounds)
    val excluded =
        Rect(
            left = picture.left - guard,
            top = picture.top - guard,
            right = picture.right + guard,
            bottom = picture.bottom + guard,
        )
    clipRect(excluded.left, excluded.top, excluded.right, excluded.bottom, ClipOp.Difference) {
        fun bar(
            rect: Rect,
            band: Brush,
            fade: Brush,
        ) {
            drawRect(band, rect.topLeft, rect.size)
            drawRect(fade, rect.topLeft, rect.size)
        }
        if (picture.left > 0.5f) {
            bar(
                rect = Rect(0f, 0f, picture.left, bounds.height),
                band = Brush.verticalGradient(light.left, startY = picture.top, endY = picture.bottom),
                fade = fades[0],
            )
        }
        if (picture.right < bounds.width - 0.5f) {
            bar(
                rect = Rect(picture.right, 0f, bounds.width, bounds.height),
                band = Brush.verticalGradient(light.right, startY = picture.top, endY = picture.bottom),
                fade = fades[1],
            )
        }
        if (picture.top > 0.5f) {
            bar(
                rect = Rect(0f, 0f, bounds.width, picture.top),
                band = Brush.horizontalGradient(light.top, startX = picture.left, endX = picture.right),
                fade = fades[2],
            )
        }
        if (picture.bottom < bounds.height - 0.5f) {
            bar(
                rect = Rect(0f, picture.bottom, bounds.width, bounds.height),
                band = Brush.horizontalGradient(light.bottom, startX = picture.left, endX = picture.right),
                fade = fades[3],
            )
        }
    }
}

/** These gradients depend only on geometry; cache them outside the colour-reading draw node. */
fun ambientLightFalloffBrushes(
    picture: Rect,
    bounds: Size,
): List<Brush> {
    fun falloff(
        from: Offset,
        to: Offset,
    ) = Brush.linearGradient(
        0f to Color.Transparent,
        0.35f to Color.Black.copy(alpha = 0.35f),
        0.7f to Color.Black.copy(alpha = 0.75f),
        1f to Color.Black,
        start = from,
        end = to,
    )
    return listOf(
        falloff(Offset(picture.left, 0f), Offset.Zero),
        falloff(Offset(picture.right, 0f), Offset(bounds.width, 0f)),
        falloff(Offset(0f, picture.top), Offset.Zero),
        falloff(Offset(0f, picture.bottom), Offset(0f, bounds.height)),
    )
}

/**
 * Averages [pixels] (row-major ARGB, [width]×[height]) into an [AmbientLight]: the outer two
 * pixel rows/columns bucketed along each edge, everything into the mean, each bucket toned.
 * Platform samplers hand their thumbnail here so the arithmetic lives in one testable place.
 */
fun ambientLightFromPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
): AmbientLight {
    require(pixels.size >= width * height && width >= 4 && height >= 4) { "thumbnail too small" }
    val rim = 2

    fun bucketAverage(
        xs: IntRange,
        ys: IntRange,
    ): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in ys) {
            for (x in xs) {
                val p = pixels[y * width + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        return if (n == 0) Color.Black else Color(r.toInt() / n, g.toInt() / n, b.toInt() / n)
    }

    fun along(
        total: Int,
        buckets: Int,
        index: Int,
    ): IntRange {
        val start = index * total / buckets
        val end = ((index + 1) * total / buckets).coerceAtLeast(start + 1)
        return start until end.coerceAtMost(total)
    }
    val side = AmbientLight.SIDE_BUCKETS
    val edge = AmbientLight.EDGE_BUCKETS
    return AmbientLight(
        left = List(side) { toneAmbientLight(bucketAverage(0 until rim, along(height, side, it))) },
        right = List(side) { toneAmbientLight(bucketAverage(width - rim until width, along(height, side, it))) },
        top = List(edge) { toneAmbientLight(bucketAverage(along(width, edge, it), 0 until rim)) },
        bottom = List(edge) { toneAmbientLight(bucketAverage(along(width, edge, it), height - rim until height)) },
        mean = toneAmbientLight(bucketAverage(0 until width, 0 until height)),
    )
}

private fun Color.toHsl(): Triple<Float, Float, Float> {
    val c = convert(ColorSpaces.Srgb)
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val lightness = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, lightness)
    val delta = max - min
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue =
        when (max) {
            c.red -> ((c.green - c.blue) / delta + (if (c.green < c.blue) 6f else 0f))
            c.green -> (c.blue - c.red) / delta + 2f
            else -> (c.red - c.green) / delta + 4f
        } * 60f
    return Triple(hue, saturation, lightness)
}
