from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
p=A+'AndroidTransportMediaDataSource.kt';t=read(p)
block='    /** Optional warmers await their bounded writes; normal playback never blocks on this. */\n    fun awaitCacheWrites(timeoutMs: Long): Boolean = diskCache?.awaitPendingWrites(timeoutMs) ?: true\n\n'
t=t.replace(block,'');i=t.index('    /**\n     * Not `@Synchronized` on purpose.')
t=t[:i]+block+t[i:];write(p,t)
p=A+'AndroidNextItemPreparation.kt';t=read(p)
t=t.replace('>= 5_000L','>=\n            minOf(15_000L, nextItemRemainingMs(state, null)?.coerceAtLeast(1_000L) ?: 15_000L)')
write(p,t)
# Resume the test-only suffix of the previous script (its source edits already ran).
p='audit/next-item-preparation-20260910/validation_fixes.py'
t=read(p);idx=t.index("p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemOutputHandoffTest.kt'")
exec(t[idx:].replace("'    @Test\\n    fun exact_video_configuration'", "'    @Test fun exact_video_configuration'").replace("'    @Test\\n    fun pcm_end'", "'    @Test fun pcm_end'"))

write('composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemRangeReuseTest.kt','''package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextItemRangeReuseTest {
    @Test fun warmed_ranges_are_consumed_by_a_new_ycore_reader_with_the_same_cache_identity() {
        val directory = Files.createTempDirectory("ycore-next-ranges").toFile()
        val media = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        val transferred = AtomicLong()
        val item = YMediaItem("next", "https://example.invalid/episode.mkv",
            headers = mapOf("Authorization" to "test-only"), cacheIdentity = YCacheIdentity("scope", "media", "version"),
            cacheMaximumBytes = 32L * 1024 * 1024)
        fun transport(): YMediaTransport = object : YMediaTransport {
            override val supportedProtocols = setOf(YSourceProtocol.Https)
            override val features = setOf(YTransportFeature.ByteRange)
            var position = 0
            var end = 0
            override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                assertEquals(item.headers, request.headers)
                val range = requireNotNull(request.range)
                position = range.startInclusive.toInt()
                end = minOf(media.size, ((range.endInclusive ?: media.lastIndex.toLong()) + 1L).toInt())
                return YMediaTransportResponse(206, media.size.toLong(), YByteRange(position.toLong(), end.toLong() - 1L))
            }
            override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return -1
                val count = minOf(length, end - position)
                media.copyInto(destination, offset, position, position + count)
                position += count
                transferred.addAndGet(count.toLong())
                return count
            }
            override suspend fun close() = Unit
        }
        try {
            AndroidProbeBudget().use { budget ->
                assertTrue(warmNextItemBytes(directory, item, budget, ::transport) >= 4L * 1024 * 1024)
            }
            assertTrue(transferred.get() > 0L)
            transferred.set(0)
            AndroidTransportMediaDataSource(uri = item.uri, protocol = YSourceProtocol.Https,
                headers = item.headers, cacheDirectory = directory, cacheIdentity = item.cacheIdentity,
                cacheMaximumBytes = item.cacheMaximumBytes, createTransport = ::transport,
                allowsSpeculativeWork = { false }).use { reader ->
                val buffer = ByteArray(64 * 1024)
                for (offset in listOf(512 * 1024, 2 * 1024 * 1024, media.size - buffer.size)) {
                    assertEquals(buffer.size, reader.readAt(offset.toLong(), buffer, 0, buffer.size))
                    assertContentEquals(media.copyOfRange(offset, offset + buffer.size), buffer)
                }
                assertTrue(reader.awaitCacheWrites(2_000L))
            }
            assertEquals(0L, transferred.get(), "Prepared file ranges should not be downloaded by the new reader")
        } finally { directory.deleteRecursively() }
    }
}
''')
write('composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemCancellationTest.kt','''package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class NextItemCancellationTest {
    @Test fun cancelling_preparation_signals_the_actual_read_without_waiting_for_its_deadline() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val job = async(Dispatchers.Default) {
            speculativeNextItemWork({ true }) { budget ->
                val stop = CountDownLatch(1)
                budget.onCancel { cancelled.set(true); stop.countDown() }
                started.countDown()
                stop.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(cancelled.get())
    }
}
''')
