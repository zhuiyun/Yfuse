package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine

/** How aggressively playback may ask Android to match the display to the video's frame rate. */
enum class FrameRateMatchMode(
    val storageValue: String,
) {
    Disabled("off"),
    SeamlessOnly("seamless_only"),

    /** May briefly blank the display while Android changes refresh rate. */
    Always("always"),
    ;

    companion object {
        fun fromStorage(value: String?): FrameRateMatchMode =
            entries.firstOrNull { it.storageValue == value } ?: Disabled
    }
}

/**
 * Compressed audio is never forced onto an incompatible route.
 *
 * [Compatible] asks the backend to preserve encoded audio only when the current Android output
 * route reports that it can consume the format. A PCM fallback is therefore a valid, observable
 * outcome rather than a false "passthrough succeeded" result.
 */
enum class AudioPassthroughMode(
    val storageValue: String,
) {
    Disabled("off"),
    Compatible("compatible"),
    ;

    companion object {
        fun fromStorage(value: String?): AudioPassthroughMode =
            entries.firstOrNull { it.storageValue == value } ?: Disabled
    }
}

enum class PlaybackOutputUnsupportedReason {
    PlatformApiTooOld,
    BackendApiNotVerified,
}

/** Static configurability. This deliberately does not claim that a request is currently active. */
sealed interface PlaybackFeatureCapability<out Mode> {
    /** [modes] contains enabled modes; disabling a feature is always possible. */
    data class Available<Mode>(
        val modes: Set<Mode>,
    ) : PlaybackFeatureCapability<Mode>

    data class Unsupported(
        val reason: PlaybackOutputUnsupportedReason,
    ) : PlaybackFeatureCapability<Nothing>
}

data class PlaybackOutputCapabilities(
    val frameRateMatching: PlaybackFeatureCapability<FrameRateMatchMode>,
    val audioPassthrough: PlaybackFeatureCapability<AudioPassthroughMode>,
) {
    companion object {
        /**
         * Backend/API support matrix, independent from the currently attached display/audio route.
         *
         * Android 11 can only request the effectively-seamless two-argument Surface API. Android
         * 12 added the explicit non-seamless strategy used by [FrameRateMatchMode.Always]. MDK is
         * intentionally unsupported until its bundled API exposes a verifiable equivalent.
         */
        fun forEngine(
            engine: PlayerEngine,
            androidApiLevel: Int,
        ): PlaybackOutputCapabilities {
            if (engine == PlayerEngine.Mdk) {
                val unsupported =
                    PlaybackFeatureCapability.Unsupported(
                        PlaybackOutputUnsupportedReason.BackendApiNotVerified,
                    )
                return PlaybackOutputCapabilities(
                    frameRateMatching = unsupported,
                    audioPassthrough = unsupported,
                )
            }

            val frameRateMatching =
                when {
                    androidApiLevel < ANDROID_FRAME_RATE_API ->
                        PlaybackFeatureCapability.Unsupported(
                            PlaybackOutputUnsupportedReason.PlatformApiTooOld,
                        )
                    androidApiLevel == ANDROID_FRAME_RATE_API ->
                        PlaybackFeatureCapability.Available(setOf(FrameRateMatchMode.SeamlessOnly))
                    else ->
                        PlaybackFeatureCapability.Available(
                            setOf(
                                FrameRateMatchMode.SeamlessOnly,
                                FrameRateMatchMode.Always,
                            ),
                        )
                }
            return PlaybackOutputCapabilities(
                frameRateMatching = frameRateMatching,
                audioPassthrough =
                    PlaybackFeatureCapability.Available(
                        setOf(AudioPassthroughMode.Compatible),
                    ),
            )
        }
    }
}

/** Runtime truth. Requested/configured are intentionally distinct from [Active]. */
sealed interface PlaybackOutputStatus {
    data object Disabled : PlaybackOutputStatus

    data class Unsupported(
        val reason: PlaybackOutputUnsupportedReason,
    ) : PlaybackOutputStatus

    /** Android accepted a display hint, but the scheduler is not required to honor it. */
    data class Requested(
        val detail: String,
    ) : PlaybackOutputStatus

    /** A backend accepted an option; the selected media/output route has not proved activation. */
    data class Configured(
        val detail: String,
    ) : PlaybackOutputStatus

    /** The backend reported an encoded output or another feature-specific activation proof. */
    data class Active(
        val detail: String,
    ) : PlaybackOutputStatus

    /** The feature is enabled, but current media or output routing fell back safely. */
    data class Inactive(
        val detail: String,
    ) : PlaybackOutputStatus

    data class Rejected(
        val detail: String,
    ) : PlaybackOutputStatus
}

internal const val ANDROID_FRAME_RATE_API = 30
internal const val ANDROID_EXPLICIT_FRAME_RATE_STRATEGY_API = 31
