package com.yfuse.core2.api

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
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

    fun selectDiscAngle(index: Int): Boolean = false

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
    /** All selectable sidecars. The singular field remains for source compatibility. */
    val externalSubtitles: List<YExternalSubtitleSource> = emptyList(),
    /** Credential-free identity for YCore-owned persistent media blocks. */
    val cacheIdentity: YCacheIdentity? = null,
    /** Per-item cache budget inherited from the user's playback setting. */
    val cacheMaximumBytes: Long = 0L,
    /** Credential-bearing DRM configuration; its own toString is redacted. */
    val drmConfiguration: PlaybackDrmConfiguration? = null,
    /**
     * Backend-neutral source facts supplied by the server/UI layer.
     *
     * These are hints, not decoder proof. Core2 uses them to decide when a local deep probe is
     * mandatory (notably extension-less remote Matroska Dolby Vision URLs), but it never claims
     * active Dolby output from these values alone.
     */
    val sourceHints: YMediaSourceHints? = null,
    /** In-memory source credentials forwarded only to the selected YCore transport. */
    val transportCredentials: YTransportCredentials? = null,
) {
    init {
        require(cacheMaximumBytes >= 0L)
        require(allExternalSubtitles.distinctBy { it.uri }.size == allExternalSubtitles.size) {
            "External subtitle URIs must be unique"
        }
    }

    val allExternalSubtitles: List<YExternalSubtitleSource>
        get() = listOfNotNull(externalSubtitle).plus(externalSubtitles).distinctBy { it.uri }
}

/**
 * Rejects an EOS that is materially earlier than a trustworthy declared duration.
 *
 * Container durations can be imprecise, especially for short clips and variable-frame-rate
 * media, so the guard deliberately ignores short items and allows both a fixed and proportional
 * tail tolerance. Its job is to catch truncation, not to demand frame-exact duration matching.
 */
internal fun isPrematurePlaybackEnd(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    if (durationMs < MIN_END_VALIDATION_DURATION_MS) return false
    val safePositionMs = positionMs.coerceAtLeast(0L)
    val toleranceMs =
        maxOf(MIN_END_TOLERANCE_MS, durationMs / END_TOLERANCE_DIVISOR)
            .coerceAtMost(MAX_END_TOLERANCE_MS)
    return durationMs - safePositionMs > toleranceMs
}

data class YMediaSourceHints(
    val container: String? = null,
    /** Server-confirmed overall source bitrate used to size NativeDirect compressed read-ahead. */
    val bitrateBitsPerSecond: Long = 0L,
    val videoCodec: String? = null,
    /** Server-declared audio codec label; probe truth remains authoritative for decoder setup. */
    val audioCodec: String? = null,
    /** Server metadata fallback for containers whose extractor track omits audio geometry. */
    val audioChannelCount: Int = 0,
    val audioSampleRateHz: Int = 0,
    /** Distinguishes a genuinely silent source from a demuxer that lost a declared audio stream. */
    val audioTrackCount: Int = 0,
    /**
     * Server-declared codec identifiers of every audio track, in server order.
     *
     * Diagnostics only: when a demuxer hides a declared track this names what it hid, which is
     * the difference between "container error" and "TrueHD is unsupported here" in a bundle.
     */
    val audioCodecs: List<String> = emptyList(),
    val dynamicRange: String? = null,
    val dolbyVision: Boolean = false,
    val dolbyVisionProfile: Int? = null,
    val dolbyRpuPresent: Boolean? = null,
    val dolbyEnhancementLayerPresent: Boolean? = null,
    val dolbyBaseLayerPresent: Boolean? = null,
    val dolbyBaseLayerCompatibilityId: Int? = null,
) {
    init {
        require(bitrateBitsPerSecond >= 0L)
        require(audioChannelCount >= 0)
        require(audioSampleRateHz >= 0)
        require(audioTrackCount >= 0)
    }
}

