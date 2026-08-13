package com.yfuse.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「水滴入云」 — the drop falls into the cloud and shatters into the play head.
 *
 * The cloud player's own launch, kept with the mark it was drawn for: the old play head
 * collapses, a drop falls under gravity, the cloud takes the hit as a damped squash, and the
 * burst gathers back into the triangle. Every shape here is drawn rather than blitted, so
 * this one ignores the mark bitmap the water-fire choreographies unfold.
 */
internal object SplashCloudDrop : SplashChoreography {
    override val durationMs = 1_900f
    override val fadeStartMs = durationMs - CloudDropFadeMs

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) = drawCloudPlayerMark(nowMs)

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WordmarkStartMs, WordmarkMs))
}

/** Draws the borderless layered cloud, falling water drop, fragments, and play head. */
private fun DrawScope.drawCloudPlayerMark(nowMs: Float) {
    val d = size.minDimension
    val intro = easeOutCubic(span(nowMs, 0f, IntroMs))
    val breathing = sin(nowMs / SplashCloudDrop.durationMs * PiF) * 0.006f
    val markScale = 0.97f + intro * 0.03f + breathing
    val pivot = Offset(d * 0.50f, d * CloudBaseY)

    // Squash and stretch preserves area: height lost has to be bought back as width, or the
    // cloud reads as a rigid shape being scaled rather than a soft one being deformed.
    val baseScaleY = (1f - cloudCompression(nowMs)).coerceIn(MinScaleY, MaxScaleY)
    val baseScaleX = squashWidth(baseScaleY)
    // The crown takes the deformation harder than the base does. A pure time lag was not
    // enough on its own — without an amplitude difference the silhouette still moved as one
    // piece, which is what made the squash disappear at a glance.
    val crownCompression = cloudCompression(nowMs, CrownLagMs) * CrownSquashBoost
    val crownScaleY = (1f - crownCompression).coerceIn(MinScaleY, MaxScaleY)
    val crownScaleX = squashWidth(crownScaleY)
    // The sideways sway is the same curve a quarter-swing later, so it peaks as the cloud
    // passes back through its resting height instead of running on a clock of its own.
    val sway = cloudCompression(nowMs, SwayLagMs) * d * SwaySpan

    withTransform({
        scale(markScale, markScale, pivot = pivot)
        translate(left = sway)
        scale(baseScaleX, baseScaleY, pivot = pivot)
    }) {
        drawCloudShadow(d)
        // The rear lobes carry the trailing part of the wobble on top of the base transform, so
        // the crown settles a beat after the body instead of the silhouette moving as one
        // rigid piece.
        withTransform({
            scale(crownScaleX / baseScaleX, crownScaleY / baseScaleY, pivot = pivot)
        }) {
            drawCloudCrown(d)
        }
        drawCloudBase(d)
        drawCenterDisc(d)
        // Inside the same transform as the cloud, so a circular ripple can never sit on an
        // elliptical disc and the play head cannot ignore the breathing.
        drawWaterTimeline(d, nowMs)
    }
}

/**
 * Compression (positive) or stretch (negative) of the cloud at [nowMs], as a fraction of its
 * height.
 *
 * [lagMs] samples the same curve slightly in the past. The crown and the sideways sway both
 * trail the base by a fraction of a swing, which is what stops the whole silhouette moving as
 * one solid object.
 */
private fun cloudCompression(
    nowMs: Float,
    lagMs: Float = 0f,
): Float {
    val t = nowMs - lagMs
    // A light tap as the drop grazes the crown on its way in, then the real hit on the disc.
    val graze = GrazeJelly(span(t, CrownContactMs, GrazeSpringMs)) * GrazeSquash
    val hit = HitJelly(span(t, ImpactMs, HitSpringMs)) * HitSquash
    // Anticipation: the cloud lifts in the last beat before the drop arrives, so the impact has
    // something to snap back from.
    val lift = bell(span(t, ImpactMs - AnticipateMs, AnticipateMs)) * AnticipateStretch
    return graze + hit - lift
}

