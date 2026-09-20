package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PlaybackResourceCleanupTest {
    @Test
    fun failed_listener_cleanup_does_not_skip_destroy_or_cache_release() {
        val cleanup = PlaybackResourceCleanup()
        val stages = mutableListOf<String>()
        val listenerFailure = IllegalStateException("listener")
        val destroyFailure = IllegalArgumentException("destroy")
        cleanup.attempt {
            stages += "listener"
            throw listenerFailure
        }
        cleanup.attempt {
            stages += "destroy"
            throw destroyFailure
        }
        cleanup.attempt { stages += "cache" }

        assertEquals(listOf("listener", "destroy", "cache"), stages)
        assertSame(listenerFailure, assertFailsWith<IllegalStateException> { cleanup.throwIfFailed() })
        assertEquals(listOf(destroyFailure), listenerFailure.suppressed.toList())
    }

    @Test
    fun successful_cleanup_can_complete_normally() {
        val cleanup = PlaybackResourceCleanup()
        var destroyed = false
        cleanup.attempt { destroyed = true }
        cleanup.throwIfFailed()
        assertEquals(true, destroyed)
    }
}
