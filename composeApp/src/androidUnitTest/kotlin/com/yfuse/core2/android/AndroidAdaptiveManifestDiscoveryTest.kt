package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAdaptiveManifestDiscoveryTest {
    @Test
    fun slow_optional_manifests_are_bounded_and_do_not_hold_the_callers_thread() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        AndroidAdaptiveManifestDiscovery(concurrency = 1, queueCapacity = 1, budgetMs = 5_000L).use { discovery ->
            assertTrue(
                discovery.submit("slow") { budget ->
                    budget.onCancel { release.countDown() }.use {
                        started.countDown()
                        check(release.await(2L, TimeUnit.SECONDS))
                        budget.publishIfActive { published.incrementAndGet() }
                    }
                },
            )
            assertTrue(started.await(1L, TimeUnit.SECONDS))
            assertFalse(discovery.submit("slow") { error("Duplicate discovery ran") })
            assertTrue(discovery.submit("queued") { budget -> budget.publishIfActive { published.incrementAndGet() } })
            assertFalse(discovery.submit("overflow") { error("Queue exceeded its bound") })
            assertEquals(0, published.get())
            discovery.close()
            release.countDown()
        }
        assertEquals(0, published.get())
    }

    @Test
    fun deadline_closes_active_transport_and_prevents_late_publication() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val published = AtomicInteger()
        AndroidAdaptiveManifestDiscovery(budgetMs = 100L).use { discovery ->
            assertTrue(
                discovery.submit("delayed") { budget ->
                    try {
                        budget.onCancel { cancelled.countDown() }.use {
                            started.countDown()
                            check(cancelled.await(2L, TimeUnit.SECONDS))
                            budget.publishIfActive { published.incrementAndGet() }
                        }
                    } finally {
                        workerFinished.countDown()
                    }
                },
            )
            assertTrue(started.await(1L, TimeUnit.SECONDS))
            assertTrue(cancelled.await(2L, TimeUnit.SECONDS))
            assertTrue(workerFinished.await(1L, TimeUnit.SECONDS))
            assertEquals(0, published.get())
        }
    }
}
