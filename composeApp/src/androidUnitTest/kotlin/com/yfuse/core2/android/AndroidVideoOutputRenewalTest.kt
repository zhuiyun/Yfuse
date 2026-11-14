package com.yfuse.core2.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVideoOutputRenewalTest {
    @Test
    fun old_callbacks_do_not_unblock_the_worker_and_new_output_needs_no_queued_command() =
        runTest {
            val renewal = AndroidVideoOutputReplacement<Any>()
            val old = Any()
            val replacement = Any()
            val result = async { renewal.awaitReplacement(old) }
            runCurrent()
            assertEquals(1L, renewal.generation.value)
            assertTrue(renewal.accept(null))
            assertTrue(renewal.accept(old))
            assertFalse(result.isCompleted)
            assertTrue(renewal.accept(replacement))
            assertSame(replacement, result.await())
            // A subsequent real lifecycle detach must still reach the player command queue.
            assertFalse(renewal.accept(null))
        }

    @Test
    fun cancellation_does_not_capture_the_next_surfaces_callbacks() =
        runTest {
            val renewal = AndroidVideoOutputReplacement<Any>()
            val result = async { renewal.awaitReplacement(Any()) }
            runCurrent()
            result.cancel()
            runCurrent()
            assertFalse(renewal.awaitingReplacement)
            assertFalse(renewal.accept(Any()))
            val next = async { renewal.awaitReplacement(Any()) }
            runCurrent()
            val replacement = Any()
            assertTrue(renewal.accept(replacement))
            assertSame(replacement, next.await())
            assertEquals(2L, renewal.generation.value)
        }

    @Test
    fun missing_surface_is_a_bounded_failure_not_cancellation_of_the_player_worker() =
        runTest {
            val renewal = AndroidVideoOutputReplacement<Any>()
            val result = async { runCatching { renewal.awaitReplacement(Any()) } }
            runCurrent()
            advanceTimeBy(5_001L)
            runCurrent()
            assertTrue(result.await().exceptionOrNull() is IllegalStateException)
            assertFalse(renewal.awaitingReplacement)
            assertFalse(renewal.accept(Any()))
        }
}
