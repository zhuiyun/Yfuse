package com.yfuse.feature.player

import android.media.MediaMetadataRetriever
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.android.BufferedScrubMedia
import com.yfuse.core2.android.GeneratedAvcTestMedia
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferedRange
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core.playback.VideoCachePool
import com.yfuse.core.playback.secureMediaCacheKeyForUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BufferedScrubDecodeInstrumentedTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun latePreviewReadsOnlyCommittedSenPlayerCacheSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val limit = 8L * 1024 * 1024
        val url = "http://127.0.0.1:1/${java.util.UUID.randomUUID()}/movie.mp4"
        val key = secureMediaCacheKeyForUrl(url)
        AndroidPlaybackHttpProxy(context, "", limit).use { proxy ->
            proxy.localUrl(url, cacheable = true)
            requireNotNull(VideoCachePool.acquire(context, limit)).use { handle ->
                val cache = handle.cache
                try {
                    val bytes = byteArrayOf(7, 23, 39, 51)
                    val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
                    try {
                        val file = cache.startFile(key, 0, bytes.size.toLong())
                        file.writeBytes(bytes)
                        cache.commitFile(file, file.length())
                    } finally {
                        cache.releaseHoleSpan(hole)
                    }
                    cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 16L))
                    BufferedScrubMedia.attach(url, emptyMap(), null).use { session ->
                        val input = requireNotNull(session.input(0)).first
                        input.use {
                            val result = ByteArray(bytes.size)
                            assertEquals(bytes.size, it.readAt(0, result, 0, result.size))
                            assertArrayEquals(bytes, result)
                            assertThrows(java.io.EOFException::class.java) { it.readAt(8, result, 0, result.size) }
                            assertFalse(it.valid())
                        }
                    }
                } finally {
                    cache.removeResource(key)
                }
            }
        }
    }

    @Test fun decodeUsesPlaybackBytesAndRejectsMissingDataAndOldEpisode() {
        val file = GeneratedAvcTestMedia.create(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, frameMarkers = true)
        // No host or server is bound to this address. Only the already buffered data can supply a picture.
        val url = "http://127.0.0.1:1/fixture.mp4"
        val s = BufferedScrubMedia.attach(url, emptyMap(), null)
        try {
            val bytes = file.readBytes()
            val writer = requireNotNull(BufferedScrubMedia.lookup(url, emptyMap(), null))
            assertNull(s.input(1_000_000))
            writer.put(0, bytes, 0, 64, bytes.size.toLong())
            val partial = s.input(2_000_000)!!.first
            assertTrue(runCatching { partial.readAt(128,ByteArray(32),0,32) }.isFailure)
            assertFalse(partial.valid())
            partial.close()
            writer.put(0,bytes,0,bytes.size,bytes.size.toLong())
            fun image(t: Long): android.graphics.Bitmap {
                val (data, at) = requireNotNull(s.input(t))
                return data.use {
                    val d = MediaMetadataRetriever()
                    try { d.setDataSource(it); requireNotNull(d.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST,160,90)).also { assertTrue(data.valid()) } }
                    finally { d.release() }
                }
            }
            val a=image(1_000_000); val b=image(7_000_000)
            try { assertFalse(a.sameAs(b)) } finally { a.recycle(); b.recycle() }
            val ranges=YBufferSnapshot(coverage=YBufferRangeCoverage.SeekableRanges,ranges=listOf(YBufferedRange(0,2000),YBufferedRange(6000,8000)))
            assertTrue(ranges.hasScrubPosition(1999));assertFalse(ranges.hasScrubPosition(2000))
            assertFalse(ranges.hasScrubPosition(5999));assertTrue(ranges.hasScrubPosition(6000))
            val next=BufferedScrubMedia.attach("http://127.0.0.1:1/ep2.mp4",emptyMap(),null)
            try {
                writer.put(0,bytes,0,bytes.size,bytes.size.toLong())
                assertNull(s.input(1_000_000));assertNull(next.input(1_000_000))
            } finally {next.close()}
        } finally { s.close();file.delete() }
    }
}
