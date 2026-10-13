package com.yfuse.feature.history

import coil3.memory.MemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackHistoryPosterStateTest {
    @Test
    fun seriesEnrichmentKeepsThePreviousDecodedPosterAsAPlaceholder() {
        val state = PlaybackHistoryPosterState()
        val decoded = MemoryCache.Key("episode")
        state.resolved("episode", decoded)
        assertEquals("series", state.candidate(listOf("series", "fallback")))
        assertEquals(decoded, state.memoryCacheKey)
        assertEquals("episode", state.resolvedUrl)
        state.resolved("series", MemoryCache.Key("series"))
        assertEquals("series", state.resolvedUrl)
    }

    @Test
    fun failedImagesUseFallbacksWithoutRepeatingEachTimeTheCardIsRecycled() {
        var now = 1_000L
        val state = PlaybackHistoryPosterState { now }
        state.failed("first")
        assertEquals("fallback", state.candidate(listOf(null, "", "first", "fallback")))
        state.failed("fallback")
        assertNull(state.candidate(listOf("first", "fallback")))
        now += 30_000
        assertEquals("first", state.candidate(listOf("first", "fallback")))
        state.failed("first")
        state.retry()
        assertEquals("first", state.candidate(listOf("first", "fallback")))
    }
}
