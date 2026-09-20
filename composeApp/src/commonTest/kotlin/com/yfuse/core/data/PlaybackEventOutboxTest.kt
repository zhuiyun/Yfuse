package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventOutboxTest {
    @Test
    fun retry_never_regenerates_a_start_that_the_server_already_acknowledged() =
        runTest {
            val outbox = PlaybackEventOutbox(MapSettings())
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
            val delivered = mutableListOf<PlaybackOutboxEventKind>()
            outbox.flush("a") {
                delivered += it.kind
                Result.success(Unit)
            }
            outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 10L, false, "DirectPlay")
            outbox.flush("a") { Result.failure(EmbyErrorException(EmbyError.NotFound)) }
            outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "item", "session", 20L, true, "DirectPlay")

            outbox.retryRejectedReports()
            outbox.flush("a") {
                delivered += it.kind
                Result.success(Unit)
            }

            assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Stopped), delivered)
            assertTrue(outbox.events.value.isEmpty())
        }

    @Test
    fun gone_http_responses_are_isolated_through_the_real_repository_error_mapping() =
        runTest {
            listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone).forEach { status ->
                val repository = testRepo { respond(content = "", status = status) }
                val server = SavedServer("a", "https://emby.example", "Emby", "user", "User", "token")
                val outbox = PlaybackEventOutbox(MapSettings())
                outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")

                outbox.flush("a") { event ->
                    repository.reportPlaybackStarted(
                        server,
                        event.itemId,
                        event.sessionId,
                        event.positionTicks,
                        event.isPaused,
                    )
                }

                assertTrue(outbox.events.value.isEmpty(), "HTTP ${status.value} must leave the active lane")
                assertEquals(1, outbox.rejectedSessions.value.size)
                assertEquals(1L, outbox.rejectedSessionWarnings.value)
            }
        }

    @Test
    fun a_missing_session_is_isolated_while_other_sessions_on_the_same_server_continue() =
        runTest {
            val settings = MapSettings()
            val outbox = PlaybackEventOutbox(settings)
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "gone", "old", 0L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "gone", "old", 10L, true, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "healthy", "new", 0L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "healthy", "new", 20L, true, "DirectPlay")
            val sent = mutableListOf<Pair<String, PlaybackOutboxEventKind>>()

            val result =
                outbox.flush("a") {
                    sent += it.sessionId to it.kind
                    if (it.sessionId ==
                        "old"
                    ) {
                        Result.failure(EmbyErrorException(EmbyError.NotFound))
                    } else {
                        Result.success(Unit)
                    }
                }

            assertEquals(
                listOf(
                    "old" to PlaybackOutboxEventKind.Started,
                    "new" to PlaybackOutboxEventKind.Started,
                    "new" to PlaybackOutboxEventKind.Stopped,
                ),
                sent,
            )
            assertEquals(2, result.deliveredCount)
            assertEquals(0, result.pendingCount)
            assertTrue(outbox.pendingServerIds().isEmpty())
            val restored = PlaybackEventOutbox(settings)
            assertEquals(1L, restored.rejectedSessionWarnings.value)
            assertEquals(
                listOf(
                    PlaybackOutboxEventKind.Started,
                    PlaybackOutboxEventKind.Stopped,
                ),
                restored.rejectedSessions.value.single().events.map {
                    it.kind
                },
            )
            assertEquals(
                10L,
                restored.rejectedSessions.value
                    .single()
                    .events
                    .last()
                    .positionTicks,
            )
        }

    @Test
    fun late_session_events_stay_isolated_and_explicit_retry_preserves_their_order() =
        runTest {
            val settings = MapSettings()
            val outbox = PlaybackEventOutbox(settings)
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
            outbox.flush("a") { Result.failure(EmbyErrorException(EmbyError.NotFound)) }
            outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 10L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "item", "session", 20L, true, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 30L, false, "DirectPlay")
            assertTrue(outbox.events.value.isEmpty())
            assertEquals(1L, outbox.rejectedSessionWarnings.value)

            val restored = PlaybackEventOutbox(settings)
            assertEquals(setOf("a"), restored.retryRejectedReports())
            assertTrue(restored.rejectedSessions.value.isEmpty())
            assertEquals(0L, restored.rejectedSessionWarnings.value)
            val sent = mutableListOf<PlaybackOutboxEvent>()
            restored.flush("a") {
                sent += it
                Result.success(Unit)
            }
            assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Stopped), sent.map { it.kind })
            assertEquals(20L, sent.last().positionTicks)
        }

    @Test
    fun ordinary_client_errors_and_throttling_are_not_silently_discarded() =
        runTest {
            listOf(400, 408, 409, 422, 429, 500, 503).forEach { status ->
                val outbox = PlaybackEventOutbox(MapSettings())
                outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
                val result = outbox.flush("a") { Result.failure(EmbyErrorException(EmbyError.Unknown("HTTP $status"))) }
                assertEquals(1, result.pendingCount)
                assertTrue(outbox.rejectedSessions.value.isEmpty())
                assertEquals(0L, outbox.rejectedSessionWarnings.value)
                assertFalse(result.authenticationRequired)
            }
        }

    @Test
    fun rejected_session_storage_is_bounded_and_warnings_survive_restart() =
        runTest {
            val settings = MapSettings()
            val outbox = PlaybackEventOutbox(settings)
            repeat(40) { index ->
                outbox.enqueue(
                    PlaybackOutboxEventKind.Stopped,
                    "a",
                    "$index",
                    "$index",
                    index.toLong(),
                    true,
                    "DirectPlay",
                )
                outbox.flush("a") { Result.failure(EmbyErrorException(EmbyError.NotFound)) }
            }
            assertEquals(32, outbox.rejectedSessions.value.size)
            assertEquals(40L, outbox.rejectedSessionWarnings.value)
            assertEquals(40L, PlaybackEventOutbox(settings).rejectedSessionWarnings.value)
            assertEquals(
                "39",
                outbox.rejectedSessions.value
                    .last()
                    .events
                    .single()
                    .sessionId,
            )
        }

    @Test
    fun terminal_loss_survives_restart_and_acknowledgement_preserves_new_losses() {
        val settings = MapSettings()
        val outbox = PlaybackEventOutbox(settings, maxEvents = 1)

        fun stop(id: String) = outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", id, id, 1L, true, "DirectPlay")
        stop("one")
        stop("two")
        assertEquals(1L, outbox.droppedTerminalEvents.value)
        assertEquals(1L, PlaybackEventOutbox(settings).droppedTerminalEvents.value)
        val observed = outbox.droppedTerminalEvents.value
        stop("three")
        outbox.acknowledgeDroppedReports(observed)
        assertEquals(1L, outbox.droppedTerminalEvents.value)
        outbox.acknowledgeDroppedReports(1L)
        assertEquals(0L, PlaybackEventOutbox(settings).droppedTerminalEvents.value)
        assertEquals(
            "three",
            outbox.events.value
                .single()
                .itemId,
        )
    }

    @Test
    fun evicting_replaceable_progress_does_not_report_lost_terminal_events() {
        val outbox = PlaybackEventOutbox(MapSettings(), maxEvents = 1)
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "one", "one", 1L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "two", "two", 1L, true, "DirectPlay")
        assertEquals(0L, outbox.droppedTerminalEvents.value)
    }

    @Test
    fun retry_backoff_is_exponential_but_has_a_hard_ceiling() {
        assertEquals(5_000L, playbackOutboxBackoffMs(1))
        assertEquals(10_000L, playbackOutboxBackoffMs(2))
        assertEquals(15L * 60L * 1_000L, playbackOutboxBackoffMs(20))
        assertEquals(playbackOutboxBackoffMs(20), playbackOutboxBackoffMs(Int.MAX_VALUE))
    }

    @Test
    fun progress_snapshots_merge_without_reordering_the_session() {
        var now = 1_000L
        val outbox = PlaybackEventOutbox(MapSettings(), nowEpochMs = { now })
        outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 10L, false, "DirectPlay")
        val progressOrder =
            outbox.events.value
                .last()
                .order
        now++
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, true, "Transcode")

        assertEquals(
            listOf(
                PlaybackOutboxEventKind.Started,
                PlaybackOutboxEventKind.Progress,
            ),
            outbox.events.value.map {
                it.kind
            },
        )
        assertEquals(
            progressOrder,
            outbox.events.value
                .last()
                .order,
        )
        assertEquals(
            20L,
            outbox.events.value
                .last()
                .positionTicks,
        )
        assertTrue(
            outbox.events.value
                .last()
                .isPaused,
        )
        assertEquals(
            "Transcode",
            outbox.events.value
                .last()
                .playMethod,
        )
    }

    @Test
    fun stopped_is_terminal_and_late_callbacks_cannot_resurrect_it() {
        val outbox = PlaybackEventOutbox(MapSettings())
        outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 10L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "item", "session", 30L, true, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 40L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 50L, false, "DirectPlay")

        assertEquals(
            listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Stopped),
            outbox.events.value.map { it.kind },
        )
        assertEquals(
            30L,
            outbox.events.value
                .last()
                .positionTicks,
        )
    }

    @Test
    fun persisted_events_replay_in_order_after_process_recreation() =
        runTest {
            val settings = MapSettings()
            PlaybackEventOutbox(settings).apply {
                enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
                enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, false, "DirectPlay")
                enqueue(PlaybackOutboxEventKind.Stopped, "a", "item", "session", 30L, true, "DirectPlay")
            }

            val restored = PlaybackEventOutbox(settings)
            val delivered = mutableListOf<PlaybackOutboxEventKind>()
            val result =
                restored.flush("a") {
                    delivered += it.kind
                    Result.success(Unit)
                }

            // Stopped compacts redundant progress but preserves the lifecycle boundary.
            assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Stopped), delivered)
            assertEquals(2, result.deliveredCount)
            assertTrue(restored.events.value.isEmpty())
        }

    @Test
    fun transient_failure_backs_off_then_continues_from_the_same_head() =
        runTest {
            var now = 1_000L
            val outbox = PlaybackEventOutbox(MapSettings(), nowEpochMs = { now })
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, false, "DirectPlay")
            var calls = 0

            val failed =
                outbox.flush("a") {
                    calls++
                    Result.failure(IllegalStateException("offline"))
                }
            assertEquals(1, calls)
            assertEquals(6_000L, failed.nextAttemptAtEpochMs)

            outbox.flush("a") {
                calls++
                Result.success(Unit)
            }
            assertEquals(1, calls, "backoff must prevent a busy retry")

            now = 6_000L
            val delivered = mutableListOf<PlaybackOutboxEventKind>()
            outbox.flush("a") {
                calls++
                delivered += it.kind
                Result.success(Unit)
            }
            assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Progress), delivered)
            assertTrue(outbox.events.value.isEmpty())
        }

    @Test
    fun unauthorized_and_forbidden_wait_for_explicit_reauthentication() =
        runTest {
            listOf(
                EmbyErrorException(EmbyError.Unauthorized),
                EmbyErrorException(EmbyError.AccessDenied("proxy")),
            ).forEach { authenticationError ->
                val outbox = PlaybackEventOutbox(MapSettings())
                outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
                var calls = 0
                val blocked =
                    outbox.flush("a") {
                        calls++
                        Result.failure(authenticationError)
                    }
                assertTrue(blocked.authenticationRequired)

                outbox.flush("a") {
                    calls++
                    Result.success(Unit)
                }
                assertEquals(1, calls)

                outbox.resumeAfterAuthentication("a")
                val resumed =
                    outbox.flush("a") {
                        calls++
                        Result.success(Unit)
                    }
                assertFalse(resumed.authenticationRequired)
                assertEquals(2, calls)
                assertTrue(outbox.events.value.isEmpty())
            }
        }

    @Test
    fun flushing_one_server_never_sends_another_servers_events() =
        runTest {
            val outbox = PlaybackEventOutbox(MapSettings())
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "a-item", "a-session", 0L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Started, "b", "b-item", "b-session", 0L, false, "DirectPlay")
            val delivered = mutableListOf<String>()

            outbox.flush("a") {
                delivered += it.serverId
                Result.success(Unit)
            }

            assertEquals(listOf("a"), delivered)
            assertEquals(listOf("b"), outbox.events.value.map { it.serverId })
        }

    @Test
    fun a_slow_server_does_not_block_or_acknowledge_another_server() =
        runTest {
            val outbox = PlaybackEventOutbox(MapSettings())
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "a-item", "a-session", 0L, false, "DirectPlay")
            outbox.enqueue(PlaybackOutboxEventKind.Started, "b", "b-item", "b-session", 0L, false, "DirectPlay")
            val aStarted = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val aFlush =
                async {
                    outbox.flush("a") {
                        aStarted.complete(Unit)
                        releaseA.await()
                        Result.success(Unit)
                    }
                }
            aStarted.await()

            val bResult = outbox.flush("b") { Result.success(Unit) }

            assertEquals(1, bResult.deliveredCount)
            assertEquals(listOf("a"), outbox.events.value.map { it.serverId })
            releaseA.complete(Unit)
            assertEquals(1, aFlush.await().deliveredCount)
            assertTrue(outbox.events.value.isEmpty())
        }

    @Test
    fun capacity_compaction_evicts_progress_before_a_stopped_event() {
        val outbox = PlaybackEventOutbox(MapSettings(), maxEvents = 3)
        outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "one", "s1", 1L, true, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "two", "s2", 2L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "three", "s3", 3L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "four", "s4", 4L, false, "DirectPlay")

        assertEquals(3, outbox.events.value.size)
        assertTrue(outbox.events.value.any { it.kind == PlaybackOutboxEventKind.Stopped })
    }
}
