package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackPlan

/** Resolve explicit software intent against demux evidence, never server hints or a hardware plan. */
internal fun requiredYCoreSoftwarePlan(
    probedVideo: YVideoRequirement?,
    protectedContent: Boolean,
): YPlaybackPlan {
    if (protectedContent || probedVideo?.secureDecodeRequired == true) {
        throw YPlaybackException(
            category = YPlaybackFailureCategory.Drm,
            stage = YPlaybackFailureStage.VideoDecoderConfigure,
            safeDetail = "Protected video cannot use software decode",
        )
    }
    val video =
        probedVideo ?: throw YPlaybackException(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Demux,
            safeDetail = "Software decode requires confirmed video format",
        )
    return yCoreInternalSoftwareRecoveryPlan(video.hdrType) ?: throw YPlaybackException(
        category = YPlaybackFailureCategory.Decoder,
        stage = YPlaybackFailureStage.VideoDecoderConfigure,
        safeDetail = "Confirmed video format has no safe YCore software plan",
    )
}
