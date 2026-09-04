package com.yfuse.core.data

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartSourceSelectionTest {
    @Test
    fun provider_identity_groups_server_copies_into_one_card() {
        val groups =
            aggregateCrossServerMedia(
                listOf(
                    hit("a", item("one", mapOf("Tmdb" to "603"))),
                    hit("b", item("two", mapOf("tmdb" to "603"))),
                    hit("c", item("remake", mapOf("Tmdb" to "999"))),
                ),
            )

        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups.first().copies.mapTo(mutableSetOf()) { it.serverId })
    }

    @Test
    fun healthy_low_latency_source_beats_offline_4k() {
        val healthy1080 = source("healthy", height = 1080, bitrate = 8_000_000)
        val offline4k = source("offline", height = 2160, bitrate = 60_000_000)
        val ranked =
            rankServerSources(
                listOf(offline4k, healthy1080),
                health =
                    mapOf(
                        "healthy" to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 35),
                        "offline" to ServerHealth(ServerHealthStatus.Offline, latencyMs = 5),
                    ),
                network = PlaybackNetworkClass.Unmetered,
            )

        assertEquals("healthy", ranked.first().source.serverId)
        assertEquals(
            "healthy",
            recommendedServerSource(
                listOf(offline4k, healthy1080),
                health =
                    mapOf(
                        "healthy" to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 35),
                        "offline" to ServerHealth(ServerHealthStatus.Offline, latencyMs = 5),
                    ),
            )?.serverId,
        )
    }

    @Test
    fun metered_network_can_prefer_efficient_1080_over_extreme_bitrate_4k() {
        val efficient = source("efficient", height = 1080, bitrate = 8_000_000)
        val remux = source("remux", height = 2160, bitrate = 100_000_000)
        val sameHealth =
            mapOf(
                "efficient" to ServerHealth(ServerHealthStatus.Healthy, 50),
                "remux" to ServerHealth(ServerHealthStatus.Healthy, 50),
            )

        assertEquals(
            "efficient",
            rankServerSources(listOf(remux, efficient), sameHealth, PlaybackNetworkClass.Metered)
                .first()
                .source
                .serverId,
        )
    }

    @Test
    fun degraded_reachable_source_beats_unmeasured_source() {
        val degraded = source("degraded", height = 1080, bitrate = 8_000_000)
        val unknown = source("unknown", height = 1080, bitrate = 8_000_000)

        assertEquals(
            "degraded",
            rankServerSources(
                sources = listOf(unknown, degraded),
                health =
                    mapOf(
                        "degraded" to
                            ServerHealth(
                                status = ServerHealthStatus.Degraded,
                                latencyMs = 1_300,
                            ),
                    ),
            ).first().source.serverId,
        )
    }

    @Test
    fun failover_plan_is_ranked_distinct_and_hard_bounded() {
        val sources =
            (1..8).flatMap { index ->
                listOf(
                    source("s$index", height = 1080, bitrate = 8_000_000),
                    source("s$index", height = 720, bitrate = 4_000_000),
                )
            }
        val ids = smartFailoverServerIds("primary", sources, maxFallbacks = 99)

        assertEquals(MAX_SMART_SOURCE_FALLBACKS, ids.size)
        assertEquals(ids.distinct(), ids)
        assertTrue("primary" !in ids)
    }

    private fun hit(
        serverId: String,
        item: MediaItem,
    ) = CrossServerMediaHit(serverId, serverId, item)

    private fun item(
        id: String,
        providers: Map<String, String>,
    ) = MediaItem(
        id = id,
        title = "黑客帝国",
        subtitle = null,
        type = "Movie",
        posterItemId = id,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
        year = 1999,
        providerIds = providers,
    )

    @Test
    fun a_degraded_server_is_still_playable_and_does_not_justify_switching() {
        val current = source("current", height = 2160, bitrate = 20_000_000)

        // One 5xx marks a server Degraded. Ranking rightly prefers a Healthy peer, but the source
        // itself is still usable, and overriding the server the user chose on that basis moved
        // playback off a working library for a single bad response.
        assertTrue(
            serverSourcePlayable(current, ServerHealth(ServerHealthStatus.Degraded, latencyMs = 90)),
        )
        assertTrue(serverSourcePlayable(current, ServerHealth(ServerHealthStatus.Healthy)))
        assertTrue(serverSourcePlayable(current, null))
    }

    @Test
    fun an_unusable_source_is_not_playable() {
        val reachable = source("current", height = 1080, bitrate = 8_000_000)

        assertFalse(serverSourcePlayable(reachable, ServerHealth(ServerHealthStatus.Offline)))
        assertFalse(serverSourcePlayable(reachable, ServerHealth(ServerHealthStatus.AuthRequired)))
        assertFalse(
            serverSourcePlayable(reachable.copy(reachable = false), ServerHealth(ServerHealthStatus.Healthy)),
        )
        assertFalse(
            serverSourcePlayable(reachable.copy(source = null), ServerHealth(ServerHealthStatus.Healthy)),
        )
        assertFalse(
            serverSourcePlayable(reachable.copy(itemId = null), ServerHealth(ServerHealthStatus.Healthy)),
        )
    }

    private fun source(
        serverId: String,
        height: Int,
        bitrate: Int,
    ) = ServerSource(
        serverId = serverId,
        serverName = serverId,
        isCurrent = false,
        source =
            SourceInfo(
                quality = "${height}p",
                size = null,
                bitrate = null,
                videoHeight = height,
                bitrateBps = bitrate,
            ),
        reachable = true,
        itemId = "item-$serverId",
    )
}
