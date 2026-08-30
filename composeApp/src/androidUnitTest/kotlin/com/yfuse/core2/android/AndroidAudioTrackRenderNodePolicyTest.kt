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
}
