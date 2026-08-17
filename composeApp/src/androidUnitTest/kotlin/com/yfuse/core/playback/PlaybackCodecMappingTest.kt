package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCodecMappingTest {
    @Test
    fun prores_names_from_android_and_ffmpeg_map_to_one_codec() {
        assertEquals(PlaybackVideoCodec.ProRes, "video/prores".toPlaybackVideoCodec())
        assertEquals(PlaybackVideoCodec.ProRes, "video/x-prores".toPlaybackVideoCodec())
        assertEquals(PlaybackVideoCodec.ProRes, "video/apple-prores".toPlaybackVideoCodec())
        assertEquals(PlaybackVideoCodec.ProRes, "prores".toPlaybackVideoCodec())
        assertEquals(PlaybackVideoCodec.ProRes, "prores_ks".toPlaybackVideoCodec())
        assertEquals(PlaybackVideoCodec.ProRes, "prores_aw".toPlaybackVideoCodec())
    }

    @Test
    fun pcm_s24le_is_normalized_to_pcm_for_native_audio_output() {
        assertEquals(PlaybackAudioCodec.Pcm, "pcm_s24le".toPlaybackAudioCodec())
        assertEquals(PlaybackAudioCodec.Pcm, "audio/raw".toPlaybackAudioCodec())
    }
}