private fun DrawScope.drawCloudShadow(d: Float) {
    drawOval(
        brush =
            Brush.radialGradient(
                colors = listOf(CloudBlue.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(d * 0.50f, d * 0.735f),
                radius = d * 0.31f,
            ),
        topLeft = Offset(d * 0.19f, d * 0.69f),
        size = Size(d * 0.62f, d * 0.105f),
    )
}

/** The two rear blue lobes — the part of the silhouette that lags behind the base. */
private fun DrawScope.drawCloudCrown(d: Float) {
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFB8E8FF), Color(0xFF72C2FA), Color(0xFF318AEF)),
                start = Offset(d * 0.42f, d * 0.20f),
                end = Offset(d * 0.57f, d * 0.66f),
            ),
        radius = d * 0.235f,
        center = Offset(d * 0.50f, d * 0.435f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFF95D8FF), Color(0xFF4AA5F7), Color(0xFF1476E5)),
                start = Offset(d * 0.65f, d * 0.36f),
                end = Offset(d * 0.76f, d * 0.70f),
            ),
        radius = d * 0.165f,
        center = Offset(d * 0.705f, d * 0.535f),
    )
}

/** Front base joins the two pale lobes into one cloud silhouette. */
private fun DrawScope.drawCloudBase(d: Float) {
    drawRoundRect(
        brush =
            Brush.verticalGradient(
                colors = listOf(Color(0xFFFBFDFF), Color(0xFFE7F3FF), Color(0xFFC9E3FF)),
                startY = d * 0.53f,
                endY = d * 0.79f,
            ),
        topLeft = Offset(d * 0.205f, d * 0.585f),
        size = Size(d * 0.61f, d * 0.195f),
        cornerRadius = CornerRadius(d * 0.095f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFEAF5FF), Color(0xFFC4E0FF)),
                start = Offset(d * 0.20f, d * 0.43f),
                end = Offset(d * 0.39f, d * 0.76f),
            ),
        radius = d * 0.175f,
        center = Offset(d * 0.315f, d * 0.60f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFEEF7FF), Color(0xFFC9E4FF)),
                start = Offset(d * 0.68f, d * 0.49f),
                end = Offset(d * 0.78f, d * 0.77f),
            ),
        radius = d * 0.165f,
        center = Offset(d * 0.715f, d * 0.615f),
    )

    // Fine highlights give the pale front lobes some volume.
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.80f), Color.Transparent),
                center = Offset(d * 0.265f, d * 0.515f),
                radius = d * 0.12f,
            ),
        radius = d * 0.12f,
        center = Offset(d * 0.265f, d * 0.515f),
    )
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.72f), Color.Transparent),
                center = Offset(d * 0.675f, d * 0.545f),
                radius = d * 0.105f,
            ),
        radius = d * 0.105f,
        center = Offset(d * 0.675f, d * 0.545f),
    )
}

private fun DrawScope.drawCenterDisc(d: Float) {
    val center = Offset(d * 0.50f, d * DiscY)
    val discRadius = d * 0.142f

    // Blue ambient occlusion around the central white disc.
    repeat(6) { index ->
        val radius = discRadius + d * (0.005f + index * 0.004f)
        drawCircle(
            color = Color(0xFF2D79D8).copy(alpha = 0.070f * (1f - index / 6f)),
            radius = radius,
            center = center.copy(y = center.y + d * 0.004f),
        )
    }
    drawCircle(
        brush =
            Brush.radialGradient(
                colorStops =
                    arrayOf(
                        0f to Color.White,
                        0.68f to Color(0xFFF8FBFF),
                        1f to Color(0xFFDCEBFB),
                    ),
                center = Offset(center.x - d * 0.035f, center.y - d * 0.045f),
                radius = discRadius * 1.45f,
            ),
        radius = discRadius,
        center = center,
    )
    drawCircle(
        color = Color(0xFFB7D6F7).copy(alpha = 0.62f),
        radius = discRadius,
        center = center,
        style = Stroke(width = d * 0.0025f),
    )
}

