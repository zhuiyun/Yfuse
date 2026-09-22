package com.yfuse.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「水漾成键」 — the drop pools in a sunken well, boils into bubbles, and blows out as spray.
 *
 * Where [SplashCloudDrop] reads the cloud reference at a sprint, this one keeps every beat it
 * has: the play head sinks into a *recessed* well rather than a flat disc, the drop lands and
 * stands as a pool before it breaks up, the bubbles reassemble into the triangle inside the
 * well, and the burst throws droplets clear of the cloud to drift and fade.
 */
internal object SplashCloudWell : SplashChoreography {
    override val durationMs = 2_000f
    override val fadeStartMs = durationMs - CloudWellFadeMs

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) = drawCloudWellMark(nowMs)

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WordmarkStartMs, WordmarkMs))
}

private fun DrawScope.drawCloudWellMark(nowMs: Float) {
    val d = size.minDimension
    val intro = easeOutCubic(span(nowMs, 0f, IntroMs))
    val breathing = sin(nowMs / SplashCloudWell.durationMs * PiF) * 0.007f
    val markScale = 0.965f + intro * 0.035f + breathing
    val pivot = Offset(d * 0.50f, d * CloudBaseY)

    val baseScaleY = (1f - cloudCompression(nowMs)).coerceIn(MinScaleY, MaxScaleY)
    val baseScaleX = squashWidth(baseScaleY)
    // The crown takes the hit harder than the base, so the dome visibly dents into the body
    // instead of the whole silhouette shrinking together.
    val crownCompression = cloudCompression(nowMs, CrownLagMs) * CrownSquashBoost
    val crownScaleY = (1f - crownCompression).coerceIn(MinScaleY, MaxScaleY)
    val crownScaleX = squashWidth(crownScaleY)
    val sway = cloudCompression(nowMs, SwayLagMs) * d * SwaySpan

    withTransform({
        scale(markScale, markScale, pivot = pivot)
        translate(left = sway)
        scale(baseScaleX, baseScaleY, pivot = pivot)
    }) {
        drawCloudShadow(d)
        withTransform({
            scale(crownScaleX / baseScaleX, crownScaleY / baseScaleY, pivot = pivot)
        }) {
            drawCloudCrown(d)
        }
        drawCloudBase(d)
        drawWellStack(d, nowMs)
    }

    // The spray is the one thing that is not attached to the cloud: once the burst throws a
    // droplet clear it flies on its own ballistic arc, so it must not inherit the squash.
    drawSpray(d, nowMs)
}

/** Compression (positive) or stretch (negative), as a fraction of the cloud's height. */
private fun cloudCompression(
    nowMs: Float,
    lagMs: Float = 0f,
): Float {
    val t = nowMs - lagMs
    val graze = GrazeJelly(span(t, CrownContactMs, GrazeSpringMs)) * GrazeSquash
    val hit = HitJelly(span(t, ImpactMs, HitSpringMs)) * HitSquash
    // The burst shoves the cloud a second time, which is what keeps it alive through the spray
    // instead of going rigid the moment the play head appears.
    val recoil = BurstJelly(span(t, BurstMs, BurstSpringMs)) * BurstSquash
    val lift = bell(span(t, ImpactMs - AnticipateMs, AnticipateMs)) * AnticipateStretch
    return graze + hit + recoil - lift
}

private fun DrawScope.drawCloudShadow(d: Float) {
    drawOval(
        brush =
            Brush.radialGradient(
                colors = listOf(Color(0xFF5DAFFD).copy(alpha = 0.20f), Color.Transparent),
                center = Offset(d * 0.50f, d * 0.745f),
                radius = d * 0.33f,
            ),
        topLeft = Offset(d * 0.16f, d * 0.695f),
        size = Size(d * 0.68f, d * 0.115f),
    )
}

