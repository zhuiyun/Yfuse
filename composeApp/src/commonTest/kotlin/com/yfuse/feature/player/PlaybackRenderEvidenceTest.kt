package com.yfuse.feature.player

import com.yfuse.core2.api.YDolbyAtmosOutputMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared render-evidence rules, each checked against the expression the engines wrote inline
 * before they fed [PlaybackRenderEvidence], on a state with every field set so a rule that touched
 * one field too many or too few would show.
 */
class PlaybackRenderEvidenceTest {
    private val playing =
        PlaybackDiagnostics(
            engine = "engine",
            decoder = "c2.vendor.hevc.decoder",
            videoOutput = "HDR10 · 硬件解码 · HDR 首帧已输出",
            audioOutput = "源码输出 · E-AC-3",
            videoReadiness = PlaybackOutputReadiness.Rendering,
            audioReadiness = PlaybackOutputReadiness.Rendering,
            dolbyVisionOutput = true,
            dolbyAtmosOutput = true,
            dolbyVisionRpuApplied = true,
            dolbyVisionEnhancementLayerComposed = true,
            immersiveAudioCarrierOutput = true,
            dolbyAtmosSourceDetected = true,
            dolbyAtmosOutputMode = YDolbyAtmosOutputMode.Eac3JocPassthrough,
            spatialAudioOutput = true,
            headTrackingAvailable = true,
            droppedFrames = 3,
            outputEvidence =
                PlaybackOutputEvidence(
                    sessionRevision = 7L,
                    videoReadiness = PlaybackOutputReadiness.Rendering,
                    audioReadiness = PlaybackOutputReadiness.Rendering,
                    videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                    audioConfidence = PlaybackEvidenceConfidence.Confirmed,
                    videoDecoder = "c2.vendor.hevc.decoder",
                    audioDecoder = "eac3",
                    videoCodecProfile = "hvc1.2.4.L153",
                    bitDepth = 10,
                    inputDynamicRange = "HDR10",
                    outputDynamicRange = "HDR10",
                    dynamicRangeOutputMode = PlaybackDynamicRangeOutputMode.DolbyVisionMediaCodec,
                    dolbyVisionRpuRendered = true,
                    dolbyVisionFelComposed = true,
                    renderApi = PlaybackVideoRenderApi.MediaCodecSurface,
                    audioMode = PlaybackAudioOutputMode.Passthrough,
                    secureDecoder = true,
                    tunneledPlayback = true,
                    codecResetCount = 2,
                    surfaceRebuildCount = 1,
                    audioUnderrunCount = 4,
                    droppedFramesMeasured = false,
                    avSyncMeasured = true,
                    displayRefreshRate = 59.94f,
                    mistimedFrameCount = 5,
                    rendererDetail = "rendered=120, maxDrop=2",
                ),
        )

    @Test
    fun a_new_load_attempt_starts_from_the_evidence_every_engine_wrote_inline() {
        val previous = playing.outputEvidence
        for (api in listOf(PlaybackVideoRenderApi.MediaCodecSurface, PlaybackVideoRenderApi.OpenGl)) {
            // Exo (MediaCodecSurface) and mpv (OpenGl): retry, item change, transcode switch.
            assertEquals(
                previous.nextSession().copy(
                    videoConfidence = PlaybackEvidenceConfidence.Requested,
                    audioConfidence = PlaybackEvidenceConfidence.Requested,
                    renderApi = api,
                ),
                previous.nextLoadAttempt(api),
            )
            // Their first attempt, built at construction.
            assertEquals(
                PlaybackOutputEvidence(
                    sessionRevision = 1L,
                    videoConfidence = PlaybackEvidenceConfidence.Requested,
                    audioConfidence = PlaybackEvidenceConfidence.Requested,
                    renderApi = api,
                ),
                PlaybackOutputEvidence().nextLoadAttempt(api),
            )
        }
        // MDK, which cannot see its audio sink.
        assertEquals(
            previous.nextSession().copy(
                videoConfidence = PlaybackEvidenceConfidence.Requested,
                renderApi = PlaybackVideoRenderApi.OpenGl,
            ),
            previous.nextLoadAttempt(PlaybackVideoRenderApi.OpenGl, audioObservable = false),
        )
        assertEquals(
            PlaybackOutputEvidence(
                sessionRevision = 1L,
                videoConfidence = PlaybackEvidenceConfidence.Requested,
                renderApi = PlaybackVideoRenderApi.OpenGl,
            ),
            PlaybackOutputEvidence().nextLoadAttempt(PlaybackVideoRenderApi.OpenGl, audioObservable = false),
        )
    }

