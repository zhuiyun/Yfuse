package com.yfuse.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import com.yfuse.core.playback.WidgetPlaybackRequest

/** Each MediaSource retains its own HTTP factory across preloading, redirects and queue changes. */
internal class WidgetMediaSourceFactory(
    private val defaultFactory: MediaSource.Factory,
    private val createBoundFactory: (WidgetPlaybackRequest) -> MediaSource.Factory,
) : MediaSource.Factory by defaultFactory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val binding = mediaItem.localConfiguration?.tag as? WidgetPlaybackRequest
        return (binding?.let(createBoundFactory) ?: defaultFactory).createMediaSource(mediaItem)
    }
}
