package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerControlFormattingDolbyTest {
    @Test
    fun nativeDolbyOutputWinsOverSourceBadge() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        dynamicRange = "Dolby Vision Profile 8",
                        dolbyVisionOutput = true,
                    ),
            )

        assertEquals("DV 原生", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun mpvToneMappingIsNotPresentedAsNativeDolby() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        dynamicRange = "Dolby Vision Profile 5",
                        videoOutput = "Dolby Vision → SDR · mpv 色调映射",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        planningReason = "设备没有完整 Dolby Vision 输出链，使用客户端 Dolby 解码和色调映射",
                    ),
            )

        assertEquals("DV→SDR", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun hdr10BaseLayerGetsExplicitCompatibilityGrade() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        dynamicRange = "HDR10",
                        videoOutput = "HDR10 · mpv 视频输出已建立",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        planningReason = "Dolby Vision 使用客户端 HDR10 基础层和色调映射，不依赖服务器转码",
                    ),
            )

        assertEquals("DV→HDR10", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun felRequiresExplicitCompositionEvidence() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        dynamicRange = "Dolby Vision Profile 7",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        dolbyVisionRpuApplied = true,
                        dolbyVisionEnhancementLayerComposed = true,
                    ),
            )

        assertEquals("DV FEL", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun mpvPostRenderFelEvidenceUpgradesReadout() {
        MpvDolbyRuntimeEvidenceRegistry.installProvider {
            MpvDolbyRuntimeEvidence(
                generation = 7L,
                rpuRendered = true,
                felComposed = true,
            )
        }
        try {
            val state =
                PlaybackState(
                    diagnostics =
                        PlaybackDiagnostics(
                            engine = "libmpv",
                            dynamicRange = "Dolby Vision Profile 7",
                            videoReadiness = PlaybackOutputReadiness.Rendering,
                        ),
                )

            assertEquals("DV FEL", state.dolbyVisionReadoutLabel())
        } finally {
            MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        }
    }

    @Test
    fun mpvEvidenceIsIgnoredBeforeVideoOutputRenders() {
        MpvDolbyRuntimeEvidenceRegistry.installProvider {
            MpvDolbyRuntimeEvidence(
                generation = 8L,
                rpuRendered = true,
                felComposed = true,
            )
        }
        try {
            val state =
                PlaybackState(
                    diagnostics =
                        PlaybackDiagnostics(
                            engine = "libmpv",
                            dynamicRange = "Dolby Vision Profile 7",
                            videoReadiness = PlaybackOutputReadiness.Waiting,
                        ),
                )

            assertEquals("DV", state.dolbyVisionReadoutLabel())
        } finally {
            MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        }
    }

    @Test
    fun staleServerTranscodeDiagnosticCannotOverrideDirectRuntimeRoute() {
        val state =
            PlaybackState(
                transcoding = false,
                diagnostics =
                    PlaybackDiagnostics(
                        playMethod = PlaybackMethod.Transcode.label,
                        engine = "libmpv",
                    ),
            )
        val readout = state.readoutLine(sourceLabel = null, containerLabel = null)

        assertEquals(PlaybackMethod.DirectPlay.label, state.playbackMethodReadoutLabel())
        assertFalse(PlaybackMethod.Transcode.label in readout)
    }

    @Test
    fun activeTranscodeRouteAlwaysReportsServerTranscode() {
        val state =
            PlaybackState(
                transcoding = true,
                diagnostics =
                    PlaybackDiagnostics(
                        playMethod = PlaybackMethod.DirectPlay.label,
                    ),
            )

        assertEquals(PlaybackMethod.Transcode.label, state.playbackMethodReadoutLabel())
    }
}
