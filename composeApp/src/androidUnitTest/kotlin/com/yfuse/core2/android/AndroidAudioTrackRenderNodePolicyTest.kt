package com.yfuse.core2.android

import android.media.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAudioTrackRenderNodePolicyTest {
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
}
