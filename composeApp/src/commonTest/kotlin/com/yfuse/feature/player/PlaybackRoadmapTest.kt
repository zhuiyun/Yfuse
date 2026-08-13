package com.yfuse.feature.player

import com.yfuse.core.data.dto.DeviceProfileDto
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRoadmapTest {
    @Test
    fun playbackInfoDirectStreamUrlBecomesPrimaryAndKeepsSession() {
        val version =
            version(
                supportsDirectPlay = false,
                supportsDirectStream = true,
                supportsTranscoding = true,
                directStreamUrl = "/Videos/item/stream.mp4?MediaSourceId=source",
            )

        val player =
            listOf(version)
                .toPlayerMediaVersions(
                    baseUrl = "https://emby.example/",
                    itemId = "item",
                    token = "secret token",
                    negotiatedPlaySessionId = "session123",
                ).single()

        assertEquals(PlaybackMethod.DirectStream, player.playMethod)
        assertContains(player.url, "https://emby.example/Videos/item/stream.mp4")
        assertContains(player.url, "api_key=secret%20token")
        assertContains(player.url, "PlaySessionId=session123")
    }

    @Test
    fun publicHttpUrlsReturnedByPlaybackInfoNeverReachThePlayer() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = false,
                    supportsDirectStream = true,
                    supportsTranscoding = true,
                    directStreamUrl = "http://media.example/direct?server=value",
                    transcodingUrl = "http://media.example/master.m3u8?server=value",
                ),
            ).toPlayerMediaVersions(
                baseUrl = "https://emby.example",
                itemId = "item",
                token = "secret-token",
                negotiatedPlaySessionId = "session",
                localCleartextConfirmed = true,
            ).single()

        assertEquals(PlaybackMethod.Transcode, player.playMethod)
        assertTrue(player.url.startsWith("https://emby.example/"), player.url)
        assertFalse("media.example" in player.url, player.url)
        assertFalse("server=value" in player.transcodeUrl, player.transcodeUrl)
    }

    @Test
    fun negotiationUsesGeneratedTranscodeWhenServerOmitsItsUrl() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                ),
            ).toPlayerMediaVersions("http://host", "item", "token", "session").single()

        assertEquals(PlaybackMethod.Transcode, player.playMethod)
        assertContains(player.url, "/Videos/item/master.m3u8")
        assertEquals(player.url, player.transcodeUrl)
    }

    @Test
    fun unsupportedTranscodingDoesNotCreateFallbackUrls() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = true,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                ),
            ).toPlayerMediaVersions("http://host", "item", "token").single()

        assertTrue(player.transcodeUrl.isEmpty())
        assertTrue(player.fallbackTranscodeUrl.isEmpty())
    }

    @Test
    fun deviceProfileAdvertisesStyledAndBitmapSubtitleDelivery() {
        val formats = DeviceProfileDto.yfuseAndroid().SubtitleProfiles.associate { it.Format to it.Method }

        assertEquals("Embed", formats["ass"])
        assertEquals("Embed", formats["ssa"])
        assertEquals("Embed", formats["pgs"])
        assertEquals("External", formats["srt"])
    }

    @Test
    fun trickplayFrameSelectsSheetAndTile() {
        val storyboard =
            TrickplayStoryboard(
                urlPattern = "https://host/Trickplay/320/{index}.jpg",
                width = 320,
                height = 180,
                tileColumns = 10,
                tileRows = 10,
                intervalMs = 10_000L,
                thumbnailCount = 250,
            )

        assertEquals(TrickplayFrame("https://host/Trickplay/320/1.jpg", 2, 0), storyboard.frameAt(1_020_000L))
        assertEquals(TrickplayFrame("https://host/Trickplay/320/2.jpg", 9, 4), storyboard.frameAt(Long.MAX_VALUE))
    }

    @Test
    fun advancedSubtitleCodecsRequestStyledRenderer() {
        assertTrue(EngineTrack("1", "ASS", null, false, "ass").requiresStyledRenderer)
        assertTrue(EngineTrack("2", "PGS", null, false, "application/pgs").requiresStyledRenderer)
        assertFalse(EngineTrack("3", "SRT", null, false, "srt").requiresStyledRenderer)
    }

    @Test
    fun pictureModeCyclesThroughFitCropAndStretch() {
        assertEquals(VideoScaleMode.Fill, VideoScaleMode.Fit.next())
        assertEquals(VideoScaleMode.Stretch, VideoScaleMode.Fill.next())
        assertEquals(VideoScaleMode.Fit, VideoScaleMode.Stretch.next())
    }

    private fun version(
        supportsDirectPlay: Boolean?,
        supportsDirectStream: Boolean?,
        supportsTranscoding: Boolean?,
        directStreamUrl: String? = null,
        transcodingUrl: String? = null,
    ) = MediaVersion(
        id = "source",
        name = "Source",
        container = "mkv",
        sizeBytes = null,
        bitrateBps = 8_000_000,
        videoCodec = "h264",
        videoHeight = 1080,
        videoRange = null,
        supportsDirectPlay = supportsDirectPlay,
        supportsDirectStream = supportsDirectStream,
        supportsTranscoding = supportsTranscoding,
        directStreamUrl = directStreamUrl,
        transcodingUrl = transcodingUrl,
    )
}
