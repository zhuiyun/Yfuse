package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackChainRegressionTest {
    private fun play(
        store: PlaybackSyncStore,
        position: Long,
        trigger: PlaybackSyncTrigger,
    ) = store.updatePlayback(
        "tmdb:1",
        emptyList(),
        position,
        3600000L,
        false,
        "old-session",
        "server-a",
        "item-a",
        PlaybackMutationKind.AutoProgress,
        trigger,
    )

    @Test fun oldSessionMustNotUndoRestart() {
        var now = 1000L
        val store = PlaybackSyncStore(MapSettings()) { now++ }
        play(store, 1200000L, PlaybackSyncTrigger.Started)
        val restarted = store.markRestarted("tmdb:1")
        play(store, 1205000L, PlaybackSyncTrigger.Stop)
        val state = store.find("tmdb:1")!!.document.state
        println(
            "REPRO restart epoch=${restarted.document.state.progressEpoch}; after old Stop position=${state.positionMs}, epoch=${state.progressEpoch}",
        )
        assertEquals(0L, state.positionMs, "Old session Stop revived reset progress")
    }

    @Test fun accountScopeChangeMustHidePreviousAccountProgress() {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        personal.bindAccount("account-a")
        val store = PlaybackSyncStore(settings, { 1000L }, personal)
        store.bindAccount("account-a")
        play(store, 1200000L, PlaybackSyncTrigger.Started)
        personal.bindAccount("account-b")
        val state = store.resolveState("tmdb:1")
        println("REPRO account-b scope=${store.scopeToken}; exposed account-a position=${state?.positionMs}")
        assertNull(state, "A local record remains visible until optional cloud sync binds B")
    }

    @Test fun productionRebindThenUpdateMustNotReportWrongEpisode() =
        runTest {
            val events = mutableListOf<String>()
            val sink =
                object : PlaybackEventSink {
                    override suspend fun started(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "start:$itemId:$positionTicks"
                    }

                    override suspend fun progress(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "progress:$itemId:$positionTicks"
                    }

                    override suspend fun stopped(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "stop:$itemId:$positionTicks"
                    }
                }
            val first = PlayerMediaItem("e1", "direct1", "hls1", "first", playSessionId = "session-1")
            val second = PlayerMediaItem("e2", "direct2", "hls2", "second", playSessionId = "session-2")
            val reporter = PlaybackProgressReporter(listOf(first, second), sink, backgroundScope)
            reporter.update(PlaybackState(playing = true, currentIndex = 1, positionMs = 1000L, itemCount = 2))
            runCurrent()
            val oldIndex = PlaybackState(playing = true, currentIndex = 1, positionMs = 2000L, itemCount = 2)
            reporter.rebind(playbackReportingQueue(listOf(first, second), listOf(second, first)), oldIndex)
            reporter.update(oldIndex)
            runCurrent()
            reporter.rebind(listOf(second, first), oldIndex.copy(currentIndex = 0))
            reporter.update(oldIndex.copy(currentIndex = 0))
            runCurrent()
            reporter.close(oldIndex.copy(currentIndex = 0, playing = false, positionMs = 3000L))
            runCurrent()
            println("REPRO production rebind/update events=$events")
            assertEquals(emptyList(), events.filter { it.contains(":e1:") }, "e2's clock was reported as e1")
        }

    @Test fun retiringServerBReporterMustNotStartNextServerAItem() =
        runTest {
            val serverBEvents = mutableListOf<String>()
            val sinkB =
                object : PlaybackEventSink {
                    override suspend fun started(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        serverBEvents +=
                            "start:$itemId:$positionTicks"
                    }

                    override suspend fun progress(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        serverBEvents +=
                            "progress:$itemId:$positionTicks"
                    }

                    override suspend fun stopped(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        serverBEvents +=
                            "stop:$itemId:$positionTicks"
                    }
                }
            val currentB =
                PlayerMediaItem("e1B", "directB", "hlsB", "first", serverId = "server-b", playSessionId = "session-B")
            val nextA =
                PlayerMediaItem("e2A", "directA", "hlsA", "second", serverId = "server-a", playSessionId = "session-A")
            val oldReporter = PlaybackProgressReporter(listOf(currentB, nextA), sinkB, backgroundScope)
            oldReporter.update(PlaybackState(playing = true, currentIndex = 0, positionMs = 1000L, itemCount = 2))
            runCurrent()
            oldReporter.close(PlaybackState(playing = true, currentIndex = 1, positionMs = 2000L, itemCount = 2))
            runCurrent()
            println("REPRO retiring server-B sink events=$serverBEvents")
            assertEquals(
                emptyList(),
                serverBEvents.filter {
                    it.contains(":e2A:")
                },
                "A's next episode was sent to retired server-B sink",
            )
        }

    @Test fun the_live_actor_routes_each_episodes_events_to_its_own_server() =
        runTest {
            val events = mutableListOf<String>()

            fun sink(target: String) =
                object : PlaybackEventSink {
                    override suspend fun started(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "$target:start:$itemId"
                    }

                    override suspend fun progress(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "$target:progress:$itemId"
                    }

                    override suspend fun stopped(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        events +=
                            "$target:stop:$itemId"
                    }
                }
            val a = sink("a")
            val b = sink("b")
            val queue =
                listOf(
                    PlayerMediaItem("firstB", "directB", "", "First", serverId = "b"),
                    PlayerMediaItem("secondA", "directA", "", "Second", serverId = "a"),
                )
            val reporter =
                PlaybackProgressReporter(queue, b, backgroundScope, sinkForItem = {
                    if (it.serverId ==
                        "a"
                    ) {
                        a
                    } else {
                        b
                    }
                })
            reporter.update(PlaybackState(currentIndex = 0, positionMs = 1000L))
            runCurrent()
            reporter.update(PlaybackState(currentIndex = 1, positionMs = 2000L))
            runCurrent()
            reporter.update(PlaybackState(currentIndex = 1, positionMs = 12000L))
            runCurrent()
            reporter.close(PlaybackState(currentIndex = 1, positionMs = 13000L))
            runCurrent()
            assertEquals(
                listOf("b:start:firstB", "b:stop:firstB", "a:start:secondA", "a:progress:secondA", "a:stop:secondA"),
                events,
            )
        }
}
