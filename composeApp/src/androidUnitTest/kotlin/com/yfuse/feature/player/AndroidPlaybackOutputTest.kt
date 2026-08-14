package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@UnstableApi
class AndroidPlaybackOutputTest {
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
