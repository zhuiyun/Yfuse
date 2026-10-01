package com.yfuse.feature.detail

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.model.Episode
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DetailReducerTest {
    private val server = SavedServer("one", "http://one", "Server", "u", "User", "token")
    private val movie = BaseItemDto(Id = "m1", Name = "电影", Type = "Movie").toMediaDetail()

    private fun DetailState.after(vararg messages: DetailMsg): DetailState =
        messages.fold(this) { state, msg -> with(DetailReducer) { state.reduce(msg) } }

    private fun episode(
        id: String,
        seasonId: String,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = 1,
        seasonNumber = 1,
        seasonId = seasonId,
        overview = null,
        runtimeMinutes = 45,
        primaryTag = null,
        playedPercentage = null,
        resumePositionTicks = null,
    )

    @Test
    fun back_from_the_player_the_unchanged_target_resumes_from_where_it_was_left() {
        val before = DetailState(detail = movie, server = server, playServer = server, playTarget = movie)

        val watched = before.after(DetailMsg.PlayPositionSynced("one", "m1", 12_000_000_000L))
        assertEquals(12_000_000_000L, watched.playPositionTicks)

        // Played to the end the record reads 0: 播放 again, with nothing for 从头 to rewind.
        val finished = watched.after(DetailMsg.PlayPositionSynced("one", "m1", 0L))
        assertEquals(0L, finished.playPositionTicks)

        // Read for a target the page has moved off since, or on another server: not this one's.
        assertSame(watched, watched.after(DetailMsg.PlayPositionSynced("one", "m2", 5L)))
        assertSame(watched, watched.after(DetailMsg.PlayPositionSynced("two", "m1", 5L)))
    }

    @Test
    fun the_listed_season_trails_a_pick_until_its_episodes_land() {
        val seasons =
            listOf(
                Season("s1", "第 1 季", 1, null),
                Season("s2", "第 2 季", 2, null),
                Season("s3", "第 3 季", 3, null),
            )
        val listed =
            DetailState(seasons = seasons)
                .after(
                    DetailMsg.SeasonsLoaded(seasons, "s1"),
                    DetailMsg.EpisodesLoaded(listOf(episode("a", "s1"))),
                )
        assertEquals("s1", listed.listedSeasonId)

        // Two picks before either has loaded: the episodes on show are still the first season's.
        val picking =
            listed.after(
                DetailMsg.EpisodesLoading,
                DetailMsg.SeasonsLoaded(seasons, "s2"),
                DetailMsg.EpisodesLoading,
                DetailMsg.SeasonsLoaded(seasons, "s3"),
            )
        assertEquals("s3", picking.selectedSeasonId)
        assertEquals("s1", picking.listedSeasonId)

        // The last pick failing goes back to the season those episodes belong to.
        val failed =
            picking.after(
                DetailMsg.SeasonsLoaded(seasons, picking.listedSeasonId),
                DetailMsg.EpisodesLoadingFinished,
            )
        assertEquals("s1", failed.selectedSeasonId)
        assertEquals(listOf("a"), failed.episodes.map { it.id })

        val landed = picking.after(DetailMsg.EpisodesLoaded(listOf(episode("c", "s3"))))
        assertEquals("s3", landed.listedSeasonId)
    }

    @Test
    fun a_dismissed_resource_failure_does_not_come_back_after_the_next_notice() {
        val failed =
            DetailState(actionMessage = "正在切换资源，完成后将自动播放")
                .after(DetailMsg.SourceFailure(SourceSelectionFailure.NetworkUnavailable))
        // The failure is what the 提示 says now, not something waiting behind an older notice.
        assertNull(failed.actionMessage)
        assertEquals(SourceSelectionFailure.NetworkUnavailable, failed.sourceFailure)

        val dismissed = failed.after(DetailMsg.MessageDismissed)
        assertNull(dismissed.sourceFailure)

        val nextNotice = dismissed.after(DetailMsg.ActionMessage("已加入收藏"), DetailMsg.MessageDismissed)
        assertNull(nextNotice.actionMessage)
        assertNull(nextNotice.sourceFailure)
    }
}
