package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeDirectPacingPolicyTest {
    @Test
    fun zeroByteWriteKeepsAudioBufferForRetry() {
        assertEquals(
            YDecodedAudioDrainProgress.Backpressured,
            decodedAudioDrainProgress(writtenBytes = 0, remainingBytes = 4096),
        )
    }

    @Test
    fun partialWriteKeepsCodecOutputOwnedByNativeDirect() {
        assertEquals(
            YDecodedAudioDrainProgress.Pending,
            decodedAudioDrainProgress(writtenBytes = 2048, remainingBytes = 2048),
        )
    }

    @Test
    fun completeWriteReleasesCodecOutput() {
        assertEquals(
            YDecodedAudioDrainProgress.Complete,
            decodedAudioDrainProgress(writtenBytes = 2048, remainingBytes = 0),
        )
    }

    @Test
    fun invalidWriteCountersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            decodedAudioDrainProgress(writtenBytes = -1, remainingBytes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            decodedAudioDrainProgress(writtenBytes = 0, remainingBytes = -1)
        }
    }
}
