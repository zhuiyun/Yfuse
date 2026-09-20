package com.yfuse.feature.player

import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.RememberedPlaybackTrack
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YTrackPreference

internal fun PlayerMediaItem.initialPlaybackTracks(
    preferences: PlaybackPreferences,
    requested: PlaybackTrackRequest.Tracks? = null,
): YInitialTrackSelection? {
    val remembered = preferences.rememberedSeriesPlayback(serverId, seriesId, id)
    val subtitle = requested?.subtitleLanguage
    return YInitialTrackSelection(
        audio =
            requested?.audioLanguage?.let { YTrackPreference(language = it) }
                ?: remembered?.audio?.toInitialTrackPreference(),
        subtitle =
            if (subtitle == PlaybackTrackRequest.SUBTITLES_OFF) {
                null
            } else {
                subtitle?.let { YTrackPreference(language = it) }
                    ?: remembered?.primarySubtitle?.toInitialTrackPreference()
            },
        subtitlesDisabled =
            subtitle == PlaybackTrackRequest.SUBTITLES_OFF ||
                subtitle == null &&
                remembered?.primarySubtitlesOff == true,
    ).orNull()
}

private fun RememberedPlaybackTrack.toInitialTrackPreference() =
    YTrackPreference(language, label, codec, languageOrdinal)

internal fun YInitialTrackSelection?.withInitialHandoff(
    item: PlayerMediaItem,
    handoff: com.yfuse.core.handoff.HandoffMedia?,
    profileId: String?,
): YInitialTrackSelection? {
    if (handoff == null ||
        handoff.profileId != profileId ||
        handoff.serverId != item.serverId ||
        handoff.itemId != item.id ||
        handoff.mediaSourceId != item.versionId ||
        handoff.mediaKey != item.watchKey
    ) {
        return this
    }
    val preference = handoff.preference
    val original = this ?: YInitialTrackSelection()
    val audio =
        YTrackPreference(
            preference?.audioLanguage,
            preference?.audioTitle,
            preference?.audioCodec,
            trackOrdinal = handoff.audioTrackIndex,
        ).takeIf { handoff.audioTrackIndex != null || !it.language.isNullOrBlank() || !it.label.isNullOrBlank() }
    val subtitle =
        YTrackPreference(
            preference?.subtitleLanguage,
            preference?.subtitleTitle,
            preference?.subtitleCodec,
            trackOrdinal = handoff.subtitleTrackIndex,
        ).takeIf { handoff.subtitleTrackIndex != null || !it.language.isNullOrBlank() || !it.label.isNullOrBlank() }
    return original
        .copy(
            audio = audio ?: original.audio,
            subtitle =
                when (preference?.subtitlesEnabled) {
                    false -> null
                    true -> subtitle
                    null -> original.subtitle
                },
            subtitlesDisabled = preference?.subtitlesEnabled?.not() ?: original.subtitlesDisabled,
        ).orNull()
}
