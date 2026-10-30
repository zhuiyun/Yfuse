package com.yfuse.feature.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory

/** Both subtitle and main players honor each MediaItem's credential origin and redirects. */
@androidx.media3.common.util.UnstableApi
internal fun playbackMediaSourceFactory(
    context: Context,
    userAgent: String,
    defaultFactory: MediaSource.Factory,
    extractors: ExtractorsFactory = DefaultExtractorsFactory(),
    transferListener: TransferListener? = null,
): MediaSource.Factory =
    WidgetMediaSourceFactory(defaultFactory) { binding ->
        val upstream =
            DefaultDataSource.Factory(
                context,
                PlaybackHttpDataSource.factory(userAgent, widgetPlaybackHttpClient(binding)),
            )
        val guarded = HlsManifestGuardDataSourceFactory(upstream)
        val bound =
            DataSource.Factory {
                guarded.createDataSource().also { source -> transferListener?.let(source::addTransferListener) }
            }
        DefaultMediaSourceFactory(bound, extractors)
    }
