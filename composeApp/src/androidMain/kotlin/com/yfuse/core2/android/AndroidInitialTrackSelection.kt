package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackPreference
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.matchingPreference
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType

internal fun platformAudioTracks(
    trackCount: Int,
    format: (Int) -> MediaFormat,
): List<YTrack> =
    (0 until trackCount).mapNotNull { index ->
        val metadata = format(index)
        val mime = metadata.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
        if (!mime.startsWith("audio/")) return@mapNotNull null
        val language = metadata.getString(MediaFormat.KEY_LANGUAGE)
        YTrack(
            id = "audio:$index",
            type = YTrackType.Audio,
            label =
                metadata.getString("title")?.takeIf(String::isNotBlank)
                    ?: language?.takeIf(String::isNotBlank) ?: "Audio ${index + 1}",
            language = language,
            codec = mime,
        )
    }

internal fun List<YTrack>.initialAudioIndex(preference: YTrackPreference?): Int? =
    (matchingPreference(preference) ?: firstOrNull())?.id?.substringAfter(':')?.toIntOrNull()

internal fun List<YDemuxTrack>.preferredAudioTrack(preference: YTrackPreference?): YDemuxTrack? {
    val audio = filter { it.type == YDemuxTrackType.Audio && it.audio != null }
    val descriptors =
        audio.map { track ->
            YTrack(
                id = track.id.value.toString(),
                type = YTrackType.Audio,
                label = track.label ?: track.language ?: "Audio ${track.id.value + 1}",
                language = track.language,
                codec = track.audio?.mimeType,
            )
        }
    val matched = descriptors.matchingPreference(preference) ?: return null
    return audio.firstOrNull { it.id.value.toString() == matched.id }
}

internal fun List<YDemuxTrack>.preferredSubtitleTrack(preference: YTrackPreference?): YDemuxTrack? {
    val subtitles = filter { it.type == YDemuxTrackType.Subtitle && it.subtitle != null }
    val descriptors =
        subtitles.map { track ->
            YTrack(
                id = track.id.value.toString(),
                type = YTrackType.Subtitle,
                label = track.label ?: track.language ?: "Subtitle ${track.id.value + 1}",
                language = track.language,
                codec = track.subtitle?.mimeType,
            )
        }
    val matched = descriptors.matchingPreference(preference) ?: return null
    return subtitles.firstOrNull { it.id.value.toString() == matched.id }
}

internal fun logInitialAudioSelection(
    route: String,
    requested: Boolean,
    tracks: List<YTrack>,
    selectedId: String?,
) {
    AppLog.info(
        category = "player.core2",
        event = "initial_audio_track_selected",
        message = "Initial audio resolved before decoder creation",
        attributes =
            mapOf(
                "route" to route,
                "preferenceRequested" to requested.toString(),
                "discoveredTracks" to tracks.size.toString(),
                "selectedOrdinal" to tracks.indexOfFirst { it.id == selectedId }.toString(),
            ),
    )
}
