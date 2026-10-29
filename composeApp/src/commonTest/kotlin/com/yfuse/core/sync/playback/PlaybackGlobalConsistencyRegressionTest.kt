package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackProgressProjection
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackGlobalConsistencyRegressionTest {
    @Test
    fun library_card_and_resume_share_the_latest_progress_after_switching_servers() {
        var now = 1_000L
        val store = PlaybackSyncStore(MapSettings()) { now++ }
        store.record("a", "item-a", 10_000L, PlaybackSyncTrigger.Started)
        store.record("b", "item-b", 20_000L, PlaybackSyncTrigger.Stop)
        val server = SavedServer("a", "http://host", "A", "user", "User", "token")
        val item = BaseItemDto(Id = "item-a", Type = "Movie", ProviderIds = mapOf("Tmdb" to "1"))
        val resume = store.authoritativeStartPositionMs("tmdb:1", listOf("emby:item-a"), "a", serverItemId = "item-a")
        assertEquals(20_000L, resume)
        assertEquals(
            resume!! * 10_000L,
            PlaybackProgressProjection(store).project(server, item).UserData?.PlaybackPositionTicks,
        )
    }

    @Test
    fun session_history_ends_at_the_actual_stop_after_a_backward_seek() {
        var now = 1_000L
        val store = PlaybackSyncStore(MapSettings()) { now++ }
        store.record("a", "item-a", 1_200_000L, PlaybackSyncTrigger.Started)
        store.record("a", "item-a", 600_000L, PlaybackSyncTrigger.Seek)
        val stopped = store.record("a", "item-a", 600_000L, PlaybackSyncTrigger.Stop)
        assertEquals(600_000L, stopped.document.state.positionMs)
        assertEquals(
            600_000L,
            stopped.document.history
                .single()
                .endPositionMs,
        )
    }

    @Test
    fun shared_bindings_survive_restart_and_server_pull_without_duplicate_records() {
        val settings = MapSettings()
        var now = 1_000L
        val store = PlaybackSyncStore(settings) { now++ }
        store.record("a", "item-a", 10_000L, PlaybackSyncTrigger.Started)
        store.record("b", "item-b", 20_000L, PlaybackSyncTrigger.Stop)
        val restored = PlaybackSyncStore(settings) { now++ }
        restored.absorbServerProgressBatch(
            "a",
            listOf(PlaybackSyncStore.ServerProgressInput("item-a", 5_000L, false)),
            restored.scopeToken,
        )
        assertEquals(20_000L, restored.stateForServerItem("a", "item-a")?.positionMs)
        assertEquals(20_000L, restored.statesForServer("a").single().positionMs)
        assertEquals(1, restored.pending().size)
    }

    @Test
    fun remote_backward_seek_updates_in_progress_history_before_stop() {
        var now = 1_000L
        val source = PlaybackSyncStore(MapSettings()) { now++ }
        val target = PlaybackSyncStore(MapSettings()) { now++ }
        val start = source.record("a", "item-a", 1_200_000L, PlaybackSyncTrigger.Started)
        target.applyRemote(start.document, "cloud", 1L)
        val seek = source.record("a", "item-a", 600_000L, PlaybackSyncTrigger.Seek)
        target.applyRemote(seek.document, "cloud", 2L)
        assertEquals(
            600_000L,
            target
                .find("tmdb:1")
                ?.document
                ?.history
                ?.single()
                ?.endPositionMs,
        )
    }

    @Test
    fun backward_wall_clock_keeps_local_writes_monotonic_and_merge_order_independent() {
        var now = 1_000L
        val source = PlaybackSyncStore(MapSettings()) { now }
        val first = source.record("a", "item-a", 1_200_000L, PlaybackSyncTrigger.Started).document
        now = 500L
        val second = source.record("a", "item-a", 600_000L, PlaybackSyncTrigger.Seek).document
        val third =
            first.copy(
                state = first.state.copy(deviceId = "tv", lastPlayedAtEpochMs = 750L, positionMs = 900_000L),
            )
        val states = listOf(first, second, third)
        for (a in states) {
            for (b in states.filter { it !== a }) {
                val c = states.single { it !== a && it !== b }
                assertEquals(
                    600_000L,
                    PlaybackConflictResolver.merge(PlaybackConflictResolver.merge(a, b), c).state.positionMs,
                )
            }
        }
    }

    @Test
    fun episode_context_binds_existing_component_progress_without_creating_zero_records() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.updatePlayback(
            "tmdb:9/s1e2",
            emptyList(),
            60_000L,
            3_600_000L,
            false,
            "component",
            null,
            null,
            PlaybackMutationKind.AutoProgress,
            PlaybackSyncTrigger.Stop,
        )
        val server = SavedServer("a", "http://host", "A", "user", "User", "token")
        val item = BaseItemDto(Id = "episode", Type = "Episode", ParentIndexNumber = 1, IndexNumber = 2)
        val projection = PlaybackProgressProjection(store)
        assertEquals(
            600_000_000L,
            projection.project(server, item, mapOf("Tmdb" to "9")).UserData?.PlaybackPositionTicks,
        )
        assertEquals(600_000_000L, projection.project(server, item).UserData?.PlaybackPositionTicks)
        val revision = store.changes.value
        projection.project(server, item, mapOf("Tmdb" to "9"))
        assertEquals(revision, store.changes.value)
        projection.project(server, item.copy(Id = "unwatched", IndexNumber = 3), mapOf("Tmdb" to "9"))
        assertEquals(null, store.stateForServerItem("a", "unwatched"))
        assertEquals(1, store.pending().size)
    }

    private fun PlaybackSyncStore.record(
        server: String,
        item: String,
        position: Long,
        trigger: PlaybackSyncTrigger,
    ) = updatePlayback(
        "tmdb:1",
        listOf("emby:$item"),
        position,
        3_600_000L,
        false,
        "session-$server",
        server,
        item,
        PlaybackMutationKind.AutoProgress,
        trigger,
    )
}
