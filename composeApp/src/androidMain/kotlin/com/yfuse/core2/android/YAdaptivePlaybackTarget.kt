package com.yfuse.core2.android

/** A complete demux/decoder reopen, never permission to splice incompatible samples into a codec. */
internal data class YAdaptivePlaybackTarget(
    val rootUri: String,
    val uri: String,
    val localPositionMs: Long,
    val presentationOffsetMs: Long,
    /** Zero means that the presentation duration is unknown. */
    val presentationDurationMs: Long,
    val revision: Long,
    val periodEndGlobalMs: Long?,
    val feedbackGeneration: Long,
) {
    init {
        require(rootUri.isNotBlank() && uri.isNotBlank())
        require(localPositionMs >= 0L && presentationOffsetMs >= 0L && presentationDurationMs >= 0L)
        require(revision >= 0L)
        require(periodEndGlobalMs == null || periodEndGlobalMs >= presentationOffsetMs)
    }

    override fun toString(): String =
        "YAdaptivePlaybackTarget(positionMs=$localPositionMs, offsetMs=$presentationOffsetMs, " +
            "durationMs=$presentationDurationMs, revision=$revision, generation=$feedbackGeneration)"
}
