package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.PlayTarget

/** A concrete episode selected using the provider's normal, locally projected progress rules. */
internal data class SeriesPlaybackResolution(
    val target: PlayTarget,
    val detail: MediaDetail,
    /** Complete directory for this launch only; incomplete catalogs keep the normal enrichment read. */
    val episodes: List<Episode>? = null,
)

/** A catalog row may omit media entirely, or contain only a summary of its first video stream. */
internal fun BaseItemDto.hasPlaybackSourceSnapshot(seriesId: String): Boolean =
    Type == "Episode" &&
        SeriesId == seriesId &&
        !MediaSources.isNullOrEmpty() &&
        MediaSources.all { source ->
            !source.Id.isNullOrBlank() &&
                !source.Path.isNullOrBlank() &&
                !source.Container.isNullOrBlank() &&
                source.MediaStreams?.any { stream ->
                    stream.Type == "Video" &&
                        !stream.Codec.isNullOrBlank() &&
                        (stream.Width ?: 0) > 0 &&
                        (stream.Height ?: 0) > 0
                } == true &&
                // A lone video summary says nothing about missing audio tracks. Silent files
                // safely use itemDetail too; do not infer silence from a catalog response.
                source.MediaStreams.orEmpty().any { it.Type == "Audio" } &&
                source.MediaStreams.orEmpty().all { stream ->
                    stream.Index != null &&
                        !stream.Type.isNullOrBlank() &&
                        (stream.Type == "Subtitle" || !stream.Codec.isNullOrBlank()) &&
                        (
                            stream.Type != "Audio" ||
                                ((stream.Channels ?: 0) > 0 && (stream.SampleRate ?: 0) > 0)
                        )
                }
        }

internal fun logSeriesPlaybackSnapshot(
    provider: String,
    reused: Boolean,
) {
    AppLog.info(
        category = "feature.player",
        event = "series_playback_snapshot",
        message = "Resolved the next episode from its series directory",
        attributes =
            mapOf(
                "provider" to provider,
                "metadataSource" to if (reused) "episode_directory" else "episode_detail_fallback",
            ),
    )
}
