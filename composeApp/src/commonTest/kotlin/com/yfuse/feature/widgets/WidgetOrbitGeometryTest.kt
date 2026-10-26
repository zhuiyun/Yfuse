package com.yfuse.feature.widgets

import com.yfuse.core.widget.WidgetMedia
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetOrbitGeometryTest {
    @Test
    fun satellites_are_distinct_real_neighbours_even_at_collection_boundaries() {
        assertEquals(emptyList(), orbitNeighbourIndices(0, 0))
        assertEquals(emptyList(), orbitNeighbourIndices(0, 1))
        assertEquals(listOf(1), orbitNeighbourIndices(0, 2))
        assertEquals(listOf(0), orbitNeighbourIndices(1, 2))
        assertEquals(listOf(1, 2), orbitNeighbourIndices(0, 5))
        assertEquals(listOf(3, 2), orbitNeighbourIndices(4, 5))
        assertEquals(listOf(3, 1), orbitNeighbourIndices(2, 5))
        for (count in 2..12) {
            for (current in 0 until count) {
                val neighbours = orbitNeighbourIndices(current, count)
                assertEquals(minOf(2, count - 1), neighbours.size)
                assertEquals(neighbours.size, neighbours.distinct().size)
                assertTrue(neighbours.all { it in 0 until count && it != current })
            }
        }
    }

    @Test
    fun missing_or_invalid_progress_never_claims_a_resume_time() {
        val film = WidgetMedia("film", "影片", durationMinutes = 100)
        for (progress in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -.2f, 1.1f)) {
            assertNull(orbitProgress(film.copy(progress = progress)))
            assertNull(orbitRemainingMinutes(film.copy(progress = progress)))
        }
        assertEquals(0f, orbitProgress(film.copy(progress = 0f)))
        assertNull(orbitRemainingMinutes(film.copy(progress = 0f)))
        assertEquals(1f, orbitProgress(film.copy(progress = .4f, played = true)))
        assertNull(orbitRemainingMinutes(film.copy(progress = .4f, played = true)))
        assertEquals(.4f, orbitProgress(film.copy(progress = .4f, played = false)))
    }

    @Test
    fun approximate_remaining_minutes_require_both_source_progress_and_duration() {
        val film = WidgetMedia("film", "影片", progress = .4f, durationMinutes = 100)
        assertEquals(60, orbitRemainingMinutes(film))
        assertEquals(1, orbitRemainingMinutes(film.copy(progress = .999f)))
        assertEquals(45, orbitRemainingMinutes(film.copy(progress = .5f, durationMinutes = 90)))
        assertNull(orbitRemainingMinutes(film.copy(durationMinutes = null)))
        assertNull(orbitRemainingMinutes(film.copy(durationMinutes = 0)))
        assertNull(orbitRemainingMinutes(film.copy(durationMinutes = -1)))
        assertNull(orbitRemainingMinutes(film.copy(progress = 1f)))
    }

    @Test
    fun refreshing_or_restoring_the_collection_preserves_the_selected_film() {
        val first = WidgetMedia("one", "同名影片")
        val second = WidgetMedia("two", "同名影片")
        val third = WidgetMedia("three", "其他影片")
        assertEquals(0, orbitAnchorIndex("two", 1, listOf(second, third, first)))
        assertEquals(1, orbitAnchorIndex("gone", 8, listOf(first, second)))
        assertEquals(0, orbitAnchorIndex("gone", -1, listOf(first, second)))
        assertEquals(0, orbitAnchorIndex("two", 1, emptyList()))
    }

    @Test
    fun episode_labels_use_only_confirmed_valid_episode_metadata() {
        val film = WidgetMedia("film", "影片")
        assertEquals("", orbitEpisodeLabel(film))
        assertEquals("", orbitEpisodeLabel(film.copy(season = 3)))
        assertEquals("", orbitEpisodeLabel(film.copy(episode = -1)))
        assertEquals("第 2 集", orbitEpisodeLabel(film.copy(episode = 2)))
        assertEquals("S0 · 第 2 集", orbitEpisodeLabel(film.copy(season = 0, episode = 2)))
    }

    @Test
    fun rotating_satellites_stay_inside_the_stage_with_focus_and_shadow() {
        for (stageWidth in listOf(180f, 320f, 480f)) {
            val artworkWidth = stageWidth - 32f
            val stageHeight = artworkWidth * .80f + 24f
            for (slot in 0..1) {
                val frame = orbitSatelliteFrame(slot)
                for (motionTilt in listOf(-4f, 0f, 4f)) {
                    val angle = (frame.tilt + motionTilt) * PI / 180
                    val centerX = 16f + artworkWidth * (frame.x + frame.width / 2)
                    val centerY = 12f + artworkWidth * (frame.y + frame.height / 2)
                    for (cornerX in listOf(-.5f, .5f)) {
                        for (cornerY in listOf(-.5f, .5f)) {
                            val x = artworkWidth * frame.width * cornerX * 1.04f
                            val y = artworkWidth * frame.height * cornerY * 1.04f
                            val px = centerX + x * cos(angle) - y * sin(angle)
                            val py = centerY + x * sin(angle) + y * cos(angle)
                            val label = "satellite $slot at $motionTilt in $stageWidth dp"
                            assertTrue(px - 4 >= 0 && px + 4 <= stageWidth, "$label clips horizontally")
                            assertTrue(py - 4 >= 0 && py + 4 <= stageHeight, "$label clips vertically")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun the_single_and_multi_item_circle_and_progress_thumb_fit_the_reserved_stage() {
        for (stageWidth in listOf(180f, 320f, 480f)) {
            val artworkWidth = stageWidth - 32f
            val stageHeight = artworkWidth * .80f + 24f
            for (single in listOf(false, true)) {
                val frame = orbitPrimaryFrame(single)
                val centerX = 16f + artworkWidth * (frame.x + frame.width / 2)
                val centerY = 12f + artworkWidth * (frame.y + frame.height / 2)
                val radius = artworkWidth * frame.width * .52f + 5f
                assertTrue(centerX - radius >= 0 && centerX + radius <= stageWidth)
                assertTrue(centerY - radius >= 0 && centerY + radius <= stageHeight)
            }
        }
    }
}
