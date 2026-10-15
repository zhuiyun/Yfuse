package com.yfuse.feature.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailProgressBatchTest {
    @Test
    fun writesAreBoundedAndProgressIncludesQueuedFailures() =
        runTest {
            var active = 0
            var peak = 0
            val progress = mutableListOf<Int>()
            val queued =
                DetailProgressBatch(concurrency = 3).write((1..12).toList(), progress::add) { item ->
                    active++
                    peak = maxOf(peak, active)
                    delay((13 - item) * 10L)
                    active--
                    if (item % 4 == 0) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
                }
            assertEquals(3, peak)
            assertEquals(0, active)
            assertEquals(3, queued)
            assertEquals((1..12).toList(), progress)
        }

    @Test
    fun cancellationStopsInFlightAndQueuedWrites() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var started = 0
            var finished = 0
            val progress = mutableListOf<Int>()
            val job =
                launch {
                    DetailProgressBatch(concurrency = 2).write((1..10).toList(), progress::add) {
                        started++
                        if (started == 2) entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            finished++
                        }
                    }
                }
            entered.await()
            job.cancelAndJoin()
            assertEquals(2, started)
            assertEquals(2, finished)
            assertTrue(progress.isEmpty())
        }
}
