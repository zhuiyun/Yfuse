package com.yfuse.core.model

import kotlinx.serialization.Serializable

/** The server-approved way the current source reaches the player. */
@Serializable
enum class PlaybackMethod(val embyValue: String, val label: String) {
    DirectPlay("DirectPlay", "直播放"),
    DirectStream("DirectStream", "直串流"),
    Transcode("Transcode", "服务器转码"),
}
