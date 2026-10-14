package com.yfuse.feature.player

import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExoOutputProbeTest {
    @Test
    fun sink_flush_and_release_invalidate_the_previous_clock_sample() {
        val calls = mutableListOf<String>()
        val sink =
            Proxy.newProxyInstance(
                AudioSink::class.java.classLoader,
                arrayOf(AudioSink::class.java),
            ) { _, method, _ ->
                calls += method.name
                if (method.name == "getCurrentPositionUs") 123_000L else null
            } as AudioSink
        val probe = ExoOutputProbe()
        val observed = probe.audioSink(sink)
        assertEquals(123_000L, observed.getCurrentPositionUs(false))
        assertEquals(123_000L, probe.audioPositionUs)
        observed.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, probe.audioPositionUs)
        assertEquals(1L, probe.audioGeneration)
        observed.getCurrentPositionUs(false)
        observed.release()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, probe.audioPositionUs)
        assertEquals(2L, probe.audioGeneration)
        assertEquals(listOf("getCurrentPositionUs", "flush", "getCurrentPositionUs", "release"), calls)
    }

    @Test
    fun dolby_input_and_display_cannot_promote_a_base_layer_decoder() {
        val base = ExoVideoCodecEvidence("c2.vendor.hevc.decoder", "video/hevc", false)
        assertFalse(base.confirmsDolbyVision("video/dolby-vision", true, true))
        val native = base.copy(mimeType = "video/dolby-vision")
        assertFalse(native.confirmsDolbyVision("video/dolby-vision", false, true))
        assertFalse(native.confirmsDolbyVision("video/dolby-vision", true, false))
        assertFalse(native.confirmsDolbyVision("video/hevc", true, true))
        assertTrue(native.confirmsDolbyVision("video/dolby-vision", true, true))
    }
}
