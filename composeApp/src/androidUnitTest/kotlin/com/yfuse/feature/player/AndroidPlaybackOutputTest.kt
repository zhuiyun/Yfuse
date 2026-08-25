package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.yfuse.core.model.DecoderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@UnstableApi
class AndroidPlaybackOutputTest {
    @Test
    fun explicit_exo_decoder_modes_are_deterministic_but_retain_a_safe_fallback() {
        val decoders = listOf("hardware", "software")

        assertEquals(
            listOf("hardware"),
            preferExoDecoderMode(decoders, DecoderMode.Hardware) { it == "software" },
        )
        assertEquals(
            listOf("software"),
            preferExoDecoderMode(decoders, DecoderMode.Software) { it == "software" },
        )
        assertEquals(
            decoders,
            preferExoDecoderMode(decoders, DecoderMode.Auto) { it == "software" },
        )
        assertEquals(
            listOf("software"),
            preferExoDecoderMode(listOf("software"), DecoderMode.Hardware) { true },
        )
    }

    @Test
    fun exo_delegates_seamless_mode_but_reserves_always_for_the_surface_api() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            exoVideoChangeFrameRateStrategy(FrameRateMatchMode.SeamlessOnly),
        )
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            exoVideoChangeFrameRateStrategy(FrameRateMatchMode.Always),
        )
        assertFalse(exoNeedsAppSurfaceFrameRate(FrameRateMatchMode.SeamlessOnly))
        assertTrue(exoNeedsAppSurfaceFrameRate(FrameRateMatchMode.Always))
    }

    @Test
    fun exo_reports_pcm_fallback_separately_from_encoded_passthrough() {
        val pcm = audioTrackConfig(encoding = C.ENCODING_PCM_16BIT)
        val encoded = audioTrackConfig(encoding = C.ENCODING_E_AC3)

        assertIs<PlaybackOutputStatus.Inactive>(
            exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, pcm),
        )
        assertIs<PlaybackOutputStatus.Active>(
            exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, encoded),
        )
        assertIs<PlaybackOutputStatus.Disabled>(
            exoAudioPassthroughStatus(AudioPassthroughMode.Disabled, encoded),
        )
    }

    @Test
    fun exo_does_not_mislabel_offload_as_hdmi_passthrough() {
        assertIs<PlaybackOutputStatus.Inactive>(
            exoAudioPassthroughStatus(
                AudioPassthroughMode.Compatible,
                audioTrackConfig(encoding = C.ENCODING_E_AC3, offload = true),
            ),
        )
    }

    @Test
    fun initialized_encoded_audio_track_is_named_as_runtime_output() {
        val config = audioTrackConfig(encoding = C.ENCODING_E_AC3_JOC)
        val status = exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, config)

        assertEquals(
            "源码输出 · Dolby Atmos / E-AC-3 JOC",
            playbackOutputDiagnosticLabel(status, "源码输出 · ${exoAudioEncodingLabel(config.encoding)}"),
        )
    }

    @Test
    fun decoded_pcm_output_includes_the_actual_audio_decoder() {
        val config = audioTrackConfig(encoding = C.ENCODING_PCM_16BIT)
        val status = exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, config)

        assertEquals(
            "PCM 解码输出 · c2.android.eac3.decoder",
            exoAudioOutputDiagnosticLabel(
                status = status,
                encoding = config.encoding,
                decoderName = "c2.android.eac3.decoder",
            ),
        )
    }

    @Test
    fun passthrough_output_does_not_claim_an_audio_decoder() {
        val config = audioTrackConfig(encoding = C.ENCODING_E_AC3_JOC)
        val status = exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, config)

        assertEquals(
            "源码输出 · Dolby Atmos / E-AC-3 JOC",
            exoAudioOutputDiagnosticLabel(
                status = status,
                encoding = config.encoding,
                decoderName = "stale.decoder.name",
            ),
        )
    }

    @Test
    fun truehd_atmos_requires_a_real_encoded_audio_track() {
        val trueHd = audioTrackConfig(encoding = C.ENCODING_DOLBY_TRUEHD)
        val active = exoAudioPassthroughStatus(AudioPassthroughMode.Compatible, trueHd)
        val disabled = exoAudioPassthroughStatus(AudioPassthroughMode.Disabled, trueHd)

        assertIs<PlaybackOutputStatus.Active>(active)
        assertTrue(trueHd.encoding in DOLBY_OBJECT_ENCODINGS)
        assertEquals("Dolby TrueHD", exoAudioEncodingLabel(trueHd.encoding))
        assertIs<PlaybackOutputStatus.Disabled>(disabled)

        // mpv reports codec identifiers instead of Android encoding constants. Both common
        // spellings must keep the Atmos badge tied to the actual encoded-output status.
        assertTrue(isDolbyObjectAudioCodec("truehd"))
        assertTrue(isDolbyObjectAudioCodec("truehd-atmos"))
        assertFalse(isDolbyObjectAudioCodec("eac3"))
        assertFalse(isDolbyObjectAudioCodec("pcm_s16le"))
    }

    private fun audioTrackConfig(
        encoding: Int,
        offload: Boolean = false,
    ): AudioSink.AudioTrackConfig =
        AudioSink.AudioTrackConfig(
            encoding,
            48_000,
            0,
            false,
            offload,
            4_096,
        )
}