    @Test
    fun load_attempts_only_ever_raise_the_session_revision() {
        var evidence = playing.outputEvidence
        for (expected in 8L..12L) {
            evidence = evidence.nextLoadAttempt(PlaybackVideoRenderApi.OpenGl, audioObservable = expected % 2L == 0L)
            assertEquals(expected, evidence.sessionRevision)
        }
    }

    @Test
    fun a_released_video_sink_is_what_exo_and_mpv_wrote_inline() {
        assertEquals(
            playing.copy(
                videoOutput = "视频 Surface 已释放",
                videoReadiness = PlaybackOutputReadiness.Released,
                dolbyVisionOutput = false,
                outputEvidence =
                    playing.outputEvidence.copy(
                        videoReadiness = PlaybackOutputReadiness.Released,
                        videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                        outputDynamicRange = "",
                    ),
            ),
            playing.withVideoOutputReleased("视频 Surface 已释放"),
        )
    }

    @Test
    fun a_released_audio_sink_is_what_exo_wrote_inline() {
        assertEquals(
            playing.copy(
                audioOutput = "音频输出已释放",
                audioReadiness = PlaybackOutputReadiness.Released,
                immersiveAudioCarrierOutput = false,
                dolbyAtmosOutput = false,
                spatialAudioOutput = false,
                headTrackingAvailable = false,
                outputEvidence =
                    playing.outputEvidence.copy(
                        audioReadiness = PlaybackOutputReadiness.Released,
                        audioMode = PlaybackAudioOutputMode.Unknown,
                    ),
            ),
            playing.withAudioOutputReleased("音频输出已释放"),
        )
    }

    @Test
    fun measured_dropped_frames_are_what_exo_and_mpv_wrote_inline() {
        assertEquals(
            playing.copy(
                droppedFrames = 42,
                outputEvidence = playing.outputEvidence.copy(droppedFramesMeasured = true),
            ),
            playing.withDroppedFrames(42),
        )
    }

    @Test
    fun verified_output_renders_and_is_confirmed_anything_else_waits_and_was_requested() {
        assertEquals(PlaybackOutputReadiness.Rendering, PlaybackRenderEvidence.readiness(verified = true))
        assertEquals(PlaybackOutputReadiness.Waiting, PlaybackRenderEvidence.readiness(verified = false))
        assertEquals(PlaybackEvidenceConfidence.Confirmed, PlaybackRenderEvidence.confidence(verified = true))
        assertEquals(PlaybackEvidenceConfidence.Requested, PlaybackRenderEvidence.confidence(verified = false))
        // A released sink waits for nothing, but verified output still wins.
        assertEquals(
            PlaybackOutputReadiness.Rendering,
            PlaybackRenderEvidence.readiness(verified = true, released = true),
        )
        assertEquals(
            PlaybackOutputReadiness.Released,
            PlaybackRenderEvidence.readiness(verified = false, released = true),
        )
        assertEquals(
            PlaybackOutputReadiness.Waiting,
            PlaybackRenderEvidence.readiness(verified = false, released = false),
        )
    }

    @Test
    fun pixel_formats_report_their_bit_depth_as_mpv_and_mdk_both_read_them() {
        val depths =
            mapOf(
                "" to 0,
                " " to 0,
                "nv12" to 8,
                "yuv420p" to 8,
                "yuv420p9le" to 9,
                "p009" to 9,
                "p010" to 10,
                "P010" to 10,
                "yuv420p10le" to 10,
                "p012" to 12,
                "yuv420p12le" to 12,
                "p014" to 14,
                "p016" to 16,
                "yuv444p16le" to 16,
                "rgb48" to 16,
                "rgba64" to 16,
            )
        for ((format, depth) in depths) {
            assertEquals(depth, format.pixelFormatBitDepth(), format)
        }
    }
}
