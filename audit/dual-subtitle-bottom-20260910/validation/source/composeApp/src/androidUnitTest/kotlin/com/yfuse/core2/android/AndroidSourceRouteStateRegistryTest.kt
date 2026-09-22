package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AndroidSourceRouteStateRegistryTest {
    @Test
    fun every_open_of_the_same_uri_shares_one_route_state() {
        val registry = AndroidSourceRouteStateRegistry(maxEntries = 2)
        val probe = registry.forSource("https://origin/item.mkv?token=a")
        val player = registry.forSource("https://origin/item.mkv?token=a")
        assertSame(probe, player)

        // A rotated token is a different source with its own redirect target.
        val rotated = registry.forSource("https://origin/item.mkv?token=b")
        assertNotSame(probe, rotated)
    }

    @Test
    fun facts_recorded_by_one_open_are_visible_to_the_next() {
        val registry = AndroidSourceRouteStateRegistry()
        registry.forSource("https://origin/a.mkv").disableCronet()
        assertEquals(false, registry.forSource("https://origin/a.mkv").cronetAvailable)
        assertEquals(true, registry.forSource("https://origin/b.mkv").cronetAvailable)
    }

    @Test
    fun the_registry_is_bounded_and_drops_the_least_recently_used_source() {
        val registry = AndroidSourceRouteStateRegistry(maxEntries = 2)
        val first = registry.forSource("https://origin/1.mkv")
        registry.forSource("https://origin/2.mkv")
        registry.forSource("https://origin/1.mkv")
        registry.forSource("https://origin/3.mkv")
        assertEquals(2, registry.size())
        assertSame(first, registry.forSource("https://origin/1.mkv"))
        assertEquals(2, registry.size())
    }
}
