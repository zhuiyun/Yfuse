package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShelfPreferencesTest {
    private val page = listOf("continue", "favorites", "calendar", "tmdb:热门", "tmdb:最新上线")

    @Test
    fun withNothingSavedThePageKeepsItsOwnOrder() {
        assertEquals(page, HomeShelfLayout().arranged(page))
    }

    @Test
    fun aMovedShelfStaysPutAndANewShelfJoinsAtTheEnd() {
        val layout = HomeShelfLayout().moved(page, "tmdb:热门", 0)
        assertEquals(listOf("tmdb:热门", "continue", "favorites", "calendar", "tmdb:最新上线"), layout.arranged(page))
        val tomorrow = page + "tmdb:即将上映"
        assertEquals("tmdb:即将上映", layout.arranged(tomorrow).last())
        assertEquals("tmdb:热门", layout.arranged(tomorrow).first())
    }

    @Test
    fun movingDownCountsPositionsWithoutTheShelfBeingMoved() {
        val layout = HomeShelfLayout().moved(page, "continue", 2)
        assertEquals(listOf("favorites", "calendar", "continue", "tmdb:热门", "tmdb:最新上线"), layout.arranged(page))
        assertEquals(layout, layout.moved(page, "missing", 1))
    }

    @Test
    fun aShelfNotOfferedTodayKeepsItsSavedEntry() {
        val saved = HomeShelfLayout(order = listOf("tmdb:正在上映", "continue"))
        val moved = saved.moved(page, "calendar", 0)
        assertEquals(listOf("calendar", "continue"), moved.arranged(page).take(2))
        assertEquals("tmdb:正在上映", moved.order.last())
    }

    @Test
    fun hiddenShelvesLeaveThePageButNotTheEditor() {
        val layout = HomeShelfLayout().withVisible("favorites", visible = false)
        assertEquals(page - "favorites", layout.visible(page))
        assertEquals(page, layout.arranged(page))
        assertEquals(page, layout.withVisible("favorites", visible = true).visible(page))
    }

    @Test
    fun theLayoutSurvivesARestartAndResetForgetsIt() {
        val settings = MapSettings()
        val first = HomeShelfPreferences(settings)
        val layout = HomeShelfLayout(order = listOf("calendar", "continue"), hidden = setOf("tmdb:热门"))
        first.update(layout)
        assertEquals(layout, HomeShelfPreferences(settings).layout.value)
        first.reset()
        assertEquals(HomeShelfLayout(), HomeShelfPreferences(settings).layout.value)
    }
}
