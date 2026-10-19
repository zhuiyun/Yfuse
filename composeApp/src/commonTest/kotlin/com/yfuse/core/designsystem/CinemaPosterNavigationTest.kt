package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CinemaPosterNavigationTest {
    private val first = MediaSharedElementKey("a", "first")
    private val second = MediaSharedElementKey("b", "second")

    @Test
    fun nested_details_return_to_their_own_posters_in_order() {
        val routes = MediaRouteOrigins()
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), first)
        routes.update(listOf("library", "first", "second"), second)
        assertEquals(second, routes.returningTo(listOf("library", "first")))
        routes.update(listOf("library", "first"), null)
        assertEquals(first, routes.returningTo(listOf("library")))
    }

    @Test
    fun unrelated_push_and_multi_pop_do_not_fly_to_a_stale_poster() {
        val routes = MediaRouteOrigins()
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), first)
        routes.update(listOf("library", "first", "settings"), null)
        assertNull(routes.returningTo(listOf("library", "first")))
        assertNull(routes.returningTo(listOf("library")))
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), null)
        assertNull(routes.returningTo(listOf("library")))
    }

    @Test
    fun replacing_the_underlay_invalidates_even_a_reused_detail_route() {
        val routes = MediaRouteOrigins()
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), first)
        assertNull(routes.returningTo(listOf("search")))
        routes.update(listOf("search", "first"), null)
        assertNull(routes.returningTo(listOf("search")))
    }

    @Test
    fun stable_recompositions_preserve_the_origin_and_a_new_tap_replaces_it() {
        val routes = MediaRouteOrigins()
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), first)
        repeat(3) { routes.update(listOf("library", "first"), null) }
        assertEquals(first, routes.returningTo(listOf("library")))
        routes.update(listOf("library"), null)
        routes.update(listOf("library", "first"), second)
        assertEquals(second, routes.returningTo(listOf("library")))
    }

    @Test
    fun restored_or_multi_push_stacks_do_not_invent_a_source() {
        val routes = MediaRouteOrigins()
        routes.update(listOf("library", "first"), first)
        assertNull(routes.returningTo(listOf("library")))
        assertNull(routes.returningTo(emptyList()))
        assertNull(routes.returningTo(listOf("library", "first")))
    }

    @Test
    fun poster_then_words_has_finite_reversible_endpoints() {
        assertEquals(0f, cinemaDetailReveal(0f))
        assertEquals(0f, cinemaDetailReveal(0.28f))
        assertEquals(1f, cinemaDetailReveal(1f))
        val samples = (-10..110).map { cinemaDetailReveal(it / 100f) }
        assertTrue(samples.all { it in 0f..1f })
        assertTrue(samples.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun source_images_are_copied_sanitized_and_isolated_by_server() {
        val controller = SharedMediaTransitionController()
        val otherServer = first.copy(serverId = "b")
        val urls = mutableListOf<String?>(null, "", "  ", "poster", "poster", "fallback")
        controller.registerArtworkUrls(first, urls)
        urls.clear()
        controller.registerArtworkUrls(otherServer, listOf("other"))
        assertEquals(listOf("poster", "fallback"), controller.artworkUrls(first))
        assertEquals(listOf("other"), controller.artworkUrls(otherServer))
        controller.registerArtworkUrls(first, emptyList())
        assertEquals(listOf("poster", "fallback"), controller.artworkUrls(first))
    }

    @Test
    fun poster_memory_is_bounded_and_the_latest_source_survives() {
        val controller = SharedMediaTransitionController()
        val keys = (0..600).map { MediaSharedElementKey("server", "$it") }
        keys.forEach { controller.registerArtworkUrls(it, listOf("image-${it.itemId}")) }
        assertEquals(512, keys.count { controller.artworkUrls(it).isNotEmpty() })
        assertEquals(listOf("image-600"), controller.artworkUrls(keys.last()))
    }
}
