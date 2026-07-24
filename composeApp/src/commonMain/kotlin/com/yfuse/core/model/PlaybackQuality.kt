package com.yfuse.core.model

/**
 * Default playback quality. Auto keeps direct play; explicit qualities start
 * with an Emby HLS transcode capped at the selected resolution and bitrate.
 */
enum class PlaybackQuality(
    val label: String,
    val maxWidth: Int?,
    val videoBitrate: Int?,
) {
    Auto("自动", null, null),
    UltraHd("4K · 20 Mbps", 3840, 20_000_000),
    FullHd("1080P · 8 Mbps", 1920, 8_000_000),
    Hd("720P · 4 Mbps", 1280, 4_000_000),
    Sd("480P · 2 Mbps", 854, 2_000_000),
}
