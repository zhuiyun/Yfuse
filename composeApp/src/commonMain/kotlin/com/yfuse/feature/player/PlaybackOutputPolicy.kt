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

internal const val MPV_AUDIO_SPDIF_CODECS = "ac3,eac3,dts,dts-hd,truehd"

/** A null option means mpv's fresh, config-disabled instance keeps passthrough off. */
internal fun mpvAudioSpdifOption(mode: AudioPassthroughMode): String? =
    MPV_AUDIO_SPDIF_CODECS.takeIf { mode == AudioPassthroughMode.Compatible }

/**
 * mpv exposes the decoder and the format written to the audio API as separate properties.
 * Requiring an SPDIF marker in either is activation evidence; merely setting audio-spdif is not.
 */
internal fun mpvAudioPassthroughStatus(
    mode: AudioPassthroughMode,
    audioOutputFormat: String?,
    audioDecoder: String?,
): PlaybackOutputStatus {
    if (mode == AudioPassthroughMode.Disabled) return PlaybackOutputStatus.Disabled
    val output = audioOutputFormat?.trim().orEmpty()
    val decoder = audioDecoder?.trim().orEmpty()
    val active =
        output.startsWith("spdif", ignoreCase = true) ||
            decoder.startsWith("spdif", ignoreCase = true)
    return when {
        active -> PlaybackOutputStatus.Active(output.ifBlank { decoder })
        output.isBlank() && decoder.isBlank() ->
            PlaybackOutputStatus.Configured("waiting for mpv audio output")
        else -> PlaybackOutputStatus.Inactive("mpv is decoding the current track to $output")
    }
}
