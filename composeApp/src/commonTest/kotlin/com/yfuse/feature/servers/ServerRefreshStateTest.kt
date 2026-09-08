package com.yfuse.feature.servers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRefreshStateTest {
    private val success = Result.success(Unit)
    private val failure = Result.failure<Unit>(IllegalStateException("unavailable"))

    @Test
    fun success_requires_both_actual_checks_and_partial_failures_are_not_hidden() {
        val ids = listOf("a", "b")
        val healthy = ids.associateWith { success }
        assertEquals(ServerRefreshResult.Success, summarizeServerRefresh(ids, healthy, healthy).result)
        val partial = summarizeServerRefresh(ids, healthy, mapOf("a" to success, "b" to failure))
        assertEquals(ServerRefreshResult.PartialFailure, partial.result)
        assertEquals(1, partial.failedServers)
        assertEquals(2, partial.totalServers)
        // Every server can have one failed operation: this is partial refresh, not total success.
        assertEquals(
            ServerRefreshResult.PartialFailure,
            summarizeServerRefresh(ids, healthy, ids.associateWith { failure }).result,
        )
        assertEquals(ServerRefreshResult.Failure, summarizeServerRefresh(ids, emptyMap(), emptyMap()).result)
        assertEquals(ServerRefreshResult.Empty, summarizeServerRefresh(emptyList(), healthy, healthy).result)
    }

    @Test
    fun a_cancelled_result_propagates_cancellation_instead_of_becoming_a_failed_or_successful_check() {
        assertFailsWith<CancellationException> {
            summarizeServerRefresh(
                listOf("a"),
                mapOf("a" to success),
                mapOf("a" to Result.failure(CancellationException("cancelled"))),
            )
        }
    }

    @Test
    fun explicit_refresh_owns_one_generation_and_clears_the_previous_outcome_before_running() =
        runTest {
            var pending = CompletableDeferred<ServerRefreshOutcome>()
            var calls = 0
            val controller =
                ServerRefreshController(backgroundScope) {
                    calls++
                    pending.await()
                }
            assertEquals(ServerRefreshState(), controller.state.value)
            assertEquals(1L, controller.refreshAll())
            assertNull(controller.refreshAll())
            assertTrue(controller.state.value.refreshing)
            assertNull(controller.state.value.outcome)
            runCurrent()
            assertEquals(1, calls)
            pending.complete(ServerRefreshOutcome(ServerRefreshResult.Success, totalServers = 2))
            runCurrent()
            assertFalse(controller.state.value.refreshing)
            assertEquals(
                ServerRefreshResult.Success,
                controller.state.value.outcome
                    ?.result,
            )

            pending = CompletableDeferred()
            assertEquals(2L, controller.refreshAll())
            assertNull(controller.state.value.outcome)
            runCurrent()
            assertEquals(2, calls)
            pending.complete(ServerRefreshOutcome(ServerRefreshResult.PartialFailure, 2, 1))
            runCurrent()
            assertEquals(
                ServerRefreshResult.PartialFailure,
                controller.state.value.outcome
                    ?.result,
            )
        }

    @Test
    fun thrown_failure_has_an_explicit_result_after_the_spinner_stops() =
        runTest {
            val controller = ServerRefreshController(backgroundScope) { error("connection failed") }
            controller.refreshAll()
            runCurrent()
            assertFalse(controller.state.value.refreshing)
            assertEquals(
                ServerRefreshResult.Failure,
                controller.state.value.outcome
                    ?.result,
            )
        }

    @Test
    fun cancellation_during_work_or_before_launch_never_becomes_success_or_a_stuck_spinner() =
        runTest {
            val owner = CoroutineScope(coroutineContext + Job())
            val started = CompletableDeferred<Unit>()
            val controller =
                ServerRefreshController(owner) {
                    started.complete(Unit)
                    CompletableDeferred<ServerRefreshOutcome>().await()
                }
            controller.refreshAll()
            runCurrent()
            assertTrue(started.isCompleted)
            owner.cancel()
            runCurrent()
            assertFalse(controller.state.value.refreshing)
            assertEquals(
                ServerRefreshResult.Cancelled,
                controller.state.value.outcome
                    ?.result,
            )

            val beforeLaunch = ServerRefreshController(owner) { error("must not execute") }
            beforeLaunch.refreshAll()
            runCurrent()
            assertFalse(beforeLaunch.state.value.refreshing)
            assertEquals(
                ServerRefreshResult.Cancelled,
                beforeLaunch.state.value.outcome
                    ?.result,
            )
        }

    @Test
    fun bootstrap_background_return_and_old_generations_cannot_replay_completion_feedback() {
        val outcome = ServerRefreshOutcome(ServerRefreshResult.Success, totalServers = 2)
        val completed = ServerRefreshState(generation = 7L, outcome = outcome)
        assertNull(serverRefreshFeedback(ServerRefreshState(), null, visible = true))
        assertNull(serverRefreshFeedback(completed, null, visible = true))
        assertNull(serverRefreshFeedback(completed, 6L, visible = true))
        assertNull(serverRefreshFeedback(completed, 7L, visible = false))
        assertNull(serverRefreshFeedback(completed.copy(refreshing = true), 7L, visible = true))
        assertEquals(outcome, serverRefreshFeedback(completed, 7L, visible = true))
        // Leaving clears the request token; a newly composed page also starts with null.
        assertNull(serverRefreshFeedback(completed, null, visible = true))
    }

    @Test
    fun background_completion_remains_suppressed_after_return_until_a_new_explicit_refresh() =
        runTest {
            val pending = CompletableDeferred<ServerRefreshOutcome>()
            val controller = ServerRefreshController(backgroundScope) { pending.await() }
            val generation = controller.refreshAll()
            runCurrent()
            controller.suppressFeedback()
            pending.complete(ServerRefreshOutcome(ServerRefreshResult.Success, totalServers = 1))
            runCurrent()
            // Real completion remains available, but resuming UI collection cannot resurrect its toast.
            assertEquals(
                ServerRefreshResult.Success,
                controller.state.value.outcome
                    ?.result,
            )
            assertNull(serverRefreshFeedback(controller.state.value, generation, visible = true))
            val next = controller.refreshAll()
            runCurrent()
            assertTrue(next != generation)
            assertEquals(ServerRefreshResult.Success, serverRefreshFeedback(controller.state.value, next, true)?.result)
        }
}
