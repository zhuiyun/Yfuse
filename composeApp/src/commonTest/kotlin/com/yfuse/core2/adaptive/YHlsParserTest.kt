package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YHlsParserTest {
    @Test
    fun master_playlist_preserves_variants_and_resolves_relative_urls() {
        val playlist =
            parseYHlsPlaylist(
                text =
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=750000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
                    video/720.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,FRAME-RATE=59.94,CODECS="hvc1.2.4.L120.B0,ac-3"
                    ../1080.m3u8
                    """.trimIndent(),
                baseUri = "https://media.example.test/path/master.m3u8?token=secret",
            )
        val master = assertIs<YHlsPlaylist.Master>(playlist)

        assertEquals(2, master.variants.size)
        assertEquals("https://media.example.test/path/video/720.m3u8", master.variants[0].uri)
        assertEquals(750_000L, master.variants[0].selectionBandwidthBitsPerSecond)
        assertEquals(listOf("avc1.64001f", "mp4a.40.2"), master.variants[0].codecs)
        assertEquals("https://media.example.test/1080.m3u8", master.variants[1].uri)
        assertEquals(59.94, master.variants[1].frameRate)
    }

    @Test
    fun media_playlist_preserves_init_ranges_encryption_and_timeline() {
        val playlist =
            parseYHlsPlaylist(
                text =
                    """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:6
                    #EXT-X-MEDIA-SEQUENCE:41
                    #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
                    #EXT-X-KEY:METHOD=AES-128,URI="keys/42",IV=0x01
                    #EXTINF:5.5,
                    #EXT-X-BYTERANGE:1000@720
                    media.mp4
                    #EXT-X-DISCONTINUITY
                    #EXTINF:6.0,
                    #EXT-X-BYTERANGE:800
                    media.mp4
                    #EXT-X-KEY:METHOD=NONE
                    #EXTINF:4.25,
                    tail.m4s
                    #EXT-X-ENDLIST
                    """.trimIndent(),
                baseUri = "https://media.example.test/vod/index.m3u8",
            )
        val media = assertIs<YHlsPlaylist.Media>(playlist)

        assertFalse(media.isLive)
        assertEquals(41L, media.mediaSequence)
        assertEquals(6_000_000L, media.targetDurationUs)
        assertEquals(3, media.segments.size)
        assertEquals(41L, media.segments[0].sequence)
        assertEquals(5_500_000L, media.segments[0].durationUs)
        assertEquals(YAdaptiveByteRange(1_000L, 720L), media.segments[0].byteRange)
        assertEquals("https://media.example.test/vod/init.mp4", media.segments[0].initialization?.uri)
        assertEquals("https://media.example.test/vod/keys/42", media.segments[0].encryption?.keyUri)
        assertTrue(media.segments[1].discontinuity)
        assertEquals(YAdaptiveByteRange(800L, 1_720L), media.segments[1].byteRange)
        assertEquals(11_500_000L, media.segments[2].startTimeUs)
        assertNull(media.segments[2].encryption)
    }
}
