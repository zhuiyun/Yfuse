package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailMoreLiftMenuTest {
    private fun menu(
        played: Boolean = false,
        favoriteAvailable: Boolean = true,
        favorite: Boolean = false,
        watchLater: Boolean = false,
        watchTogether: (() -> Unit)? = {},
        seasonDownload: String? = null,
        events: MutableList<String> = mutableListOf(),
    ) = detailMoreLiftMenu(
        title = "深海回声",
        played = played,
        favoriteAvailable = favoriteAvailable,
        favorite = favorite,
        watchLater = watchLater,
        onTogglePlayed = { events += "played" },
        onToggleFavorite = { events += "favorite" },
        onToggleWatchLater = { events += "later" },
        onDownload = { events += "download" },
        onWatchTogether = watchTogether,
        onAllActions = { events += "all" },
        seasonDownload = seasonDownload,
        onDownloadSeason = { events += "season" },
    )

    @Test
    fun theHeldMenuOffersTheEverydayActionsThenTheWholeSheet() {
        val built = menu()
        assertTrue(built.anchored)
        assertEquals(
            listOf("标记为已看", "收藏", "稍后看", "下载…", "一起看…", "全部操作…"),
            built.actions.map { it.label },
        )
        assertEquals(4, built.sections.size)
    }

    @Test
    fun rowsFollowTheTitlesCurrentStateAndDropWhatCannotBeDone() {
        val built = menu(played = true, favoriteAvailable = false, watchLater = true, watchTogether = null)
        assertEquals(listOf("标记为未看", "移出稍后看", "下载…", "全部操作…"), built.actions.map { it.label })
    }

    @Test
    fun aListedSeasonCanBeTakenWholeByName() {
        val events = mutableListOf<String>()
        val built = menu(seasonDownload = "第 2 季（10 集）", events = events)
        assertEquals(
            listOf("标记为已看", "收藏", "稍后看", "下载…", "下载第 2 季（10 集）…", "一起看…", "全部操作…"),
            built.actions.map { it.label },
        )
        built.actions.first { it.label.startsWith("下载第") }.onSelect()
        assertEquals(listOf("season"), events)
    }

    @Test
    fun lettingGoOnTheButtonAndTheLastRowBothOpenTheSheet() {
        val events = mutableListOf<String>()
        val built = menu(events = events)
        built.onOpen?.invoke()
        built.actions.last().onSelect()
        built.actions.first().onSelect()
        assertEquals(listOf("all", "all", "played"), events)
    }
}
