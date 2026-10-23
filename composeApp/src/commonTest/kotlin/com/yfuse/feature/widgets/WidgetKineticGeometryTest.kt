package com.yfuse.feature.widgets

import com.yfuse.core.widget.WidgetStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetKineticGeometryTest {
    private val styles =
        listOf(
            WidgetStyle.LayeredPoster,
            WidgetStyle.Accordion,
            WidgetStyle.ArcPoster,
            WidgetStyle.BookSpine,
            WidgetStyle.DepthGallery,
            WidgetStyle.FoldingGallery,
        )

    @Test
    fun rotated_covers_and_shadows_remain_inside_the_stage_throughout_a_swipe() {
        for (style in styles) {
            for (stageWidth in listOf(180f, 320f, 480f)) {
                val artworkWidth = stageWidth - 48f
                val stageHeight = artworkWidth * kineticStageRatio(style) + 48f
                for (step in -300..500) {
                    val distance = step / 100f
                    val frame = kineticPosterFrame(style, distance)
                    if (frame.opacity <= .015f) continue
                    assertTrue(frame.width > 0f && frame.height > 0f)
                    val centerX = 24f + artworkWidth * (frame.x + frame.width / 2)
                    val centerY = 24f + artworkWidth * (frame.y + frame.height / 2)
                    val turn = frame.turn * PI / 180
                    val tilt = frame.tilt * PI / 180
                    val shadow = if (abs(distance) < .5f) 6f else 2f
                    for (cornerX in listOf(-.5f, .5f)) {
                        for (cornerY in listOf(-.5f, .5f)) {
                            // Include the design system's focus scale and this stage's perspective.
                            val x = artworkWidth * frame.width * cornerX * 1.04f
                            val y = artworkWidth * frame.height * cornerY * 1.04f
                            val rotatedX = x * cos(turn)
                            val camera = artworkWidth * frame.width * 12f
                            val perspective = camera / (camera - x * sin(turn))
                            val projectedX = centerX + (rotatedX * cos(tilt) - y * sin(tilt)) * perspective
                            val projectedY = centerY + (rotatedX * sin(tilt) + y * cos(tilt)) * perspective
                            val label = "$style at $distance in $stageWidth dp"
                            assertTrue(projectedX - shadow >= 0, "$label clips left")
                            assertTrue(projectedX + shadow <= stageWidth, "$label clips right")
                            assertTrue(projectedY - shadow >= 0, "$label clips top")
                            assertTrue(projectedY + shadow <= stageHeight, "$label reaches the next component")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun depth_gallery_uses_distinct_positions_and_progressively_smaller_real_neighbours() {
        val frames = (0..4).map { kineticPosterFrame(WidgetStyle.DepthGallery, it.toFloat()) }
        assertEquals(5, frames.map { it.x }.distinct().size)
        frames.zipWithNext().forEach { (front, back) ->
            assertTrue(front.width > back.width)
            assertTrue(front.height > back.height)
            assertTrue(front.x < back.x)
            assertTrue(front.y > back.y)
        }
    }

    @Test
    fun releasing_a_drag_snaps_to_the_nearest_film_and_fast_fling_advances_without_skipping_the_collection() {
        assertEquals(1, kineticTargetIndex(1.49f, 0f, 150f, 8))
        assertEquals(2, kineticTargetIndex(1.51f, 0f, 150f, 8))
        assertEquals(3, kineticTargetIndex(2.1f, -10_000f, 150f, 8))
        assertEquals(1, kineticTargetIndex(2.1f, 10_000f, 150f, 8))
        assertEquals(0, kineticTargetIndex(0f, 10_000f, 150f, 8))
        assertEquals(8, kineticTargetIndex(8f, -10_000f, 150f, 8))
    }

    @Test
    fun empty_single_and_shrinking_collections_cannot_snap_to_missing_items() {
        assertEquals(0, kineticTargetIndex(18f, -10_000f, 150f, -1))
        assertEquals(0, kineticTargetIndex(18f, -10_000f, 150f, 0))
        assertEquals(2, kineticTargetIndex(18f, -10_000f, 150f, 2))
        assertEquals(0, kineticTargetIndex(-2f, 10_000f, 150f, 2))
    }
}
