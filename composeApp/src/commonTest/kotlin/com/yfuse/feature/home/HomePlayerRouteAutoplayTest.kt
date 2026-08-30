package com.yfuse.feature.home

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HomePlayerRouteAutoplayTest {
    @Test
    fun a_saved_player_route_without_autoplay_restores_the_legacy_true_default() {
        val route =
            HomeTabComponent.Config.Player(
                serverId = "server-a",
                itemId = "episode-a",
                startPositionTicks = 12_340_000L,
            )
        val encoded = Json.encodeToString(HomeTabComponent.Config.serializer(), route)

        assertFalse("startPlaybackRequested" in encoded)
        val restored =
            Json.decodeFromString(HomeTabComponent.Config.serializer(), encoded)

        assertTrue(assertIs<HomeTabComponent.Config.Player>(restored).startPlaybackRequested)
    }

    @Test
    fun an_explicit_paused_start_survives_player_route_serialization() {
        val route =
            HomeTabComponent.Config.Player(
                serverId = "server-a",
                itemId = "episode-a",
                startPositionTicks = 12_340_000L,
                startPlaybackRequested = false,
            )
        val encoded = Json.encodeToString(HomeTabComponent.Config.serializer(), route)
        val restored =
            Json.decodeFromString(HomeTabComponent.Config.serializer(), encoded)

        assertFalse(assertIs<HomeTabComponent.Config.Player>(restored).startPlaybackRequested)
    }
}