private fun DrawScope.drawWaterTimeline(
    d: Float,
    nowMs: Float,
) {
    val center = Offset(d * 0.50f, d * DiscY)

    // The old play head first collapses into the center, leaving a clear landing target.
    val initialPlay = 1f - smooth(span(nowMs, 0f, IntroMs))
    if (initialPlay > 0f) {
        drawPlayHead(d = d, center = center, scale = initialPlay, alpha = initialPlay)
    }

    // A separate drop appears above the cloud and accelerates under gravity, stretching as it
    // gains speed. It stays fully opaque right up to the impact frame, then flattens against
    // the disc as it hands over to the fragments — without that there is never a frame of
    // contact and the drop just thins out in mid-air.
    val fall = span(nowMs, DropStartMs, DropFallMs)
    val splat = smooth(span(nowMs, ImpactMs, DropSplatMs))
    val dropAlpha = smooth(span(nowMs, DropStartMs, DropFadeInMs)) * (1f - splat)
    if (dropAlpha > 0f) {
        val stretch = smooth(fall)
        drawWaterDrop(
            d = d,
            center =
                Offset(
                    x = center.x + sin(fall * PiF) * d * 0.006f,
                    y = lerp(d * DropStartY, d * DropEndY, fall * fall),
                ),
            scaleX = lerp(lerp(1f, 0.78f, stretch), 1.62f, splat),
            scaleY = lerp(lerp(0.82f, 1.42f, stretch), 0.26f, splat),
            alpha = dropAlpha,
        )
    }

    // Impact ripple on the center disc.
    val ripple = span(nowMs, ImpactMs, RippleMs)
    if (ripple in 0.001f..0.999f) {
        drawCircle(
            color = Color(0xFF4FA7F4).copy(alpha = (1f - smooth(ripple)) * 0.55f),
            radius = d * lerp(0.018f, 0.118f, smooth(ripple)),
            center = center,
            style = Stroke(width = d * 0.004f * (1f - ripple)),
        )
    }

    // The drop bursts radially, pauses at maximum spread, then every fragment reverses course
    // toward a point in a right-facing triangle. The solid mark fades in only as those points
    // meet, so the triangle visibly comes from the water rather than appearing underneath it.
    val explode = smooth(span(nowMs, ImpactMs, ExplodeMs))
    val gather = smooth(span(nowMs, GatherStartMs, GatherMs))
    val fragmentAlpha =
        smooth(span(nowMs, ImpactMs, FragmentInMs)) *
            (1f - smooth(span(nowMs, FragmentOutStartMs, FragmentOutMs)))
    if (fragmentAlpha > 0f) {
        TriangleDropletTargets.forEachIndexed { index, target ->
            val angle = index * (PI * 2.0 / TriangleDropletTargets.size) - PI / 2.0
            val burstRadius = d * (0.048f + (index % 4) * 0.010f) * explode
            val burst =
                Offset(
                    x = center.x + cos(angle).toFloat() * burstRadius,
                    y = center.y + sin(angle).toFloat() * burstRadius,
                )
            val destination =
                Offset(
                    x = center.x + d * target.first,
                    y = center.y + d * target.second,
                )
            val position =
                Offset(
                    x = lerp(burst.x, destination.x, gather),
                    y = lerp(burst.y, destination.y, gather),
                )
            val radius = d * (0.0105f + (index % 3) * 0.0020f) * lerp(1f, 0.78f, gather)
            drawDropletBubble(position, radius, fragmentAlpha)
        }
    }

    val finalPlay = easeOutBack(span(nowMs, PlayHeadStartMs, PlayHeadMs))
    if (finalPlay > 0f) {
        drawPlayHead(
            d = d,
            center = center,
            scale = finalPlay,
            alpha = smooth(span(nowMs, PlayHeadStartMs, PlayHeadFadeMs)),
        )
    }

    val glint = bell(span(nowMs, GlintStartMs, GlintMs))
    if (glint > 0f) {
        drawCircle(
            color = Color.White.copy(alpha = glint * 0.70f),
            radius = d * 0.009f * glint,
            center = Offset(center.x - d * 0.035f, center.y - d * 0.050f),
        )
    }
}

