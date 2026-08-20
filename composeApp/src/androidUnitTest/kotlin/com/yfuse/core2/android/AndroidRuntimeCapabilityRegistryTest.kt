package com.yfuse.core2.android

import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidRuntimeCapabilityRegistryTest {
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
}
