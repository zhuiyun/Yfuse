package com.yfuse.feature.detail

import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.model.Episode
import com.yfuse.core.offline.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeLiftMenuTest {
    private val marks = mutableListOf<Pair<Set<String>, Boolean>>()

    private fun episode(
        id: String,
        played: Boolean,
        percent: Double? = null,
    ) = Episode(
        id = id,
        name = "Episode $id",
        indexNumber = id.removePrefix("e").toInt(),
        seasonNumber = 1,
        seasonId = "s1",
        overview = null,
        runtimeMinutes = 45,
        primaryTag = null,
        playedPercentage = percent,
        played = played,
        resumePositionTicks = null,
    )

    private val season = listOf(episode("e1", false), episode("e2", true), episode("e3", false), episode("e4", false))

    private fun menu(
        episode: Episode,
        downloaded: Boolean = false,
    ): LiftMenu =
        episodeLiftMenu(
            episode = episode,
            episodes = season,
            artworkUrl = "https://emby.example/e.jpg",
            downloaded = downloaded,
            onOpen = {},
            onPlay = {},
            onMark = { ids, played -> marks += ids to played },
            onDownload = {},
            onSelectFrom = {},
        )

    @Test
    fun theMenuKeepsTheOrderOfTheSingleEpisodeDesign() {
        assertEquals(
            listOf("播放", "标记为已看", "标记此前全部已看", "下载", "从这里开始多选"),
            menu(season[3]).actions.map { it.label },
        )
        assertEquals("第4集 · Episode e4", menu(season[3]).title)
    }

    @Test
    fun markingEverythingEarlierTakesOnlyTheUnwatchedBeforeIt() {
        val earlier = menu(season[3]).actions.first { it.id == "episode.markEarlier" }
        assertEquals("2 集", earlier.detail)
        earlier.onSelect()
        assertEquals(listOf(setOf("e1", "e3") to true), marks)
        assertEquals(setOf("e1", "e3"), unwatchedBefore(season, "e4"))
        assertTrue(unwatchedBefore(season, "e1").isEmpty())
        assertTrue(unwatchedBefore(season, "missing").isEmpty())
    }

    @Test
    fun theFirstEpisodeAndADownloadedOneLeaveTheirRowsOut() {
        val ids = menu(season[0], downloaded = true).actions.map { it.id }
        assertFalse("episode.markEarlier" in ids)
        assertFalse("episode.download" in ids)
        assertTrue("episode.selectFrom" in ids)
    }

    @Test
    fun aWatchedEpisodeOffersUnwatchedAndShowsAFullBar() {
        val watched = menu(season[1])
        watched.actions.first { it.label == "标记为未看" }.onSelect()
        assertEquals(listOf(setOf("e2") to false), marks)
        assertEquals(1f, watched.progress)
        assertEquals("已看完", watched.progressLabel)
        assertEquals(0.25f, menu(episode("e3", false, percent = 25.0)).progress)
        assertNull(menu(season[0]).progress)
    }

    @Test
    fun actionsThatOpenSomethingLetTheLiftGetOutOfTheWayFirst() {
        val actions = menu(season[2]).actions.associateBy { it.id }
        assertTrue(actions.getValue("episode.play").leavesPage)
        assertTrue(actions.getValue("episode.selectFrom").leavesPage)
        assertFalse(actions.getValue("episode.download").leavesPage)
    }

    @Test
    fun anEpisodesDownloadIsNamedByHowFarItHasGot() {
        assertNull(episodeDownloadLabel(null))
        assertEquals("已下载", episodeDownloadLabel(DownloadStatus.Completed))
        assertEquals("下载中", episodeDownloadLabel(DownloadStatus.Queued))
        assertEquals("下载中", episodeDownloadLabel(DownloadStatus.Downloading))
        assertEquals("下载已暂停", episodeDownloadLabel(DownloadStatus.Paused))
        assertEquals("下载失败", episodeDownloadLabel(DownloadStatus.Failed))
    }
}
