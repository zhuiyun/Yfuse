package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.DolbyVisionP7OutputEvidence
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
        val item =
            PlayerMediaItem(
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
        val item =
            PlayerMediaItem(
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
                dolbyVisionOutput = true,
                dolbyAtmosOutput = true,
            ).let { it.hasActiveDolbyVisionOutput() && it.hasActiveDolbyAtmosOutput() },
        )
        assertFalse(
            PlaybackDiagnostics(
                dolbyVisionOutput = false,
                dolbyAtmosOutput = false,
            ).let { it.hasActiveDolbyVisionOutput() || it.hasActiveDolbyAtmosOutput() },
        )
    }

    /**
     * A badge is a claim made to the viewer about their hardware, so it must not be reachable
     * by wording. These were recovered by substring-matching the diagnostic labels — 首帧已输出,
     * 未声明支持, 源码输出, Atmos, TrueHD — which made the badges a property of how a sentence
     * was phrased. Reordering a label, or translating the app, would have turned them on or off.
     */
    @Test
    fun a_dolby_badge_cannot_be_produced_by_the_wording_of_a_label() {
        val labelsThatUsedToPass =
            PlaybackDiagnostics(
                videoOutput = "Dolby Vision · 硬件解码 · HDR 首帧已输出",
                audioOutput = "源码输出 · Dolby Atmos / E-AC-3 JOC",
            )

        assertFalse(labelsThatUsedToPass.hasActiveDolbyVisionOutput())
        assertFalse(labelsThatUsedToPass.hasActiveDolbyAtmosOutput())
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
                serverTranscodeSupported = true,
            )
        val probe =
            PlayerMediaItem(
                id = "movie",
                url = version.url,
                transcodeUrl = version.transcodeUrl,
                title = "电影",
                versions = listOf(version),
                versionId = version.id,
                serverTranscodeSupported = true,
            ).playbackMediaProbe()

        assertEquals("MKV", probe.normalizedContainer)
        assertTrue(probe.hasServerTranscode)
        assertFalse(secret in probe.capabilitySignature)
        assertFalse("example" in probe.capabilitySignature)
    }

    @Test
    fun generated_fallback_url_is_not_treated_as_server_approved_transcoding() {
        val probe =
            PlayerMediaItem(
                id = "dolby",
                url = "direct",
                transcodeUrl = "generated-best-effort-hls",
                fallbackTranscodeUrl = "generated-best-effort-progressive",
                title = "Dolby Vision",
                playMethod = PlaybackMethod.DirectPlay,
                serverTranscodeSupported = false,
            ).playbackMediaProbe()

        assertFalse(probe.hasServerTranscode)
    }

    @Test
    fun dolby_source_disables_automatic_server_fallback_but_keeps_manual_choice() {
        val version =
            PlayerMediaVersion(
                id = "dolby-source",
                label = "Dolby Vision",
                detail = "4K Dolby Vision",
                url = "direct-dolby",
                transcodeUrl = "server-hls",
                fallbackTranscodeUrl = "server-mp4",
                dolbyVision = true,
                playMethod = PlaybackMethod.Transcode,
                serverTranscodeSupported = true,
            )
        val item =
            PlayerMediaItem(
                id = "dolby",
                url = version.url,
                transcodeUrl = version.transcodeUrl,
                fallbackTranscodeUrl = version.fallbackTranscodeUrl,
                title = "Dolby Vision",
                versions = listOf(version),
                versionId = version.id,
                playMethod = version.playMethod,
                serverTranscodeSupported = true,
            )

        assertFalse(item.startsWithServerTranscode(PlaybackQuality.Auto))
        assertFalse(item.playbackMediaProbe().hasServerTranscode)
        assertFalse(item.allowsServerTranscodeFallback("解码失败"))
        assertTrue(item.allowsServerTranscodeFallback("用户手动选择服务器转码"))
        assertTrue(item.startsWithServerTranscode(PlaybackQuality.FullHd))
    }

    @Test
    fun p7_fel_log_never_claims_enhancement_without_explicit_output_trace() {
        val version =
            PlayerMediaVersion(
                id = "p7-fel",
                label = "Dolby Vision P7",
                detail = "P7 + EL",
                url = "direct",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                dolbyVision = true,
                dolbyProfile = 7,
                sourceDolbyRpuPresent = true,
                sourceDolbyEnhancementLayerPresent = true,
                sourceDolbyBaseLayerPresent = true,
            )

        val baseLayer =
            version.dolbyVisionP7Output(
                PlaybackDiagnostics(videoReadiness = PlaybackOutputReadiness.Rendering),
            )
        val composed =
            version.dolbyVisionP7Output(
                PlaybackDiagnostics(
                    videoReadiness = PlaybackOutputReadiness.Rendering,
                    dolbyVisionRpuApplied = true,
                    dolbyVisionEnhancementLayerComposed = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.BaseLayerOnly, baseLayer.evidence)
        assertFalse(baseLayer.canClaimFel)
        assertEquals(DolbyVisionP7OutputEvidence.EnhancementLayerComposed, composed.evidence)
        assertTrue(composed.canClaimFel)
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