/** The blue dome and its right shoulder — softer and higher than 动画1's crown. */
private fun DrawScope.drawCloudCrown(d: Float) {
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFB6E1FE), Color(0xFF7CC0FD), Color(0xFF4E9EF0)),
                start = Offset(d * 0.38f, d * 0.16f),
                end = Offset(d * 0.60f, d * 0.64f),
            ),
        radius = d * 0.245f,
        center = Offset(d * 0.485f, d * 0.415f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFF93CDFD), Color(0xFF5DAFFD), Color(0xFF3F86D0)),
                start = Offset(d * 0.64f, d * 0.34f),
                end = Offset(d * 0.79f, d * 0.70f),
            ),
        radius = d * 0.175f,
        center = Offset(d * 0.715f, d * 0.520f),
    )
}

/** Front base joins the pale lobes into one puffy silhouette. */
private fun DrawScope.drawCloudBase(d: Float) {
    drawRoundRect(
        brush =
            Brush.verticalGradient(
                colors = listOf(Color(0xFFFDFEFF), Color(0xFFEDF4FD), Color(0xFFD8E6F8)),
                startY = d * 0.52f,
                endY = d * 0.80f,
            ),
        topLeft = Offset(d * 0.185f, d * 0.575f),
        size = Size(d * 0.645f, d * 0.205f),
        cornerRadius = CornerRadius(d * 0.102f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFF0F6FE), Color(0xFFD2E2F7)),
                start = Offset(d * 0.17f, d * 0.41f),
                end = Offset(d * 0.38f, d * 0.77f),
            ),
        radius = d * 0.190f,
        center = Offset(d * 0.300f, d * 0.590f),
    )
    drawCircle(
        brush =
            Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFF2F7FE), Color(0xFFD6E5F8)),
                start = Offset(d * 0.67f, d * 0.48f),
                end = Offset(d * 0.80f, d * 0.78f),
            ),
        radius = d * 0.172f,
        center = Offset(d * 0.716f, d * 0.607f),
    )

    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.85f), Color.Transparent),
                center = Offset(d * 0.250f, d * 0.500f),
                radius = d * 0.130f,
            ),
        radius = d * 0.130f,
        center = Offset(d * 0.250f, d * 0.500f),
    )
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.70f), Color.Transparent),
                center = Offset(d * 0.680f, d * 0.540f),
                radius = d * 0.110f,
            ),
        radius = d * 0.110f,
        center = Offset(d * 0.680f, d * 0.540f),
    )
}

/** Everything that happens inside the well, in back-to-front order. */
private fun DrawScope.drawWellStack(
    d: Float,
    nowMs: Float,
) {
    val centre = Offset(d * 0.50f, d * WellY)
    val radius = d * WellRadius

    // The well reads as a hollow carved into the cloud right up until the play head resolves,
    // when it fills in and becomes the solid white disc of the finished logo.
    val solid = smooth(span(nowMs, DiscSolidStartMs, DiscSolidMs))
    drawWell(d, centre, radius, solid)

    // The drop lands and stands as a pool before anything breaks it up. That held beat is what
    // makes the burst afterwards feel like a reaction rather than a cut.
    val pool =
        smooth(span(nowMs, ImpactMs, PoolMs)) *
            (1f - smooth(span(nowMs, BoilStartMs, BoilMs)))
    if (pool > 0.004f) drawPool(centre, radius, pool)

    drawFallingDrop(d, centre, nowMs)

    val ripple = span(nowMs, ImpactMs, RippleMs)
    if (ripple in 0.001f..0.999f) {
        drawCircle(
            color = Color(0xFF62B2FA).copy(alpha = (1f - smooth(ripple)) * 0.5f),
            radius = radius * lerp(0.14f, 0.98f, smooth(ripple)),
            center = centre,
            style = Stroke(width = d * 0.0045f * (1f - ripple)),
        )
    }

    drawWellBubbles(d, centre, radius, nowMs)
    drawPlayHead(d, centre, nowMs)
    drawDiscGlint(d, centre, radius, nowMs)
}

/**
 * The sunken well. Compose has no blur inside a [DrawScope], so the soft inner shadow along the
 * top lip and the bounce light along the bottom are each built from a short stack of arcs with
 * falling alpha — cheap, and it holds up at every size the mark is drawn at.
 */
