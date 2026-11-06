package com.yfuse.feature.player

import com.yfuse.core.data.SenPlayerEnhancementLevel

/** An original option acknowledgement is not proof of HDR output or model inference. */
enum class SenPlayerEnhancementStatus {
    Waiting,
    Accepted,
    Rejected,
    Failed,
}

data class SenPlayerEnhancementState(
    val level: SenPlayerEnhancementLevel = SenPlayerEnhancementLevel.Off,
    val status: SenPlayerEnhancementStatus = SenPlayerEnhancementStatus.Waiting,
    val nativeCode: Int? = null,
    val detail: String? = null,
    /** Original runtime's active flag for this source, not inferred from the selected level. */
    val active: Boolean? = null,
)

/** Present only for a local SenPlayer engine; never copied into receiver output evidence. */
data class SenPlayerPictureState(
    val sdrToHdr: SenPlayerEnhancementState = SenPlayerEnhancementState(),
    val upscaling: SenPlayerEnhancementState = SenPlayerEnhancementState(),
) {
    fun withoutActiveEvidence(): SenPlayerPictureState =
        copy(sdrToHdr = sdrToHdr.copy(active = null), upscaling = upscaling.copy(active = null))

    fun awaitingApplication(): SenPlayerPictureState =
        copy(
            sdrToHdr = SenPlayerEnhancementState(level = sdrToHdr.level),
            upscaling = SenPlayerEnhancementState(level = upscaling.level),
        )
}
