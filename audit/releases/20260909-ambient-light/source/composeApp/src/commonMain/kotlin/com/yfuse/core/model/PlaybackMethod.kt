package com.yfuse.core.model

import kotlinx.serialization.Serializable

/**
 * Playback method negotiated with the media server.
 *
 * This is server input, not necessarily the route currently opened by a local engine: client
 * policy may deliberately keep the original source (for example to preserve a Dolby pipeline).
 * Runtime UI must use the effective/active playback route rather than presenting this value alone.
 */
@Serializable
enum class PlaybackMethod(
    val embyValue: String,
    val label: String,
) {
    DirectPlay("DirectPlay", "直播放"),
    DirectStream("DirectStream", "直串流"),
    Transcode("Transcode", "服务器转码"),
}