private fun DrawScope.drawWell(
    d: Float,
    centre: Offset,
    radius: Float,
    solid: Float,
) {
    val hollow = 1f - solid

    // Occlusion just outside the lip, so the hollow sits *in* the cloud rather than on it.
    repeat(5) { index ->
        drawCircle(
            color = Color(0xFF7FA9DC).copy(alpha = 0.038f * (1f - index / 5f) * hollow),
            radius = radius + d * (0.004f + index * 0.005f),
            center = centre.copy(y = centre.y + d * 0.003f),
        )
    }

    // Interior: dark at the top where the lip overhangs, bright at the bottom where light
    // bounces back up out of the bowl. That single inversion is what reads as "concave" — but
    // it has to stay light and blue. Taking the top end too dark turns the well into a grey
    // plate sitting on the cloud instead of a hollow cut into it.
    drawCircle(
        brush =
            Brush.verticalGradient(
                colors = listOf(Color(0xFFC3D9F2), Color(0xFFDCE8F7), Color(0xFFEFF5FD)),
                startY = centre.y - radius,
                endY = centre.y + radius,
            ),
        radius = radius,
        center = centre,
        alpha = hollow,
    )
    // The finished disc fades in over the top of it.
    drawCircle(
        brush =
            Brush.radialGradient(
                colorStops =
                    arrayOf(
                        0f to Color.White,
                        0.70f to Color(0xFFFAFCFF),
                        1f to Color(0xFFDFEBFB),
                    ),
                center = Offset(centre.x - radius * 0.28f, centre.y - radius * 0.32f),
                radius = radius * 1.45f,
            ),
        radius = radius,
        center = centre,
        alpha = solid,
    )

    if (hollow > 0.004f) {
        val arcTopLeft = Offset(centre.x - radius, centre.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        repeat(4) { index ->
            val inset = radius * (0.03f + index * 0.055f)
            drawArc(
                color = Color(0xFF9DC0E8).copy(alpha = 0.21f * (1f - index / 4f) * hollow),
                startAngle = 186f,
                sweepAngle = 168f,
                useCenter = false,
                topLeft = arcTopLeft + Offset(inset, inset),
                size = Size(arcSize.width - inset * 2f, arcSize.height - inset * 2f),
                style = Stroke(width = radius * 0.13f, cap = StrokeCap.Round),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.30f * (1f - index / 4f) * hollow),
                startAngle = 26f,
                sweepAngle = 128f,
                useCenter = false,
                topLeft = arcTopLeft + Offset(inset, inset),
                size = Size(arcSize.width - inset * 2f, arcSize.height - inset * 2f),
                style = Stroke(width = radius * 0.11f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Water standing in the bowl, clipped to the well so it cannot spill over the lip. */
private fun DrawScope.drawPool(
    centre: Offset,
    radius: Float,
    level: Float,
) {
    val surfaceY = lerp(centre.y + radius * 0.92f, centre.y - radius * 0.42f, level)
    val wellPath =
        Path().apply {
            addOval(Rect(centre.x - radius, centre.y - radius, centre.x + radius, centre.y + radius))
        }
    clipPath(wellPath) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color(0xFFA9D6FA).copy(alpha = 0.92f),
                            Color(0xFF6FB6F6).copy(alpha = 0.88f),
                        ),
                    startY = surfaceY,
                    endY = centre.y + radius,
                ),
            topLeft = Offset(centre.x - radius, surfaceY),
            size = Size(radius * 2f, centre.y + radius - surfaceY),
        )
        // Meniscus: the surface is an ellipse, not a straight cut, and it catches a highlight.
        val halfWidth =
            radius *
                sqrt(
                    (1f - ((surfaceY - centre.y) / radius).let { it * it })
                        .coerceAtLeast(0f),
                )
        drawOval(
            color = Color(0xFFCDE8FE).copy(alpha = 0.85f),
            topLeft = Offset(centre.x - halfWidth, surfaceY - radius * 0.10f),
            size = Size(halfWidth * 2f, radius * 0.20f),
        )
        drawOval(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(centre.x - halfWidth * 0.45f, surfaceY - radius * 0.055f),
            size = Size(halfWidth * 0.55f, radius * 0.07f),
        )
    }
}

private fun DrawScope.drawFallingDrop(
    d: Float,
    centre: Offset,
    nowMs: Float,
) {
    val fall = span(nowMs, DropStartMs, DropFallMs)
    val merge = smooth(span(nowMs, ImpactMs, DropMergeMs))
    val alpha = smooth(span(nowMs, DropStartMs, DropFadeInMs)) * (1f - merge)
    if (alpha <= 0.004f) return

    val stretch = smooth(fall)
    // Gravity, so the last third of the fall is much faster than the first.
    val y = lerp(d * DropStartY, centre.y + d * 0.004f, fall * fall)
    drawWaterDrop(
        d = d,
        centre = Offset(centre.x + sin(fall * PiF) * d * 0.005f, y),
        scaleX = lerp(lerp(1f, 0.76f, stretch), 1.55f, merge),
        scaleY = lerp(lerp(0.84f, 1.46f, stretch), 0.30f, merge),
        alpha = alpha,
    )
}

/**
 * The pool boils into bubbles that fill the well, then every bubble walks to a point inside a
 * right-facing triangle. The solid play head only fades in once they have arrived, so the mark
 * is visibly made of the water rather than revealed beneath it.
 */
private fun DrawScope.drawWellBubbles(
    d: Float,
    centre: Offset,
    radius: Float,
    nowMs: Float,
) {
    val boil = smooth(span(nowMs, BoilStartMs, BoilMs))
    val gather = smooth(span(nowMs, GatherStartMs, GatherMs))
    val alpha =
        smooth(span(nowMs, BoilStartMs, BubbleFadeInMs)) *
            (1f - smooth(span(nowMs, BubbleOutStartMs, BubbleOutMs)))
    if (alpha <= 0.004f) return

    TriangleFill.forEachIndexed { index, target ->
        // Scattered start inside the bowl, biased low because that is where the pool was.
        val angle = scatter(index, 11) * Tau
        val spread = radius * (0.18f + 0.62f * scatter(index, 12)) * boil
        val start =
            Offset(
                x = centre.x + cos(angle) * spread,
                y = centre.y + sin(angle) * spread * 0.78f + radius * 0.22f * (1f - boil),
            )
        val destination = Offset(centre.x + d * target.first, centre.y + d * target.second)
        val position =
            Offset(
                x = lerp(start.x, destination.x, gather),
                y = lerp(start.y, destination.y, gather),
            )
        val size = radius * lerp(0.085f, 0.155f, scatter(index, 13)) * lerp(1f, 0.74f, gather)
        drawBubble(position, size, alpha)
    }
}

private fun DrawScope.drawPlayHead(
    d: Float,
    centre: Offset,
    nowMs: Float,
) {
    val collapse = 1f - smooth(span(nowMs, 0f, IntroMs))
    if (collapse > 0.004f) drawPlayGlyph(d, centre, collapse, collapse)

    val grow = easeOutBack(span(nowMs, PlayHeadStartMs, PlayHeadMs))
    if (grow > 0.004f) {
        drawPlayGlyph(d, centre, grow, smooth(span(nowMs, PlayHeadStartMs, PlayHeadFadeMs)))
    }
}

/** Rounded-corner triangle. The round join on a fat stroke is what softens the points. */
private fun DrawScope.drawPlayGlyph(
    d: Float,
    centre: Offset,
    scale: Float,
    alpha: Float,
) {
    if (scale <= 0f || alpha <= 0f) return
    val path =
        Path().apply {
            moveTo(centre.x - d * 0.040f * scale, centre.y - d * 0.058f * scale)
            lineTo(centre.x + d * 0.058f * scale, centre.y)
            lineTo(centre.x - d * 0.040f * scale, centre.y + d * 0.058f * scale)
            close()
        }
    val brush =
        Brush.linearGradient(
            colors =
                listOf(
                    Color(0xFF8ACBFE).copy(alpha = alpha),
                    Color(0xFF4AA3FD).copy(alpha = alpha),
                    Color(0xFF2E86EE).copy(alpha = alpha),
                ),
            start = Offset(centre.x - d * 0.045f, centre.y - d * 0.060f),
            end = Offset(centre.x + d * 0.058f, centre.y + d * 0.060f),
        )
    drawPath(path, brush)
    drawPath(
        path = path,
        brush = brush,
        style =
            Stroke(
                width = d * 0.021f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
    )
}

private fun DrawScope.drawDiscGlint(
    d: Float,
    centre: Offset,
    radius: Float,
    nowMs: Float,
) {
    val glint = bell(span(nowMs, GlintStartMs, GlintMs))
    if (glint <= 0f) return
    drawArc(
        color = Color.White.copy(alpha = glint * 0.85f),
        startAngle = 196f,
        sweepAngle = 62f,
        useCenter = false,
        topLeft = Offset(centre.x - radius * 0.82f, centre.y - radius * 0.82f),
        size = Size(radius * 1.64f, radius * 1.64f),
        style = Stroke(width = d * 0.006f * glint, cap = StrokeCap.Round),
    )
}

/**
 * Droplets thrown clear of the cloud. Each one keeps a constant outward velocity and picks up
 * gravity, so the spray arcs over and rains down instead of radiating as a flat star, and the
 * staggered launch keeps it from reading as one ring.
 */
private fun DrawScope.drawSpray(
    d: Float,
    nowMs: Float,
) {
    val progress = span(nowMs, BurstMs, SprayMs)
    if (progress <= 0f) return
    val origin = Offset(d * 0.50f, d * WellY)

    repeat(SprayCount) { index ->
        val launch = scatter(index, 21) * 0.26f
        val travelled = ((progress - launch) / (1f - launch)).coerceIn(0f, 1f)
        if (travelled <= 0f) return@repeat

        val angle = scatter(index, 22) * Tau
        val reach = d * lerp(0.34f, 1.05f, scatter(index, 23))
        val position =
            Offset(
                x = origin.x + cos(angle) * reach * travelled,
                y =
                    origin.y + sin(angle) * reach * travelled * 0.82f +
                        d * SprayGravity * travelled * travelled,
            )
        val radius = d * lerp(0.0055f, 0.0185f, scatter(index, 24)) * lerp(1f, 0.68f, travelled)
        val alpha = smooth((travelled / 0.10f).coerceAtMost(1f)) * (1f - smooth(travelled))
        drawBubble(position, radius, alpha)
    }
}

private fun DrawScope.drawWaterDrop(
    d: Float,
    centre: Offset,
    scaleX: Float,
    scaleY: Float,
    alpha: Float,
) {
    val rx = d * 0.046f * scaleX
    val ry = d * 0.046f * scaleY
    val path =
        Path().apply {
            moveTo(centre.x, centre.y - ry * 1.38f)
            cubicTo(
                centre.x - rx * 0.24f,
                centre.y - ry * 0.72f,
                centre.x - rx * 0.80f,
                centre.y - ry * 0.18f,
                centre.x - rx * 0.80f,
                centre.y + ry * 0.30f,
            )
            cubicTo(
                centre.x - rx * 0.80f,
                centre.y + ry * 1.10f,
                centre.x + rx * 0.80f,
                centre.y + ry * 1.10f,
                centre.x + rx * 0.80f,
                centre.y + ry * 0.30f,
            )
            cubicTo(
                centre.x + rx * 0.80f,
                centre.y - ry * 0.18f,
                centre.x + rx * 0.24f,
                centre.y - ry * 0.72f,
                centre.x,
                centre.y - ry * 1.38f,
            )
            close()
        }
    drawPath(
        path,
        Brush.linearGradient(
            colors =
                listOf(
                    Color(0xFFCDE8FE).copy(alpha = alpha),
                    Color(0xFF7CC0FD).copy(alpha = alpha),
                    Color(0xFF3F86D0).copy(alpha = alpha),
                ),
            start = Offset(centre.x - rx, centre.y - ry),
            end = Offset(centre.x + rx, centre.y + ry),
        ),
    )
    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.80f),
        radius = rx * 0.17f,
        center = Offset(centre.x - rx * 0.30f, centre.y - ry * 0.36f),
    )
}

