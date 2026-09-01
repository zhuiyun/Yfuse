package com.yfuse.core2.api

/** Events that make previously observed Dolby output evidence stale. */
enum class YOutputEvidenceResetReason {
    Initial,
    SourceChanged,
    Seek,
    SurfaceChanged,
    AdaptiveVariantChanged,
    DrmKeysChanged,
    AudioTrackChanged,
    AudioRouteChanged,
    DecoderReconfigured,
}

/**
 * Fail-closed, single-session evidence reducer.
 *
 * Every disruptive event starts a new generation and clears both sides. A dual-Dolby claim can
 * return only after a native DV frame and an advancing Atmos output have both been observed in the
 * current generation.
 */
data class YDualDolbyEvidenceState(
    val generation: Long = 0L,
    val lastResetReason: YOutputEvidenceResetReason = YOutputEvidenceResetReason.Initial,
    val videoOutputVerified: Boolean = false,
    val dolbyVisionOutput: Boolean = false,
    val audioOutputVerified: Boolean = false,
    val dolbyAtmosSourceDetected: Boolean = false,
    val dolbyAtmosOutputMode: YDolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
) {
    val nativeDualDolbyOutput: Boolean
        get() =
            videoOutputVerified &&
                dolbyVisionOutput &&
                audioOutputVerified &&
                dolbyAtmosOutputMode.encodedPassthrough

    val nativeDualDolbyPresentationOutput: Boolean
        get() =
            videoOutputVerified &&
                dolbyVisionOutput &&
                audioOutputVerified &&
                dolbyAtmosSourceDetected &&
                dolbyAtmosOutputMode.verifiedAtmosOutput

    fun invalidate(reason: YOutputEvidenceResetReason): YDualDolbyEvidenceState =
        YDualDolbyEvidenceState(
            generation = generation + 1L,
            lastResetReason = reason,
        )

    fun observeVideo(
        outputVerified: Boolean,
        dolbyVisionVerified: Boolean,
    ): YDualDolbyEvidenceState =
        copy(
            videoOutputVerified = outputVerified,
            dolbyVisionOutput = outputVerified && dolbyVisionVerified,
        )

    fun observeAudio(
        outputVerified: Boolean,
        atmosSourceDetected: Boolean,
        outputMode: YDolbyAtmosOutputMode,
    ): YDualDolbyEvidenceState =
        copy(
            audioOutputVerified = outputVerified,
            dolbyAtmosSourceDetected = atmosSourceDetected,
            dolbyAtmosOutputMode =
                if (outputVerified) outputMode else YDolbyAtmosOutputMode.None,
        )
}

/** Atomically removes stale video and audio proof from a published diagnostics snapshot. */
fun YPlayerDiagnostics.invalidateOutputEvidence(
    reason: YOutputEvidenceResetReason,
): YPlayerDiagnostics =
    copy(
        outputEvidenceGeneration = outputEvidenceGeneration + 1L,
        outputEvidenceResetReason = reason,
        videoOutputVerified = false,
        audioOutputVerified = false,
        dolbyVisionOutput = false,
        dolbyVisionRpuApplied = false,
        dolbyVisionEnhancementLayerDelivered = false,
        dolbyVisionFelComposed = false,
        immersiveAudioCarrierOutput = false,
        dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
        audioOutputRoute = "",
        audioOutputRouteVerified = false,
        dolbyAtmosOutput = false,
        spatialAudioOutput = false,
        headTrackingAvailable = false,
    )
