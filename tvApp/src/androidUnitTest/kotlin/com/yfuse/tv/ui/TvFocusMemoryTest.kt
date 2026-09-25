package com.yfuse.tv.ui

import com.yfuse.tv.focus.FocusAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
