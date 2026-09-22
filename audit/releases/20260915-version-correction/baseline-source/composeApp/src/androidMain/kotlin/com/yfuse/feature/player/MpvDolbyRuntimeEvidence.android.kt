package com.yfuse.feature.player

/**
 * First access initializes the exact native-build capability reader. That discovery installs the
 * cached provider used by subsequent common-code reads; this fallback also returns the same native
 * snapshot immediately so the first rendered frame can be graded without waiting for recomposition.
 */
internal actual fun platformMpvDolbyRuntimeEvidence(): MpvDolbyRuntimeEvidence {
    val capabilities = installedMpvNativeBuildCapabilities
    if (!capabilities.pinnedYfuseDolbyVisionArtifact) return MpvDolbyRuntimeEvidence.None

    return runCatching {
        val marker = Class.forName(YFUSE_MPV_CAPABILITY_CLASS, false, MpvVideoEngine::class.java.classLoader)
        val generation =
            (marker.getMethod("dolbyVisionRuntimeGeneration").invoke(null) as? Number)?.toLong()
                ?: 0L
        val mask =
            (marker.getMethod("dolbyVisionRuntimeEvidence").invoke(null) as? Number)?.toInt()
                ?: 0
        MpvDolbyRuntimeEvidence(
            generation = generation,
            rpuRendered = mask and 1 != 0,
            felComposed = mask and 2 != 0,
        )
    }.getOrDefault(MpvDolbyRuntimeEvidence.None)
}
