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
    fun master_playlist_recognizes_dolby_vision_and_atmos_renditions() {
        val master =
            assertIs<YHlsPlaylist.Master>(
                parseYHlsPlaylist(
                    text = dualDolbyMaster,
                    baseUri = "https://media.example.test/master.m3u8",
                ),
            )

        val dolbyVariant = master.variants.single { it.id == "dv-4k" }
        val atmos = master.renditions.single { it.name == "Atmos" }
        assertTrue(dolbyVariant.isDolbyVision)
        assertEquals(YHlsVideoRange.Pq, dolbyVariant.videoRange)
        assertEquals("atmos", dolbyVariant.audioGroupId)
        assertTrue(atmos.isDolbyAtmos)
        assertEquals("https://media.example.test/audio/atmos.m3u8", atmos.uri)
    }

    @Test
    fun supplemental_dolby_vision_codec_and_compatibility_brand_survive_rewrite() {
        val master =
            assertIs<YHlsPlaylist.Master>(
                parseYHlsPlaylist(
                    text =
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS="hvc1.2.4.L153.b0,ec-3",SUPPLEMENTAL-CODECS="dvh1.08.07/db4h",VIDEO-RANGE=HLG
                        video/dv84.m3u8
                        """.trimIndent(),
                    baseUri = "https://media.example.test/master.m3u8",
                ),
            )
        val variant = master.variants.single()

        assertTrue(variant.isDolbyVision)
        assertTrue(variant.hasUsableDolbyVisionSignaling)
        assertEquals(listOf("dvh1.08.07/db4h"), variant.supplementalCodecs)
        assertEquals(setOf("db4h"), variant.dolbyVisionCompatibilityBrands)

        val playback =
            selectYHlsPlaybackSet(
                master,
                YAdaptiveSelectionConditions(20_000_000L, 10_000_000L),
                YHlsPlaybackCapabilities(dolbyVisionOutput = true),
            )
        val rendered = buildYHlsPlaybackMaster(playback) { uri, _ -> uri }
        assertTrue("SUPPLEMENTAL-CODECS=\"dvh1.08.07/db4h\"" in rendered)
    }

    @Test
    fun supplemental_dolby_vision_with_contradictory_range_is_not_selected() {
        val master =
            assertIs<YHlsPlaylist.Master>(
                parseYHlsPlaylist(
                    text =
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=SDR
                        video/sdr.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS="hvc1.2.4.L153.b0",SUPPLEMENTAL-CODECS="dvh1.08.07/db4h",VIDEO-RANGE=PQ
                        video/invalid-dv.m3u8
                        """.trimIndent(),
                    baseUri = "https://media.example.test/master.m3u8",
                ),
            )
        val invalidDolby = master.variants.single(YAdaptiveVariant::isDolbyVision)
        assertFalse(invalidDolby.hasUsableDolbyVisionSignaling)

        val playback =
            selectYHlsPlaybackSet(
                master,
                YAdaptiveSelectionConditions(20_000_000L, 10_000_000L),
                YHlsPlaybackCapabilities(dolbyVisionOutput = true),
            )
        assertFalse(playback.initialVariant.isDolbyVision)
    }

    @Test
    fun playback_selection_keeps_dual_dolby_ladder_only_when_output_route_supports_it() {
        val master =
            assertIs<YHlsPlaylist.Master>(
                parseYHlsPlaylist(
                    text = dualDolbyMaster,
                    baseUri = "https://media.example.test/master.m3u8",
                ),
            )
        val conditions =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond = 20_000_000L,
                bufferedDurationUs = 20_000_000L,
            )

        val dolby =
            selectYHlsPlaybackSet(
                master,
                conditions,
                YHlsPlaybackCapabilities(dolbyVisionOutput = true, dolbyAtmosOutput = true),
            )
        assertTrue(dolby.variants.all(YAdaptiveVariant::isDolbyVision))
        assertTrue(dolby.renditions.single { it.type == YHlsRenditionType.Audio }.isDolbyAtmos)
        val dolbyText = buildYHlsPlaybackMaster(dolby) { uri, _ -> "local://${uri.substringAfterLast('/')}" }
        assertTrue("CHANNELS=\"6/JOC\"" in dolbyText)
        assertTrue("VIDEO-RANGE=PQ" in dolbyText)
        assertFalse("audio/stereo.m3u8" in dolbyText)

        val compatible =
            selectYHlsPlaybackSet(
                master,
                conditions,
                YHlsPlaybackCapabilities(),
            )
        assertTrue(compatible.variants.none(YAdaptiveVariant::isDolbyVision))
        assertFalse(compatible.renditions.single { it.type == YHlsRenditionType.Audio }.isDolbyAtmos)
    }

    @Test
    fun playback_master_keeps_alternate_audio_renditions_in_the_selected_group() {
        val master =
            assertIs<YHlsPlaylist.Master>(
                parseYHlsPlaylist(
                    text =
                        """
                        #EXTM3U
                        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="main",NAME="中文",LANGUAGE="zh-CN",DEFAULT=YES,AUTOSELECT=YES,URI="audio/zh.m3u8"
                        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="main",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="audio/en.m3u8"
                        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="main"
                        video/main.m3u8
                        """.trimIndent(),
                    baseUri = "https://media.example.test/master.m3u8",
                ),
            )
        val playback =
            selectYHlsPlaybackSet(
                master,
                YAdaptiveSelectionConditions(8_000_000L, 10_000_000L),
                YHlsPlaybackCapabilities(),
            )
        val rendered = buildYHlsPlaybackMaster(playback) { uri, _ -> uri }

        assertEquals(listOf("中文", "English"), playback.renditions.map { it.name })
        assertTrue("audio/zh.m3u8" in rendered)
        assertTrue("audio/en.m3u8" in rendered)
        assertEquals(1, Regex("DEFAULT=YES").findAll(rendered).count())
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

    @Test
    fun resource_rewriter_keeps_every_hls_fetch_inside_the_transport_boundary() {
        val kinds = mutableListOf<YHlsResourceKind>()
        val rewritten =
            rewriteYHlsResourceUris(
                text =
                    """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,URI="audio/main.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=1000000
                    video/main.m3u8
                    #EXT-X-MAP:URI="init.mp4"
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    #EXT-X-PART:DURATION=0.5,URI="part-1.m4s"
                    #EXTINF:4,
                    segment-1.m4s
                    """.trimIndent(),
                baseUri = "https://media.example.test/root/master.m3u8?token=secret",
            ) { uri, kind ->
                kinds += kind
                "http://127.0.0.1/resource/${uri.substringAfterLast('/')}"
            }

        assertTrue("URI=\"http://127.0.0.1/resource/main.m3u8\"" in rewritten)
        assertTrue("http://127.0.0.1/resource/init.mp4" in rewritten)
        assertTrue("http://127.0.0.1/resource/key.bin" in rewritten)
        assertTrue("http://127.0.0.1/resource/part-1.m4s" in rewritten)
        assertTrue("http://127.0.0.1/resource/segment-1.m4s" in rewritten)
        assertEquals(
            listOf(
                YHlsResourceKind.RenditionPlaylist,
                YHlsResourceKind.VariantPlaylist,
                YHlsResourceKind.InitializationSegment,
                YHlsResourceKind.EncryptionKey,
                YHlsResourceKind.MediaSegment,
                YHlsResourceKind.MediaSegment,
            ),
            kinds,
        )
        assertFalse("token=secret" in rewritten)
    }

    @Test
    fun low_latency_media_playlist_exposes_reload_window_parts_and_rendition_state() {
        val playlist =
            parseYHlsPlaylist(
                text =
                    """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXT-X-PART-INF:PART-TARGET=0.5
                    #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,CAN-SKIP-UNTIL=24,CAN-SKIP-DATERANGES=YES,HOLD-BACK=12,PART-HOLD-BACK=1.5
                    #EXT-X-MEDIA-SEQUENCE:100
                    #EXT-X-DISCONTINUITY-SEQUENCE:7
                    #EXT-X-SKIP:SKIPPED-SEGMENTS=2
                    #EXTINF:4,
                    segment-102.m4s
                    #EXT-X-PART:DURATION=0.5,URI="segment-103.0.m4s",INDEPENDENT=YES,BYTERANGE="100@20"
                    #EXT-X-PART:DURATION=0.5,URI="segment-103.1.m4s",GAP=YES,BYTERANGE="80"
                    #EXT-X-PRELOAD-HINT:TYPE=PART,URI="segment-103.2.m4s",BYTERANGE-START=200,BYTERANGE-LENGTH=120
                    #EXT-X-RENDITION-REPORT:URI="../alt/live.m3u8",LAST-MSN=103,LAST-PART=1
                    """.trimIndent(),
                baseUri = "https://media.example.test/main/live.m3u8?token=secret",
            )
        val media = assertIs<YHlsPlaylist.Media>(playlist)

        assertTrue(media.isLive)
        assertEquals(7L, media.discontinuitySequence)
        assertEquals(500_000L, media.partTargetDurationUs)
        assertEquals(2, media.skippedSegmentCount)
        assertEquals(102L, media.segments.single().sequence)
        assertTrue(media.serverControl?.canBlockReload == true)
        assertEquals(24_000_000L, media.serverControl?.canSkipUntilUs)
        assertTrue(media.serverControl?.canSkipDateRanges == true)
        assertEquals(1_500_000L, media.serverControl?.partHoldBackUs)
        assertEquals(2, media.partialSegments.size)
        assertEquals(103L, media.partialSegments[0].mediaSequence)
        assertEquals(0, media.partialSegments[0].partIndex)
        assertTrue(media.partialSegments[0].independent)
        assertEquals(YAdaptiveByteRange(100L, 20L), media.partialSegments[0].byteRange)
        assertEquals(YAdaptiveByteRange(80L, 120L), media.partialSegments[1].byteRange)
        assertTrue(media.partialSegments[1].gap)
        assertEquals("https://media.example.test/main/segment-103.2.m4s", media.preloadHint?.uri)
        assertEquals(200L, media.preloadHint?.byteRangeStart)
        assertEquals("https://media.example.test/alt/live.m3u8", media.renditionReports.single().uri)
        assertEquals(103L, media.renditionReports.single().lastMediaSequence)
    }

    @Test
    fun low_latency_playlist_can_start_with_partial_segments_only() {
        val media =
            assertIs<YHlsPlaylist.Media>(
                parseYHlsPlaylist(
                    text =
                        """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:2
                        #EXT-X-PART-INF:PART-TARGET=0.25
                        #EXT-X-MEDIA-SEQUENCE:8
                        #EXT-X-PART:DURATION=0.25,URI="part.m4s",INDEPENDENT=YES
                        """.trimIndent(),
                    baseUri = "https://media.example.test/live.m3u8",
                ),
            )

        assertTrue(media.segments.isEmpty())
        assertEquals(8L, media.partialSegments.single().mediaSequence)
    }

    private companion object {
        val dualDolbyMaster =
            listOf(
                "#EXTM3U",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Stereo\"," +
                    "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"audio/stereo.m3u8\"",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"atmos\",NAME=\"Atmos\"," +
                    "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"6/JOC\",URI=\"audio/atmos.m3u8\"",
                "#EXT-X-STREAM-INF:BANDWIDTH=3500000,STABLE-VARIANT-ID=\"sdr-1080\"," +
                    "RESOLUTION=1920x1080,CODECS=\"hvc1.2.4.L120.B0,mp4a.40.2\"," +
                    "VIDEO-RANGE=SDR,AUDIO=\"stereo\"",
                "video/sdr-1080.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=6500000,STABLE-VARIANT-ID=\"dv-1080\"," +
                    "RESOLUTION=1920x1080,CODECS=\"dvh1.08.06,ec-3\",VIDEO-RANGE=PQ,AUDIO=\"atmos\"",
                "video/dv-1080.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=14000000,STABLE-VARIANT-ID=\"dv-4k\"," +
                    "RESOLUTION=3840x2160,CODECS=\"dvh1.08.06,ec-3\",VIDEO-RANGE=PQ,AUDIO=\"atmos\"",
                "video/dv-4k.m3u8",
            ).joinToString("\n")
    }
}
