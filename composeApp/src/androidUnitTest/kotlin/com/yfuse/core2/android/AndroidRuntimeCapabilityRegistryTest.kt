package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidRuntimeCapabilityRegistryTest {
    private val systemImage = "37:12345"
    private val dayMs = 24L * 60L * 60L * 1_000L
    private val nowMs = 400L * dayMs

    @Test
    fun ourParserRejectionNeverBecomesADecoderStrike() {
        val parserFailure = IllegalArgumentException("HEVC configuration contains no SPS")
        // 1.0.83 reported the parser inside the decoder-configure stage; the stage label is no evidence.
        val asDecoderStage =
            YPlaybackException(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.VideoDecoderConfigure,
                cause = parserFailure,
            )
        val asBitstream =
            YPlaybackException(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Bitstream,
                cause = parserFailure,
                deterministic = true,
            )
        val recorded = mutableListOf<YRuntimeVideoCapabilityKey>()

        listOf(parserFailure, asDecoderStage, asBitstream, IllegalStateException("Vulkan output unavailable"))
            .forEach { failure -> assertFalse(recordRuntimeConfigureFailure(key, failure) { recorded += it }) }

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun onlyAMediaCodecRefusalIsADecoderStrike() {
        val refused =
            YVideoDecoderConfigurationException(
                mime = "video/dolby-vision",
                profile = 32,
                failures = listOf(YVideoDecoderAttemptFailure("c2.dolby.decoder.hevc", "CodecException")),
            )
        val recorded = mutableListOf<YRuntimeVideoCapabilityKey>()

        assertTrue(
            recordRuntimeConfigureFailure(
                key,
                YPlaybackException(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.VideoDecoderConfigure,
                    cause = refused,
                ),
            ) { recorded += it },
        )
        assertEquals(listOf(key), recorded)
    }

    @Test
    fun contentionAndEmptyCandidateListsProveNothing() {
        val transient =
            YVideoDecoderConfigurationException(
                mime = "video/hevc",
                profile = null,
                failures =
                    listOf(YVideoDecoderAttemptFailure("c2.qti.hevc.decoder", "CodecException", transient = true)),
            )
        val recoverable =
            YVideoDecoderConfigurationException(
                mime = "video/hevc",
                profile = null,
                failures =
                    listOf(YVideoDecoderAttemptFailure("c2.qti.hevc.decoder", "CodecException", recoverable = true)),
            )
        val noCandidate = YVideoDecoderConfigurationException("video/dolby-vision", 32, emptyList())

        assertFalse(transient.isRuntimeDecoderRejection())
        assertFalse(recoverable.isRuntimeDecoderRejection())
        assertFalse(noCandidate.isRuntimeDecoderRejection())
    }

    @Test
    fun recordsWrittenBeforeTheVersionBumpAreDroppedOnRead() {
        val current =
            YRuntimeCapabilityRecord(key, YRuntimeCapabilityEvidence.Rendered, consecutiveFailures = 0, nowMs)
        val encoded = encodeRuntimeCapabilityRecord(current, systemImage)
        // A 1.0.83 record: same layout, version "1". Its strikes may have been our own parser's.
        val poisoned =
            (listOf("1") + encoded.split("\t").drop(1))
                .toMutableList()
                .apply {
                    this[10] = YRuntimeCapabilityEvidence.Rejected.name
                    this[11] = "2"
                }.joinToString("\t")

        assertEquals(current, decodeRuntimeCapabilityRecord(encoded, systemImage))
        assertNull(decodeRuntimeCapabilityRecord(poisoned, systemImage))
        assertEquals(listOf(current), liveRuntimeCapabilityRecords(setOf(encoded, poisoned), systemImage, nowMs))
        assertTrue(liveRuntimeCapabilityRecords(setOf(encoded), "36:999", nowMs).isEmpty())
    }

    @Test
    fun rejectionsExpireLongBeforePositiveEvidence() {
        fun record(
            evidence: YRuntimeCapabilityEvidence,
            ageDays: Long,
        ) = YRuntimeCapabilityRecord(key, evidence, consecutiveFailures = 2, nowMs - ageDays * dayMs)

        assertTrue(runtimeCapabilityRecordLive(record(YRuntimeCapabilityEvidence.Rejected, 2), nowMs))
        assertFalse(runtimeCapabilityRecordLive(record(YRuntimeCapabilityEvidence.Rejected, 4), nowMs))
        assertTrue(runtimeCapabilityRecordLive(record(YRuntimeCapabilityEvidence.Rendered, 4), nowMs))
        assertTrue(runtimeCapabilityRecordLive(record(YRuntimeCapabilityEvidence.Configured, 29), nowMs))
        assertFalse(runtimeCapabilityRecordLive(record(YRuntimeCapabilityEvidence.Rendered, 31), nowMs))
        assertTrue(
            runtimeCapabilityEvidenceTtlMs(YRuntimeCapabilityEvidence.Rejected) <
                runtimeCapabilityEvidenceTtlMs(YRuntimeCapabilityEvidence.Rendered),
        )
    }

    private val key =
        YRuntimeVideoCapabilityKey(
            decoderName = "c2.vendor.hevc.decoder",
            codec = YVideoCodec.H265,
            width = 3840,
            height = 2160,
            bitDepth = 10,
            hdrType = YHdrType.Hdr10,
            dolbyVisionProfile = null,
            tunneled = false,
        )

    @Test
    fun consecutiveRejectsAccumulateUntilSuccess() {
        val first = updateRuntimeCapabilityRecord(null, key, YRuntimeCapabilityEvidence.Rejected, 1L)
        val second = updateRuntimeCapabilityRecord(first, key, YRuntimeCapabilityEvidence.Rejected, 2L)
        val recovered = updateRuntimeCapabilityRecord(second, key, YRuntimeCapabilityEvidence.Configured, 3L)

        assertEquals(1, first.consecutiveFailures)
        assertEquals(2, second.consecutiveFailures)
        assertEquals(YRuntimeCapabilityEvidence.Configured, recovered.evidence)
        assertEquals(0, recovered.consecutiveFailures)
    }

    @Test
    fun configuredEvidenceCannotDowngradeRenderedEvidence() {
        val rendered = updateRuntimeCapabilityRecord(null, key, YRuntimeCapabilityEvidence.Rendered, 1L)
        val configured = updateRuntimeCapabilityRecord(rendered, key, YRuntimeCapabilityEvidence.Configured, 2L)

        assertEquals(YRuntimeCapabilityEvidence.Rendered, configured.evidence)
        assertEquals(2L, configured.updatedAtEpochMs)
    }

    @Test
    fun capabilityKeyRecordsDecoderInputInsteadOfToneMappedOutput() {
        val request =
            YPlaybackRequest(
                container = YContainer.Matroska,
                video =
                    YVideoRequirement(
                        codec = YVideoCodec.H265,
                        hdrType = YHdrType.DolbyVision,
                        dolbyVisionProfile = 7,
                    ),
                platformDemuxSupported = false,
                fallbackHdrType = YHdrType.Hdr10,
            )
        val plan =
            YPlaybackPlan(
                route = YPlaybackRoute.GpuEnhanced,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.Gpu,
                inputHdrType = YHdrType.Hdr10,
                outputHdrType = YHdrType.Sdr,
                decoderName = "c2.vendor.hevc.decoder",
                usesHdrFallback = true,
                reason = "test",
            )

        val key = requireNotNull(runtimeVideoCapabilityKey(request, plan))

        assertEquals(YHdrType.Hdr10, key.hdrType)
        assertEquals(null, key.dolbyVisionProfile)
    }
}
