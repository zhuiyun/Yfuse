package com.yfuse.feature.player

import com.yfuse.core.util.androidAppContext
import com.yfuse.tv.integration.TvContinueWatchingRuntime

internal actual fun publishSystemPlaybackProgress(event: SystemPlaybackProgressEvent) {
    val context = androidAppContext ?: return
    // The runtime returns immediately on phones; playback reporting never depends on TV surfaces.
    runCatching { TvContinueWatchingRuntime.recordSystemProgress(context, event) }
}
