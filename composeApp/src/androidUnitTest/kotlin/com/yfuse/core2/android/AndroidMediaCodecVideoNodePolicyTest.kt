package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidMediaCodecVideoNodePolicyTest {
    @Test
    fun plannedDecoderNameWinsOverMimeSelection() {
        val created =
            createPlannedVideoDecoder(
                mime = "video/hevc",
                decoderName = "c2.vendor.hevc.decoder",
                createByType = { "type:$it" },
                createByName = { "name:$it" },
            )

        assertEquals("name:c2.vendor.hevc.decoder", created)
    }

    @Test
    fun missingPlannedNameFallsBackToMimeSelection() {
        val created =
            createPlannedVideoDecoder(
                mime = "video/avc",
                decoderName = " ",
                createByType = { "type:$it" },
                createByName = { "name:$it" },
            )

        assertEquals("type:video/avc", created)
    }

    @Test
    fun dolbyCandidatesPreferPlannedThenExactFormatThenAdvertisedProfile() {
        assertEquals(
            listOf(
                "c2.vendor.planned.decoder",
                "c2.vendor.exact.decoder",
                "c2.vendor.profile.decoder",
                "c2.vendor.generic.decoder",
            ),
            orderedVideoDecoderNames(
                plannedDecoderName = "c2.vendor.planned.decoder",
                preferredDecoderName = "c2.vendor.exact.decoder",
                profileMatchingDecoderNames =
                    listOf(
                        "c2.vendor.profile.decoder",
                        "c2.vendor.exact.decoder",
                    ),
                mimeDecoderNames =
                    listOf(
                        "c2.vendor.generic.decoder",
                        "c2.vendor.profile.decoder",
                    ),
            ),
        )
    }

    @Test
    fun dolbyCandidatesRemoveBlankAndDuplicateAliases() {
        assertEquals(
            listOf("c2.vendor.dolby.decoder", "OMX.vendor.dolby.decoder"),
            orderedVideoDecoderNames(
                plannedDecoderName = " ",
                preferredDecoderName = "c2.vendor.dolby.decoder",
                profileMatchingDecoderNames = listOf("c2.vendor.dolby.decoder"),
                mimeDecoderNames = listOf("", "OMX.vendor.dolby.decoder"),
            ),
        )
    }

    @Test
    fun exactFormatFailureRetainsOnlyStructuredCodecEvidence() {
        val failure =
            YVideoDecoderConfigurationException(
                mime = "video/dolby-vision",
                profile = 32,
                failures =
                    listOf(
                        YVideoDecoderAttemptFailure(
                            decoderName = "c2.vendor.dolby.decoder",
                            errorType = "CodecException",
                            diagnosticInfo = "android.media.MediaCodec.error_neg_22",
                            errorCode = -22,
                        ),
                    ),
            )

        assertEquals("video/dolby-vision", failure.mime)
        assertEquals(32, failure.profile)
        assertEquals("c2.vendor.dolby.decoder", failure.failures.single().decoderName)
        assertEquals(-22, failure.failures.single().errorCode)
    }

    @Test
    fun genericDecoderConfigureFailureRetainsMimeAndAttemptedDecoder() {
        val failure =
            IllegalArgumentException("framework detail that must not become the user message")
                .toVideoDecoderConfigurationException(
                    mime = "video/hevc",
                    profile = 2,
                    decoderName = "c2.qti.hevc.decoder",
                )

        assertEquals("video/hevc", failure.mime)
        assertEquals(2, failure.profile)
        assertEquals("c2.qti.hevc.decoder", failure.failures.single().decoderName)
        assertEquals("IllegalArgumentException", failure.failures.single().errorType)
    }

    @Test
    fun dolbyVisionRetriesOptionalMetadataOnAndroid10AndNewer() {
        assertEquals(
            listOf(
                YDolbyVisionConfigureVariant.Exact,
                YDolbyVisionConfigureVariant.WithoutCsd2,
                YDolbyVisionConfigureVariant.WithoutCsd2AndProfile,
            ),
            dolbyVisionConfigureVariants(
                androidApi = 36,
                hasCsd2 = true,
                hasProfile = true,
            ),
        )
    }

    @Test
    fun dolbyVisionKeepsExactFormatWhenMetadataCannotBeRemoved() {
        assertEquals(
            listOf(YDolbyVisionConfigureVariant.Exact),
            dolbyVisionConfigureVariants(
                androidApi = 28,
                hasCsd2 = true,
                hasProfile = true,
            ),
        )
    }

    @Test
    fun dolbyVisionSkipsUnavailableRetryVariants() {
        assertEquals(
            listOf(YDolbyVisionConfigureVariant.Exact),
            dolbyVisionConfigureVariants(
                androidApi = 36,
                hasCsd2 = false,
                hasProfile = false,
            ),
        )
    }

    @Test
    fun frameAheadOfMasterIsHeld() {
        val decision =
            videoFrameReleaseDecision(
                presentationTimeUs = 1_300_001L,
                masterPositionUs = 1_000_000L,
                desiredReleaseTimeNs = 2_300_000_000L,
                nowNs = 2_000_000_000L,
                maximumScheduleAheadUs = 250_000L,
                lateDropThresholdNs = 100_000_000L,
                lateImmediateAllowanceNs = 50_000_000L,
            )

        assertEquals(YVideoFrameReleaseDecision.Hold, decision)
    }

    @Test
    fun frameTooLateForMasterIsDropped() {
        val decision =
            videoFrameReleaseDecision(
                presentationTimeUs = 900_000L,
                masterPositionUs = 1_000_000L,
                desiredReleaseTimeNs = 1_899_999_999L,
                nowNs = 2_000_000_000L,
                maximumScheduleAheadUs = 250_000L,
                lateDropThresholdNs = 100_000_000L,
                lateImmediateAllowanceNs = 50_000_000L,
            )

        assertEquals(YVideoFrameReleaseDecision.Drop, decision)
    }

    @Test
    fun slightlyLateFrameRendersImmediatelyWithinAllowance() {
        val decision =
            videoFrameReleaseDecision(
                presentationTimeUs = 990_000L,
                masterPositionUs = 1_000_000L,
                desiredReleaseTimeNs = 1_975_000_000L,
                nowNs = 2_000_000_000L,
                maximumScheduleAheadUs = 250_000L,
                lateDropThresholdNs = 100_000_000L,
                lateImmediateAllowanceNs = 10_000_000L,
            )

        assertEquals(1_990_000_000L, assertIs<YVideoFrameReleaseDecision.Render>(decision).releaseTimeNs)
    }

    @Test
    fun lateFirstFrameRendersImmediatelyInsteadOfLeavingTheSurfaceBlank() {
        assertEquals(
            YVideoFrameReleaseDecision.Render(2_000_000_000L),
            preserveFirstVideoFrame(
                decision = YVideoFrameReleaseDecision.Drop,
                firstFrameRendered = false,
                nowNs = 2_000_000_000L,
            ),
        )
    }

    @Test
    fun lateFramesCanDropAfterTheFirstFrameWasRendered() {
        assertEquals(
            YVideoFrameReleaseDecision.Drop,
            preserveFirstVideoFrame(
                decision = YVideoFrameReleaseDecision.Drop,
                firstFrameRendered = true,
                nowNs = 2_000_000_000L,
            ),
        )
    }

    @Test
    fun adaptiveDecoderIsSurfaceCapableEvenWhenLegacyOmxOmitsSurfaceColorFormat() {
        assertEquals(
            true,
            supportsSurfaceOutput(
                colorFormats = intArrayOf(19, 21),
                adaptivePlayback = true,
                tunneledPlayback = false,
            ),
        )
    }

    @Test
    fun emptyTailSeekMovesBackByOneSecond() {
        assertEquals(
            6_369_000L,
            emptyTailSeekRetryTarget(
                currentTargetUs = 7_369_000L,
                retryCount = 0,
            ),
        )
    }

    @Test
    fun emptyTailSeekClampsTheRetryToTheStart() {
        assertEquals(
            0L,
            emptyTailSeekRetryTarget(
                currentTargetUs = 500_000L,
                retryCount = 1,
            ),
        )
    }

    @Test
    fun emptyTailSeekStopsAtTheRetryLimitOrAtTheStart() {
        assertNull(emptyTailSeekRetryTarget(currentTargetUs = 7_369_000L, retryCount = 3))
        assertNull(emptyTailSeekRetryTarget(currentTargetUs = 0L, retryCount = 0))
    }
}
