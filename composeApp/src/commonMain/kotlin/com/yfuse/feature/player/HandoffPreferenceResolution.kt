package com.yfuse.feature.player

import com.yfuse.core.handoff.HandoffMedia

internal data class HandoffPreferenceResolution(
    val apply: Boolean,
    val audio: EngineTrack?,
    val subtitle: EngineTrack?,
    val secondarySubtitle: EngineTrack?,
    val missing: List<String>,
)

/** One fixed deadline per received request; changing track lists never extends it. */
internal class HandoffPreferenceWait(
    private val startedAtElapsedMs: Long,
) {
    fun remainingMs(nowElapsedMs: Long): Long =
        (10_000L - (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0)).coerceAtLeast(0)

    fun resolve(
        media: HandoffMedia,
        audioTracks: List<EngineTrack>,
        subtitleTracks: List<EngineTrack>,
        supportsSecondary: Boolean,
        nowElapsedMs: Long,
    ): HandoffPreferenceResolution {
        val preference = media.preference
        val audio =
            matchHandoffTrack(
                audioTracks,
                media.audioTrackIndex,
                preference?.audioLanguage,
                preference?.audioTitle,
                preference?.audioCodec,
            )
        val subtitle =
            if (preference?.subtitlesEnabled == true) {
                matchHandoffTrack(
                    subtitleTracks,
                    media.subtitleTrackIndex,
                    preference.subtitleLanguage,
                    preference.subtitleTitle,
                    preference.subtitleCodec,
                )
            } else {
                null
            }
        val secondary =
            if (media.secondarySubtitlesEnabled == true && supportsSecondary) {
                matchHandoffTrack(
                    subtitleTracks,
                    media.secondarySubtitleTrackIndex,
                    media.secondarySubtitleLanguage,
                    media.secondarySubtitleTitle,
                    media.secondarySubtitleCodec,
                )
            } else {
                null
            }
        val audioRequested =
            media.audioTrackIndex != null ||
                listOf(
                    preference?.audioLanguage,
                    preference?.audioTitle,
                    preference?.audioCodec,
                ).any { !it.isNullOrBlank() }
        val missing =
            buildList {
                if (audioRequested && audio == null) add("音轨")
                if (preference?.subtitlesEnabled == true && subtitle == null) add("主字幕")
                if (media.secondarySubtitlesEnabled == true && secondary == null) add("副字幕")
            }
        val hasDiscoveredTracks = audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()
        return HandoffPreferenceResolution(
            apply = remainingMs(nowElapsedMs) == 0L || hasDiscoveredTracks && missing.isEmpty(),
            audio = audio,
            subtitle = subtitle,
            secondarySubtitle = secondary,
            missing = missing,
        )
    }
}

/** Receiver strips indexes unless the original server AND original non-null version match. */
private fun matchHandoffTrack(
    tracks: List<EngineTrack>,
    trustedIndex: Int?,
    language: String?,
    title: String?,
    codec: String?,
): EngineTrack? {
    val indexed = trustedIndex?.let(tracks::getOrNull)
    if (indexed != null &&
        (language.isNullOrBlank() || indexed.language.equals(language, ignoreCase = true)) &&
        (title.isNullOrBlank() || indexed.label.equals(title, ignoreCase = true)) &&
        (codec.isNullOrBlank() || indexed.codec.equals(codec, ignoreCase = true))
    ) {
        return indexed
    }
    return tracks.bestRestoreMatch(TrackRestorePreference(language, title.orEmpty(), codec))
}
