package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackOutboxEventKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackReportingCoordinatorTest {
    @Test
    fun concurrentFlushPassesShareTheOrderedServerLane() =
        runTest {
            val deliveredPaths = mutableListOf<String>()
            val repo =
                testRepo { request ->
                    synchronized(deliveredPaths) {
                        deliveredPaths += request.url.encodedPath
                    }
                    json("{}")
                }
            val registry = testRegistry()
            val server =
                SavedServer(
                    id = "server",
                    baseUrl = "https://emby.example",
                    serverName = "Emby",
                    userId = "user",
                    userName = "User",
                    accessToken = "token",
                )
            registry.addOrUpdate(server)
            val outbox = PlaybackEventOutbox(MapSettings())
            enqueue(outbox, PlaybackOutboxEventKind.Started, positionTicks = 10L)
            enqueue(outbox, PlaybackOutboxEventKind.Progress, positionTicks = 20L)
            val coordinator = PlaybackReportingCoordinator(repo, registry, outbox)

            val summaries =
                listOf(
                    async { coordinator.flushPendingOnce() },
                    async { coordinator.flushPendingOnce() },
                ).awaitAll()

            assertEquals(
                listOf("/Sessions/Playing", "/Sessions/Playing/Progress"),
                synchronized(deliveredPaths) { deliveredPaths.toList() },
            )
            assertEquals(2, summaries.sumOf(PlaybackOutboxFlushSummary::deliveredCount))
            assertTrue(outbox.events.value.isEmpty())
            assertTrue(summaries.all { !it.hasRetryablePending })
        }

    @Test
    fun removedServerStaysQueuedWithoutCreatingAWorkerRetryLoop() =
        runTest {
            val outbox = PlaybackEventOutbox(MapSettings())
            enqueue(outbox, PlaybackOutboxEventKind.Started, positionTicks = 10L)
            val coordinator =
                PlaybackReportingCoordinator(
                    repository = testRepo { error("No request expected") },
                    registry = testRegistry(),
                    outbox = outbox,
                )

            val summary = coordinator.flushPendingOnce()

            assertEquals(1, summary.pendingCount)
            assertTrue(summary.unavailableServer)
            assertFalse(summary.hasRetryablePending)
            assertEquals(1, outbox.events.value.size)
        }

    @Test
    fun disabledProgressSyncKeepsRemoteReportsLocalOnly() =
        runTest {
            var requests = 0
            val repo =
                testRepo {
                    requests++
                    error("Disabled progress sync must not reach HTTP")
                }
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer(
                    id = "server",
                    baseUrl = "https://emby.example",
                    serverName = "Emby",
                    userId = "user",
                    userName = "User",
                    accessToken = "token",
                ),
            )
            val outbox = PlaybackEventOutbox(MapSettings())
            val coordinator =
                PlaybackReportingCoordinator(
                    repository = repo,
                    registry = registry,
                    outbox = outbox,
                    progressSyncEnabled = MutableStateFlow(false),
                )

            coordinator
                .sinkFor("server")!!
                .progress(
                    itemId = "item",
                    sessionId = "session",
                    positionTicks = 20L,
                    isPaused = false,
                )

            assertTrue(outbox.events.value.isEmpty())
            assertEquals(0, requests)

            enqueue(outbox, PlaybackOutboxEventKind.Progress, positionTicks = 30L)
            val summary = coordinator.flushPendingOnce()

            assertEquals(1, summary.pendingCount)
            assertFalse(summary.hasRetryablePending)
            assertEquals(1, outbox.events.value.size)
            assertEquals(0, requests)
        }

    private fun enqueue(
        outbox: PlaybackEventOutbox,
        kind: PlaybackOutboxEventKind,
        positionTicks: Long,
    ) {
        outbox.enqueue(
            kind = kind,
            serverId = "server",
            itemId = "item",
            sessionId = "session",
            positionTicks = positionTicks,
            isPaused = false,
            playMethod = "DirectPlay",
        )
    }
}
