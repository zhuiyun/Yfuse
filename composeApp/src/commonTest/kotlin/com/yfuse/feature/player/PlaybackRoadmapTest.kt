package com.yfuse.feature.player

import com.yfuse.core.data.dto.DeviceProfileDto
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun crossOriginPlaybackInfoUrlsReachThePlayerWithoutEmbyCredentials() {
        val direct = "https://cdn.example/video.mp4"
        val transcode = "https://cdn.example/master.m3u8"
        val player =
            listOf(
                version(
                    supportsDirectPlay = false,
                    supportsDirectStream = true,
                    supportsTranscoding = true,
                    directStreamUrl = direct,
                    transcodingUrl = transcode,
                ),
            ).toPlayerMediaVersions(
                baseUrl = "https://emby.example",
                itemId = "item",
                token = "server-secret",
                negotiatedPlaySessionId = "session123",
            ).single()

        assertEquals(PlaybackMethod.DirectStream, player.playMethod)
        assertEquals(direct, player.url)
        assertEquals(transcode, player.transcodeUrl)
        listOf(player.url, player.transcodeUrl).forEach { url ->
            assertFalse("server-secret" in url, url)
            assertFalse("api_key" in url, url)
            assertFalse("DeviceId" in url, url)
            assertFalse("PlaySessionId" in url, url)
        }
    }

    @Test
    fun publicHttpPlaybackInfoUrlsFollowTheExistingPolicyButStayCredentialFree() {
        val direct = "http://media.example/direct?server=value"
        val transcode = "http://media.example/master.m3u8?server=value"
        val player =
            listOf(
                version(
                    supportsDirectPlay = false,
                    supportsDirectStream = true,
                    supportsTranscoding = true,
                    directStreamUrl = direct,
                    transcodingUrl = transcode,
                ),
            ).toPlayerMediaVersions(
                baseUrl = "https://emby.example",
                itemId = "item",
                token = "secret-token",
                negotiatedPlaySessionId = "session",
                localCleartextConfirmed = true,
            ).single()

        assertEquals(PlaybackMethod.DirectStream, player.playMethod)
        assertEquals(direct, player.url)
        assertEquals(transcode, player.transcodeUrl)
        assertFalse("secret-token" in player.url, player.url)
        assertFalse("api_key" in player.url, player.url)
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
        assertTrue(player.serverTranscodeSupported)
        assertContains(player.url, "/Videos/item/master.m3u8")
        assertEquals(player.url, player.transcodeUrl)
    }

    @Test
    fun iso_never_falls_through_to_the_original_file_when_negotiation_is_incomplete() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = null,
                    supportsDirectStream = null,
                    supportsTranscoding = null,
                    container = "iso",
                    videoType = "Iso",
                    path = "/media/Movie/Movie.iso",
                ),
            ).toPlayerMediaVersions("http://host", "item", "token", "session").single()

        assertTrue(player.discSource)
        assertEquals(PlaybackMethod.Transcode, player.playMethod)
        assertContains(player.url, "/Videos/item/master.m3u8")
        assertEquals(player.transcodeUrl, player.url)
    }

    @Test
    fun iso_uses_a_best_effort_server_stream_even_when_an_old_server_denies_the_flag() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = true,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    container = "iso",
                ),
            ).toPlayerMediaVersions("http://host", "item", "token", "session").single()

        assertEquals(PlaybackMethod.Transcode, player.playMethod)
        assertContains(player.transcodeUrl, "/Videos/item/master.m3u8")
        assertContains(player.fallbackTranscodeUrl, "/Videos/item/stream.mp4")
    }

    @Test
    fun a_server_selected_disc_direct_stream_is_used_instead_of_the_raw_iso() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    supportsTranscoding = true,
                    directStreamUrl = "/Videos/item/stream.m2ts?MediaSourceId=source",
                    container = "iso",
                    videoType = "BluRay",
                ),
            ).toPlayerMediaVersions("http://host", "item", "token", "session").single()

        assertEquals(PlaybackMethod.DirectStream, player.playMethod)
        assertContains(player.url, "/Videos/item/stream.m2ts")
        assertFalse("static=true" in player.url)
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
        assertFalse(player.serverTranscodeSupported)
    }

    @Test
    fun omitted_transcode_capability_keeps_best_effort_urls_out_of_preflight_truth() {
        val player =
            listOf(
                version(
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    supportsTranscoding = null,
                ),
            ).toPlayerMediaVersions("http://host", "item", "token").single()

        assertTrue(player.transcodeUrl.isNotEmpty())
        assertFalse(player.serverTranscodeSupported)
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

    @Test
    fun trickplay_cache_is_lazy_bounded_and_remembers_metadata_misses() {
        val first = TrickplayCacheKey("server", "episode-1", "source-1")
        val miss = TrickplayCacheKey("server", "episode-2", "source-2")
        val storyboard =
            TrickplayStoryboard("https://host/{index}.jpg", 320, 180, 10, 10, 10_000L, 200)
        var cache = emptyMap<TrickplayCacheKey, TrickplayStoryboard?>()

        cache = cache.withTrickplayResult(first, storyboard, maxEntries = 2)
        cache = cache.withTrickplayResult(miss, null, maxEntries = 2)

        assertEquals(storyboard, cache[first])
        assertTrue(cache.containsKey(miss), "A confirmed metadata miss must not refetch on recomposition")
        assertNull(cache[miss])

        val third = TrickplayCacheKey("server", "episode-3", "source-3")
        cache = cache.withTrickplayResult(third, storyboard, maxEntries = 2)
        assertFalse(cache.containsKey(first))
        assertTrue(cache.containsKey(miss))
        assertTrue(cache.containsKey(third))
    }

    @Test
    fun hdr_caption_luminance_and_sleep_presets_have_stable_bounds() {
        assertEquals(89, subtitleBrightnessByte(0f))
        assertEquals(255, subtitleBrightnessByte(2f))
        assertEquals("0x999999ff", subtitleBrightnessRgba(0.6f))
        assertEquals("#ff999999", subtitleBrightnessMpvColor(0.6f))
        assertEquals(15 * 60_000L, SleepTimerOption.Minutes15.durationMs)
        assertEquals(60 * 60_000L, SleepTimerOption.Minutes60.durationMs)
        assertNull(SleepTimerOption.EndOfEpisode.durationMs)
    }

    @Test
    fun end_of_episode_timer_distinguishes_auto_transition_manual_switch_and_cast_ownership() {
        assertTrue(
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = 3,
                currentIndex = 3,
                ended = true,
                playing = false,
                armedItemReachedEnd = true,
            ),
        )
        assertTrue(
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = 3,
                currentIndex = 4,
                ended = false,
                playing = false,
                armedItemReachedEnd = true,
            ),
        )
        assertFalse(
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = 3,
                currentIndex = 4,
                ended = false,
                playing = false,
                armedItemReachedEnd = false,
            ),
            "A paused manual episode switch is not an end-of-episode event",
        )
        assertTrue(
            shouldCompleteCastEndOfEpisodeTimer(
                armedIndex = 3,
                armedSessionRevision = 9L,
                currentIndex = 3,
                currentSessionRevision = 9L,
                castEnded = true,
            ),
        )
        assertFalse(
            shouldCompleteCastEndOfEpisodeTimer(
                armedIndex = 3,
                armedSessionRevision = 8L,
                currentIndex = 3,
                currentSessionRevision = 9L,
                castEnded = true,
            ),
            "A stale receiver session must not disarm or advance the current cast",
        )
    }

    private fun version(
        supportsDirectPlay: Boolean?,
        supportsDirectStream: Boolean?,
        supportsTranscoding: Boolean?,
        directStreamUrl: String? = null,
        transcodingUrl: String? = null,
        container: String = "mkv",
        videoType: String? = null,
        path: String? = null,
    ) = MediaVersion(
        id = "source",
        name = "Source",
        container = container,
        sizeBytes = null,
        bitrateBps = 8_000_000,
        videoCodec = "h264",
        videoHeight = 1080,
        videoRange = null,
        path = path,
        videoType = videoType,
        supportsDirectPlay = supportsDirectPlay,
        supportsDirectStream = supportsDirectStream,
        supportsTranscoding = supportsTranscoding,
        directStreamUrl = directStreamUrl,
        transcodingUrl = transcodingUrl,
    )
}
