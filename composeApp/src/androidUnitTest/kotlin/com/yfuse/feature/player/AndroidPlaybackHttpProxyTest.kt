package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlaybackHttpProxyTest {
    @Test
    fun onlyWebMediaUsesPlatformTransportBridge() {
        assertTrue(shouldProxyMpvNetworkUrl("https://media.example/video.mkv"))
        assertTrue(shouldProxyMpvNetworkUrl("http://192.168.1.2/video.m2ts"))
        assertFalse(shouldProxyMpvNetworkUrl("file:///storage/video.mkv"))
        assertFalse(shouldProxyMpvNetworkUrl("bd://longest"))
        assertFalse(shouldProxyMpvNetworkUrl("not a URL"))
    }

    @Test
    fun singleByteRangesAreParsedWithoutAcceptingAmbiguousRequests() {
        assertEquals(
            PlaybackHttpByteRange(start = 1024L, endInclusive = 2047L),
            parsePlaybackHttpByteRange("bytes=1024-2047"),
        )
        assertEquals(
            PlaybackHttpByteRange(start = 4096L, endInclusive = null),
            parsePlaybackHttpByteRange("bytes=4096-"),
        )
        assertNull(parsePlaybackHttpByteRange("bytes=-4096"))
        assertNull(parsePlaybackHttpByteRange("bytes=50-10"))
        assertNull(parsePlaybackHttpByteRange("bytes=0-1,4-5"))
    }

    @Test
    fun hlsVariantsSegmentsAndKeysStayOnThePlatformTransportBridge() {
        val localized = linkedMapOf<String, String>()
        val rewritten =
            rewriteMpvHlsManifest(
                manifest =
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/track.m3u8"
                    variant/main.m3u8
                    https://cdn.example/segment.ts
                    """.trimIndent(),
                upstreamUrl = "https://emby.example/Videos/item/master.m3u8",
                localize = { upstream ->
                    localized.getOrPut(upstream) { "http://127.0.0.1/local/${localized.size}" }
                },
            )

        assertTrue("http://127.0.0.1/local/0" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/1" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/2" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/3" in rewritten, rewritten)
        assertEquals(
            setOf(
                "https://emby.example/Videos/item/keys/key.bin",
                "https://emby.example/Videos/item/audio/track.m3u8",
                "https://emby.example/Videos/item/variant/main.m3u8",
                "https://cdn.example/segment.ts",
            ),
            localized.keys,
        )
    }
}
