package com.yfuse.core.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalAutoSyncTest {
    @Test
    fun mergesWhenAnAccountSignsInAndNeverWhileSignedOut() =
        runTest {
            val fixture = Fixture(this)
            fixture.changes.value++
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(emptyList(), fixture.merges)

            fixture.user.value = "viewer"
            runCurrent()

            assertEquals(listOf(60_000L), fixture.merges)
        }

    @Test
    fun aChangeMergesTenSecondsLaterAndABurstOfChangesMergesOnce() =
        runTest {
            val fixture = Fixture(this)
            fixture.user.value = "viewer"
            runCurrent()

            fixture.changes.value++
            runCurrent()
            advanceTimeBy(4_000L)
            fixture.changes.value++
            runCurrent()
            advanceTimeBy(5_999L)
            runCurrent()
            assertEquals(listOf(0L), fixture.merges)
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(0L, 10_000L), fixture.merges)
        }

    @Test
    fun returningToTheForegroundMergesUnlessAMergeJustSucceeded() =
        runTest {
            val fixture = Fixture(this)
            fixture.user.value = "viewer"
            runCurrent()

            advanceTimeBy(30_000L)
            fixture.foreground.value = true
            runCurrent()
            fixture.foreground.value = false
            runCurrent()
            assertEquals(listOf(0L), fixture.merges)
            advanceTimeBy(31_000L)
            fixture.foreground.value = true
            runCurrent()

            assertEquals(listOf(0L, 61_000L), fixture.merges)
        }

    @Test
    fun failuresBackOffAndASuccessEndsTheBackoff() =
        runTest {
            val fixture = Fixture(this)
            fixture.outcomes += Result.failure(IllegalStateException("offline"))
            fixture.outcomes += Result.failure(IllegalStateException("offline"))
            fixture.user.value = "viewer"
            runCurrent()

            // A change cannot pull the retry forward past the backoff.
            fixture.changes.value++
            runCurrent()
            advanceTimeBy(29_999L)
            runCurrent()
            assertEquals(listOf(0L), fixture.merges)
            advanceTimeBy(1L)
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf(0L, 30_000L, 90_000L), fixture.merges)

            fixture.changes.value++
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(listOf(0L, 30_000L, 90_000L, 100_000L), fixture.merges)
        }

    @Test
    fun aChangeMadeDuringAMergeGetsAMergeOfItsOwn() =
        runTest {
            val fixture = Fixture(this)
            val firstMerge = CompletableDeferred<Unit>()
            fixture.holds += firstMerge
            fixture.user.value = "viewer"
            runCurrent()

            advanceTimeBy(2_000L)
            fixture.changes.value++
            runCurrent()
            firstMerge.complete(Unit)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(listOf(0L, 12_000L), fixture.merges)
        }

    @Test
    fun signingOutCancelsTheMergeAChangeScheduled() =
        runTest {
            val fixture = Fixture(this)
            fixture.user.value = "viewer"
            runCurrent()
            fixture.changes.value++
            runCurrent()

            advanceTimeBy(5_000L)
            fixture.user.value = null
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(listOf(0L), fixture.merges)
            fixture.user.value = "viewer"
            runCurrent()

            assertEquals(listOf(0L, 15_000L), fixture.merges)
        }

    private class Fixture(
        test: TestScope,
    ) {
        val user = MutableStateFlow<String?>(null)
        val changes = MutableStateFlow(0L)
        val foreground = MutableStateFlow(false)

        /** Outcomes of the merges to come, in order; a success once these run out. */
        val outcomes = ArrayDeque<Result<Unit>>()

        /** Merges to hold open until completed, in order. */
        val holds = ArrayDeque<CompletableDeferred<Unit>>()

        /** When each merge started, in virtual milliseconds. */
        val merges = mutableListOf<Long>()

        init {
            val background = test.backgroundScope.coroutineContext
            PersonalAutoSync(
                signedInUser = user,
                changes = changes,
                foreground = foreground,
                merge = {
                    merges += test.testScheduler.currentTime
                    holds.removeFirstOrNull()?.await()
                    outcomes.removeFirstOrNull() ?: Result.success(Unit)
                },
                scope = CoroutineScope(background + Job(background[Job])),
                nowEpochMs = { test.testScheduler.currentTime },
            ).start()
            // Subscribe before the test changes anything, as the app does at startup.
            test.testScheduler.runCurrent()
        }
    }
}
