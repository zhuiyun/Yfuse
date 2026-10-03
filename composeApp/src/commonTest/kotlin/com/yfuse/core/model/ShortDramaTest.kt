package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortDramaTest {
    @Test
    fun a_picture_is_upright_after_its_rotation() {
        assertEquals(true, isPortraitPicture(1080, 1920))
        assertEquals(false, isPortraitPicture(1920, 1080))
        assertEquals(true, isPortraitPicture(1920, 1080, rotationDegrees = 90))
        assertEquals(true, isPortraitPicture(1920, 1080, rotationDegrees = -90))
        assertEquals(false, isPortraitPicture(1920, 1080, rotationDegrees = 180))
        assertEquals(false, isPortraitPicture(1080, 1080))
        assertNull(isPortraitPicture(null, 1920))
        assertNull(isPortraitPicture(1080, 0))
    }

    @Test
    fun short_means_under_five_minutes() {
        assertEquals(true, isShortRuntime(119_000L))
        assertEquals(false, isShortRuntime(300_000L))
        assertNull(isShortRuntime(null))
        assertNull(isShortRuntime(0L))
    }

    @Test
    fun a_series_reads_as_a_short_drama_when_most_known_pictures_are_upright() {
        assertTrue(looksLikeShortDrama(listOf(true, true, null, false)))
        assertFalse(looksLikeShortDrama(listOf(true, false)))
        assertFalse(looksLikeShortDrama(listOf(null, null)))
        assertFalse(looksLikeShortDrama(emptyList()))
    }

    @Test
    fun the_viewers_choice_overrides_detection() {
        assertTrue(ShortDramaMode.Auto.resolve(detected = true))
        assertFalse(ShortDramaMode.Auto.resolve(detected = false))
        assertTrue(ShortDramaMode.On.resolve(detected = false))
        assertFalse(ShortDramaMode.Off.resolve(detected = true))
        assertEquals(ShortDramaMode.Auto, ShortDramaMode.fromStorage("bogus"))
        assertEquals(ShortDramaMode.On, ShortDramaMode.fromStorage("On"))
    }
}
