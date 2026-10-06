package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YEnhancedDemuxSourceTest {
    private val item =
        YMediaItem(
            id = "movie",
            uri = "https://media.example.test/movie.mkv",
            headers = mapOf("Authorization" to "test-authorization", "User-Agent" to "Yfuse-test"),
            transportCredentials = YTransportCredentials.UsernamePassword("test-user", "test-password"),
            cacheIdentity = YCacheIdentity("test-scope", "movie"),
            cacheMaximumBytes = 8_388_608L,
        )

    @Test
    fun probeAndPlaybackUseTheSameProxyAndUpstreamCredentials() {
        val localize: (YMediaItem) -> String = { upstream ->
            assertSame(item, upstream)
            "http://127.0.0.1:12345/media/opaque"
        }
        val probe = enhancedDemuxSource(item, probeOnly = true, localize = localize)
        val playback = enhancedDemuxSource(item, localize = localize)

        assertEquals(playback, probe.copy(probeOnly = false))
        assertTrue(probe.probeOnly)
        assertFalse(playback.probeOnly)
        assertTrue(probe.headers.isEmpty())
        assertNull(probe.transportCredentials)
        assertEquals(item.cacheIdentity, probe.cacheIdentity)
        assertEquals(item.cacheMaximumBytes, probe.cacheMaximumBytes)
    }

    @Test
    fun directSourceRetainsHeadersAndCredentials() {
        val source = enhancedDemuxSource(item, probeOnly = true)
        assertEquals(item.uri, source.uri)
        assertEquals(item.headers, source.headers)
        assertSame(item.transportCredentials, source.transportCredentials)
    }

    @Test
    fun proxyDecliningTheSourceDoesNotStripCredentials() {
        val source = enhancedDemuxSource(item, localize = { it.uri })
        assertEquals(item.headers, source.headers)
        assertSame(item.transportCredentials, source.transportCredentials)
    }

    @Test
    fun loopbackAndFileSourcesAreNotWrappedAgain() {
        listOf("http://127.0.0.1:12345/media/opaque", "file:///video.mkv").forEach { uri ->
            val source = enhancedDemuxSource(item.copy(uri = uri), localize = { error("Unexpected proxy") })
            assertEquals(uri, source.uri)
            assertEquals(item.headers, source.headers)
        }
    }

    @Test
    fun documentsAndSmbUseTheOwnedTransportWithoutForwardingCredentialsToFfmpeg() {
        listOf("content://media/video/1", "smb://nas.example.test/share/video.mkv").forEach { uri ->
            val upstream = item.copy(uri = uri)
            listOf(false, true).forEach { probeOnly ->
                var localized = false
                val source =
                    enhancedDemuxSource(upstream, probeOnly = probeOnly) { requested ->
                        assertSame(upstream, requested)
                        assertSame(item.transportCredentials, requested.transportCredentials)
                        localized = true
                        "http://127.0.0.1:12345/media/opaque"
                    }

                assertTrue(localized)
                assertEquals("http://127.0.0.1:12345/media/opaque", source.uri)
                assertTrue(source.headers.isEmpty())
                assertNull(source.transportCredentials)
                assertEquals(item.cacheIdentity, source.cacheIdentity)
                assertEquals(item.cacheMaximumBytes, source.cacheMaximumBytes)
                assertEquals(probeOnly, source.probeOnly)
            }
        }
    }
}
