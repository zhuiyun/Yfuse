package com.yfuse.core2.android

import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.api.YFrameRateSample
import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.subtitle.YSubtitleCue

internal data class YEnhancedPlaybackSnapshot(
    val positionUs: Long,
    val durationUs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val firstVideoFrameRendered: Boolean,
    val outputHdrType: YHdrType,
    val softwareVideoToneMapped: Boolean,
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val audioRendering: Boolean,
    val audioPassthrough: Boolean,
    val immersiveAudioCarrierOutput: Boolean,
    val dolbyAtmosSourceDetected: Boolean,
    val dolbyAtmosOutputMode: YDolbyAtmosOutputMode,
    val audioOutputRoute: String,
    val audioOutputRouteVerified: Boolean,
    val audioOutputFingerprint: String,
    val dolbyAtmosOutput: Boolean,
    val spatialAudioOutput: Boolean,
    val headTrackingAvailable: Boolean,
    val audioFallbackCount: Int,
    val audioUnderrunCount: Int,
    val audioSinkDiagnostics: Map<String, String>,
    val droppedFrames: Int,
    val avSyncOffsetUs: Long?,
    val sourceQueueBytes: Long,
    val sourceBufferedUs: Long,
    val sourceStarvationCount: Long,
    val sourceNetworkBitsPerSecond: Long,
    val subtitleCues: List<YSubtitleCue>,
    val secondarySubtitleCues: List<YSubtitleCue> = emptyList(),
    val subtitleDiagnostics: Map<String, String> = emptyMap(),
    val nativeGpuFeatureMask: Long = 0L,
    val gpuFrameDurationNs: Long = 0L,
    val anime4KDescription: String? = null,
    val dolbyVisionRpuApplied: Boolean = false,
    val dolbyVisionEnhancementLayerDelivered: Boolean = false,
    val dolbyVisionFelComposed: Boolean = false,
    val outputEvidenceGeneration: Long = 0L,
    val outputEvidenceResetReason: YOutputEvidenceResetReason = YOutputEvidenceResetReason.Initial,
    val pausedPreviewSubmittedUnconfirmed: Boolean = false,
    val renderedFrameRate: YFrameRateSample? = null,
)
