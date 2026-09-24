package com.yfuse.core2.android

import android.media.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAudioTrackRenderNodePolicyTest {
    @Test
    fun `an empty declared channel mask falls back to the layout for the channel count`() {
        // Codec2 AAC declares channel-mask 0 until it decodes; the replayed format carried that 0.
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, audioTrackChannelMask(declaredMask = 0, channelCount = 2))
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, audioTrackChannelMask(declaredMask = null, channelCount = 6))
    }

    @Test
    fun `a declared mask that describes another channel count is ignored`() {
        assertEquals(
            AudioFormat.CHANNEL_OUT_5POINT1,
            audioTrackChannelMask(declaredMask = AudioFormat.CHANNEL_OUT_STEREO, channelCount = 6),
        )
        // CHANNEL_OUT_DEFAULT has one bit but names no output position.
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, audioTrackChannelMask(declaredMask = 1, channelCount = 1))
    }

    @Test
    fun `a declared mask matching the channel count is kept`() {
        // 3.1 is not the quad layout channelMaskForCount(4) would pick, so this proves it was kept.
        val threePointOne =
            AudioFormat.CHANNEL_OUT_FRONT_LEFT or
                AudioFormat.CHANNEL_OUT_FRONT_RIGHT or
                AudioFormat.CHANNEL_OUT_FRONT_CENTER or
                AudioFormat.CHANNEL_OUT_LOW_FREQUENCY

        assertEquals(threePointOne, audioTrackChannelMask(declaredMask = threePointOne, channelCount = 4))
    }

    @Test
    fun `truly unsupported layouts still fail closed`() {
        assertEquals(AudioFormat.CHANNEL_INVALID, audioTrackChannelMask(declaredMask = 0, channelCount = 9))
    }

    @Test
    fun `pause and resume around a rebuffer are not an audio route change`() {
        val filter = AudioRouteChangeFilter()

        assertFalse(filter.routed("2:7"), "the first route is the initial one")
        assertFalse(filter.routed(null), "a paused track reports no routed device")
        assertFalse(filter.routed("2:7"), "resuming on the same device")
        assertTrue(filter.routed("8:12"), "a different output device")
        assertFalse(filter.routed("8:12"))
    }

    @Test
    fun `stereo pcm keeps two seconds of decoded audio`() {
        assertEquals(
            384_000,
            nativeDirectAudioBufferSizeBytes(
                minimumBufferBytes = 16_384,
                sampleRate = 48_000,
                channelCount = 2,
                encoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
    }

    @Test
    fun `minimum platform buffer remains authoritative`() {
        assertEquals(
            2_400_000,
            nativeDirectAudioBufferSizeBytes(
                minimumBufferBytes = 600_000,
                sampleRate = 48_000,
                channelCount = 2,
                encoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
    }

    @Test
    fun `large multichannel target is bounded`() {
        assertEquals(
            2 * 1024 * 1024,
            nativeDirectAudioBufferSizeBytes(
                minimumBufferBytes = 16_384,
                sampleRate = 192_000,
                channelCount = 8,
                encoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }

    @Test
    fun `known layouts map to their platform mask`() {
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, channelMaskForCount(1))
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, channelMaskForCount(2))
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, channelMaskForCount(6))
        assertEquals(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, channelMaskForCount(8))
    }

    @Test
    fun `unmapped channel counts fail closed instead of narrowing to stereo`() {
        // Reinterpreting interleaved multichannel PCM through a stereo mask is audible corruption,
        // so the mask has to be rejected and handed back to the route policy.
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(9))
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(11))
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(16))
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(0))
    }

    @Test
    fun `height layouts are refused below api 32`() {
        // Build.VERSION.SDK_INT is 0 under the JVM unit-test runtime, which is the pre-API-32 path.
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(10))
        assertEquals(AudioFormat.CHANNEL_INVALID, channelMaskForCount(12))
    }

    @Test
    fun `startup threshold does not require filling the two second buffer`() {
        assertEquals(1_920, nativeDirectAudioStartThresholdFrames(48_000, 96_000))
        assertEquals(1_764, nativeDirectAudioStartThresholdFrames(44_100, 88_200))
    }

    @Test
    fun `startup threshold is bounded by the actual device allocation`() {
        assertEquals(240, nativeDirectAudioStartThresholdFrames(48_000, 240))
        assertEquals(1, nativeDirectAudioStartThresholdFrames(1, 1))
        assertEquals(85_899_345, nativeDirectAudioStartThresholdFrames(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