private fun DrawScope.drawBubble(
    centre: Offset,
    radius: Float,
    alpha: Float,
) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = alpha * 0.95f),
                        Color(0xFFA9D6FA).copy(alpha = alpha * 0.88f),
                        Color(0xFF4E9EF0).copy(alpha = alpha * 0.80f),
                    ),
                center = Offset(centre.x - radius * 0.32f, centre.y - radius * 0.36f),
                radius = radius * 1.4f,
            ),
        radius = radius,
        center = centre,
    )
}

// ---- Geometry, in fractions of the mark's smallest dimension. ----

private const val CloudBaseY = 0.765f
private const val WellY = 0.578f
private const val WellRadius = 0.148f
private const val CloudCrownY = 0.170f
private const val DropStartY = 0.020f
private const val DropLeadingEdge = 0.054f

/** Points filling a right-facing triangle, so the bubbles can reassemble into the play head. */
private val TriangleFill: List<Pair<Float, Float>> =
    run {
        val columns = 6
        val points = mutableListOf<Pair<Float, Float>>()
        for (column in 0 until columns) {
            val ratio = column / (columns - 1f)
            val x = lerp(-0.040f, 0.060f, ratio)
            val halfHeight = lerp(0.056f, 0.004f, ratio)
            val rows = columns - column
            for (row in 0 until rows) {
                val y = if (rows == 1) 0f else lerp(-halfHeight, halfHeight, row / (rows - 1f))
                points += x to y
            }
        }
        points
    }