data class YExternalSubtitleSource(
    val uri: String,
    val language: String? = null,
    val format: YSubtitleFormat? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
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

private const val MIN_END_VALIDATION_DURATION_MS = 60_000L
private const val MIN_END_TOLERANCE_MS = 15_000L
private const val MAX_END_TOLERANCE_MS = 60_000L
private const val END_TOLERANCE_DIVISOR = 50L

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

/**
 * Machine-readable Dolby audio result for the active sink.
 *
 * The source codec, compatible carrier and verified object-audio output are deliberately separate:
 * Android can advertise a TrueHD carrier without exposing evidence that an attached receiver
 * rendered the Atmos extension. Spatialized PCM is also kept distinct from encoded passthrough so
 * mobile/headphone playback can be reported truthfully without pretending to be HDMI bitstream.
 */
enum class YDolbyAtmosOutputMode {
    None,

    /** An immersive source is flowing through a compatible carrier, but object output is unproven. */
    CarrierOnly,

    /** The active AudioTrack route accepts the format-specific E-AC-3 JOC encoding. */
    Eac3JocPassthrough,

    /** TrueHD is flowing, but the active sink has not supplied independent Atmos evidence. */
    TrueHdCarrierPassthrough,

    /** TrueHD Atmos has independent active-sink evidence in addition to the TrueHD carrier. */
    TrueHdAtmosPassthrough,

    /** An Atmos source was decoded to PCM and Android's format-specific Spatializer is active. */
    AtmosSourceSpatializedPcm,
    ;

    val verifiedAtmosOutput: Boolean
        get() =
            this == Eac3JocPassthrough ||
                this == TrueHdAtmosPassthrough ||
                this == AtmosSourceSpatializedPcm

    val encodedPassthrough: Boolean
        get() = this == Eac3JocPassthrough || this == TrueHdAtmosPassthrough
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
    /** Bounded compressed queue owned by the source/demux worker, not codec buffers. */
    val sourceQueueBytes: Long = 0L,
    val sourceBufferedMs: Long = 0L,
    /** Number of non-EOF polls that found the read-ahead queue empty. */
    val sourceStarvationCount: Long = 0L,
    /** Smoothed source-read throughput; zero means the active route cannot measure it. */
    val networkBitsPerSecond: Long = 0L,
    /** Source/track metadata. Never use this field alone as proof of active HDR/DV output. */
    val dynamicRange: String = "",
    val videoOutput: String = "",
    val audioOutput: String = "",
    /** Monotonic playback-session generation for current physical output evidence. */
    val outputEvidenceGeneration: Long = 0L,
    val outputEvidenceResetReason: YOutputEvidenceResetReason = YOutputEvidenceResetReason.Initial,
    /** Backend-observed frame/sample output, independent from the human-readable labels above. */
    val videoOutputVerified: Boolean = false,
    val audioOutputVerified: Boolean = false,
    /** Verified active output claims; source metadata must not set these booleans. */
    val dolbyVisionOutput: Boolean = false,
    /** A rendered native-DV frame followed an access unit that actually carried a P7 RPU. */
    val dolbyVisionRpuApplied: Boolean = false,
    /** P7 enhancement-layer NALs reached the exact Dolby decoder; this is not composition proof. */
    val dolbyVisionEnhancementLayerDelivered: Boolean = false,
    /** Independent output evidence only. Merely detecting or delivering EL must leave this false. */
    val dolbyVisionFelComposed: Boolean = false,
    /** A compatible object-audio carrier is flowing; this alone is not verified Atmos output. */
    val immersiveAudioCarrierOutput: Boolean = false,
    /** The selected source track was positively identified as E-AC-3 JOC or TrueHD Atmos. */
    val dolbyAtmosSourceDetected: Boolean = false,
    /** Exact result for the active sink; use this instead of parsing [audioOutput]. */
    val dolbyAtmosOutputMode: YDolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
    /** Active AudioTrack route label, redacted to device type/product name only. */
    val audioOutputRoute: String = "",
    /** True only after AudioTrack reports a routed device while its clock is advancing. */
    val audioOutputRouteVerified: Boolean = false,
    val dolbyAtmosOutput: Boolean = false,
    /** Android's format-specific system Spatializer is active on the decoded PCM sink. */
    val spatialAudioOutput: Boolean = false,
    /** A head tracker is available for the active system-spatialized PCM route. */
    val headTrackingAvailable: Boolean = false,
    /** Video presentation timestamp minus the active audio/master clock. */
    val avSyncOffsetMs: Long? = null,
    val avSyncMeasured: Boolean = avSyncOffsetMs != null,
    val avSyncMeasurement: String = "当前内核不可测",
    val reason: String? = null,
    /** Rebuffer transitions after first output; startup buffering is excluded. */
    val bufferEvents: Int = 0,
) {
    /**
     * True only when one active playback session has independently verified native Dolby Vision
     * video output and encoded Dolby Atmos audio output. Source metadata must never set this.
     */
    val nativeDualDolbyOutput: Boolean
        get() =
            videoOutputVerified &&
                audioOutputVerified &&
                dolbyVisionOutput &&
                dolbyAtmosOutput

    /**
     * iOS-style presentation parity: verified Dolby Vision plus either encoded Atmos output or an
     * Atmos source actively rendered through Android's format-specific PCM Spatializer.
     */
    val nativeDualDolbyPresentationOutput: Boolean
        get() =
            videoOutputVerified &&
                audioOutputVerified &&
                dolbyVisionOutput &&
                dolbyAtmosSourceDetected &&
                dolbyAtmosOutputMode.verifiedAtmosOutput
}

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
