package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtworkBlurCacheTest {
    @Test
    fun neighbouring_frames_share_effects_and_recently_used_entries_survive_eviction() {
        val cache = ArtworkBlurCache(capacity = 2)
        val first = cache.effect(1f)
        val second = cache.effect(2f)
        assertSame(first, cache.effect(1.1f))
        cache.effect(3f)
        assertSame(first, cache.effect(1f))
        assertNotSame(second, cache.effect(2f))
        assertEquals(2, cache.size)
    }

    @Test
    fun revealing_many_posters_never_grows_the_effect_cache_past_its_capacity() {
        val cache = ArtworkBlurCache()
        repeat(1_000) { index ->
            cache.effect(index * 0.5f)
            assertTrue(cache.size <= 64)
        }
        assertNull(cache.effect(0f))
        assertNull(cache.effect(Float.NaN))
        assertNull(cache.effect(Float.POSITIVE_INFINITY))
        assertEquals(64, cache.size)
    }

    @Test
    fun blur_quantization_is_monotonic_and_within_a_quarter_physical_pixel() {
        var last = 0
        repeat(10_001) { index ->
            val radius = index / 100f
            val step = artworkBlurStep(radius)
            assertTrue(step >= last)
            assertTrue(abs(step * ARTWORK_BLUR_STEP_PX - radius) <= 0.25001f)
            last = step
        }
    }
}
