package com.yfuse.feature.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalMetadataLookupTest {
    @Test
    fun slowProviderCannotDelayTheFallbackOrQueueMoreReads() =
        runBlocking {
            val executor = executor()
            val reader = ExternalMetadataLookup(executor)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val result =
                    async(Dispatchers.Default) {
                        reader.read(timeoutMs = 1_000) {
                            started.countDown()
                            release.awaitIgnoringInterrupts()
                            "late title"
                        }
                    }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertNull(withTimeout(3_000) { result.await() })
                // The blocked worker still owns its slot even though its Future was cancelled.
                assertNull(reader.read { "unexpected queued title" })
                assertEquals(0, executor.queue.size)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun closingTheCallerDoesNotWaitForANonCooperativeProvider() =
        runBlocking {
            val executor = executor()
            val reader = ExternalMetadataLookup(executor)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val result =
                    async(Dispatchers.Default) {
                        reader.read(timeoutMs = 60_000) {
                            started.countDown()
                            release.awaitIgnoringInterrupts()
                            "late title"
                        }
                    }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                withTimeout(3_000) { result.cancelAndJoin() }
                assertTrue(result.isCancelled)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun providerFailuresAndMissingNamesUseTheFallback() =
        runBlocking {
            val executor = executor()
            val reader = ExternalMetadataLookup(executor)
            try {
                assertNull(reader.read<String> { throw SecurityException("private provider") })
                assertNull(reader.read<String?> { null })
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun anAvailableNameIsReturnedBeforeTheDeadline() =
        runBlocking {
            val executor = executor()
            try {
                assertEquals("film.mkv", ExternalMetadataLookup(executor).read { "film.mkv" })
            } finally {
                executor.shutdownNow()
            }
        }

    private fun executor() =
        ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.AbortPolicy())

    private fun CountDownLatch.awaitIgnoringInterrupts() {
        while (count > 0) {
            try {
                await()
            } catch (_: InterruptedException) {
                // A remote Binder provider is allowed to ignore the client's interrupt.
            }
        }
    }
}
