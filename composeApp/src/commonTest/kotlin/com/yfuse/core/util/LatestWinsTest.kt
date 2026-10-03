package com.yfuse.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LatestWinsTest {
    @Test
    fun a_new_request_cancels_the_previous_job_and_outdates_its_ticket() =
        runTest {
            val latest = LatestWins(backgroundScope)
            lateinit var older: LatestWins.Ticket
            val olderJob =
                latest.launch {
                    older = it
                    awaitCancellation()
                }
            runCurrent()
            assertTrue(older.isCurrent)

            val newerJob = latest.launch { awaitCancellation() }
            runCurrent()

            assertTrue(olderJob.isCancelled)
            assertFalse(older.isCurrent)
            assertTrue(newerJob.isActive)
            assertTrue(latest.isActive)
        }

    @Test
    fun a_result_that_outlives_its_cancellation_is_still_dropped() =
        runTest {
            val latest = LatestWins(backgroundScope)
            val answer = CompletableDeferred<String>()
            val published = mutableListOf<String>()
            latest.launch { request ->
                // A repository that turns the cancellation into an answer of its own, as a bare
                // runCatching does: the job carries on past its suspension point.
                val value = runCatching { answer.await() }.getOrElse { "answered while cancelled" }
                if (request.isCurrent) published += value
            }
            runCurrent()

            latest.launch { request -> if (request.isCurrent) published += "newer" }
            runCurrent()

            assertEquals(listOf("newer"), published)
        }

    @Test
    fun an_older_job_is_already_outdated_when_its_cancellation_runs_on_the_spot() =
        runTest {
            // An unconfined dispatcher runs a cancelled job's handlers inside cancel() itself.
            val onTheSpot = backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            val latest = LatestWins(CoroutineScope(onTheSpot))
            var currentWhenCancelled: Boolean? = null
            latest.launch { request ->
                try {
                    awaitCancellation()
                } finally {
                    currentWhenCancelled = request.isCurrent
                }
            }
            assertNull(currentWhenCancelled)

            latest.next()

            assertEquals(false, currentWhenCancelled)
        }

    @Test
    fun outdating_lets_the_older_job_finish_without_publishing() =
        runTest {
            val latest = LatestWins(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val olderJob =
                latest.launch { request ->
                    gate.await()
                    if (request.isCurrent) published += "older"
                }
            runCurrent()

            latest.launch(latest.outdate()) { request -> if (request.isCurrent) published += "newer" }
            runCurrent()
            assertTrue(olderJob.isActive)

            gate.complete(Unit)
            runCurrent()

            assertTrue(olderJob.isCompleted)
            assertFalse(olderJob.isCancelled)
            assertEquals(listOf("newer"), published)
        }

    @Test
    fun cancelling_stops_the_job_and_its_ticket_without_starting_another() =
        runTest {
            val latest = LatestWins(backgroundScope)
            lateinit var ticket: LatestWins.Ticket
            val job =
                latest.launch {
                    ticket = it
                    awaitCancellation()
                }
            runCurrent()

            latest.cancel()
            runCurrent()

            assertTrue(job.isCancelled)
            assertFalse(ticket.isCurrent)
            assertFalse(latest.isActive)
        }

    @Test
    fun is_active_follows_the_newest_job_to_its_end() =
        runTest {
            val latest = LatestWins(backgroundScope)
            assertFalse(latest.isActive)
            val gate = CompletableDeferred<Unit>()
            latest.launch { gate.await() }
            assertTrue(latest.isActive)

            gate.complete(Unit)
            runCurrent()

            assertFalse(latest.isActive)
        }

    @Test
    fun finishing_releases_only_the_newest_request() =
        runTest {
            val latest = LatestWins(backgroundScope)
            val olderGate = CompletableDeferred<Unit>()
            val newerGate = CompletableDeferred<Unit>()
            var olderFinished: Boolean? = null
            var newerFinished: Boolean? = null
            var activeOnceFinished: Boolean? = null
            latest.launch { request ->
                olderGate.await()
                olderFinished = latest.finish(request)
            }
            runCurrent()
            latest.launch(latest.outdate()) { request ->
                newerGate.await()
                newerFinished = latest.finish(request)
                // The job is still running here, but it has said it is done.
                activeOnceFinished = latest.isActive
            }
            runCurrent()

            olderGate.complete(Unit)
            runCurrent()
            assertEquals(false, olderFinished)
            assertTrue(latest.isActive)

            newerGate.complete(Unit)
            runCurrent()
            assertEquals(true, newerFinished)
            assertEquals(false, activeOnceFinished)
        }

    @Test
    fun joining_waits_for_the_newest_job() =
        runTest {
            val latest = LatestWins(backgroundScope)
            latest.join()

            val gate = CompletableDeferred<Unit>()
            latest.launch { gate.await() }
            val waiter = async { latest.join() }
            runCurrent()
            assertFalse(waiter.isCompleted)

            gate.complete(Unit)
            runCurrent()

            assertTrue(waiter.isCompleted)
        }

    @Test
    fun the_block_runs_under_the_ticket_it_was_launched_with() =
        runTest {
            val latest = LatestWins(backgroundScope)
            val ticket = latest.next()
            var received: LatestWins.Ticket? = null

            latest.launch(ticket) { received = it }
            runCurrent()

            assertSame(ticket, received)
            assertTrue(ticket.isCurrent)
        }

    @Test
    fun the_current_ticket_and_generation_follow_every_request() =
        runTest {
            val latest = LatestWins(backgroundScope)
            val page = latest.current
            assertTrue(page.isCurrent)
            assertEquals(0L, latest.generation)

            val first = latest.next()
            assertFalse(page.isCurrent)
            assertTrue(first.isCurrent)
            assertEquals(1L, first.generation)

            latest.outdate()
            latest.cancel()
            assertEquals(3L, latest.generation)
            assertFalse(first.isCurrent)
            assertTrue(latest.current.isCurrent)
        }

    @Test
    fun one_holder_s_requests_do_not_outdate_another_s() =
        runTest {
            val sources = LatestWins(backgroundScope)
            val related = LatestWins(backgroundScope)
            val ticket = sources.next()

            related.next()
            related.cancel()

            assertTrue(ticket.isCurrent)
        }
}
