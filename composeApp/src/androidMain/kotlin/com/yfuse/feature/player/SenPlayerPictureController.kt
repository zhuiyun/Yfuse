package com.yfuse.feature.player

import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPictureSettings

internal enum class SenPlayerPictureFeature {
    Upscaling,
    SdrToHdr,
}

internal data class SenPlayerPictureEligibility(
    val upscalingUnavailableReason: String? = "等待视频信息",
    val sdrToHdrUnavailableReason: String? = "等待视频信息",
)

/** Owned by the engine worker. A rejection is remembered until the choice or output changes. */
internal class SenPlayerPictureController {
    var state = SenPlayerPictureState()
        private set
    private var previousEligibility: SenPlayerPictureEligibility? = null

    fun reset() {
        state = state.awaitingApplication()
        previousEligibility = null
    }

    fun apply(
        settings: SenPlayerPictureSettings,
        eligibility: SenPlayerPictureEligibility,
        send: (SenPlayerPictureFeature, SenPlayerEnhancementLevel) -> SenPlayerEnhancementState,
    ): SenPlayerPictureState {
        if (previousEligibility != eligibility) {
            state =
                state.copy(
                    upscaling =
                        if (previousEligibility?.upscalingUnavailableReason !=
                            eligibility.upscalingUnavailableReason
                        ) {
                            SenPlayerEnhancementState(level = state.upscaling.level)
                        } else {
                            state.upscaling
                        },
                    sdrToHdr =
                        if (previousEligibility?.sdrToHdrUnavailableReason !=
                            eligibility.sdrToHdrUnavailableReason
                        ) {
                            SenPlayerEnhancementState(level = state.sdrToHdr.level)
                        } else {
                            state.sdrToHdr
                        },
                )
            previousEligibility = eligibility
        }
        // Keep the same order as original op3.k0; one rejected option must not suppress the other.
        if (needsSending(state.upscaling, settings.upscaling)) {
            state =
                state.copy(
                    upscaling =
                        applyOne(
                            SenPlayerPictureFeature.Upscaling,
                            settings.upscaling,
                            eligibility.upscalingUnavailableReason,
                            send,
                        ),
                )
        }
        if (needsSending(state.sdrToHdr, settings.sdrToHdr)) {
            state =
                state.copy(
                    sdrToHdr =
                        applyOne(
                            SenPlayerPictureFeature.SdrToHdr,
                            settings.sdrToHdr,
                            eligibility.sdrToHdrUnavailableReason,
                            send,
                        ),
                )
        }
        return state
    }

    private fun applyOne(
        feature: SenPlayerPictureFeature,
        requested: SenPlayerEnhancementLevel,
        unavailable: String?,
        send: (SenPlayerPictureFeature, SenPlayerEnhancementLevel) -> SenPlayerEnhancementState,
    ): SenPlayerEnhancementState {
        if (requested == SenPlayerEnhancementLevel.Off || unavailable == null) return send(feature, requested)
        // Clear a previously enabled effect when moving to an ineligible file/display.
        val disabled = send(feature, SenPlayerEnhancementLevel.Off)
        return SenPlayerEnhancementState(
            level = requested,
            status = SenPlayerEnhancementStatus.Rejected,
            nativeCode = disabled.nativeCode,
            detail =
                if (disabled.status ==
                    SenPlayerEnhancementStatus.Accepted
                ) {
                    unavailable
                } else {
                    "$unavailable；关闭请求未确认"
                },
        )
    }

    private fun needsSending(
        current: SenPlayerEnhancementState,
        requested: SenPlayerEnhancementLevel,
    ): Boolean = current.level != requested || current.status == SenPlayerEnhancementStatus.Waiting
}
