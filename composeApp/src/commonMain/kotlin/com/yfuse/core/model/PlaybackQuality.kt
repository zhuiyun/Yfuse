package com.yfuse.core.model

/** User-visible playback quality. Manual caps start on the server transcode URL. */
enum class PlaybackQuality(
    val label: String,
    val maxWidth: Int?,
    val videoBitrate: Int?,
) {
    Auto("自动", null, null),
    Original("原画", null, null),
    UltraHd("4K · 20 Mbps", 3840, 20_000_000),
    FullHd("1080P · 8 Mbps", 1920, 8_000_000),
    Hd("720P · 4 Mbps", 1280, 4_000_000),
    Sd("480P · 2 Mbps", 854, 2_000_000),

    ;

    /** Auto and Original prefer the server-approved direct/direct-stream source. */
    val requiresServerTranscode: Boolean
        get() = maxWidth != null && videoBitrate != null
}
