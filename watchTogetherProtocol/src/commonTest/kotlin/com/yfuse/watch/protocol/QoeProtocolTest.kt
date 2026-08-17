package com.yfuse.watch.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QoeProtocolTest {
    @Test
    fun accepts_only_fixed_buckets_and_no_more_successes_than_attempts() {
        val report = report()

        assertTrue(QoeProtocol.isValid(report))
        assertFalse(QoeProtocol.isValid(report.copy(startupUpperBoundMs = 1_234)))
        assertFalse(QoeProtocol.isValid(report.copy(videoCodec = QoeCodecFamily.Aac)))
        assertFalse(QoeProtocol.isValid(report.copy(audioCodec = QoeCodecFamily.Av1)))
        assertTrue(
            QoeProtocol.isValid(
                report.copy(
                    startupUpperBoundMs = Int.MAX_VALUE,
                    observedUpperBoundSeconds = Int.MAX_VALUE,
                    rebufferEventsUpperBound = Int.MAX_VALUE,
                    droppedFramesPerMinuteUpperBound = Int.MAX_VALUE,
                    avSyncAbsoluteUpperBoundMs = Int.MAX_VALUE,
                    networkRecoveryAttemptsUpperBound = Int.MAX_VALUE,
                    networkRecoverySuccessesUpperBound = Int.MAX_VALUE,
                ),
            ),
        )
        assertFalse(
            QoeProtocol.isValid(
                report.copy(
                    networkRecoveryAttemptsUpperBound = 1,
                    networkRecoverySuccessesUpperBound = 2,
                ),
            ),
        )
    }

    private fun report() =
        AnonymousPlaybackQoeReport(
            appVersion = "1.2.3",
            engine = QoeEngine.Exo,
            platformApi = QoePlatformApiBucket.Api35Plus,
            deviceFamily = QoeDeviceFamily.GoogleTensor,
            videoCodec = QoeCodecFamily.Hevc,
            audioCodec = QoeCodecFamily.Other,
            container = QoeContainerFamily.Mkv,
            resolution = QoeResolutionBucket.UltraHd,
            dynamicRange = QoeDynamicRange.Hdr10,
            playbackMethod = QoePlaybackMethod.Direct,
            startupUpperBoundMs = 2_500,
            observedUpperBoundSeconds = 60,
            rebufferEventsUpperBound = 1,
            droppedFramesPerMinuteUpperBound = 5,
            avSyncAbsoluteUpperBoundMs = 40,
            networkRecoveryAttemptsUpperBound = 1,
            networkRecoverySuccessesUpperBound = 1,
        )
}
