package com.yfuse.core2.hdr

import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType

enum class YColorPrimaries {
    Bt709,
    Bt2020,
    DisplayP3,
    Unknown,
}

enum class YColorTransfer {
    Sdr,
    Pq,
    Hlg,
    Unknown,
}

data class YHdr10PlusMetadata(
    val ituT35Payload: ByteArray,
) {
    init {
        require(ituT35Payload.isNotEmpty())
        require(ituT35Payload.size <= MAX_HDR10_PLUS_BYTES)
    }

    override fun equals(other: Any?): Boolean =
        other is YHdr10PlusMetadata && ituT35Payload.contentEquals(other.ituT35Payload)

    override fun hashCode(): Int = ituT35Payload.contentHashCode()
}

data class YHdrPlaybackDescriptor(
    val type: YHdrType,
    val primaries: YColorPrimaries,
    val transfer: YColorTransfer,
    val staticMetadata: YHdrStaticMetadata? = null,
    val hdr10PlusMetadata: YHdr10PlusMetadata? = null,
) {
    init {
        if (type == YHdrType.Hdr10Plus) require(hdr10PlusMetadata != null)
        if (type == YHdrType.Hlg) require(transfer == YColorTransfer.Hlg)
        if (type in setOf(YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.DolbyVision)) {
            require(transfer == YColorTransfer.Pq)
        }
    }
}

sealed interface YHdrRouteDecision {
    data class Native(
        val outputType: YHdrType,
    ) : YHdrRouteDecision

    data class GpuToneMap(
        val sourceType: YHdrType,
        val outputType: YHdrType = YHdrType.Sdr,
    ) : YHdrRouteDecision
}

object YHdrRouter {
    fun decide(
        descriptor: YHdrPlaybackDescriptor,
        capabilities: YDeviceCapabilities,
    ): YHdrRouteDecision =
        if (capabilities.supportsDisplayHdr(descriptor.type)) {
            YHdrRouteDecision.Native(descriptor.type)
        } else {
            YHdrRouteDecision.GpuToneMap(descriptor.type)
        }
}

private const val MAX_HDR10_PLUS_BYTES = 64 * 1024
