package com.yfuse.feature.player

/**
 * Output evidence exported by the concrete mpv/libplacebo render path.
 *
 * This is deliberately separate from source metadata. `rpuRendered` means a frame carrying parsed
 * Dolby Vision metadata completed a libplacebo render; `felComposed` additionally means that same
 * rendered frame carried the Profile 7 enhancement-layer input.
 */
internal data class MpvDolbyRuntimeEvidence(
    val generation: Long = 0L,
    val rpuRendered: Boolean = false,
    val felComposed: Boolean = false,
) {
    companion object {
        val None = MpvDolbyRuntimeEvidence()
    }
}

/**
 * Platform code installs a zero-allocation reader for the native marker class.
 *
 * Common playback policy can then consume the runtime fact without knowing about JNI or Android.
 * The provider is read only for an actively rendering mpv state, which prevents evidence from a
 * previous native session being promoted during startup of a new one.
 */
internal object MpvDolbyRuntimeEvidenceRegistry {
    private var provider: (() -> MpvDolbyRuntimeEvidence)? = null

    fun installProvider(value: () -> MpvDolbyRuntimeEvidence) {
        provider = value
    }

    fun clearProvider() {
        provider = null
    }

    fun current(): MpvDolbyRuntimeEvidence =
        runCatching { provider?.invoke() ?: MpvDolbyRuntimeEvidence.None }
            .getOrDefault(MpvDolbyRuntimeEvidence.None)
}

/** Native evidence is meaningful only after mpv has actually established video output. */
internal fun PlaybackDiagnostics.mpvDolbyRuntimeEvidence(): MpvDolbyRuntimeEvidence {
    if (!engine.contains("mpv", ignoreCase = true)) return MpvDolbyRuntimeEvidence.None
    if (videoReadiness != PlaybackOutputReadiness.Rendering) return MpvDolbyRuntimeEvidence.None
    return MpvDolbyRuntimeEvidenceRegistry.current()
}
