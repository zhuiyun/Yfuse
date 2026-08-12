package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventOutboxTest {

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
        val progressOrder = outbox.events.value.last().order
        now++
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, true, "Transcode")

        assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Progress), outbox.events.value.map { it.kind })
        assertEquals(progressOrder, outbox.events.value.last().order)
        assertEquals(20L, outbox.events.value.last().positionTicks)
        assertTrue(outbox.events.value.last().isPaused)
        assertEquals("Transcode", outbox.events.value.last().playMethod)
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
        assertEquals(30L, outbox.events.value.last().positionTicks)
    }

    @Test
    fun persisted_events_replay_in_order_after_process_recreation() = runTest {
        val settings = MapSettings()
        PlaybackEventOutbox(settings).apply {
            enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
            enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, false, "DirectPlay")
            enqueue(PlaybackOutboxEventKind.Stopped, "a", "item", "session", 30L, true, "DirectPlay")
        }

        val restored = PlaybackEventOutbox(settings)
        val delivered = mutableListOf<PlaybackOutboxEventKind>()
        val result = restored.flush("a") {
            delivered += it.kind
            Result.success(Unit)
        }

        // Stopped compacts redundant progress but preserves the lifecycle boundary.
        assertEquals(listOf(PlaybackOutboxEventKind.Started, PlaybackOutboxEventKind.Stopped), delivered)
        assertEquals(2, result.deliveredCount)
        assertTrue(restored.events.value.isEmpty())
    }

    @Test
    fun transient_failure_backs_off_then_continues_from_the_same_head() = runTest {
        var now = 1_000L
        val outbox = PlaybackEventOutbox(MapSettings(), nowEpochMs = { now })
        outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "item", "session", 20L, false, "DirectPlay")
        var calls = 0

        val failed = outbox.flush("a") {
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
    fun unauthorized_and_forbidden_wait_for_explicit_reauthentication() = runTest {
        listOf(
            EmbyErrorException(EmbyError.Unauthorized),
            EmbyErrorException(EmbyError.AccessDenied("proxy")),
        ).forEach { authenticationError ->
            val outbox = PlaybackEventOutbox(MapSettings())
            outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "item", "session", 0L, false, "DirectPlay")
            var calls = 0
            val blocked = outbox.flush("a") {
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
            val resumed = outbox.flush("a") {
                calls++
                Result.success(Unit)
            }
            assertFalse(resumed.authenticationRequired)
            assertEquals(2, calls)
            assertTrue(outbox.events.value.isEmpty())
        }
    }

    @Test
    fun flushing_one_server_never_sends_another_servers_events() = runTest {
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
    fun a_slow_server_does_not_block_or_acknowledge_another_server() = runTest {
        val outbox = PlaybackEventOutbox(MapSettings())
        outbox.enqueue(PlaybackOutboxEventKind.Started, "a", "a-item", "a-session", 0L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Started, "b", "b-item", "b-session", 0L, false, "DirectPlay")
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val aFlush = async {
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
