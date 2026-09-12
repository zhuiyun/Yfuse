package com.yfuse.core2.android

import com.yfuse.core2.adaptive.YHlsPlaylist
import com.yfuse.core2.adaptive.parseYDashManifest
import com.yfuse.core2.adaptive.parseYHlsPlaylist
import com.yfuse.core2.network.YCacheIdentity
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlaybackAuditRegressionTest {
    @Test fun dashAcceptsEquivalentZeroStartsOverHttp() {
        for (zero in listOf("PT0S", "PT0.0S", "PT0.000S", "PT0H0M0S", "P0D")) {
            val manifest =
                parseYDashManifest(
                    """<MPD mediaPresentationDuration="PT10S"><Period start="$zero"><AdaptationSet mimeType="video/mp4"><Representation id="v" bandwidth="1000"><SegmentTemplate timescale="1" duration="2" initialization="init.mp4" media="s-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet></Period></MPD>""",
                    "http://media.example/manifest.mpd",
                )
            assertEquals(0L, manifest.periods.single().startUs)
        }
    }

    @Test fun hlsZeroPlaceholderDoesNotRejectFollowingMedia() {
        val media =
            parseYHlsPlaylist(
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:0,\nempty.ts\n#EXTINF:2,\nreal.ts\n#EXT-X-ENDLIST",
                "http://media.example/list.m3u8",
            ) as YHlsPlaylist.Media
        assertEquals(2, media.segments.size)
        assertEquals("http://media.example/real.ts", media.segments.last().uri)
    }

    @Test fun rejectedAudioChoiceRestoresPositionAndOldTrack() {
        var track = "aac"
        var position = 42L
        val accepted =
            playbackTrackSwitch(
                change = {
                    track = "dts"
                    position = 90L
                    error("codec unavailable")
                },
                restore = {
                    track = "aac"
                    position = 42L
                },
                rejected = { },
            )
        assertFalse(accepted)
        assertEquals("aac", track)
        assertEquals(42L, position)
    }

    @Test fun rollbackFailureRetainsBothErrors() {
        val failure =
            assertFailsWith<IllegalStateException> {
                playbackTrackSwitch(change = {
                    error("new track")
                }, restore = { error("old track") }, rejected = { fail("rollback failed") })
            }
        assertEquals("old track", failure.message)
        assertEquals("new track", failure.suppressed.single().message)
    }

    @Test fun closeWakesBlockedReaderWithoutReadTimeout() {
        val queue = CancellableChunkQueue<String>(2)
        val started = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read =
                worker.submit<Boolean> {
                    started.countDown()
                    assertFailsWith<IOException> { queue.poll(20, TimeUnit.SECONDS) }
                    true
                }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            queue.close()
            assertTrue(read.get(1, TimeUnit.SECONDS))
            val next = CancellableChunkQueue<String>(2)
            queue.put("old callback")
            next.put("new response")
            assertEquals("new response", next.poll(1, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun closeAlsoUnblocksBackpressuredProducer() {
        val queue = CancellableChunkQueue<String>(1)
        queue.put("one")
        val worker = Executors.newSingleThreadExecutor()
        try {
            val write = worker.submit { queue.put("two") }
            queue.close()
            write.get(1, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun headerLimitStopsReadingBeforeAllocationGrows() {
        val reader = StringReader("a".repeat(100_000))
        assertFailsWith<IOException> { readProxyLine(reader, 8192) }
        assertEquals('a'.code, reader.read())
        assertEquals("GET / HTTP/1.1", readProxyLine(StringReader("GET / HTTP/1.1\r\n"), 64))
    }

    @Test fun httpIsAllowedAndCrossOriginHeadersDoNotLeak() {
        val headers = mapOf("Authorization" to "secret", "X-Emby-Token" to "secret", "User-Agent" to "Yfuse")
        assertEquals(headers, scopedMediaHeaders(headers, "http://media.example/a", "http://media.example/b"))
        assertEquals(
            mapOf("User-Agent" to "Yfuse"),
            scopedMediaHeaders(headers, "http://media.example/a", "http://cdn.example/b"),
        )
        assertEquals(mapOf("User-Agent" to "Yfuse"), scopedMediaHeaders(headers, null, "http://license.example"))
    }

    @Test fun sameLengthReplacementInvalidatesBlocksAndOldWriters() {
        val directory = Files.createTempDirectory("ycore-representation").toFile()
        val identity = YCacheIdentity("scope", "media", "source")
        try {
            val old = AndroidYCoreBlockCache(directory, identity, 8, 1024)
            old.validateRepresentation(16, "\"v1\"")
            old.writeBlock(0, byteArrayOf(1, 2, 3, 4), 16)
            val fresh = AndroidYCoreBlockCache(directory, identity, 8, 1024)
            fresh.validateRepresentation(16, "\"v2\"")
            assertNull(fresh.readBlock(0))
            old.writeBlock(0, byteArrayOf(1, 2, 3, 4), 16)
            assertNull(fresh.readBlock(0))
            fresh.writeBlock(0, byteArrayOf(5, 6), 16)
            assertNull(old.readBlock(0))
            assertContentEquals(byteArrayOf(5, 6), fresh.readBlock(0))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun matchingEntityReusesCacheButMissingEntityCannot() {
        val directory = Files.createTempDirectory("ycore-reuse").toFile()
        val identity = YCacheIdentity("scope", "media", "source")
        try {
            val first = AndroidYCoreBlockCache(directory, identity, 8, 1024)
            first.validateRepresentation(8, "\"v1\"")
            first.writeBlock(0, byteArrayOf(1, 2), 8)
            val next = AndroidYCoreBlockCache(directory, identity, 8, 1024)
            next.validateRepresentation(8, "\"v1\"")
            assertContentEquals(byteArrayOf(1, 2), next.readBlock(0))
            next.validateRepresentation(8, null)
            assertNull(next.readBlock(0))
        } finally {
            directory.deleteRecursively()
        }
    }
}
