package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YDashParserTest {
    @Test
    fun static_mpd_inherits_templates_and_preserves_content_protection() {
        val manifest =
            parseYDashManifest(
                xml =
                    """
                    <?xml version="1.0"?>
                    <MPD type="static" mediaPresentationDuration="PT1M30.5S">
                      <BaseURL>https://cdn.example.test/root/</BaseURL>
                      <Period>
                        <AdaptationSet contentType="video" mimeType="video/mp4" codecs="hvc1.2.4.L120.B0">
                          <ContentProtection schemeIdUri="urn:uuid:edef8ba9" cenc:default_KID="kid-1">
                            <cenc:pssh>AAAATEST</cenc:pssh>
                          </ContentProtection>
                          <SegmentTemplate timescale="1000" duration="5000" startNumber="7" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="seg-${'$'}Number%05d${'$'}.m4s"/>
                          <Representation id="v720" bandwidth="1400000" width="1280" height="720" frameRate="30000/1001"/>
                          <Representation id="v1080" bandwidth="3200000" width="1920" height="1080"/>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """.trimIndent(),
                baseUri = "https://origin.example.test/manifest.mpd",
            )

        assertFalse(manifest.isLive)
        assertEquals(90_500_000L, manifest.mediaPresentationDurationUs)
        assertEquals(2, manifest.representations.size)
        val first = manifest.representations.first()
        assertEquals(YDashContentType.Video, first.contentType)
        assertEquals(30_000.0 / 1_001.0, first.frameRate)
        assertEquals("kid-1", first.contentProtections.single().defaultKeyId)
        assertEquals("AAAATEST", first.contentProtections.single().psshBase64)
        val template = assertNotNull(first.segmentTemplate)
        assertEquals(7L, template.startNumber)
        assertEquals(
            "https://cdn.example.test/root/seg-00007.m4s",
            renderDashTemplate(template.media, first, number = 7L),
        )
        assertEquals(
            "https://cdn.example.test/root/init-v720.mp4",
            renderDashTemplate(assertNotNull(template.initialization), first, number = 7L),
        )
    }

    @Test
    fun dynamic_mpd_parses_timeline_and_representation_base_url() {
        val manifest =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="dynamic" minimumUpdatePeriod="PT5S">
                      <Period>
                        <AdaptationSet mimeType="audio/mp4" lang="zh-CN">
                          <Representation id="a1" bandwidth="192000" audioSamplingRate="48000" codecs="mp4a.40.2">
                            <BaseURL>audio/</BaseURL>
                            <SegmentTemplate timescale="48000" initialization="init.m4s" media="chunk-${'$'}Time${'$'}.m4s">
                              <SegmentTimeline>
                                <S t="96000" d="240000" r="2"/>
                              </SegmentTimeline>
                            </SegmentTemplate>
                          </Representation>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """.trimIndent(),
                baseUri = "https://live.example.test/path/live.mpd",
            )

        assertTrue(manifest.isLive)
        assertEquals(5_000_000L, manifest.minimumUpdatePeriodUs)
        val audio = manifest.representations.single()
        assertEquals(YDashContentType.Audio, audio.contentType)
        assertEquals("https://live.example.test/path/audio/", audio.baseUri)
        assertEquals("zh-CN", audio.language)
        val expanded = expandDashTimeline(assertNotNull(audio.segmentTemplate))
        assertEquals(listOf(96_000L, 336_000L, 576_000L), expanded.map { it.time })
        assertEquals(
            "https://live.example.test/path/audio/chunk-96000.m4s",
            renderDashTemplate(assertNotNull(audio.segmentTemplate).media, audio, number = 1L, time = 96_000L),
        )
    }
}