// ---- Timeline, in milliseconds. Reference beats at 0.4x. ----

/** This choreography's own hand-off window; the shared [FadeMs] is the water-fire one. */
private const val CloudWellFadeMs = 240f
private const val IntroMs = 200f

private const val DropStartMs = 380f
private const val DropFallMs = 440f

/** The frame the drop reaches the well. Every reaction is timed off this. */
private const val ImpactMs = DropStartMs + DropFallMs

private const val DropFadeInMs = 80f
private const val DropMergeMs = 100f
private const val PoolMs = 120f
private const val RippleMs = 280f

private const val BoilStartMs = 940f
private const val BoilMs = 240f
private const val BubbleFadeInMs = 70f
private const val GatherStartMs = 1_180f
private const val GatherMs = 280f
private const val BubbleOutStartMs = 1_400f
private const val BubbleOutMs = 150f

/**
 * The burst: play head resolves and the spray leaves the cloud on the same frame.
 *
 * [SprayMs] has to finish its visible spread *before* the cross-fade starts, not after. A
 * longer, more reference-faithful throw put the whole payoff behind the fade — the droplets
 * were still bunched around the disc at the last frame anyone sees.
 */
private const val BurstMs = 1_390f
private const val SprayMs = 560f
private const val SprayCount = 44
private const val SprayGravity = 0.34f

