package com.yfuse.feature.home

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

    @Test
    fun oneUndoPutsSeveralCardsBackEachWhereItWas() {
        // a b c d e, with b and d taken off together.
        val shelf = listOf(entry("a"), entry("c"), entry("e"))
        val cards = listOf(entry("d") to 3, entry("b") to 1)
        assertEquals(listOf("a", "b", "c", "d", "e"), shelf.restoringAll(cards).ids())
        assertEquals(listOf("a", "b", "c", "d", "e"), shelf.restoringAll(cards.reversed()).ids())
    }

    @Test
    fun aShelfAReloadHasChangedStillTakesEachCardBackOnce() {
        // A reload has already brought d back, and the shelf has since lost e.
        val shelf = listOf(entry("a"), entry("d"))
        assertEquals(
            listOf("a", "b", "d"),
            shelf.restoringAll(listOf(entry("b") to 1, entry("d") to 3)).ids(),
        )
        assertEquals(shelf.ids(), shelf.restoringAll(emptyList()).ids())
    }

    @Test
    fun theToastNamesOneCardAndCountsSeveral() {
        assertEquals("已从继续观看移除「片 a」", heldCardsMessage(listOf("片 a"), watched = false))
        assertEquals("已标记为已看「片 a」", heldCardsMessage(listOf("片 a"), watched = true))
        assertEquals("已从继续观看移除「a」等 3 项", heldCardsMessage(listOf("a", "b", "c"), watched = false))
        assertEquals("已将「a」等 2 项标记为已看", heldCardsMessage(listOf("a", "b"), watched = true))
    }

    @Test
    fun twoBatchesInARowNeverReadAlike() {
        // A toast re-posts only on a new message; the second batch's 撤销 depends on this.
        assertNotEquals(
            heldCardsMessage(listOf("a", "b"), watched = false),
            heldCardsMessage(listOf("c", "d"), watched = false),
        )
    }
}
