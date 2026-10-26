package com.yfuse.feature.widgets

import com.yfuse.core.widget.WidgetStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun kineticTargetIndex(
    position: Float,
    velocity: Float,
    stepPixels: Float,
    lastIndex: Int,
): Int {
    if (lastIndex <= 0) return 0
    val projected = (velocity / stepPixels.coerceAtLeast(1f) * .10f).coerceIn(-1f, 1f)
    return (position - projected).roundToInt().coerceIn(0, lastIndex)
}

/** Coordinates are fractions of the artwork stage width, before its safety inset. */
internal data class KineticPosterFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val turn: Float = 0f,
    val tilt: Float = 0f,
    val opacity: Float = 1f,
)

internal fun kineticStageRatio(style: WidgetStyle): Float =
    when (style) {
        WidgetStyle.LayeredPoster -> .67f
        WidgetStyle.ArcPoster -> .60f
        WidgetStyle.DepthGallery -> .79f
        WidgetStyle.Accordion -> .61f
        WidgetStyle.BookSpine -> .60f
        WidgetStyle.FoldingGallery -> .62f
        else -> .67f
    }

/** Piecewise interpolation keeps adjacent films on the same continuous gesture timeline. */
internal fun kineticPosterFrame(
    style: WidgetStyle,
    distance: Float,
): KineticPosterFrame {
    val lower = floor(distance).toInt()
    val fraction = distance - lower
    val from = kineticKeyFrame(style, lower)
    val to = kineticKeyFrame(style, lower + 1)

    fun blend(
        a: Float,
        b: Float,
    ): Float = a + (b - a) * fraction
    return KineticPosterFrame(
        x = blend(from.x, to.x),
        y = blend(from.y, to.y),
        width = blend(from.width, to.width),
        height = blend(from.height, to.height),
        turn = blend(from.turn, to.turn),
        tilt = blend(from.tilt, to.tilt),
        opacity = blend(from.opacity, to.opacity).coerceIn(0f, 1f),
    )
}

private fun kineticKeyFrame(
    style: WidgetStyle,
    offset: Int,
): KineticPosterFrame {
    val side = if (offset < 0) -1f else 1f
    val distance = abs(offset)
    return when (style) {
        WidgetStyle.LayeredPoster -> {
            val width =
                when (distance) {
                    0 -> .42f
                    1 -> .34f
                    2 -> .28f
                    else -> .16f
                }
            val center =
                .5f + side *
                    when (distance) {
                        0 -> 0f
                        1 -> .20f
                        2 -> .35f
                        else -> .43f
                    }
            KineticPosterFrame(
                center - width / 2,
                .05f * distance.coerceAtMost(3),
                width,
                width * 1.5f,
                tilt = side * distance.coerceAtMost(3) * 5f,
                opacity =
                    when (distance) {
                        0 -> 1f
                        1 -> .94f
                        2 -> .78f
                        else -> 0f
                    },
            )
        }
        WidgetStyle.ArcPoster -> {
            val width =
                when (distance) {
                    0 -> .37f
                    1 -> .33f
                    2 -> .25f
                    else -> .12f
                }
            val center =
                .5f + side *
                    when (distance) {
                        0 -> 0f
                        1 -> .215f
                        2 -> .36f
                        else -> .45f
                    }
            KineticPosterFrame(
                center - width / 2,
                when (distance) {
                    0 -> 0f
                    1 -> .035f
                    else -> .08f
                },
                width,
                width * 1.5f,
                tilt =
                    side *
                        when (distance) {
                            0 -> 0f
                            1 -> 9f
                            else -> 18f
                        },
                opacity = if (distance <= 2) 1f else 0f,
            )
        }
        WidgetStyle.DepthGallery ->
            when (offset) {
                0 -> KineticPosterFrame(.015f, .16f, .41f, .615f)
                1 -> KineticPosterFrame(.32f, .09f, .31f, .465f, turn = -12f)
                2 -> KineticPosterFrame(.55f, .04f, .23f, .345f, turn = -17f, opacity = .94f)
                3 -> KineticPosterFrame(.72f, .015f, .165f, .2475f, turn = -21f, opacity = .86f)
                4 -> KineticPosterFrame(.855f, 0f, .12f, .18f, turn = -24f, opacity = .74f)
                else ->
                    if (offset < 0) {
                        KineticPosterFrame(.015f, .26f, .32f, .48f, turn = 8f, opacity = 0f)
                    } else {
                        KineticPosterFrame(.91f, 0f, .08f, .12f, turn = -28f, opacity = 0f)
                    }
            }
        WidgetStyle.Accordion ->
            when (offset) {
                -1 -> KineticPosterFrame(-.015f, 0f, .15f, .60f)
                0 -> KineticPosterFrame(.155f, 0f, .50f, .60f)
                1 -> KineticPosterFrame(.675f, 0f, .15f, .60f)
                2 -> KineticPosterFrame(.845f, 0f, .15f, .60f)
                else -> KineticPosterFrame(if (offset < 0) -.015f else .915f, 0f, .08f, .60f, opacity = 0f)
            }
        WidgetStyle.BookSpine -> {
            val width =
                when (distance) {
                    0 -> .32f
                    1, 2 -> .12f
                    else -> .08f
                }
            val center =
                .5f + side *
                    when (distance) {
                        0 -> 0f
                        1 -> .25f
                        2 -> .41f
                        else -> .45f
                    }
            KineticPosterFrame(
                center - width / 2,
                .025f * distance.coerceAtMost(3),
                width,
                .58f - .025f * distance.coerceAtMost(3),
                turn = -side * if (distance == 0) 0f else 15f,
                opacity = if (distance <= 2) 1f else 0f,
            )
        }
        WidgetStyle.FoldingGallery -> {
            // Projected hinge edges meet after rotation (cos 45° / cos 60°).
            val width =
                when (distance) {
                    0 -> .30f
                    1 -> .28f
                    2 -> .22f
                    else -> .12f
                }
            val center =
                .5f + side *
                    when (distance) {
                        0 -> 0f
                        1 -> .249f
                        2 -> .403f
                        else -> .46f
                    }
            KineticPosterFrame(
                center - width / 2,
                if (distance <= 1) .02f else .05f,
                width,
                if (distance <= 1) .57f else .51f,
                turn =
                    side *
                        when (distance) {
                            0 -> 0f
                            1 -> 45f
                            else -> -60f
                        },
                opacity = if (distance <= 2) 1f else 0f,
            )
        }
        else -> KineticPosterFrame(.29f, 0f, .42f, .63f, opacity = if (offset == 0) 1f else 0f)
    }
}
