package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackOfflineLicenseNativeTest {
    @Test
    fun dashWidevinePsshIsExtractedWithoutExoPlayer() {
        val manifest =
            """
            <MPD xmlns:cenc="urn:mpeg:cenc:2013" mediaPresentationDuration="PT10S">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed">
                    <cenc:pssh>AQIDBA==</cenc:pssh>
                  </ContentProtection>
                  <SegmentTemplate media="segment-${'$'}Number${'$'}.m4s" duration="2" />
                  <Representation id="video" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
            """.trimIndent()

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            extractWidevinePsshFromDash(manifest, "https://media.example/movie.mpd"),
        )
    }

    @Test
    fun hlsWidevineSessionKeyIsExtractedWithoutExoPlayer() {
        val manifest =
            """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",URI="data:text/plain;base64,AQIDBA=="
            """.trimIndent()

        assertContentEquals(byteArrayOf(1, 2, 3, 4), extractWidevinePsshFromHls(manifest))
        assertNull(extractWidevinePsshFromHls("#EXTM3U\n#EXT-X-VERSION:7"))
    }

    @Test
    fun keyStatusDurationsBecomeAbsoluteExpiries() {
        val updatedAt = 1_000_000L
        val license =
            mapOf(
                "LicenseDurationRemaining" to "120",
                "PlaybackDurationRemaining" to "30",
            ).toOfflineLicense(
                id = "license-id",
                acquiredAtEpochMs = 900_000L,
                updatedAtEpochMs = updatedAt,
            )

        assertEquals(1_120_000L, license.licenseExpiresAtEpochMs)
        assertEquals(1_030_000L, license.playbackExpiresAtEpochMs)
    }
}
