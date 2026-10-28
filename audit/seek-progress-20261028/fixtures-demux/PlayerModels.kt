package com.yfuse.core2.api

import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials

/** Plain source identity only; playback/session implementations are not linked into this harness. */
data class YMediaItem(
    val id: String,
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val transportCredentials: YTransportCredentials? = null,
    val cacheIdentity: YCacheIdentity? = null,
    val cacheMaximumBytes: Long = 0L,
    val drmConfiguration: Any? = null,
    val sourceHints: Any? = null,
    val initialTrackSelection: YInitialTrackSelection? = null,
)

class YInitialTrackSelection {
    fun orNull(): YInitialTrackSelection? = error("Unused host fixture")
}

enum class YPlaybackFailureCategory { Authorization, Drm, Network, Container, Decoder, Renderer, AudioSink, Unknown }
enum class YPlaybackRoute { Legacy, NativeTunnel, NativeDirect, NativeEnhanced, GpuEnhanced, SoftwareFallback }
