package com.yfuse.core2.api

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import kotlinx.coroutines.flow.StateFlow

/** Public product-level playback API shared by Legacy and YCore 2.0 implementations. */
interface YPlayer {
    val state: StateFlow<YPlayerState>

    /** Requested intent stays true while a backend is buffering or preparing. */
    val playbackRequested: Boolean get() = state.value.playbackRequested

    /** Legacy engines prepare during construction; Core2 implementations may do real work here. */
    fun prepare() = Unit

    /**
     * Attaches or detaches the platform video output.
     *
     * The common API carries only an opaque handle; Android owns the Surface type in its platform
     * adapter. Legacy returns false until its existing surface lifecycle is migrated. Core2 returns
     * true and keeps decode output direct-to-Surface.
     */
    fun setVideoOutput(output: YVideoOutput?): Boolean = false

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun selectTrack(
        type: YTrackType,
        id: String,
    )

    fun selectItem(index: Int)

    fun selectDiscTitle(index: Int): Boolean = false

    fun selectDiscChapter(index: Int): Boolean = false

    fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

    /**
     * Adds entries to the tail without disturbing the active item.
     *
     * Implementations that cannot extend their live queue return false so the product can rebuild
     * at the current item and position. An empty extension is always already satisfied.
     */
    fun appendItems(items: List<YMediaItem>): Boolean = items.isEmpty()

    fun currentPositionMs(): Long = state.value.positionMs

    fun retry()

    fun release()
}

/** Platform-specific video target marker; platform modules own the concrete Surface/texture type. */
interface YVideoOutput

/** Construction boundary so the App never needs to know whether Legacy or Core2 is underneath. */
fun interface YPlayerFactory {
    fun create(request: YPlayerOpenRequest): YPlayer
}

data class YPlayerOpenRequest(
    val items: List<YMediaItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
    val autoNext: Boolean = true,
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
    /** Optional backend-neutral optical-disc descriptor for libbluray/libdvdnav routing. */
    val disc: YDiscMedia? = null,
    /** Optional sidecar subtitle rendered independently above the direct video Surface. */
    val externalSubtitle: YExternalSubtitleSource? = null,
    /** Credential-free identity for YCore-owned persistent media blocks. */
    val cacheIdentity: YCacheIdentity? = null,
    /** Per-item cache budget inherited from the user's playback setting. */
    val cacheMaximumBytes: Long = 0L,
    /** Credential-bearing DRM configuration; its own toString is redacted. */
    val drmConfiguration: PlaybackDrmConfiguration? = null,
) {
    init {
        require(cacheMaximumBytes >= 0L)
    }
}

data class YExternalSubtitleSource(
    val uri: String,
    val language: String? = null,
    val format: YSubtitleFormat? = null,
) {
    init {
        require(uri.isNotBlank()) { "External subtitle URI must not be blank" }
        require(format?.standaloneTextSupported != false) {
            "External bitmap or packet subtitles require a dedicated sidecar decoder"
        }
    }
}

data class YDiscMedia(
    val kind: YDiscKind,
    val container: String? = null,
    val label: String? = null,
)

enum class YDiscKind {
    Iso,
    Dvd,
    BluRay,
    Bdmv,
    Unknown,
}

/** Returns an extended queue, or null when appending would make item identity ambiguous. */
internal fun List<YMediaItem>.appendingDistinct(items: List<YMediaItem>): List<YMediaItem>? {
    if (items.isEmpty()) return this
    val ids = mapTo(mutableSetOf(), YMediaItem::id)
    if (items.any { item -> !ids.add(item.id) }) return null
    return this + items
}

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

/**
 * Machine-readable recovery domain. UI text must never be parsed to decide whether a backend,
 * credential, network route or media source should be penalized.
 */
enum class YPlaybackFailureCategory {
    Authorization,
    Drm,
    Network,
    Container,
    Decoder,
    Renderer,
    AudioSink,
    Unknown,
}

enum class YPlaybackRoute {
    /** Existing Exo/mpv/MDK implementation wrapped behind the new product API. */
    Legacy,

    /** Platform demux + hardware codec + tunneled/sideband presentation. */
    NativeTunnel,

    /** Platform demux + hardware codec + direct Surface presentation. */
    NativeDirect,

    /** Custom/FFmpeg demux + normalized compressed bitstream + hardware codec + Surface. */
    NativeEnhanced,

    /** Hardware decode into a GPU path for tone mapping or other required processing. */
    GpuEnhanced,

    /** Software decoder + GPU renderer; last-resort compatibility path. */
    SoftwareFallback,
}

data class YPlayerDiagnostics(
    val route: YPlaybackRoute = YPlaybackRoute.Legacy,
    val container: String = "",
    val demuxer: String = "",
    val decoder: String = "",
    val renderer: String = "",
    val videoCodec: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val audioCodec: String = "",
    val bitrateBitsPerSecond: Long = 0L,
    val droppedFrames: Int = 0,
    /** True only when the active backend exposes a real renderer counter. */
    val droppedFramesMeasured: Boolean = false,
    val codecResetCount: Int = 0,
    val audioUnderrunCount: Int = 0,
    /** Source/track metadata. Never use this field alone as proof of active HDR/DV output. */
    val dynamicRange: String = "",
    val videoOutput: String = "",
    val audioOutput: String = "",
    /** Backend-observed frame/sample output, independent from the human-readable labels above. */
    val videoOutputVerified: Boolean = false,
    val audioOutputVerified: Boolean = false,
    /** Verified active output claims; source metadata must not set these booleans. */
    val dolbyVisionOutput: Boolean = false,
    val dolbyAtmosOutput: Boolean = false,
    /** Video presentation timestamp minus the active audio/master clock. */
    val avSyncOffsetMs: Long? = null,
    val avSyncMeasured: Boolean = avSyncOffsetMs != null,
    val avSyncMeasurement: String = "当前内核不可测",
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
    /** Buffered Core2 cues; presentation applies the user subtitle delay against [positionMs]. */
    val subtitleCues: List<YSubtitleCue> = emptyList(),
    val discNavigation: PlaybackDiscNavigationState = PlaybackDiscNavigationState(),
    val error: String? = null,
    val errorCategory: YPlaybackFailureCategory? = null,
    val diagnostics: YPlayerDiagnostics = YPlayerDiagnostics(),
) {
    val hasNext: Boolean get() = currentIndex + 1 < itemCount
    val hasPrevious: Boolean get() = currentIndex > 0
}
