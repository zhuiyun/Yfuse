package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTruthTest {

    @Test
    fun manual_cap_starts_on_transcode_and_records_the_user_reason() {
        val item = PlayerMediaItem(
            id = "movie",
            url = "direct",
            transcodeUrl = "hls",
            title = "电影",
        )

        assertTrue(item.startsWithServerTranscode(PlaybackQuality.FullHd))
        assertEquals(
            PlaybackMethod.Transcode,
            item.effectivePlaybackMethod(PlaybackQuality.FullHd),
        )
        assertEquals("用户选择 1080P · 8 Mbps", item.initialFallbackReason(PlaybackQuality.FullHd))
    }

    @Test
    fun auto_preserves_the_server_negotiated_direct_stream_truth() {
        val item = PlayerMediaItem(
            id = "movie",
            url = "direct-stream",
            transcodeUrl = "hls",
            title = "电影",
            playMethod = PlaybackMethod.DirectStream,
        )

        assertFalse(item.startsWithServerTranscode(PlaybackQuality.Auto))
        assertEquals(PlaybackMethod.DirectStream, item.effectivePlaybackMethod(PlaybackQuality.Auto))
        assertEquals("服务器协商为直串流", item.initialFallbackReason(PlaybackQuality.Auto))
    }

    @Test
    fun missing_transcode_url_does_not_replace_a_working_direct_source() {
        val item = PlayerMediaItem("movie", "direct", "", "电影")

        assertFalse(item.startsWithServerTranscode(PlaybackQuality.Hd))
        assertEquals(PlaybackMethod.DirectPlay, item.effectivePlaybackMethod(PlaybackQuality.Hd))
        assertEquals(
            "服务器未提供转码地址，已保留原始播放方式",
            item.initialFallbackReason(PlaybackQuality.Hd),
        )
        assertNull(item.initialFallbackReason(PlaybackQuality.Original))
    }

    @Test
    fun device_preflight_starts_on_transcode_before_the_engine_is_created() {
        val item =
            PlayerMediaItem(
                id = "dolby",
                url = "direct-dolby",
                transcodeUrl = "safe-hls",
                title = "杜比视界",
            ).withForcedServerTranscode("设备不支持 4K60 Dolby Vision")

        assertTrue(item.startsWithServerTranscode(PlaybackQuality.Auto))
        assertEquals(PlaybackMethod.Transcode, item.effectivePlaybackMethod(PlaybackQuality.Auto))
        assertEquals(
            "设备不支持 4K60 Dolby Vision",
            item.initialFallbackReason(PlaybackQuality.Auto),
        )
    }

    @Test
    fun dolby_badges_require_runtime_output_evidence_not_source_metadata() {
        assertTrue(
            PlaybackDiagnostics(
                videoOutput = "Dolby Vision · 硬件解码 · HDR 首帧已输出",
                audioOutput = "源码输出 · Dolby Atmos / E-AC-3 JOC",
            ).let { it.hasActiveDolbyVisionOutput() && it.hasActiveDolbyAtmosOutput() },
        )
        assertFalse(
            PlaybackDiagnostics(
                videoOutput = "Dolby Vision · 等待首帧",
                audioOutput = "PCM 解码输出",
            ).let { it.hasActiveDolbyVisionOutput() || it.hasActiveDolbyAtmosOutput() },
        )
    }

    @Test
    fun fast_probe_builds_a_credential_free_capability_signature() {
        val secret = "private-access-token"
        val version =
            PlayerMediaVersion(
                id = "source",
                label = "4K",
                detail = "4K HEVC",
                url = "https://example/video?api_key=$secret",
                transcodeUrl = "https://example/master.m3u8?api_key=$secret",
                fallbackTranscodeUrl = "",
                container = "mkv",
                sourceVideoCodec = "hevc",
                sourceWidth = 3_840,
                sourceHeight = 2_160,
                sourceFrameRate = 23.976,
                sourceBitDepth = 10,
                sourceDynamicRange = "HDR10",
            )
        val probe =
            PlayerMediaItem(
                id = "movie",
                url = version.url,
                transcodeUrl = version.transcodeUrl,
                title = "电影",
                versions = listOf(version),
                versionId = version.id,
            ).playbackMediaProbe()

        assertEquals("MKV", probe.normalizedContainer)
        assertTrue(probe.hasServerTranscode)
        assertFalse(secret in probe.capabilitySignature)
        assertFalse("example" in probe.capabilitySignature)
    }

    @Test
    fun secure_item_marks_the_probe_without_leaking_license_credentials() {
        val secret = "https://license.example.test/widevine?token=secret"
        val item =
            PlayerMediaItem(
                id = "secure",
                url = "https://media.example.test/manifest.mpd",
                transcodeUrl = "",
                title = "Secure",
                drmConfiguration =
                    PlaybackDrmConfiguration(
                        scheme = PlaybackDrmScheme.Widevine,
                        licenseUri = secret,
                    ),
            )

        val probe = item.playbackMediaProbe()

        assertTrue(probe.drmProtected)
        assertFalse(secret in probe.capabilitySignature)
    }
}
