package com.yfuse.core.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoofCutoutWorkQueueTest {
    @Test
    fun duplicate_cards_share_one_request_and_scrolling_away_does_not_cancel_cache_completion() =
        runTest {
            val queue = PoofCutoutWorkQueue<String>(backgroundScope)
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val first =
                async {
                    queue.run("poster") {
                        calls++
                        finish.await()
                        "cached"
                    }
                }
            runCurrent()
            val second =
                async {
                    queue.run("poster") {
                        calls++
                        "duplicate"
                    }
                }
            runCurrent()
            first.cancel()
            finish.complete(Unit)
            runCurrent()
            assertEquals("cached", second.await())
            assertEquals(1, calls)
        }

    @Test
    fun fast_scrolling_cannot_accumulate_an_unbounded_paid_work_queue() =
        runTest {
            val queue = PoofCutoutWorkQueue<String>(backgroundScope, maxPending = 1)
            val finish = CompletableDeferred<Unit>()
            val first =
                async {
                    queue.run("first") {
                        finish.await()
                        "done"
                    }
                }
            runCurrent()
            assertNull(queue.run("second") { "must not start" })
            finish.complete(Unit)
            runCurrent()
            assertEquals("done", first.await())
            val after = async { queue.run("second") { "available" } }
            runCurrent()
            assertEquals("available", after.await())
        }
}
