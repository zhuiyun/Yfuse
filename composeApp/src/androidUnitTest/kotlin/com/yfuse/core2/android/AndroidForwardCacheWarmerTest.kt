package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidForwardCacheWarmerTest {
    @Test
    fun `disk warming respects the window and skips cached blocks`() {
        val directory = Files.createTempDirectory("forward-cache-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val cache = AndroidYCoreBlockCache(directory, YCacheIdentity("test", "video"), 64, 1024)
        cache.writeBlock(4, ByteArray(64), 1024)
        val loaded = mutableListOf<Long>()
        val warmer =
            AndroidForwardCacheWarmer(
                cache,
                executor,
                ::UnusedCacheTransport,
                load = { index, _, _ ->
                    loaded.add(index)
                    ByteArray(64) to 1024L
                },
                canWarm = { true },
            )
        try {
            warmer.updateWindow(3, 7)
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertTrue(cache.awaitPendingWrites(2_000L))
            assertEquals(listOf(3L, 5L, 6L), loaded)
            assertEquals(64, cache.cachedBlockLength(6))
            assertNull(cache.cachedBlockLength(7))
        } finally {
            warmer.close()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an abandoned seek response is never written to disk`() {
        val directory = Files.createTempDirectory("forward-cache-seek-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val cache = AndroidYCoreBlockCache(directory, YCacheIdentity("test", "video"), 64, 1024)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val warmer =
            AndroidForwardCacheWarmer(
                cache,
                executor,
                ::UnusedCacheTransport,
                load = { _, _, _ ->
                    started.countDown()
                    finish.await()
                    ByteArray(64) to 1024L
                },
                canWarm = { true },
            )
        try {
            warmer.updateWindow(3, 4)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            warmer.updateWindow(0, 0)
            finish.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertNull(cache.cachedBlockLength(3))
        } finally {
            finish.countDown()
            warmer.close()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}

private class UnusedCacheTransport : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse = error("Unused")

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int = error("Unused")

    override suspend fun close() = Unit
}
