package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Amount of on-device storage available to the streaming video cache. */
enum class VideoCacheSize(val label: String, val bytes: Long) {
    Off("关闭", 0L),
    Small("256 MB", 256L * 1024L * 1024L),
    Medium("512 MB", 512L * 1024L * 1024L),
    Large("1 GB", 1024L * 1024L * 1024L),
    ExtraLarge("2 GB", 2L * 1024L * 1024L * 1024L),
}

/** Playback settings that are independent from appearance and decoder selection. */
class PlaybackPreferences(private val settings: Settings) {
    private val _videoCacheSize = MutableStateFlow(
        settings.getStringOrNull(KEY_VIDEO_CACHE_SIZE)
            ?.let { stored -> VideoCacheSize.entries.firstOrNull { it.name == stored } }
            ?: VideoCacheSize.Medium,
    )
    val videoCacheSize: StateFlow<VideoCacheSize> = _videoCacheSize.asStateFlow()

    fun setVideoCacheSize(size: VideoCacheSize) {
        _videoCacheSize.value = size
        settings.putString(KEY_VIDEO_CACHE_SIZE, size.name)
    }

    private companion object {
        const val KEY_VIDEO_CACHE_SIZE = "player.videoCacheSize"
    }
}