private fun DrawScope.drawPlayHead(
    d: Float,
    center: Offset,
    scale: Float,
    alpha: Float,
) {
    if (scale <= 0f || alpha <= 0f) return
    val path =
        Path().apply {
            moveTo(center.x - d * 0.038f * scale, center.y - d * 0.057f * scale)
            lineTo(center.x + d * 0.055f * scale, center.y)
            lineTo(center.x - d * 0.038f * scale, center.y + d * 0.057f * scale)
            close()
        }
    val brush =
        Brush.linearGradient(
            colors =
                listOf(
                    Color(0xFF6BC5FF).copy(alpha = alpha),
                    Color(0xFF2F91F4).copy(alpha = alpha),
                    Color(0xFF0B6BE5).copy(alpha = alpha),
                ),
            start = Offset(center.x - d * 0.04f, center.y - d * 0.06f),
            end = Offset(center.x + d * 0.055f, center.y + d * 0.06f),
        )
    drawPath(path, brush)
    drawPath(
        path = path,
        brush = brush,
        style =
            Stroke(
                width = d * 0.012f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
    )
}

private fun DrawScope.drawWaterDrop(
    d: Float,
    center: Offset,
    scaleX: Float,
    scaleY: Float,
    alpha: Float,
) {
    val rx = d * 0.048f * scaleX
    val ry = d * 0.048f * scaleY
    val path =
        Path().apply {
            moveTo(center.x, center.y - ry * 1.35f)
            cubicTo(
                center.x - rx * 0.25f,
                center.y - ry * 0.70f,
                center.x - rx * 0.78f,
                center.y - ry * 0.18f,
                center.x - rx * 0.78f,
                center.y + ry * 0.30f,
            )
            cubicTo(
                center.x - rx * 0.78f,
                center.y + ry * 1.08f,
                center.x + rx * 0.78f,
                center.y + ry * 1.08f,
                center.x + rx * 0.78f,
                center.y + ry * 0.30f,
            )
            cubicTo(
                center.x + rx * 0.78f,
                center.y - ry * 0.18f,
                center.x + rx * 0.25f,
                center.y - ry * 0.70f,
                center.x,
                center.y - ry * 1.35f,
            )
            close()
        }
    drawPath(
        path,
        Brush.linearGradient(
            colors =
                listOf(
                    Color(0xFFB9E8FF).copy(alpha = alpha),
                    Color(0xFF55ADF7).copy(alpha = alpha),
                    Color(0xFF176ED1).copy(alpha = alpha),
                ),
            start = Offset(center.x - rx, center.y - ry),
            end = Offset(center.x + rx, center.y + ry),
        ),
    )
    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.75f),
        radius = rx * 0.15f,
        center = Offset(center.x - rx * 0.28f, center.y - ry * 0.35f),
    )
}

private fun DrawScope.drawDropletBubble(
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    if (radius <= 0f) return
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = alpha * 0.92f),
                        Color(0xFF8DD5FF).copy(alpha = alpha * 0.88f),
                        Color(0xFF277ED4).copy(alpha = alpha * 0.82f),
                    ),
                center = Offset(center.x - radius * 0.30f, center.y - radius * 0.35f),
                radius = radius * 1.35f,
            ),
        radius = radius,
        center = center,
    )
}

private val CloudBlue = Color(0xFF318AEF)

// ---- Geometry, in fractions of the mark's smallest dimension. ----

