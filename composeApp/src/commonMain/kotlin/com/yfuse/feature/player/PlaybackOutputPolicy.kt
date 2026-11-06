package com.yfuse.feature.player

/** A platform-neutral plan which can be tested without an Android Surface. */
internal sealed interface SurfaceFrameRatePlan {
    data object Disabled : SurfaceFrameRatePlan

    data class Unsupported(
        val reason: PlaybackOutputUnsupportedReason,
    ) : SurfaceFrameRatePlan

    data class Invalid(
        val detail: String,
    ) : SurfaceFrameRatePlan

    data class Apply(
        val frameRate: Float,
        val allowNonSeamlessSwitch: Boolean,
        val useExplicitStrategyApi: Boolean,
    ) : SurfaceFrameRatePlan
}

internal fun surfaceFrameRatePlan(
    mode: FrameRateMatchMode,
    contentFrameRate: Float,
    androidApiLevel: Int,
): SurfaceFrameRatePlan {
    if (mode == FrameRateMatchMode.Disabled) return SurfaceFrameRatePlan.Disabled
    if (androidApiLevel < ANDROID_FRAME_RATE_API) {
        return SurfaceFrameRatePlan.Unsupported(PlaybackOutputUnsupportedReason.PlatformApiTooOld)
    }
    if (!contentFrameRate.isFinite() || contentFrameRate <= 0f) {
        return SurfaceFrameRatePlan.Invalid("content frame rate must be finite and positive")
    }
    if (
        mode == FrameRateMatchMode.Always &&
        androidApiLevel < ANDROID_EXPLICIT_FRAME_RATE_STRATEGY_API
    ) {
        return SurfaceFrameRatePlan.Unsupported(PlaybackOutputUnsupportedReason.PlatformApiTooOld)
    }
    return SurfaceFrameRatePlan.Apply(
        frameRate = contentFrameRate,
        allowNonSeamlessSwitch = mode == FrameRateMatchMode.Always,
        useExplicitStrategyApi = androidApiLevel >= ANDROID_EXPLICIT_FRAME_RATE_STRATEGY_API,
    )
}
