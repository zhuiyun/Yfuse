package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchConnectionOwnershipTest {
    @Test
    fun stale_finally_cannot_clear_a_new_session() {
        val ownership = WatchConnectionOwnership<String>()
        val first = ownership.advance()
        assertTrue(ownership.claim(first, "room-a"))

        val second = ownership.advance()
        assertTrue(ownership.claim(second, "room-b"))

        ownership.clear(first)
        assertEquals("room-b", ownership.current())
        assertFalse(ownership.claim(first, "stale-reconnect"))
    }

    @Test
    fun leave_advances_generation_and_releases_the_session() {
        val generation = WatchConnectionOwnership<String>()
        val active = generation.advance()
        assertTrue(generation.claim(active, "socket"))

        val afterLeave = generation.advance()
        assertNull(generation.current())
        assertFalse(generation.isCurrent(active))
        assertTrue(generation.isCurrent(afterLeave))
    }
}