/** Pivot for every squash: the point the cloud rests on. */
private const val CloudBaseY = 0.76f

/** Centre of the white disc the drop lands on. */
private const val DiscY = 0.585f

/** Top of the tallest rear lobe — centre 0.435 minus radius 0.235. */
private const val CloudCrownY = 0.200f

private const val DropStartY = 0.022f
private const val DropEndY = DiscY + 0.006f

/** How far the drop's leading edge sits below its centre around the middle of the fall. */
private const val DropLeadingEdge = 0.055f

// ---- Timeline, in milliseconds from the start of the splash. ----

/** This choreography's own hand-off window; the shared [FadeMs] is the water-fire one. */
private const val CloudDropFadeMs = 260f
private const val IntroMs = 240f

private const val DropStartMs = 220f
private const val DropFallMs = 580f

/** The frame the drop reaches the disc. Every impact effect is timed off this. */
private const val ImpactMs = DropStartMs + DropFallMs

private const val DropFadeInMs = 90f
private const val DropSplatMs = 90f

/**
 * When the drop's leading edge first touches the crown, derived from the fall rather than typed
 * in — a hand-written constant drifts the moment either the trajectory or the cloud is retuned.
 */
private val CrownContactMs: Float =
    run {
        val reach = ((CloudCrownY - DropLeadingEdge) - DropStartY) / (DropEndY - DropStartY)
        DropStartMs + sqrt(reach.coerceIn(0f, 1f)) * DropFallMs
    }

private const val GrazeSpringMs = 380f
private const val HitSpringMs = 720f
private const val AnticipateMs = 130f

/** How far the crown and the sway trail the base. Roughly a sixth and a quarter of a swing. */
private const val CrownLagMs = 62f
private const val SwayLagMs = 100f

private const val RippleMs = 300f
private const val ExplodeMs = 160f
private const val GatherStartMs = 960f
private const val GatherMs = 320f
private const val FragmentInMs = 70f
private const val FragmentOutStartMs = 1_260f
private const val FragmentOutMs = 150f
private const val PlayHeadStartMs = 1_240f
private const val PlayHeadMs = 290f
private const val PlayHeadFadeMs = 180f
private const val GlintStartMs = 1_400f
private const val GlintMs = 200f

private const val WordmarkStartMs = 800f
private const val WordmarkMs = 370f

// ---- Squash amplitudes, as fractions of the cloud's height. ----

/**
 * [cycles] is the perceptual knob, not just a shape one: it sets how long the first compression
 * dwells at its peak. At 1.55 over a 640ms window that quarter-swing lasted 103ms — two or
 * three frames at 60fps, and half that on a phone running an animator duration scale below 1.
 * The squash was measurably there and visibly absent. 1.25 over 720ms holds it for ~144ms and
 * still fits two and a half swings in.
 */
private val HitJelly = Jelly(cycles = 1.25f, damping = 3.4f)
private val GrazeJelly = Jelly(cycles = 1.25f, damping = 5.2f)

private const val HitSquash = 0.200f
private const val GrazeSquash = 0.060f
private const val AnticipateStretch = 0.038f
private const val SwaySpan = 0.12f

/** How much harder the crown compresses than the base. */
private const val CrownSquashBoost = 1.35f

/** Clamps keep a retune of the amplitudes from ever inverting the mark. */
private const val MinScaleY = 0.60f
private const val MaxScaleY = 1.40f

private val TriangleDropletTargets =
    listOf(
        -0.040f to -0.052f,
        -0.040f to -0.026f,
        -0.040f to 0.000f,
        -0.040f to 0.026f,
        -0.040f to 0.052f,
        -0.014f to -0.039f,
        -0.014f to -0.013f,
        -0.014f to 0.013f,
        -0.014f to 0.039f,
        0.012f to -0.026f,
        0.012f to 0.000f,
        0.012f to 0.026f,
        0.038f to -0.013f,
        0.038f to 0.013f,
        0.061f to 0.000f,
    )
