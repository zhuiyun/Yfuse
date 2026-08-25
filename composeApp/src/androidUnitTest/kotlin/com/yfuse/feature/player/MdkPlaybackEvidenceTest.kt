package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkPlaybackEvidenceTest {
    @Test
    fun native_snapshot_maps_codec_color_decoder_and_first_frame() {
        val evidence =
            decodeMdkPlaybackEvidence(
                arrayOf(
                    "1",
                    "AMediaCodec",
                    "FFmpeg",
                    "hevc",
                    "yuv420p10le",
                    "3840",
                    "2160",
                    "42000000",
                    "23.976",
                    "BT.2100 PQ",
                    "8",
                    "2",
                    "eac3",
                    "8",
                    "48000",
                    "1536000",
                    "45000000",
                    "27",
                    "9472",
                ),
            )

        assertTrue(evidence.firstVideoFrameRendered)
        assertEquals("AMediaCodec", evidence.videoDecoder)
        assertEquals(8, evidence.dolbyVisionProfile)
        assertEquals(3840, evidence.videoWidth)
        assertEquals(23.976f, evidence.frameRate)
        assertEquals(27L, evidence.eventRevision)
        assertEquals("0.37.0", evidence.runtimeVersion)
    }

    @Test
    fun rendered_dolby_source_proves_a_frame_but_not_dolby_display_output() {
        val diagnostics =
            PlaybackDiagnostics(engine = "MDK")
                .withMdkPlaybackEvidence(
                    MdkPlaybackEvidence(
                        firstVideoFrameRendered = true,
                        videoDecoder = "AMediaCodec",
                        videoCodec = "hevc",
                        pixelFormat = "yuv420p10le",
                        videoWidth = 3840,
                        colorSpace = "BT.2100 PQ",
                        dolbyVisionProfile = 8,
                    ),
                )

        assertEquals(PlaybackOutputReadiness.Rendering, diagnostics.videoReadiness)
        assertEquals("Dolby Vision Profile 8", diagnostics.dynamicRange)
        assertTrue("首帧已输出" in diagnostics.videoOutput)
        assertTrue("HDR 显示链路未验证" in diagnostics.videoOutput)
        assertFalse(diagnostics.dolbyVisionOutput)
    }

    @Test
    fun malformed_native_snapshot_stays_conservative() {
        val evidence = decodeMdkPlaybackEvidence(arrayOf("1", "AMediaCodec"))
        val diagnostics = PlaybackDiagnostics(engine = "MDK").withMdkPlaybackEvidence(evidence)

        assertFalse(evidence.firstVideoFrameRendered)
        assertEquals(PlaybackOutputReadiness.Waiting, diagnostics.videoReadiness)
        assertEquals(PlaybackOutputReadiness.Unknown, diagnostics.audioReadiness)
    }

    @Test
    fun nv12_is_eight_bit_despite_the_format_number() {
        val fields = Array(19) { "0" }
        fields[0] = "1"
        fields[4] = "nv12"
        fields[9] = "BT.709"

        val diagnostics =
            PlaybackDiagnostics(engine = "MDK")
                .withMdkPlaybackEvidence(decodeMdkPlaybackEvidence(fields))

        assertEquals(8, diagnostics.outputEvidence.bitDepth)
    }
}
