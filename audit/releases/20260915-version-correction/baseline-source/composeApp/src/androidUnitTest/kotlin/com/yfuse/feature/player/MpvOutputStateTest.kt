package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvOutputStateTest {
    @Test
    fun scale_modes_use_supported_mpv_properties() {
        assertEquals(
            MpvScaleModeProperties(panscan = 0.0, keepAspect = true),
            mpvScaleModeProperties(VideoScaleMode.Fit),
        )
        assertEquals(
            MpvScaleModeProperties(panscan = 1.0, keepAspect = true),
            mpvScaleModeProperties(VideoScaleMode.Fill),
        )
        assertEquals(
            MpvScaleModeProperties(panscan = 0.0, keepAspect = false),
            mpvScaleModeProperties(VideoScaleMode.Stretch),
        )
    }

    @Test
    fun audio_is_rendering_only_after_android_output_is_established() {
        assertEquals(
            PlaybackOutputReadiness.Waiting,
            mpvAudioOutputReadiness(outputDriver = null, outputFormat = null),
        )
        assertEquals(
            PlaybackOutputReadiness.Waiting,
            mpvAudioOutputReadiness(outputDriver = "audiotrack", outputFormat = null),
        )
        assertEquals(
            PlaybackOutputReadiness.Waiting,
            mpvAudioOutputReadiness(outputDriver = "null", outputFormat = "s16"),
        )
        assertEquals(
            PlaybackOutputReadiness.Rendering,
            mpvAudioOutputReadiness(outputDriver = "audiotrack", outputFormat = "s16"),
        )
    }

    @Test
    fun active_decoder_reports_hardware_or_ffmpeg_from_mpv_evidence() {
        assertEquals("硬件解码 · mediacodec", mpvDecoderDiagnostic("mediacodec"))
        assertEquals("FFmpeg 软件解码", mpvDecoderDiagnostic("no"))
        assertEquals("FFmpeg 软件解码", mpvDecoderDiagnostic(null))
    }

    @Test
    fun mpv_pixel_formats_report_output_bit_depth_without_claiming_unknown_formats() {
        assertEquals(10, "p010".mpvPixelFormatBitDepth())
        assertEquals(12, "yuv420p12le".mpvPixelFormatBitDepth())
        assertEquals(8, "nv12".mpvPixelFormatBitDepth())
        assertEquals(0, "".mpvPixelFormatBitDepth())
    }
}
