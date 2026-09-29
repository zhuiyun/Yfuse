package com.yfuse.feature.home

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeLiftMenuTest {
    private val server = SavedServer("s1", "https://emby.example", "家里", "u", "我", "t")

    private fun entry(
        type: String = "Episode",
        resumeTicks: Long? = 12_000_000_000L,
    ) = HomeResumeEntry(
        MediaItem(
            id = "e1",
            title = "某剧",
            subtitle = "S1E3 第三集",
            type = type,
            posterItemId = "s1",
            posterTag = null,
            backdropItemId = null,
            backdropTag = null,
            playedPercentage = 40.0,
            resumePositionTicks = resumeTicks,
            runtimeMinutes = 45,
        ),
        server,
    )

    @Test
    fun a_card_that_resumes_on_a_tap_leads_its_menu_with_the_title_page() {
        val sent = mutableListOf<HomeIntent>()
        val menu = entry().homeLiftMenu(onIntent = { sent += it }, inResume = true, playsOnTap = true)
        val labels = menu.actions.map { it.label }
        assertEquals(listOf("查看详情", "从头播放"), labels.take(2))
        assertTrue("标记为已看" in labels)
        assertTrue("从继续观看移除" in labels)
        // The row is one way to the page; letting go on the card is the other.
        menu.actions.first().onSelect()
        menu.onOpen?.invoke()
        assertEquals(listOf<HomeIntent>(HomeIntent.OpenResume(entry()), HomeIntent.OpenResume(entry())), sent)
        assertTrue(menu.actions.first().leavesPage)
    }

    @Test
    fun a_card_whose_tap_opens_the_title_still_offers_play_first() {
        val sent = mutableListOf<HomeIntent>()
        val menu = entry().homeLiftMenu(onIntent = { sent += it })
        assertEquals("继续播放", menu.actions.first().label)
        menu.actions.first().onSelect()
        assertEquals(listOf<HomeIntent>(HomeIntent.PlayEntry(entry())), sent)
    }

    @Test
    fun an_unstarted_next_episode_has_nothing_to_start_over() {
        val labels = entry(resumeTicks = null).homeLiftMenu(onIntent = {}, playsOnTap = true).actions.map { it.label }
        assertEquals("查看详情", labels.first())
        assertTrue("从头播放" !in labels)
    }

    @Test
    fun edit_does_to_every_ticked_card_what_the_lift_does_to_one() {
        val sent = mutableListOf<HomeIntent>()
        val watched = entry().let { it.copy(item = it.item.copy(id = "e2", played = true)) }
        val bar = resumeSelectionActions(listOf(entry(), watched)) { sent += it }
        assertEquals(listOf("标记已看", "移除"), bar.map { it.label })
        assertEquals(listOf(false, true), bar.map { it.destructive })

        bar.forEach { it.onClick() }
        // A card already watched has nothing to mark; it still goes when the cards are removed.
        assertEquals(
            listOf(
                HomeIntent.MarkEntriesWatched(listOf(entry())),
                HomeIntent.RemoveEntriesFromResume(listOf(entry(), watched)),
            ),
            sent,
        )
    }

    @Test
    fun edit_dims_what_has_nothing_to_act_on() {
        assertTrue(resumeSelectionActions(emptyList()) {}.none { it.enabled })
        val watched = entry().let { it.copy(item = it.item.copy(played = true)) }
        assertEquals(listOf(false, true), resumeSelectionActions(listOf(watched)) {}.map { it.enabled })
    }
}