private const val PlayHeadStartMs = 1_420f
private const val PlayHeadMs = 300f
private const val PlayHeadFadeMs = 190f
private const val DiscSolidStartMs = 1_380f
private const val DiscSolidMs = 260f
private const val GlintStartMs = 1_620f
private const val GlintMs = 220f

private const val WordmarkStartMs = 1_180f
private const val WordmarkMs = 360f

/** Derived, not typed in, so retuning the fall cannot desynchronise the crown from the drop. */
private val CrownContactMs: Float =
    run {
        val target = CloudCrownY - DropLeadingEdge
        val reach = (target - DropStartY) / ((WellY + 0.004f) - DropStartY)
        DropStartMs + sqrt(reach.coerceIn(0f, 1f)) * DropFallMs
    }

// ---- Squash. Stronger and longer-lived than 动画1: the reference cloud is softer. ----

/** See 动画1: [Jelly.cycles] governs how long the peak compression is actually on screen. */
private val HitJelly = Jelly(cycles = 1.25f, damping = 3.3f)
private val GrazeJelly = Jelly(cycles = 1.25f, damping = 5.2f)
private val BurstJelly = Jelly(cycles = 1.15f, damping = 4.0f)

private const val GrazeSpringMs = 340f
private const val HitSpringMs = 720f
private const val BurstSpringMs = 560f
private const val AnticipateMs = 120f

private const val HitSquash = 0.215f
private const val GrazeSquash = 0.065f
private const val BurstSquash = 0.075f
private const val AnticipateStretch = 0.040f
private const val SwaySpan = 0.13f
private const val CrownLagMs = 70f
private const val SwayLagMs = 110f

/** How much harder the crown compresses than the base. */
private const val CrownSquashBoost = 1.35f

private const val MinScaleY = 0.60f
private const val MaxScaleY = 1.40f
