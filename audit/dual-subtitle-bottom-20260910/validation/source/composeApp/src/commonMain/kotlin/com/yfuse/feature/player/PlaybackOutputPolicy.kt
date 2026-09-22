package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackAudioCodec

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

/**
 * Builds mpv's encoded-audio allow-list from the active Android route, not from the user's intent
 * alone. A null option leaves passthrough disabled on mpv's fresh, config-disabled instance.
 */
internal fun mpvAudioSpdifOption(
    mode: AudioPassthroughMode,
    directAudioFormats: Set<PlaybackAudioCodec>,
): String? {
    if (mode != AudioPassthroughMode.Compatible) return null
    return buildList {
        if (PlaybackAudioCodec.Ac3 in directAudioFormats) add("ac3")
        if (
            PlaybackAudioCodec.Eac3 in directAudioFormats ||
            PlaybackAudioCodec.Eac3Joc in directAudioFormats
        ) {
            add("eac3")
        }
        if (PlaybackAudioCodec.Dts in directAudioFormats) add("dts")
        if (PlaybackAudioCodec.DtsHd in directAudioFormats) add("dts-hd")
        if (PlaybackAudioCodec.TrueHd in directAudioFormats) add("truehd")
    }.takeIf(List<String>::isNotEmpty)?.joinToString(",")
}

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
    val detectedOutput = output.ifBlank { decoder }
    val active =
        output.startsWith("spdif", ignoreCase = true) ||
            decoder.startsWith("spdif", ignoreCase = true)
    return when {
        active -> PlaybackOutputStatus.Active(detectedOutput)
        detectedOutput.isBlank() -> PlaybackOutputStatus.Configured("waiting for mpv audio output")
        else -> PlaybackOutputStatus.Inactive("mpv is decoding the current track to $detectedOutput")
    }
}
