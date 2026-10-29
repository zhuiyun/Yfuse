package com.yfuse.feature.detail

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.model.Episode
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackStateRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailLocalProgressTest {
    private val server = SavedServer("one", "http://one", "Server", "u", "User", "token")
    private val target = BaseItemDto(Id = "e1", Name = "Episode", Type = "Episode").toMediaDetail()
    private val episode =
        Episode(
            id = "e1",
            name = "Episode",
            indexNumber = 1,
            seasonNumber = 1,
            seasonId = "season",
            overview = null,
            runtimeMinutes = 2,
            primaryTag = null,
            playedPercentage = null,
            resumePositionTicks = null,
        )
    private val initial =
        DetailState(
            server = server,
            detail = target,
            playServer = server,
            playSourceDetail = target,
            playTarget = target,
            episodes = listOf(episode),
            selectedEpisodeId = "e1",
            selectedSeasonId = "season",
            progressSelection = setOf("e1"),
        )

    private fun record(
        positionMs: Long,
        played: Boolean = false,
    ) = PlaybackStateRecord(
        mediaKey = "emby:e1",
        positionMs = positionMs,
        durationMs = 120_000L,
        played = played,
        deviceId = "device",
        serverId = server.id,
        serverItemId = "e1",
    )

    private fun DetailState.project(record: PlaybackStateRecord?): DetailState =
        with(DetailReducer) {
            reduce(DetailMsg.LocalProgressSynced(localProgressSnapshot { _, _ -> record }))
        }

    @Test
    fun local_updates_refresh_target_and_episode_without_changing_selection() {
        val updated = initial.project(record(30_000L))
        assertEquals(300_000_000L, updated.playPositionTicks)
        assertEquals(300_000_000L, updated.detail?.resumePositionTicks)
        assertEquals(300_000_000L, updated.playTarget?.resumePositionTicks)
        assertEquals(300_000_000L, updated.playSourceDetail?.resumePositionTicks)
        assertEquals(300_000_000L, updated.episodes.single().resumePositionTicks)
        assertEquals(25.0, updated.episodes.single().playedPercentage)
        assertEquals(initial.selectedSeasonId, updated.selectedSeasonId)
        assertEquals(initial.selectedEpisodeId, updated.selectedEpisodeId)
        assertEquals(initial.progressSelection, updated.progressSelection)
        assertEquals(updated, updated.project(record(30_000L)))
    }

    @Test
    fun final_write_after_resume_updates_completion_then_reset_clears_it() {
        val resumed = initial.project(record(30_000L))
        val completed = resumed.project(record(120_000L, played = true))
        assertEquals(0L, completed.playPositionTicks)
        assertEquals(true, completed.detail?.played)
        assertEquals(true, completed.playTarget?.played)
        assertEquals(true, completed.episodes.single().played)
        assertNull(completed.episodes.single().playedPercentage)
        assertNull(completed.episodes.single().resumePositionTicks)
        val reset = completed.project(record(0L))
        assertEquals(false, reset.detail?.played)
        assertEquals(false, reset.episodes.single().played)
        assertEquals(0L, reset.playPositionTicks)
    }

    @Test
    fun missing_records_clear_cached_progress_and_other_servers_cannot_overwrite_it() {
        val stale = initial.project(record(30_000L))
        val cleared = stale.project(null)
        assertEquals(0L, cleared.playPositionTicks)
        assertNull(cleared.episodes.single().resumePositionTicks)
        assertEquals(false, cleared.episodes.single().played)
        val foreign = mapOf(("two" to "e1") to record(50_000L))
        assertEquals(stale, stale.withLocalProgress(foreign))
        val reads = mutableListOf<Pair<String, String>>()
        initial.localProgressSnapshot { serverId, itemId ->
            reads += serverId to itemId
            null
        }
        assertEquals(setOf("one" to "e1"), reads.toSet())
    }

    @Test
    fun progress_uses_each_items_own_server_when_resource_is_switched() {
        val other = server.copy(id = "two")
        val state = initial.copy(playServer = other)
        val progress =
            state.localProgressSnapshot { serverId, _ ->
                record(if (serverId == "one") 10_000L else 50_000L)
            }
        val updated = state.withLocalProgress(progress)
        assertEquals(100_000_000L, updated.detail?.resumePositionTicks)
        assertEquals(500_000_000L, updated.playPositionTicks)
        assertEquals(500_000_000L, updated.episodes.single().resumePositionTicks)
    }

    @Test
    fun observation_handles_late_writes_visibility_and_newly_loaded_items() =
        runTest {
            val states = MutableStateFlow(initial)
            val visible = MutableStateFlow(true)
            val changes = MutableStateFlow(0L)
            var current = record(30_000L)
            val received = mutableListOf<Map<Pair<String, String>, PlaybackStateRecord?>?>()
            val job =
                localDetailProgress(states, visible, changes) { _, _ -> current }
                    .onEach { received += it }
                    .launchIn(backgroundScope)
            runCurrent()
            assertEquals(30_000L, received.last()?.get("one" to "e1")?.positionMs)
            // The final stop write may arrive after the detail page is already resumed.
            current = record(45_000L)
            changes.value++
            runCurrent()
            assertEquals(45_000L, received.last()?.get("one" to "e1")?.positionMs)
            visible.value = false
            runCurrent()
            val hiddenCount = received.size
            current = record(60_000L)
            changes.value++
            runCurrent()
            assertEquals(hiddenCount, received.size)
            visible.value = true
            runCurrent()
            assertEquals(60_000L, received.last()?.get("one" to "e1")?.positionMs)
            // A late cached metadata response must be corrected even when the record is unchanged.
            val beforeMetadata = received.size
            states.value = initial.copy(playPositionTicks = 1L)
            runCurrent()
            assertEquals(beforeMetadata + 1, received.size)
            assertEquals(60_000L, received.last()?.get("one" to "e1")?.positionMs)
            states.value = initial.copy(playTarget = target.copy(id = "e2"))
            runCurrent()
            assertEquals(60_000L, received.last()?.get("one" to "e2")?.positionMs)
            job.cancel()
        }

    @Test
    fun invalid_and_extreme_positions_do_not_overflow() {
        assertEquals(0L, initial.project(record(-1L)).playPositionTicks)
        assertEquals(Long.MAX_VALUE, initial.project(record(Long.MAX_VALUE)).playPositionTicks)
    }

    @Test
    fun exact_library_progress_wins_and_component_progress_is_only_a_missing_record_fallback() {
        val local =
            com.yfuse.core.sync.playback
                .PlaybackSyncStore(com.russhwolf.settings.MapSettings()) { 1000L }
        local.updatePlayback(
            "emby:e1",
            listOf("emby:e1"),
            10_000L,
            1_000_000L,
            false,
            "old",
            server.id,
            "e1",
            com.yfuse.core.sync.playback.PlaybackMutationKind.AutoProgress,
            com.yfuse.core.sync.playback.PlaybackSyncTrigger.Stop,
        )
        local.updatePlayback(
            "tmdb:42/s2e3",
            listOf("tmdb:42/s2e3"),
            123_000L,
            1_000_000L,
            false,
            "session",
            null,
            null,
            com.yfuse.core.sync.playback.PlaybackMutationKind.AutoProgress,
            com.yfuse.core.sync.playback.PlaybackSyncTrigger.Stop,
        )
        val series = target.copy(id = "series", type = "Series", providerIds = mapOf("Tmdb" to "42"))
        val state =
            initial.copy(
                detail = series,
                playSourceDetail = series,
                playTarget = target.copy(seriesId = "series", seasonNumber = 2, episodeNumber = 3),
                episodes = listOf(episode.copy(seasonNumber = 2, indexNumber = 3)),
            )
        assertEquals(10_000L, state.localProgressForItem(local, server.id, "e1")?.positionMs)
        val missing =
            state.copy(
                playTarget = state.playTarget?.copy(id = "unrecorded"),
                episodes = listOf(episode.copy(id = "unrecorded", seasonNumber = 2, indexNumber = 3)),
            )
        assertEquals(123_000L, missing.localProgressForItem(local, server.id, "unrecorded")?.positionMs)
        val otherEpisode = state.copy(episodes = listOf(episode.copy(id = "e4", seasonNumber = 2, indexNumber = 4)))
        assertNull(otherEpisode.localProgressForItem(local, server.id, "e4"))
    }
}
