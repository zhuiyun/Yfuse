package com.yfuse.feature.player

import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.sync.playback.PlaybackTrackPreference

internal fun PlayerMediaItem.personalMediaRef(): PersonalMediaRef =
    PersonalMediaRef(
        mediaKey = watchKey,
        title = title.ifBlank { "未命名影片" },
        mediaType = mediaType.ifBlank { if (seriesId != null) "Episode" else "Movie" },
        tmdbId =
            providerIds.entries
                .firstOrNull { it.key.equals("tmdb", true) }
                ?.value
                ?.toIntOrNull(),
        year = year,
        serverId = serverId,
        serverItemId = id,
    )

internal fun PlayerMediaItem.handoffMedia(
    state: PlaybackState,
    positionMs: Long,
    profileId: String?,
    subtitleOffsetMs: Long,
    secondarySubtitleOffsetMs: Long,
    audioOffsetMs: Long,
): HandoffMedia? {
    val server = serverId ?: return null
    val audio = state.audioTracks.firstOrNull { it.selected }
    val subtitle = state.subtitleTracks.firstOrNull { it.selected }
    val secondary = state.subtitleTracks.firstOrNull { it.id == state.secondarySubtitleTrackId }
    return HandoffMedia(
        mediaKey = watchKey,
        title = title,
        serverId = server,
        itemId = id,
        positionMs =
            if (state.durationMs >
                0
            ) {
                positionMs.coerceIn(0L, state.durationMs)
            } else {
                positionMs.coerceAtLeast(0L)
            },
        durationMs = state.durationMs.coerceAtLeast(0L),
        aliases = matchKeys.distinct().take(16),
        profileId = profileId,
        mediaSourceId = versionId,
        versionName = activeVersion?.label,
        preference =
            PlaybackTrackPreference(
                audioLanguage = audio?.language,
                audioCodec = audio?.codec,
                audioTitle = audio?.label,
                subtitleLanguage = subtitle?.language,
                subtitleCodec = subtitle?.codec,
                subtitleTitle = subtitle?.label,
                subtitlesEnabled = subtitle != null,
                playbackSpeed = state.speed,
            ),
        audioTrackIndex = audio?.let(state.audioTracks::indexOf),
        subtitleTrackIndex = subtitle?.let(state.subtitleTracks::indexOf),
        secondarySubtitleTrackIndex = secondary?.let(state.subtitleTracks::indexOf),
        audioOffsetMs = audioOffsetMs,
        subtitleOffsetMs = subtitleOffsetMs,
        secondarySubtitleOffsetMs = secondarySubtitleOffsetMs,
        secondarySubtitleLanguage = secondary?.language,
        secondarySubtitleTitle = secondary?.label,
        secondarySubtitleCodec = secondary?.codec,
        secondarySubtitlesEnabled = secondary != null,
    ).takeIf(HandoffMedia::valid)
}
