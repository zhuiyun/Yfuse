package com.yfuse.feature.player

/** How a queue entry chooses the server which receives Emby playback events. */
internal sealed interface PlaybackReportingTarget {
    data class SavedServer(
        val id: String,
    ) : PlaybackReportingTarget

    data object DefaultServer : PlaybackReportingTarget

    data object Disabled : PlaybackReportingTarget
}

/**
 * A local file without source metadata must never be reported to whichever account happens to
 * be the current default. Remote legacy entries retain the old default-server compatibility.
 */
internal fun playbackReportingTarget(item: PlayerMediaItem?): PlaybackReportingTarget {
    if (item == null) return PlaybackReportingTarget.Disabled
    item.serverId?.takeIf(String::isNotBlank)?.let { serverId ->
        return PlaybackReportingTarget.SavedServer(serverId)
    }
    return if (item.url.hasLocalPlaybackScheme()) {
        PlaybackReportingTarget.Disabled
    } else {
        PlaybackReportingTarget.DefaultServer
    }
}

private fun String.hasLocalPlaybackScheme(): Boolean =
    substringBefore(':', missingDelimiterValue = "")
        .lowercase() in setOf("file", "content", "android.resource")
