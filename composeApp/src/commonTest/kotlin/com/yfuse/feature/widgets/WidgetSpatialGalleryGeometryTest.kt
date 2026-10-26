package com.yfuse.feature.widgets

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetSpatialGalleryGeometryTest {
    @Test
    fun refreshed_gallery_retains_the_selected_id_and_clamps_removed_items() {
        assertEquals(0f, galleryRestoredPosition(1.2f, listOf("a", "b", "c"), listOf("b", "c", "a")))
        assertEquals(2f, galleryRestoredPosition(.8f, listOf("a", "b", "c"), listOf("c", "a", "b")))
        assertEquals(1f, galleryRestoredPosition(2f, listOf("a", "b", "c"), listOf("a", "b")))
        assertEquals(0f, galleryRestoredPosition(2f, listOf("a", "b", "c"), emptyList()))
        assertEquals(0f, galleryRestoredPosition(Float.NaN, emptyList(), listOf("a", "b")))
        assertEquals(0f, galleryRestoredPosition(Float.POSITIVE_INFINITY, listOf("a"), listOf("a", "b")))
        assertEquals(1f, galleryRestoredPosition(100f, emptyList(), listOf("a", "b")))
        assertEquals(0f, galleryRestoredPosition(-10f, emptyList(), listOf("a", "b")))
    }

    @Test
    fun prism_surfaces_share_real_cube_edges_throughout_rotation() {
        for (step in 0..700) {
            val faces = prismGalleryFaces(step / 100f, 8)
            val sides = faces.filterNot { it.top }
            val top = faces.single { it.top }
            assertTrue(sides.size in 1..2)
            for (side in sides) {
                assertTrue(top.corners.any { near(it, side.corners[0]) }, "top separates at $step")
                assertTrue(top.corners.any { near(it, side.corners[1]) }, "top separates at $step")
                // Orthographic mapping must stay affine for the real poster texture.
                assertNear(side.corners[0].x + side.corners[2].x, side.corners[1].x + side.corners[3].x)
                assertNear(side.corners[0].y + side.corners[2].y, side.corners[1].y + side.corners[3].y)
            }
            if (sides.size == 2) {
                assertEquals(2, sides[0].corners.count { first -> sides[1].corners.any { near(first, it) } })
            }
        }
    }

    @Test
    fun prism_does_not_jump_at_item_boundaries_and_has_no_stage_overflow() {
        for (boundary in 1..6) {
            val before = prismGalleryFaces(boundary - .0001f, 8)
            val after = prismGalleryFaces(boundary + .0001f, 8)
            for (face in before) {
                val matching = after.single { if (face.top) it.top else !it.top && it.index == face.index }
                assertEquals(face.index, matching.index, "artwork must not reset at an integer position")
                face.corners.zip(matching.corners).forEach { (a, b) ->
                    assertTrue(hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < .001)
                }
            }
        }
        for (step in 0..700) {
            val faces = prismGalleryFaces(step / 100f, 8)
            assertEquals(faces.map { it.depth }.sorted(), faces.map { it.depth })
            faces.forEach { face ->
                assertTrue(face.index in 0 until 8)
                assertTrue(face.shade in 0f..1f)
                face.corners.forEach { point ->
                    assertTrue(point.x in 0.07f..0.93f, "horizontal overflow at $step")
                    assertTrue(point.y in 0.005f..0.975f, "vertical overflow at $step: $point")
                }
            }
            val sides = faces.filterNot { it.top || it.bottom }
            assertEquals(sides.size, sides.map { it.index }.distinct().size)
        }
    }

    @Test
    fun prism_empty_single_clamped_positions_and_rtl_remain_valid() {
        assertTrue(prismGalleryFaces(0f, 0).isEmpty())
        assertTrue(prismGalleryFaces(0f, -1).isEmpty())
        for (count in 1..5) {
            for (position in listOf(-100f, 0f, .6f, 2.1f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val left = prismGalleryFaces(position, count)
                val right = prismGalleryFaces(position, count, rtl = true)
                assertEquals(left.size, right.size)
                left.zip(right).forEach { (ltr, rtl) ->
                    assertTrue(ltr.index in 0 until count)
                    assertEquals(ltr.index, rtl.index)
                    ltr.corners.zip(rtl.corners).forEach { (a, b) ->
                        assertNear(1f - a.x, b.x)
                        assertNear(a.y, b.y)
                    }
                }
            }
        }
        assertEquals(prismGalleryFaces(0f, 4), prismGalleryFaces(-100f, 4))
        assertEquals(prismGalleryFaces(3f, 4), prismGalleryFaces(100f, 4))
    }

    @Test
    fun prism_stays_closed_bounded_and_affine_through_complete_vertical_turns() {
        for (yawStep in 0..70) {
            for (pitch in -360..360 step 15) {
                val faces = prismGalleryFaces(yawStep / 10f, 8, pitchDegrees = pitch.toFloat())
                assertTrue(faces.size in 1..3)
                assertEquals(faces.map { it.depth }.sorted(), faces.map { it.depth })
                val sides = faces.filterNot { it.top || it.bottom }
                val cap = faces.singleOrNull { it.top || it.bottom }
                faces.forEach { face ->
                    assertTrue(face.index in 0..7)
                    face.corners.forEach { point ->
                        assertTrue(point.x in 0.07f..0.93f)
                        assertTrue(point.y in 0.03f..0.91f)
                    }
                    assertNear(face.corners[0].x + face.corners[2].x, face.corners[1].x + face.corners[3].x)
                    assertNear(face.corners[0].y + face.corners[2].y, face.corners[1].y + face.corners[3].y)
                    val center =
                        GalleryPoint(
                            face.corners.sumOf { it.x.toDouble() }.toFloat() / 4,
                            face.corners.sumOf { it.y.toDouble() }.toFloat() / 4,
                        )
                    assertTrue(galleryQuadContains(face.corners, center))
                }
                if (cap != null) {
                    for (side in sides) {
                        val edge = if (cap.top) listOf(0, 1) else listOf(2, 3)
                        edge.forEach { vertex -> assertTrue(cap.corners.any { near(it, side.corners[vertex]) }) }
                    }
                }
                if (sides.size == 2) {
                    assertEquals(2, sides[0].corners.count { a -> sides[1].corners.any { near(a, it) } })
                }
                val mirrored = prismGalleryFaces(yawStep / 10f, 8, rtl = true, pitchDegrees = pitch.toFloat())
                faces.zip(mirrored).forEach { (left, right) ->
                    assertEquals(left.index, right.index)
                    left.corners.zip(right.corners).forEach { (a, b) ->
                        assertNear(1f - a.x, b.x)
                        assertNear(a.y, b.y)
                    }
                }
            }
        }
    }

    @Test
    fun vertical_turns_show_both_caps_and_keep_the_caption_on_the_front_photograph() {
        assertEquals(0, prismGalleryFrontIndex(prismGalleryFaces(0f, 8)))
        assertEquals(2, prismGalleryFrontIndex(prismGalleryFaces(0f, 8, pitchDegrees = -68f)))
        assertEquals(3, prismGalleryFrontIndex(prismGalleryFaces(0f, 8, pitchDegrees = 112f)))
        for (pitch in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(prismGalleryFaces(0f, 8), prismGalleryFaces(0f, 8, pitchDegrees = pitch))
        }
        val before = prismGalleryFaces(1.3f, 8, pitchDegrees = 179.999f)
        val after = prismGalleryFaces(1.3f, 8, pitchDegrees = -179.999f)
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.index, b.index)
            a.corners.zip(b.corners).forEach { (p, q) ->
                assertTrue(abs(p.x - q.x) < .0001f && abs(p.y - q.y) < .0001f)
            }
        }
    }

    @Test
    fun polygon_hit_testing_does_not_select_empty_bounding_box_corners() {
        val diamond = listOf(GalleryPoint(.5f, 0f), GalleryPoint(1f, .5f), GalleryPoint(.5f, 1f), GalleryPoint(0f, .5f))
        assertTrue(galleryQuadContains(diamond, GalleryPoint(.5f, .5f)))
        assertTrue(galleryQuadContains(diamond, GalleryPoint(.75f, .25f)))
        assertFalse(galleryQuadContains(diamond, GalleryPoint(.95f, .05f)))
        assertFalse(galleryQuadContains(diamond, GalleryPoint(.05f, .95f)))
        assertFalse(galleryQuadContains(List(4) { GalleryPoint(.5f, .5f) }, GalleryPoint(.5f, .5f)))
        assertFalse(galleryQuadContains(diamond, GalleryPoint(Float.NaN, 0f)))
        assertFalse(galleryQuadContains(emptyList(), GalleryPoint(.5f, .5f)))
        assertTrue(galleryQuadContains(diamond.reversed(), GalleryPoint(.5f, .5f)))
    }

    @Test
    fun ribbon_wave_is_bounded_continuous_and_periodic() {
        for (step in -200..500) {
            val x = step / 100f
            for (phase in listOf(0f, .5f, 1f, 4.2f)) {
                val y = ribbonGalleryWave(x, phase)
                assertTrue(abs(y) <= .065001f)
                assertNear(y, ribbonGalleryWave(x + 1f, phase))
                assertTrue(abs(y - ribbonGalleryWave(x + .0001f, phase)) < .00005f)
                assertTrue(abs(y - ribbonGalleryWave(x, phase + .0001f)) < .00001f)
            }
        }
        assertTrue(ribbonGalleryWave(Float.NaN, Float.POSITIVE_INFINITY).isFinite())
    }

    @Test
    fun ribbon_segment_hits_follow_the_translated_strip_and_mirror_for_rtl() {
        assertEquals(-1, ribbonGalleryIndexAt(.5f, 0f, 0))
        assertEquals(-1, ribbonGalleryIndexAt(.5f, 0f, -2))
        assertEquals(0, ribbonGalleryIndexAt(.99f, 0f, 1))
        assertEquals(2, ribbonGalleryIndexAt(.281f, 2f, 5))
        assertEquals(2, ribbonGalleryIndexAt(.719f, 2f, 5))
        assertEquals(1, ribbonGalleryIndexAt(.279f, 2f, 5))
        assertEquals(3, ribbonGalleryIndexAt(.721f, 2f, 5))
        assertEquals(3, ribbonGalleryIndexAt(.5f + .44f * .25f, 2.75f, 5))
        for (step in 0..400) {
            val position = step / 100f
            for (xStep in -20..120) {
                val x = xStep / 100f
                val index = ribbonGalleryIndexAt(x, position, 5)
                assertTrue(index in 0..4)
                assertEquals(index, ribbonGalleryIndexAt(1f - x, position, 5, rtl = true))
            }
        }
        assertEquals(0, ribbonGalleryIndexAt(-10f, -100f, 5))
        assertEquals(4, ribbonGalleryIndexAt(10f, 100f, 5))
        assertEquals(0, ribbonGalleryIndexAt(Float.NaN, Float.NaN, 1))
    }

    private fun near(
        a: GalleryPoint,
        b: GalleryPoint,
    ): Boolean = abs(a.x - b.x) < .00001f && abs(a.y - b.y) < .00001f

    private fun assertNear(
        expected: Float,
        actual: Float,
    ) {
        assertTrue(abs(expected - actual) < .00001f, "Expected $expected, got $actual")
    }
}
