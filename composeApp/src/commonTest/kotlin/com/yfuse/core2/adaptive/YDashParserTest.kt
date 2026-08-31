package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun playback_mpd_keeps_only_selected_clear_representations_and_local_urls() {
        val manifest =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="static" mediaPresentationDuration="PT30S">
                      <Period>
                        <AdaptationSet contentType="video" mimeType="video/mp4">
                          <SegmentTemplate timescale="1000" duration="5000" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="v-${'$'}Number${'$'}.m4s"/>
                          <Representation id="v-low" bandwidth="500000" width="640" height="360" codecs="avc1.64001f"/>
                          <Representation id="v-high" bandwidth="4000000" width="1920" height="1080" codecs="hvc1.2.4.L120.B0"/>
                        </AdaptationSet>
                        <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="zh-CN">
                          <SegmentTemplate timescale="48000" duration="240000" initialization="a-init.mp4" media="a-${'$'}Number${'$'}.m4s"/>
                          <Representation id="a-main" bandwidth="192000" audioSamplingRate="48000" codecs="mp4a.40.2"/>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """.trimIndent(),
                baseUri = "https://origin.example.test/path/manifest.mpd?token=secret",
            )
        val selection =
            selectYDashPlaybackRepresentations(
                manifest,
                YAdaptiveSelectionConditions(
                    estimatedBandwidthBitsPerSecond = 8_000_000L,
                    bufferedDurationUs = 10_000_000L,
                    maximumWidth = 1920,
                    maximumHeight = 1080,
                ),
            )
        val playback =
            buildYDashPlaybackManifest(manifest, selection) { representation, _, kind ->
                "http://127.0.0.1/${representation.id}/${kind.name}"
            }

        assertEquals("v-high", selection.video.id)
        assertEquals("a-main", selection.audio?.id)
        assertTrue("id=\"v-high\"" in playback)
        assertTrue("id=\"a-main\"" in playback)
        assertFalse("v-low" in playback)
        assertFalse("origin.example.test" in playback)
        assertFalse("token=secret" in playback)
        assertTrue("mediaPresentationDuration=\"PT30S\"" in playback)
    }

    @Test
    fun playback_mpd_keeps_distinct_audio_languages_and_text_tracks() {
        val manifest =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="static" mediaPresentationDuration="PT30S"><Period>
                      <AdaptationSet contentType="video" mimeType="video/mp4">
                        <SegmentTemplate duration="2" initialization="v-init.mp4" media="v-${'$'}Number${'$'}.m4s"/>
                        <Representation id="v" bandwidth="2000000" codecs="avc1.640028"/>
                      </AdaptationSet>
                      <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="zh-CN">
                        <SegmentTemplate duration="2" initialization="a-zh-init.mp4" media="a-zh-${'$'}Number${'$'}.m4s"/>
                        <Representation id="a-zh-low" bandwidth="96000" codecs="mp4a.40.2"/>
                        <Representation id="a-zh-high" bandwidth="192000" codecs="mp4a.40.2"/>
                      </AdaptationSet>
                      <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                        <SegmentTemplate duration="2" initialization="a-en-init.mp4" media="a-en-${'$'}Number${'$'}.m4s"/>
                        <Representation id="a-en" bandwidth="128000" codecs="mp4a.40.2"/>
                      </AdaptationSet>
                      <AdaptationSet contentType="text" mimeType="application/mp4" lang="zh-CN">
                        <SegmentTemplate duration="2" initialization="s-init.mp4" media="s-${'$'}Number${'$'}.m4s"/>
                        <Representation id="s-zh" bandwidth="1000" codecs="wvtt"/>
                      </AdaptationSet>
                    </Period></MPD>
                    """.trimIndent(),
                baseUri = "https://media.example.test/manifest.mpd",
            )
        val selection =
            selectYDashPlaybackRepresentations(
                manifest,
                YAdaptiveSelectionConditions(5_000_000L, 10_000_000L),
            )
        val playback = buildYDashPlaybackManifest(manifest, selection) { _, template, _ -> template }

        assertEquals(
            setOf("a-zh-high", "a-en"),
            (listOfNotNull(selection.audio) + selection.alternateAudio).map { it.id }.toSet(),
        )
        assertEquals(listOf("s-zh"), selection.text.map { it.id })
        assertTrue("id=\"a-zh-high\"" in playback)
        assertTrue("id=\"a-en\"" in playback)
        assertFalse("id=\"a-zh-low\"" in playback)
        assertTrue("id=\"s-zh\"" in playback)
    }

    @Test
    fun playback_mpd_requires_drm_opt_in_and_preserves_dynamic_live_window() {
        val protected =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="static" mediaPresentationDuration="PT10S"><Period>
                      <AdaptationSet contentType="video" mimeType="video/mp4">
                        <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                        <SegmentTemplate duration="5" initialization="init.mp4" media="s-${'$'}Number${'$'}.m4s"/>
                        <Representation id="v1" bandwidth="1000000"/>
                      </AdaptationSet>
                    </Period></MPD>
                    """.trimIndent(),
                baseUri = "https://media.example.test/manifest.mpd",
            )
        val conditions = YAdaptiveSelectionConditions(2_000_000L, 10_000_000L)
        val protectedSelection = selectYDashPlaybackRepresentations(protected, conditions)
        assertFailsWith<IllegalArgumentException> {
            buildYDashPlaybackManifest(protected, protectedSelection) { _, template, _ -> template }
        }
        val protectedPlayback =
            buildYDashPlaybackManifest(
                manifest = protected,
                selection = protectedSelection,
                allowContentProtection = true,
            ) { _, template, _ -> template }
        assertTrue("schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"" in protectedPlayback)

        val dynamic =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="dynamic" availabilityStartTime="2026-08-31T00:00:00Z"
                         publishTime="2026-08-31T00:01:00Z" minimumUpdatePeriod="PT2S"
                         timeShiftBufferDepth="PT60S" suggestedPresentationDelay="PT8S"><Period start="PT0S">
                      <AdaptationSet contentType="video" mimeType="video/mp4">
                        <SegmentTemplate timescale="1000" initialization="init.mp4" media="s-${'$'}Number${'$'}.m4s">
                          <SegmentTimeline><S t="0" d="2000" r="-1"/></SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="live-v1" bandwidth="1000000" codecs="avc1.64001f"/>
                      </AdaptationSet>
                    </Period></MPD>
                    """.trimIndent(),
                baseUri = "https://media.example.test/live.mpd",
            )
        val dynamicSelection = selectYDashPlaybackRepresentations(dynamic, conditions)
        val dynamicPlayback = buildYDashPlaybackManifest(dynamic, dynamicSelection) { _, template, _ -> template }
        assertTrue("type=\"dynamic\"" in dynamicPlayback)
        assertTrue("availabilityStartTime=\"2026-08-31T00:00:00Z\"" in dynamicPlayback)
        assertTrue("minimumUpdatePeriod=\"PT2S\"" in dynamicPlayback)
        assertTrue("timeShiftBufferDepth=\"PT60S\"" in dynamicPlayback)
        assertTrue("suggestedPresentationDelay=\"PT8S\"" in dynamicPlayback)
        assertTrue("r=\"-1\"" in dynamicPlayback)
    }

    @Test
    fun selects_and_preserves_the_Dolby_Vision_Atmos_family() {
        val manifest =
            parseYDashManifest(
                xml =
                    """
                    <MPD type="static" mediaPresentationDuration="PT10S"><Period>
                      <AdaptationSet contentType="video" mimeType="video/mp4">
                        <SegmentTemplate duration="5" initialization="v-init.mp4" media="v-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"/>
                        <Representation id="sdr" bandwidth="1000000" codecs="hvc1.2.4.L120"/>
                        <Representation id="dv" bandwidth="2000000" codecs="dvh1.08.06"/>
                      </AdaptationSet>
                      <AdaptationSet contentType="audio" mimeType="audio/mp4">
                        <SegmentTemplate duration="5" initialization="a-init.mp4" media="a-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"/>
                        <Representation id="eac3" bandwidth="256000" codecs="ec-3"/>
                        <Representation id="joc" bandwidth="768000" codecs="ec-3">
                          <SupplementalProperty schemeIdUri="tag:dolby.com,2018:dash:EC3_ExtensionType:2018" value="JOC"/>
                        </Representation>
                      </AdaptationSet>
                    </Period></MPD>
                    """.trimIndent(),
                baseUri = "https://media.example.test/dolby.mpd",
            )
        val selection =
            selectYDashPlaybackRepresentations(
                manifest = manifest,
                conditions = YAdaptiveSelectionConditions(5_000_000L, 10_000_000L),
                capabilities =
                    YDashPlaybackCapabilities(
                        dolbyVisionOutput = true,
                        dolbyAtmosOutput = true,
                    ),
            )

        assertEquals("dv", selection.video.id)
        assertEquals("joc", selection.audio?.id)
        assertTrue(selection.audio?.isDolbyAtmos == true)
        val playback = buildYDashPlaybackManifest(manifest, selection) { _, template, _ -> template }
        assertTrue("EC3_ExtensionType" in playback)
        assertTrue("value=\"JOC\"" in playback)
    }
}
