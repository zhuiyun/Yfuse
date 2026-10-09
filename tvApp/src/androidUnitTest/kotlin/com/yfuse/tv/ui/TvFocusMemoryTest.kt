package com.yfuse.tv.ui

import com.yfuse.tv.focus.FocusAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TvFocusMemoryTest {
    @Test
    fun `every detail page is a route of its own`() {
        assertEquals(tvDetailRoute("42"), tvFocusRoute("detail:42:hero"))
        assertEquals(tvDetailRoute("42"), tvFocusRoute("detail:42:episodes"))
        assertEquals(tvDetailRoute("7"), tvFocusRoute("detail:7:related"))
    }

    @Test
    fun `other scopes keep their first segment`() {
        // The detail dialogs have no section after an id and are not pages.
        assertEquals("detail", tvFocusRoute("detail:more"))
        assertEquals("home", tvFocusRoute("home:tmdb:热门:3"))
        assertEquals("settings", tvFocusRoute("settings"))
        assertEquals("settings", tvFocusRoute("settings:downloads"))
    }

    @Test
    fun `an entry waits until focus lands in its own route`() {
        val memory = TvUiFocusMemory()
        memory.beginRestore("home")

        memory.remember("navigation", "navigation:Home")
        assertTrue(memory.restorePending("home"))

        memory.remember("home:resume", "resume:emby:server:item")
        assertFalse(memory.restorePending("home"))
    }

    @Test
    fun `focus on one detail page settles only that page`() {
        val memory = TvUiFocusMemory()
        memory.beginRestore(tvDetailRoute("a"))
        memory.beginRestore(tvDetailRoute("b"))

        memory.remember("detail:b:hero", "detail:b:play")

        assertTrue(memory.restorePending(tvDetailRoute("a")))
        assertFalse(memory.restorePending(tvDetailRoute("b")))
    }

    @Test
    fun `a restore a card left behind ends only with that card`() {
        val memory = TvUiFocusMemory()
        val card = Any()
        memory.beginRestore("home", owner = card)

        // The rail leaves it waiting, as it does a page's own entry; only its card can end it.
        memory.remember("navigation", "navigation:Home")
        memory.endRestore("home", owner = Any())
        assertTrue(memory.restoreOwnedBy("home", card))

        memory.endRestore("home", owner = card)
        assertFalse(memory.restorePending("home"))
    }

    @Test
    fun `a row shown other content starts again where it is told`() {
        val memory = TvUiFocusMemory()
        val firstSeason = memory.rowState("detail:x:episodes", content = "s1e1", initialIndex = 0)

        // The same season, back from the player: the row is where it was left.
        assertSame(firstSeason, memory.rowState("detail:x:episodes", content = "s1e1", initialIndex = 5))

        val secondSeason = memory.rowState("detail:x:episodes", content = "s2e1", initialIndex = 3)
        assertNotSame(firstSeason, secondSeason)
        assertEquals(3, secondSeason.firstVisibleItemIndex)
    }

    @Test
    fun `the settings root finds its own row after a sub-page moved the route on`() {
        val memory = TvUiFocusMemory()
        memory.repository.record(settingsAnchor(section = "settings", item = "settings:danmaku"))
        memory.repository.record(settingsAnchor(section = "settings:danmaku", item = "danmaku:enabled"))

        assertEquals("danmaku:enabled", memory.lastForRoute("settings")?.itemStableId)
        assertEquals("settings:danmaku", memory.lastInSection("settings", "settings")?.itemStableId)
    }

    private fun settingsAnchor(
        section: String,
        item: String,
    ) = FocusAnchor(
        route = "settings",
        sectionId = section,
        itemStableId = item,
        fallbackIndex = 0,
        scrollOffset = 0,
    )
}
