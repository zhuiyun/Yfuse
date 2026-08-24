package com.yfuse.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPlayerNavigationPolicyTest {
    @Test
    fun repeated_player_destinations_are_collapsed_to_one_entry() {
        val detail = LibraryComponent.Config.Detail(serverId = "server", itemId = "movie")
        val player =
            LibraryComponent.Config.Player(
                serverId = "server",
                itemId = "movie",
                startPositionTicks = 42L,
                mediaSourceId = "source",
            )

        val result =
            replacePlayerDestination(
                stack = listOf(LibraryComponent.Config.Home, detail, player, player),
                destination = player,
            )

        assertEquals(listOf(LibraryComponent.Config.Home, detail, player), result)
    }

    @Test
    fun a_new_player_replaces_every_older_player_but_keeps_the_detail_history() {
        val detail = LibraryComponent.Config.Detail(serverId = "server", itemId = "movie")
        val oldPlayer =
            LibraryComponent.Config.Player(
                serverId = "server",
                itemId = "old",
                startPositionTicks = 0L,
            )
        val nextPlayer =
            LibraryComponent.Config.Player(
                serverId = "server",
                itemId = "next",
                startPositionTicks = 10L,
            )

        val result =
            replacePlayerDestination(
                stack = listOf(LibraryComponent.Config.Home, oldPlayer, detail, oldPlayer),
                destination = nextPlayer,
            )

        assertEquals(listOf(LibraryComponent.Config.Home, detail, nextPlayer), result)
    }
}
