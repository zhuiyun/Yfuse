package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnhancedAudioRecoveryPolicyTest {
    @Test
    fun aFailedPlatformPcmDecoderHasOneOwnedSoftwareRecovery() {
        val failure = failure(YPlaybackFailureCategory.Decoder, YPlaybackFailureStage.AudioDecoderQueue)
        assertTrue(
            canRecoverEnhancedAudioDecoder(failure, softwareActive = false, passthrough = false, hasTrack = true),
        )
        assertFalse(
            canRecoverEnhancedAudioDecoder(failure, softwareActive = true, passthrough = false, hasTrack = true),
        )
    }

    @Test
    fun audioRecoveryNeverMasksVideoOrSourceAuthorizationFailures() {
        for ((category, stage) in listOf(
            YPlaybackFailureCategory.Decoder to YPlaybackFailureStage.VideoDecoderQueue,
            YPlaybackFailureCategory.Authorization to YPlaybackFailureStage.AudioDecoderQueue,
            YPlaybackFailureCategory.Drm to YPlaybackFailureStage.AudioDecoderConfigure,
        )) {
            assertFalse(
                canRecoverEnhancedAudioDecoder(
                    failure(category, stage),
                    softwareActive = false,
                    passthrough = false,
                    hasTrack = true,
                ),
            )
        }
    }

    @Test
    fun passthroughAndAbsentTracksCannotEnterPcmDecoderRecovery() {
        val failure = failure(YPlaybackFailureCategory.Decoder, YPlaybackFailureStage.AudioDecoderConfigure)
        assertFalse(
            canRecoverEnhancedAudioDecoder(failure, softwareActive = false, passthrough = true, hasTrack = true),
        )
        assertFalse(
            canRecoverEnhancedAudioDecoder(failure, softwareActive = false, passthrough = false, hasTrack = false),
        )
    }

    private fun failure(
        category: YPlaybackFailureCategory,
        stage: YPlaybackFailureStage,
    ) = YPlaybackException(category = category, stage = stage, safeDetail = "Test decoder failure")
}
