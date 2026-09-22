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
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1,
                                videoReadiness = PlaybackOutputReadiness.Rendering,
                                dynamicRangeOutputMode =
                                    PlaybackDynamicRangeOutputMode.DolbyVisionMediaCodec,
                            ),
                    ),
            )

        assertEquals("Dolby Vision MediaCodec 原生输出", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun mpvToneMappingIsNotPresentedAsNativeDolby() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        dynamicRange = "Dolby Vision Profile 5",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1,
                                videoReadiness = PlaybackOutputReadiness.Rendering,
                                dynamicRangeOutputMode =
                                    PlaybackDynamicRangeOutputMode.HdrToSdrToneMapped,
                            ),
                    ),
            )

        assertEquals("HDR→SDR 色调映射", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun hdr10BaseLayerGetsExplicitCompatibilityGrade() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        dynamicRange = "HDR10",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1,
                                videoReadiness = PlaybackOutputReadiness.Rendering,
                                dynamicRangeOutputMode =
                                    PlaybackDynamicRangeOutputMode.Hdr10BaseLayer,
                            ),
                    ),
            )

        assertEquals("HDR10 基础层输出", state.dolbyVisionReadoutLabel())
    }

    @Test
    fun felRequiresExplicitCompositionEvidence() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        dynamicRange = "Dolby Vision Profile 7",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1,
                                videoReadiness = PlaybackOutputReadiness.Rendering,
                                dolbyVisionRpuRendered = true,
                                dolbyVisionFelComposed = true,
                            ),
                    ),
            )

        assertEquals("P7 FEL 已合成", state.dolbyVisionReadoutLabel())
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

            assertEquals("P7 FEL 已合成", state.dolbyVisionReadoutLabel())
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

            assertEquals(null, state.dolbyVisionReadoutLabel())
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
