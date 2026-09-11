package com.yfuse.core2.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class NextItemCancellationTest {
    @Test fun cancelling_preparation_signals_the_actual_read_without_waiting_for_its_deadline() =
        runBlocking {
            val started = CountDownLatch(1)
            val cancelled = AtomicBoolean()
            val job =
                async(Dispatchers.Default) {
                    speculativeNextItemWork({ true }) { budget ->
                        val stop = CountDownLatch(1)
                        budget.onCancel {
                            cancelled.set(true)
                            stop.countDown()
                        }
                        started.countDown()
                        stop.await(10, TimeUnit.SECONDS)
                    }
                }
            assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(cancelled.get())
        }
}
