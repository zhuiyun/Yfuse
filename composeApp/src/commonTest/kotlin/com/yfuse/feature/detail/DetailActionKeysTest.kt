package com.yfuse.feature.detail

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.offline.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailActionKeysTest {
    private fun facts(
        serverActions: Boolean = true,
        favoriteAvailable: Boolean = true,
        favorite: Boolean = false,
        watchLater: Boolean = false,
        watchLaterMutating: Boolean = false,
        played: Boolean = false,
        downloadable: Boolean? = true,
        download: DownloadStatus? = null,
    ) = DetailActionFacts(
        serverActions = serverActions,
        favoriteAvailable = favoriteAvailable,
        favorite = favorite,
        watchLater = watchLater,
        watchLaterMutating = watchLaterMutating,
        played = played,
        downloadable = downloadable,
        download = download,
    )

    private fun keys(
        facts: DetailActionFacts,
        events: MutableList<String> = mutableListOf(),
    ) = detailActionKeys(
        facts,
        onToggleFavorite = { events += "favorite" },
        onToggleWatchLater = { events += "later" },
        onTogglePlayed = { events += "played" },
        onDownload = { events += "download" },
    )

    @Test
    fun aTitleOnAServerOffersItsEverydayActionsInReadingOrder() {
        val built = keys(facts())
        assertEquals(listOf("收藏", "稍后看", "已看", "下载"), built.map { it.label })
        assertEquals(
            listOf(
                DetailActionKeyIds.FAVORITE,
                DetailActionKeyIds.WATCH_LATER,
                DetailActionKeyIds.PLAYED,
                DetailActionKeyIds.DOWNLOAD,
            ),
            built.map { it.id },
        )
        // The three switches say their state; 下载 is an action and has none until there is a copy.
        assertEquals(listOf("未收藏", "未加入", "未看过", null), built.map { it.stateDescription })
        assertEquals(listOf(false, false, false, null), built.map { it.checked })
    }

    @Test
    fun eachSwitchSaysItsStateAndTheFavouriteSaysWhoseListItIs() {
        val built = keys(facts(favorite = true, watchLater = true, played = true)).associateBy { it.id }
        val favorite = built.getValue(DetailActionKeyIds.FAVORITE)
        assertEquals("服务器收藏", favorite.description)
        assertEquals("已收藏", favorite.stateDescription)
        assertEquals(AppIcons.HeartFilled, favorite.checkedIcon)
        assertEquals("已加入", built.getValue(DetailActionKeyIds.WATCH_LATER).stateDescription)
        assertEquals("已看过", built.getValue(DetailActionKeyIds.PLAYED).stateDescription)
        assertTrue(built.values.filter { it.checked != null }.all { it.checked == true })
    }

    @Test
    fun whatCannotWorkIsLeftOut() {
        // A server that keeps no favourites (Plex).
        assertEquals(listOf("稍后看", "已看", "下载"), keys(facts(favoriteAvailable = false)).map { it.label })
        // Nothing to write to: only a file could still be downloaded.
        assertEquals(listOf("下载"), keys(facts(serverActions = false)).map { it.label })
        // A season's page: 播放's target is no file of its own.
        assertEquals(listOf("收藏", "稍后看", "已看"), keys(facts(downloadable = false)).map { it.label })
    }

    @Test
    fun downloadWaitsForItsFileAndThenSaysHowFarItsCopyHasGot() {
        val resolving = keys(facts(downloadable = null)).last()
        assertFalse(resolving.enabled)
        assertEquals("下载", resolving.label)
        assertTrue(keys(facts()).last().enabled)
        val paused = keys(facts(download = DownloadStatus.Paused)).last()
        assertEquals("已暂停", paused.label)
        assertEquals("下载已暂停", paused.stateDescription)
        assertEquals("下载", paused.description)
        assertEquals("已下载", keys(facts(download = DownloadStatus.Completed)).last().label)
        assertEquals("下载中", keys(facts(download = DownloadStatus.WaitingForWifi)).last().label)
    }

    @Test
    fun aWatchLaterWriteInFlightWaitsAndSaysSo() {
        val later = keys(facts(watchLater = true, watchLaterMutating = true))[1]
        assertTrue(later.busy)
        assertEquals("正在同步", later.stateDescription)
        assertFalse(keys(facts(watchLater = true))[1].busy)
    }

    @Test
    fun eachKeyRunsItsOwnAction() {
        val events = mutableListOf<String>()
        keys(facts(), events).forEach { it.onClick() }
        assertEquals(listOf("favorite", "later", "played", "download"), events)
    }

    @Test
    fun anotherKeyJoinsTheRowAsOneMoreEntry() {
        val trailer = DetailActionKey(id = "trailer", icon = AppIcons.Play, label = "预告片", onClick = {})
        val row = keys(facts()) + trailer
        assertEquals(listOf("收藏", "稍后看", "已看", "下载", "预告片"), row.map { it.label })
        assertEquals("预告片", trailer.description)
        assertNull(trailer.checked)
    }
}
