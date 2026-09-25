package com.yfuse.feature.detail

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.model.Episode
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeProgressReducerTest {
    private fun episode(
        id: String,
        played: Boolean,
        position: Long? = null,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = id.removePrefix("e").toInt(),
        seasonNumber = 1,
        seasonId = "s1",
        overview = null,
        runtimeMinutes = 45,
        primaryTag = null,
        playedPercentage = position?.let { 25.0 },
        played = played,
        resumePositionTicks = position,
    )

    @Test
    fun progress_manager_selection_and_apply_are_reduced_atomically() {
        val original =
            DetailState(
                episodes =
                    listOf(
                        episode("e1", played = false, position = 100L),
                        episode("e2", played = true),
                    ),
            )
        val opened = with(DetailReducer) { original.reduce(DetailMsg.ProgressManagerOpened) }
        val selected =
            with(DetailReducer) {
                opened.reduce(DetailMsg.ProgressSelectionChanged(setOf("e1")))
            }
        val applied =
            with(DetailReducer) {
                selected.reduce(
                    DetailMsg.EpisodesProgressChanged(
                        episodeIds = setOf("e1"),
                        played = true,
                        message = "已更新",
                    ),
                )
            }

        assertTrue(opened.progressManagerOpen)
        assertEquals(setOf("e1"), selected.progressSelection)
        assertTrue(applied.episodes.first().played)
        assertEquals(null, applied.episodes.first().resumePositionTicks)
        assertFalse(applied.progressManagerOpen)
        assertEquals("已更新", applied.actionMessage)
    }

    private val server = SavedServer("one", "http://one", "Server", "u", "User", "token")
    private val series = BaseItemDto(Id = "s1", Name = "剧集", Type = "Series").toMediaDetail()

    @Test
    fun marking_the_whole_series_moves_the_rail_and_the_play_key_with_it() {
        val watching =
            DetailState(
                detail = series,
                server = server,
                playTarget = BaseItemDto(Id = "e9", Type = "Episode").toMediaDetail(),
                playPositionTicks = 12_000_000L,
                episodes = listOf(episode("e1", played = true), episode("e2", played = false, position = 100L)),
            )

        val unplayed =
            with(DetailReducer) {
                watching.reduce(DetailMsg.SeriesProgressChanged("one", "s1", played = false, message = "已标记为未看"))
            }

        assertEquals(listOf(false, false), unplayed.episodes.map { it.played })
        assertEquals(listOf(null, null), unplayed.episodes.map { it.resumePositionTicks })
        // 播放's target sits in a season the rail is not showing; its resume point is gone all the same.
        assertEquals(0L, unplayed.playPositionTicks)
        assertEquals("已标记为未看", unplayed.actionMessage)

        val otherTitle =
            with(DetailReducer) {
                watching.reduce(DetailMsg.SeriesProgressChanged("one", "s2", played = true, message = "x"))
            }
        assertEquals(watching, otherTitle)
    }

    @Test
    fun a_played_mark_on_the_title_that_would_play_drops_its_resume_point() {
        val movie = BaseItemDto(Id = "m1", Type = "Movie").toMediaDetail()
        val resumable =
            DetailState(detail = movie, server = server, playTarget = movie, playPositionTicks = 40_000_000L)

        val played = with(DetailReducer) { resumable.reduce(DetailMsg.PlayedChanged("one", "m1", true)) }

        assertTrue(played.detail?.played == true)
        assertEquals(0L, played.playPositionTicks)
    }

    @Test
    fun the_series_confirmation_names_every_season_and_what_is_lost() {
        assertEquals(
            "《剧集》全部 3 季的所有剧集都会标记为已看，续播进度会一并清除。",
            seriesProgressConfirmMessage("剧集", seasonCount = 3, markPlayed = true),
        )
        assertEquals(
            "《剧集》所有剧集的已看记录和续播进度都会清除。",
            seriesProgressConfirmMessage("剧集", seasonCount = 1, markPlayed = false),
        )
    }
}
