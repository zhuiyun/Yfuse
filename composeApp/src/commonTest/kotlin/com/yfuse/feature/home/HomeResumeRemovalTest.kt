package com.yfuse.feature.home

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeResumeRemovalTest {
    private val server =
        SavedServer(
            id = "s1",
            baseUrl = "https://emby.example",
            serverName = "家里",
            userId = "u",
            userName = "我",
            accessToken = "t",
        )

    private fun entry(id: String) =
        HomeResumeEntry(
            MediaItem(
                id = id,
                title = "片 $id",
                subtitle = null,
                type = "Movie",
                posterItemId = id,
                posterTag = null,
                backdropItemId = null,
                backdropTag = null,
                playedPercentage = 40.0,
            ),
            server,
        )

    private fun List<HomeResumeEntry>.ids() = map { it.item.id }

    @Test
    fun undoPutsTheCardBackWhereItWas() {
        val shelf = listOf(entry("a"), entry("c"))
        assertEquals(listOf("a", "b", "c"), shelf.restoring(entry("b"), 1).ids())
    }

    @Test
    fun aShelfThatShrankMeanwhileTakesItLast() {
        assertEquals(listOf("a", "b"), listOf(entry("a")).restoring(entry("b"), 5).ids())
    }

    @Test
    fun aReloadThatAlreadyBroughtItBackDoesNotListItTwice() {
        val shelf = listOf(entry("b"), entry("a"))
        assertEquals(listOf("a", "b"), shelf.restoring(entry("b"), 1).ids())
    }

    @Test
    fun theSameTitleOnTwoServersIsTwoCards() {
        val other = entry("a").copy(server = server.copy(id = "s2"))
        assertEquals(listOf("s1:a", "s2:a"), listOf(entry("a"), other).map { it.key })
    }
}
