package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCodecMappingTest {
    @Test
    fun prores_aliases_map_to_the_ffmpeg_software_first_codec() {
        listOf(
            "video/prores",
            "video/x-prores",
            "video/apple-prores",
            "prores",
            "prores_ks",
            "prores_aw",
        ).forEach { alias ->
            assertEquals(PlaybackVideoCodec.ProRes, alias.toPlaybackVideoCodec(), alias)
        }
    }

    @Test
    fun pcm_s24le_is_normalized_to_pcm() {
        assertEquals(PlaybackAudioCodec.Pcm, "pcm_s24le".toPlaybackAudioCodec())
        assertEquals(PlaybackAudioCodec.Pcm, "audio/raw".toPlaybackAudioCodec())
    }
}
