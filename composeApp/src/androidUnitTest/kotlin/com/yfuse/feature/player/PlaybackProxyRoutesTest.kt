package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackProxyRoutesTest {
    @Test
    fun sliding_live_windows_keep_only_current_segments_and_bounded_history() {
        var now = 0L
        PlaybackProxyRoutes(retiredLimit = 4, graceMs = 120_000L, nowMs = { now }).use { routes ->
            val root = assertNotNull(routes.registerRoot(route("live.m3u8")))
            routes.acquire(root)!!.use { manifest ->
                var latest = emptyList<String>()
                repeat(100) { window ->
                    now += 100L
                    latest = rewrite(routes, manifest, (window until window + 3).map { "segment-$it.ts" })
                    assertTrue(routes.entryCount() <= 8, "root + three current segments + four retired routes")
                }
                latest.forEach { id -> assertNotNull(routes.acquire(id)).close() }
                now += 120_001L
                assertEquals(4, routes.entryCount())
            }
        }
    }

    @Test
    fun vod_manifest_retains_early_segments_for_backward_seeking() {
        var now = 0L
        PlaybackProxyRoutes(retiredLimit = 2, graceMs = 1_000L, nowMs = { now }).use { routes ->
            val root = assertNotNull(routes.registerRoot(route("movie.m3u8")))
            routes.acquire(root)!!.use { manifest ->
                val segments = rewrite(routes, manifest, (0..99).map { "segment-$it.ts" } + "#EXT-X-ENDLIST")
                now = 90_000L
                assertEquals(101, routes.entryCount())
                assertNotNull(routes.acquire(segments.first())).use {
                    assertEquals("https://media.example/segment-0.ts", it.route.upstreamUrl)
                }
            }
        }
    }

    @Test
    fun retired_segment_remains_available_until_its_active_read_finishes() {
        var now = 0L
        PlaybackProxyRoutes(retiredLimit = 0, graceMs = 1_000L, nowMs = { now }).use { routes ->
            val root = assertNotNull(routes.registerRoot(route("live.m3u8")))
            routes.acquire(root)!!.use { manifest ->
                val first = rewrite(routes, manifest, listOf("first.ts")).single()
                val inFlight = assertNotNull(routes.acquire(first))
                try {
                    now = 2_000L
                    rewrite(routes, manifest, listOf("next.ts"))
                    assertNotNull(routes.acquire(first)).close()
                    assertEquals("https://media.example/first.ts", inFlight.route.upstreamUrl)
                    assertEquals(3, routes.entryCount())
                } finally {
                    inFlight.close()
                }
                assertEquals(2, routes.entryCount())
                assertNull(routes.acquire(first))
            }
        }
    }

    @Test
    fun variant_children_survive_and_old_source_is_retired_after_handover() {
        PlaybackProxyRoutes(retiredLimit = 0).use { routes ->
            val masterId = assertNotNull(routes.registerRoot(route("master.m3u8")))
            val master = assertNotNull(routes.acquire(masterId))
            val variantId = rewrite(routes, master, listOf("variant.m3u8")).single()
            val segmentId =
                assertNotNull(routes.acquire(variantId)).use { variant ->
                    rewrite(routes, variant, listOf("segment.ts")).single()
                }
            routes.registerRoot(route("other.mkv"))
            assertNotNull(routes.acquire(segmentId)).close()
            master.close()
            assertEquals(1, routes.entryCount())
            assertNull(routes.acquire(segmentId))
            assertNull(routes.acquire(variantId))
        }
    }

    private fun route(path: String) = PlaybackProxyRoute("https://media.example/$path", cacheable = false)

    private fun rewrite(
        routes: PlaybackProxyRoutes,
        parent: PlaybackProxyRoutes.Lease,
        lines: List<String>,
    ): List<String> =
        routes
            .rewriteManifest(parent, "#EXTM3U\n" + lines.joinToString("\n"), parent.route.upstreamUrl) { it }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .toList()
}
