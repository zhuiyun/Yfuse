package com.yfuse.feature.player

import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackProxyRequestsTest {
    @Test
    fun shutdown_closes_clients_but_retains_cache_ownership_until_the_last_worker_finishes() {
        var releases = 0
        val cancellations = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val requests = PlaybackProxyRequests { releases++ }
        val first = assertNotNull(requests.register(Socket()))
        val blockedCacheWorker = assertNotNull(requests.register(Socket()))
        first.attachUpstreamCancellation {
            cancellations.incrementAndGet()
            cancelled.countDown()
        }

        requests.close()
        assertTrue(first.socket.isClosed)
        assertTrue(blockedCacheWorker.socket.isClosed)
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, cancellations.get())
        assertEquals(0, releases, "An uncancellable cache open still owns its lease")
        requests.finish(first)
        assertEquals(0, releases)
        requests.finish(blockedCacheWorker)
        assertEquals(1, releases)
        requests.finish(first)
        requests.close()
        assertEquals(1, releases)
        assertEquals(1, cancellations.get())
    }

    @Test
    fun connection_published_after_cancellation_is_disconnected_without_becoming_active() {
        val requests = PlaybackProxyRequests {}
        val request = assertNotNull(requests.register(Socket()))
        requests.close()
        val cancellations = AtomicInteger()
        val cancelled = CountDownLatch(1)
        assertFailsWith<IOException> {
            request.attachUpstreamCancellation {
                cancellations.incrementAndGet()
                cancelled.countDown()
            }
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, cancellations.get())
        assertFailsWith<IOException> { request.ensureOpen() }
        requests.finish(request)
        assertEquals(1, cancellations.get())
    }

    @Test
    fun blocking_upstream_cancellation_does_not_delay_close_or_release_a_running_workers_lease() {
        val releases = AtomicInteger()
        val requests = PlaybackProxyRequests { releases.incrementAndGet() }
        val request = assertNotNull(requests.register(Socket()))
        val cancellationEntered = CountDownLatch(1)
        val allowCancellationToFinish = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val closer = Executors.newSingleThreadExecutor()
        request.attachUpstreamCancellation {
            cancellationEntered.countDown()
            try {
                allowCancellationToFinish.await()
            } finally {
                cancellationFinished.countDown()
            }
        }
        try {
            closer.submit { requests.close() }.get(1, TimeUnit.SECONDS)
            assertTrue(request.socket.isClosed)
            assertTrue(request.isCancelled)
            assertTrue(cancellationEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, cancellationFinished.count, "The disconnect is still blocked")
            assertEquals(0, releases.get(), "The original worker still owns the cache lease")
            assertFailsWith<IOException> { request.ensureOpen() }

            allowCancellationToFinish.countDown()
            assertTrue(cancellationFinished.await(2, TimeUnit.SECONDS))
            assertEquals(0, releases.get(), "Disconnect completion alone cannot return the worker's lease")
            requests.finish(request)
            assertEquals(1, releases.get())
            requests.close()
            requests.finish(request)
            assertEquals(1, releases.get())
        } finally {
            allowCancellationToFinish.countDown()
            requests.close()
            requests.finish(request)
            closer.shutdownNow()
        }
    }

    @Test
    fun accepting_a_socket_after_shutdown_cannot_reopen_the_drained_owner() {
        var releases = 0
        val requests = PlaybackProxyRequests { releases++ }
        requests.close()
        val socket = Socket()
        assertNull(requests.register(socket))
        assertTrue(socket.isClosed)
        requests.close()
        assertEquals(1, releases)
    }
}
