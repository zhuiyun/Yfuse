package com.yfuse.core2.api

import kotlinx.coroutines.flow.StateFlow

/** Public product-level playback API shared by Legacy and YCore 2.0 implementations. */
interface YPlayer {
    val state: StateFlow<YPlayerState>

    /** Requested intent stays true while a backend is buffering or preparing. */
    val playbackRequested: Boolean get() = state.value.playbackRequested

    /** Legacy engines prepare during construction; Core2 implementations may do real work here. */
    fun prepare() = Unit

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun selectTrack(
        type: YTrackType,
        id: String,
    )

    fun selectItem(index: Int)

    fun currentPositionMs(): Long = state.value.positionMs

    fun retry()

    fun release()
}

/** Construction boundary so the App never needs to know whether Legacy or Core2 is underneath. */
fun interface YPlayerFactory {
    fun create(request: YPlayerOpenRequest): YPlayer
}

data class YPlayerOpenRequest(
    val items: List<YMediaItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
) {
    init {
        require(items.isNotEmpty()) { "YPlayer requires at least one media item" }
        require(startIndex in items.indices) { "startIndex is outside the playback queue" }
        require(startPositionMs >= 0L) { "startPositionMs must be non-negative" }
    }
}

data class YMediaItem(
    val id: String,
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Opaque provider identity; Core2 must not require Emby/Jellyfin-specific models. */
    val providerKey: String? = null,
)

enum class YTrackType {
    Audio,
    Subtitle,
}

data class YTrack(
    val id: String,
    val type: YTrackType,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val selected: Boolean = false,
)

enum class YPlaybackPhase {
    Idle,
    Preparing,
    Ready,
    Ended,
    Failed,
}

enum class YPlaybackRoute {
    /** Existing Exo/mpv/MDK implementation wrapped behind the new product API. */
    Legacy,

    /** Platform demux + hardware codec + tunneled/sideband presentation. */
    NativeTunnel,

    /** Platform demux + hardware codec + direct Surface presentation. */
    NativeDirect,

    /** Custom/FFmpeg demux + normalized bitstream + hardware codec + Surface. */
    NativeEnhanced,

    /** Hardware decode into a GPU path for tone mapping or other required processing. */
    GpuEnhanced,

    /** Software decoder + GPU renderer; last-resort compatibility path. */
    SoftwareFallback,
}

data class YPlayerDiagnostics(
    val route: YPlaybackRoute = YPlaybackRoute.Legacy,
    val demuxer: String = "",
    val decoder: String = "",
    val renderer: String = "",
    val dynamicRange: String = "",
    val videoOutput: String = "",
    val audioOutput: String = "",
    val reason: String? = null,
)

data class YPlayerState(
    val phase: YPlaybackPhase = YPlaybackPhase.Idle,
    val playing: Boolean = false,
    val playbackRequested: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val currentIndex: Int = 0,
    val itemCount: Int = 1,
    val audioTracks: List<YTrack> = emptyList(),
    val subtitleTracks: List<YTrack> = emptyList(),
    val error: String? = null,
    val diagnostics: YPlayerDiagnostics = YPlayerDiagnostics(),
) {
    val hasNext: Boolean get() = currentIndex + 1 < itemCount
    val hasPrevious: Boolean get() = currentIndex > 0
}
