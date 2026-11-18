package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.strategy.YPlaybackPlan

/** A forced recovery skips platform probes, but never skips demux format and protection checks. */
internal fun softwareRecoveryPlanFromDemux(
    video: YVideoTrackFormat,
    protectedContent: Boolean,
): YPlaybackPlan =
    requiredYCoreSoftwarePlan(
        probedVideo =
            YVideoRequirement(
                codec = video.codec,
                width = video.width,
                height = video.height,
                frameRate = video.frameRate,
                hdrType =
                    if (video.dolbyVisionConfig != null ||
                        video.mimeType.equals("video/dolby-vision", ignoreCase = true)
                    ) {
                        YHdrType.DolbyVision
                    } else {
                        video.hdrType
                    },
            ),
        protectedContent = protectedContent,
        dolbyConfig = video.dolbyVisionConfig.takeIf { video.samplePacking != null },
    )

/** Resolve explicit software intent against demux evidence, never server hints or a hardware plan. */
internal fun requiredYCoreSoftwarePlan(
    probedVideo: YVideoRequirement?,
    protectedContent: Boolean,
    dolbyConfig: YDolbyVisionConfig? = null,
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
    val config = dolbyConfig.takeIf { video.codec == YVideoCodec.H265 }
    return yCoreInternalSoftwareRecoveryPlan(video.hdrType, config) ?: throw YPlaybackException(
        category = YPlaybackFailureCategory.Decoder,
        stage = YPlaybackFailureStage.VideoDecoderConfigure,
        safeDetail = "Confirmed video format has no safe YCore software plan",
    )
}
