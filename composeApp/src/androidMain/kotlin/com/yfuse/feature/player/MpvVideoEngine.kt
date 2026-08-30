package com.yfuse.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDolbyVisionPath
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.bluRayDiscRoot
import com.yfuse.core.playback.cachedLocalPlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind
import com.yfuse.core.playback.mpvBufferProfile
import com.yfuse.core.playback.playbackDolbyVisionRoute
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "YfusePlayer"

internal data class MpvScaleModeProperties(
    val panscan: Double,
    val keepAspect: Boolean,
)

internal fun mpvScaleModeProperties(mode: VideoScaleMode): MpvScaleModeProperties =
    MpvScaleModeProperties(
        panscan = if (mode == VideoScaleMode.Fill) 1.0 else 0.0,
        keepAspect = mode != VideoScaleMode.Stretch,
    )

internal fun externalSubtitleMpvCommand(item: PlayerMediaItem): Array<String>? {
    return externalSubtitleMpvCommands(item).firstOrNull()
}

internal fun externalSubtitleMpvCommands(item: PlayerMediaItem): List<Array<String>> =
    item.playbackExternalSubtitles().map { subtitle ->
        val language = subtitle.language?.takeIf(String::isNotBlank)
        val selection = if (subtitle.default) "select" else "auto"
        if (language == null) {
            arrayOf("sub-add", subtitle.uri, selection, subtitle.label)
        } else {
            arrayOf("sub-add", subtitle.uri, selection, subtitle.label, language)
        }
    }

internal fun mpvAudioOutputReadiness(
    outputDriver: String?,
    outputFormat: String?,
): PlaybackOutputReadiness {
    val driver = outputDriver?.trim().orEmpty()
    val format = outputFormat?.trim().orEmpty()
    return if (
        driver.isNotEmpty() &&
        !driver.equals("null", ignoreCase = true) &&
        format.isNotEmpty()
    ) {
        PlaybackOutputReadiness.Rendering
    } else {
        PlaybackOutputReadiness.Waiting
    }
}

internal fun mpvDecoderDiagnostic(hwdecCurrent: String?): String =
    hwdecCurrent
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("no", ignoreCase = true) }
        ?.let { "硬件解码 · $it" }
        ?: "FFmpeg 软件解码"

internal fun String.mpvPixelFormatBitDepth(): Int =
    lowercase().let { format ->
        when {
            format.isBlank() -> 0
            format.startsWith("p016") || "p16" in format || format in setOf("rgb48", "rgba64") -> 16
            format.startsWith("p014") || "p14" in format -> 14
            format.startsWith("p012") || "p12" in format -> 12
            format.startsWith("p010") || "p10" in format -> 10
            format.startsWith("p009") || "p9" in format -> 9
            else -> 8
        }
    }

internal fun mpvDolbyVisionVideoFilter(stripToBaseLayer: Boolean): String =
    if (stripToBaseLayer) {
        "format=dolbyvision=no:enhancement-layer=no"
    } else {
        ""
    }

/**
 * Native optical-disc URLs are explicit so Blu-ray always starts on the main feature instead of
 * relying on whichever playlist libbluray happens to expose first. mpv documents `bd://longest`
 * as the longest Blu-ray playlist and uses the same `edition` property for later title changes.
 */
internal fun mpvDiscPlaybackUrl(kind: PlaybackDiscKind): String? =
    when (kind) {
        PlaybackDiscKind.Dvd -> "dvd://"
        PlaybackDiscKind.BluRay,
        PlaybackDiscKind.Bdmv,
        -> "bd://longest"
        else -> null
    }

/**
 * libmpv behind the engine-agnostic [VideoEngine] contract.
 *
 * mpv owns decoding and rendering, so the engine owns the `MPVLib` handle and
 * [MpvSurface] only hands it a `Surface`. State comes from observed properties
 * rather than polling — mpv pushes `time-pos`, `duration`, `pause` and friends
 * on its own thread, which is safe because [MutableStateFlow] is thread-safe.
 */
class MpvVideoEngine(
    private val context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    private val startPositionMs: Long,
    startPlaybackRequested: Boolean,
    private val startSpeed: Float,
    private val decoderMode: DecoderMode,
    private val optimizationMode: PlaybackOptimizationMode,
    private val autoNext: Boolean,
    private val customUserAgent: String,
    private val scope: CoroutineScope,
    private val stopEncoding: suspend (String) -> Boolean = { true },
    private val dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities =
        PlaybackDolbyVisionRuntimeCapabilities.conservative(),
    private val videoCacheBytes: Long = 0L,
) : VideoEngine {
    private val items = items
    private val outputPreferences = GlobalContext.get().get<PlaybackPreferences>()
    private val audioPassthroughMode = outputPreferences.audioPassthrough.value.toPlayerMode()
    private val frameRateMatchMode = outputPreferences.frameRateMatch.value.toPlayerMode()
    private val capabilityProvider =
        runCatching { GlobalContext.get().get<PlaybackDeviceCapabilitiesProvider>() }.getOrNull()

    /** Entries pushed off their original file onto the server's transcode, and past that
     *  onto its progressive MP4. Kept per index so one bad episode doesn't transcode the
     *  rest of the season. */
    private val transcodedIndices =
        items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
            index.takeIf { item.startsWithServerTranscode() }
        }
    private val progressiveIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private var fallbackJob: Job? = null
    private var audioRouteJob: Job? = null
    private var fileLoadWatchdogJob: Job? = null
    private val fileLoadAttempt = AtomicLong(0L)
    private val fileLoadStartedAtMs = AtomicLong(-1L)
    private val fileLoadLastProgressMs = AtomicLong(-1L)
    private val endFileTracker = MpvEndFileTracker()
    private val networkProxy =
        runCatching {
            AndroidPlaybackHttpProxy(
                context = context.applicationContext,
                userAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
            )
        }.onFailure { error ->
            AppLog.warning(
                category = "player.mpv.network",
                event = "platform_proxy_unavailable",
                message = "Could not start the Android platform transport bridge",
                throwable = error,
            )
        }.getOrNull()
    private val surfaceGeneration = AtomicLong(0L)
    private val surfaceRecoveryAttempts = AtomicLong(0L)
    private val surfaceRecoveryInProgress = AtomicBoolean(false)
    private var decoderDroppedFrames = 0
    private var rendererDroppedFrames = 0

    @Volatile
    private var activeDolbyVisionPath = PlaybackDolbyVisionPath.None

    private fun resetFrameEvidence() {
        decoderDroppedFrames = 0
        rendererDroppedFrames = 0
    }

    @Volatile
    private var pauseAtEndOfCurrentItem = false

    private val _state =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex,
                itemCount = items.size.coerceAtLeast(1),
                speed = startSpeed,
                transcoding = startIndex in transcodedIndices,
                discNavigation =
                    items.getOrNull(startIndex).initialDiscNavigation(startIndex in transcodedIndices),
                videoHeight =
                    items
                        .getOrNull(startIndex)
                        ?.sourceVideoHeight(startIndex in transcodedIndices)
                        ?: 0,
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "libmpv",
                        decoder = decoderMode.label,
                        item = items.getOrNull(startIndex),
                    ).copy(
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1L,
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                audioConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var mpv: MPVLib? = null

    @Volatile
    private var released = false

    @Volatile
    private var playRequested = startPlaybackRequested

    override val playbackRequested: Boolean
        get() = playRequested && !_state.value.ended

    @Volatile
    private var attachedSurface: Surface? = null

    @Volatile
    private var pendingSeekMs = startPositionMs.coerceAtLeast(0L)

    private var lastPositionMs = -PLAYBACK_PROGRESS_STEP_MS
    private var wasBuffering = true

    private val logObserver =
        object : MPVLib.LogObserver {
            override fun logMessage(
                prefix: String,
                level: Int,
                text: String,
            ) {
                // Native open/probe log traffic is a useful heartbeat before cache properties exist.
                // It extends only the stall timer; the policy hard limit can never be extended.
                markFileLoadProgress()
                if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) return
                val details = text.trim().take(600)
                if (details.isEmpty()) return
                if (isNativeSurfaceLossFailure(details) && recoverVideoSurface(details)) return
                nativePlaybackLogFailure(details)?.let(::markTerminalFailure)
                val attributes =
                    mapOf(
                        "prefix" to prefix,
                        "level" to level.toString(),
                        "details" to details,
                    )
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                    AppLog.error(
                        category = "player.mpv.native",
                        event = "native_error",
                        message = "libmpv reported an error",
                        attributes = attributes,
                    )
                } else {
                    AppLog.warning(
                        category = "player.mpv.native",
                        event = "native_warning",
                        message = "libmpv reported a warning",
                        attributes = attributes,
                    )
                }
            }
        }

    private val observer =
        object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {
                if (property == "hwdec-current") {
                    _state.update {
                        it.copy(
                            diagnostics =
                                it.diagnostics.copy(
                                    decoder = mpvDecoderDiagnostic(null),
                                ),
                        )
                    }
                }
            }

            override fun eventProperty(
                property: String,
                value: Long,
            ) {
                when (property) {
                    "track-list/count" -> readTracks()
                    "editions", "current-edition", "chapters", "chapter" ->
                        readDiscNavigation()
                    "video-params/h" -> _state.update { it.copy(videoHeight = value.toInt()) }
                    "video-params/w" ->
                        _state.update {
                            it.copy(diagnostics = it.diagnostics.copy(videoWidth = value.toInt()))
                        }
                    "audio-params/channel-count" ->
                        _state.update {
                            val codec =
                                it.diagnostics.audioFormat
                                    .substringBefore(" · ")
                                    .takeIf(String::isNotBlank)
                            val channels =
                                when (value.toInt()) {
                                    1 -> "单声道"
                                    2 -> "2.0"
                                    6 -> "5.1"
                                    8 -> "7.1"
                                    else -> "${value.toInt()} 声道"
                                }
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        audioFormat = listOfNotNull(codec, channels).joinToString(" · "),
                                    ),
                            )
                        }
                    "decoder-frame-drop-count", "vo-drop-frame-count" ->
                        _state.update {
                            if (property == "decoder-frame-drop-count") {
                                decoderDroppedFrames = value.toInt().coerceAtLeast(0)
                            } else {
                                rendererDroppedFrames = value.toInt().coerceAtLeast(0)
                            }
                            val total = decoderDroppedFrames + rendererDroppedFrames
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        droppedFrames = total,
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(
                                                droppedFramesMeasured = true,
                                            ),
                                    ),
                            )
                        }
                    "mistimed-frame-count" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(
                                                mistimedFrameCount = value.toInt().coerceAtLeast(0),
                                            ),
                                    ),
                            )
                        }
                }
            }

            override fun eventProperty(
                property: String,
                value: Double,
            ) {
                when (property) {
                    "time-pos" -> {
                        val ms = (value * 1000).toLong().coerceAtLeast(0L)
                        if (kotlin.math.abs(ms - lastPositionMs) < PLAYBACK_PROGRESS_STEP_MS) return
                        lastPositionMs = ms
                        _state.update {
                            it.copy(
                                positionMs = ms,
                                bufferedPositionMs =
                                    bufferedEndPositionMs(
                                        positionMs = ms,
                                        durationMs = it.durationMs,
                                        bufferedDurationMs = it.diagnostics.bufferedDurationMs,
                                    ),
                            )
                        }
                    }

                    "duration" -> {
                        val durationMs = (value * 1000).toLong()
                        if (durationMs > 0L) {
                            _state.update { it.copy(durationMs = durationMs) }
                        }
                    }
                    "speed" -> _state.update { it.copy(speed = value.toFloat()) }
                    "estimated-vf-fps" ->
                        _state.update {
                            val rate = value.toFloat()
                            attachedSurface?.let { surface ->
                                requestSurfaceFrameRate(surface, frameRateMatchMode, rate)
                            }
                            it.copy(diagnostics = it.diagnostics.copy(frameRate = rate))
                        }
                    "display-fps", "estimated-display-fps" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(
                                                displayRefreshRate = value.toFloat().coerceAtLeast(0f),
                                            ),
                                    ),
                            )
                        }
                    "video-bitrate" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        bitrateBitsPerSecond = value.toLong().coerceAtLeast(0L),
                                    ),
                            )
                        }
                    "cache-speed" -> {
                        markFileLoadProgress()
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        networkBitsPerSecond = (value * 8.0).toLong().coerceAtLeast(0L),
                                    ),
                            )
                        }
                    }
                    "demuxer-cache-duration" -> {
                        markFileLoadProgress()
                        _state.update {
                            val bufferedDurationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                            it.copy(
                                bufferedPositionMs =
                                    bufferedEndPositionMs(
                                        positionMs = it.positionMs,
                                        durationMs = it.durationMs,
                                        bufferedDurationMs = bufferedDurationMs,
                                    ),
                                diagnostics =
                                    it.diagnostics.copy(
                                        bufferedDurationMs = bufferedDurationMs,
                                    ),
                            )
                        }
                    }
                    "avsync" ->
                        if (value.isFinite()) {
                            _state.update {
                                it.copy(
                                    diagnostics =
                                        it.diagnostics.copy(
                                            avSyncOffsetMs =
                                                (value * 1_000.0)
                                                    .toLong()
                                                    .coerceIn(
                                                        -MAX_MPV_REPORTED_AV_SYNC_OFFSET_MS,
                                                        MAX_MPV_REPORTED_AV_SYNC_OFFSET_MS,
                                                    ),
                                            avSyncMeasurement = "mpv 音视频时钟",
                                            outputEvidence =
                                                it.diagnostics.outputEvidence.copy(
                                                    avSyncMeasured = true,
                                                ),
                                        ),
                                )
                            }
                        }
                }
            }

            override fun eventProperty(
                property: String,
                value: Boolean,
            ) {
                when (property) {
                    "pause" -> {
                        playRequested = !value
                        _state.update { it.copy(playing = !value) }
                    }
                    "paused-for-cache" -> {
                        val bufferEvent = value && !wasBuffering
                        wasBuffering = value
                        _state.update {
                            it.copy(
                                buffering = value,
                                diagnostics =
                                    it.diagnostics.copy(
                                        bufferEvents =
                                            it.diagnostics.bufferEvents + if (bufferEvent) 1 else 0,
                                    ),
                            )
                        }
                    }
                    // keep-open=always parks mpv on the last frame instead of
                    // advancing, so the queue is stepped by hand.
                    "eof-reached" ->
                        when {
                            !value -> _state.update { it.copy(ended = false) }
                            pauseAtEndOfCurrentItem -> {
                                playRequested = false
                                _state.update { it.copy(playing = false, buffering = false, ended = true) }
                            }
                            autoNext && _state.value.hasNext -> playNextIfAny()
                            else -> _state.update { it.copy(playing = false, buffering = false, ended = true) }
                        }
                    "vo-configured" ->
                        if (value) {
                            readVideoOutput()
                        } else {
                            _state.update {
                                it.copy(
                                    diagnostics =
                                        it.diagnostics.copy(
                                            videoOutput = "等待 mpv 视频输出",
                                            videoReadiness = PlaybackOutputReadiness.Waiting,
                                            dolbyVisionOutput = false,
                                        ),
                                )
                            }
                        }
                }
            }

            override fun eventProperty(
                property: String,
                value: String,
            ) {
                // aid/sid are read as strings because either can be "no".
                when (property) {
                    "aid", "sid" -> readTracks()
                    "video-codec" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        videoCodec = value,
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(
                                                videoCodecProfile = value,
                                            ),
                                    ),
                            )
                        }
                    "hwdec-current" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        decoder = mpvDecoderDiagnostic(value),
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(
                                                videoDecoder = mpvDecoderDiagnostic(value),
                                            ),
                                    ),
                            )
                        }
                    "video-params/gamma" ->
                        _state.update {
                            it.copy(diagnostics = it.diagnostics.copy(dynamicRange = mpvDynamicRange(value)))
                        }
                    "audio-codec-name" ->
                        _state.update {
                            val channels =
                                it.diagnostics.audioFormat
                                    .substringAfter(" · ", "")
                                    .takeIf(String::isNotBlank)
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        audioFormat = listOfNotNull(value.uppercase(), channels).joinToString(" · "),
                                        outputEvidence =
                                            it.diagnostics.outputEvidence.copy(audioDecoder = value),
                                    ),
                            )
                        }
                    "current-vo", "current-gpu-context", "video-out-params/gamma",
                    "video-out-params/pixelformat",
                    -> readVideoOutput()
                }
            }

            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                        surfaceRecoveryAttempts.set(0L)
                        markFileLoadProgress()
                        _state.update {
                            it.copy(
                                buffering = true,
                                bufferedPositionMs = it.positionMs,
                                error = null,
                                ended = false,
                                diagnostics = it.diagnostics.copy(bufferedDurationMs = 0L),
                            )
                        }
                    }

                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        cancelFileLoadWatchdog()
                        val seekMs = pendingSeekMs
                        pendingSeekMs = -1L
                        if (seekMs > 0L) {
                            withMpv {
                                it.command(
                                    arrayOf(
                                        "seek",
                                        (seekMs / 1000.0).toString(),
                                        "absolute",
                                    ),
                                )
                            }
                        }
                        _state.update {
                            it.copy(
                                buffering = false,
                                fallbacksExhausted = false,
                                automaticFallbackBlocked = false,
                            )
                        }
                        items
                            .getOrNull(_state.value.currentIndex)
                            ?.let(::externalSubtitleMpvCommands)
                            .orEmpty()
                            .forEach { command -> withMpv { it.command(command) } }
                        readTracks()
                        readDiscNavigation()
                        readVideoSize()
                        readVideoOutput()
                        logAudioOutput()
                        val source = items.getOrNull(_state.value.currentIndex)?.activeVersion
                        val detectedVideoCodec =
                            runCatching { mpv?.getPropertyString("video-codec") }.getOrNull()
                        val detectedDecoder =
                            runCatching { mpv?.getPropertyString("hwdec-current") }.getOrNull()
                        val detectedDynamicRange =
                            runCatching { mpv?.getPropertyString("video-params/gamma") }.getOrNull()
                        AppLog.info(
                            category = "player.mpv",
                            event = "file_loaded",
                            message = "mpv loaded the selected media",
                            attributes =
                                mapOf(
                                    "itemIndex" to _state.value.currentIndex.toString(),
                                    "resumePositionMs" to seekMs.coerceAtLeast(0L).toString(),
                                    "videoCodec" to (detectedVideoCodec ?: source?.sourceVideoCodec.orEmpty()),
                                    "decoder" to (detectedDecoder ?: "software"),
                                    "dynamicRange" to (detectedDynamicRange ?: source?.sourceDynamicRange.orEmpty()),
                                    "dolbyProfile" to (source?.dolbyProfile?.toString() ?: "unknown"),
                                    "sourceAtmos" to (source?.dolbyAtmos == true).toString(),
                                    "surfaceGeneration" to surfaceGeneration.get().toString(),
                                    "surfaceValid" to (attachedSurface?.isValid == true).toString(),
                                    "platformProxy" to
                                        (networkProxy != null && shouldProxyMpvNetworkUrl(currentUrl())).toString(),
                                ),
                        )
                    }

                    MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                        readVideoSize()
                        readVideoOutput()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> logAudioOutput()
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART ->
                        AppLog.info(
                            category = "player.mpv",
                            event = "playback_started",
                            message = "mpv restarted media playback",
                            attributes =
                                mapOf(
                                    "itemIndex" to _state.value.currentIndex.toString(),
                                ),
                        )
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                        if (!endFileTracker.consumeExpectedEnd()) handleEndFile()
                    }
                }
            }
        }

    /**
     * Creates mpv on the first surface and re-attaches to the existing instance
     * afterwards, so a surface teardown (rotation, backgrounding) keeps the
     * playback position.
     */
    fun attach(surface: Surface) {
        if (released) return
        attachedSurface = surface
        val generation = surfaceGeneration.incrementAndGet()
        AppLog.info(
            category = "player.mpv",
            event = "surface_attached",
            message = "mpv received an Android video surface",
            attributes =
                mapOf(
                    "generation" to generation.toString(),
                    "valid" to surface.isValid.toString(),
                ),
        )
        mpv?.let { existing ->
            runCatching {
                existing.attachSurface(surface)
                existing.setPropertyString("force-window", "yes")
                existing.setPropertyString("vo", "gpu")
            }.onFailure {
                safeLogcat(Log.ERROR, TAG, "mpv re-attach failed", it)
                AppLog.error(
                    category = "player.mpv",
                    event = "surface_reattach_failed",
                    message = "mpv surface re-attach failed",
                    throwable = it,
                )
                markTerminalFailure(
                    fallbackMessage = "mpv 无法重新连接视频画面，正在尝试其他播放器",
                    details = it.message,
                )
            }
            return
        }

        var created: MPVLib? = null
        runCatching {
            val instance = MPVLib.create(context)
            if (instance == null) {
                safeLogcat(Log.ERROR, TAG, "MPVLib.create returned null")
                AppLog.error(
                    category = "player.mpv",
                    event = "initialization_failed",
                    message = "MPVLib.create returned null",
                )
                markTerminalFailure("无法初始化 mpv，正在尝试其他播放器")
                return
            }
            created = instance
            // Don't read the user's mpv config from disk.
            instance.requireOption("config", "no")
            instance.requireOption("vo", "gpu")
            instance.requireOption("gpu-context", "android")
            instance.requireOption(
                "hwdec",
                when (decoderMode) {
                    DecoderMode.Hardware -> "auto-safe"
                    DecoderMode.Software -> "no"
                    DecoderMode.Auto -> "auto"
                },
            )
            // libmpv otherwise has a race where it can shut down before the
            // Surface callback gets a chance to issue the first loadfile.
            instance.requireOption("idle", "yes")
            instance.requireOption("keep-open", "always")
            instance.requireOption("cache", "yes")
            val hugeRemoteSource =
                items.any { item ->
                    val version = item.activeVersion
                    version != null &&
                        (version.sourceSizeBytes ?: 0L) >= HUGE_REMOTE_MEDIA_BYTES &&
                        !item.url.startsWith("file://", ignoreCase = true) &&
                        !item.url.startsWith("content://", ignoreCase = true)
                }
            if (hugeRemoteSource) {
                // Keep remote remux/BD seeks bounded while following the user's memory/startup goal.
                val bufferProfile = mpvBufferProfile(optimizationMode)
                instance.optionalOption("demuxer-max-bytes", bufferProfile.forwardBytes.toString())
                instance.optionalOption("demuxer-max-back-bytes", bufferProfile.backBytes.toString())
                instance.optionalOption(
                    "demuxer-readahead-secs",
                    bufferProfile.readaheadSeconds.toString(),
                )
                instance.optionalOption("cache-pause-initial", "yes")
                instance.optionalOption("cache-pause-wait", "1")
            }
            // Every source is already a resolved media URL. Running ytdl after an upstream error
            // only adds misleading failures and delays the real fallback path.
            instance.optionalOption("ytdl", "no")
            // Let mpv select the available Android AO. The actual driver is verified through
            // current-ao, and a failed driver can now fall through mpv's own output selection.
            mpvAudioSpdifOption(audioPassthroughMode, currentDirectAudioFormats())?.let { codecs ->
                instance.optionalOption("audio-spdif", codecs)
            }
            customUserAgent.trim().takeIf { it.isNotEmpty() }?.let { value ->
                instance.requireOption("user-agent", value)
            }
            instance.requireOption("keepaspect", "yes")
            instance.requireOption("panscan", "0")
            // HDR, which mpv otherwise does nothing about.
            //
            // Without these an HDR10 or HLG file is handed to an SDR panel with its PQ
            // curve intact, which is why every HDR film looked washed-out and grey on this
            // engine while ExoPlayer showed it correctly. `target-colorspace-hint` passes
            // the signal through to the display on the devices that can take it (Android 13
            // and up), and the tone-mapping pair is what happens on the ones that cannot.
            //
            // Optional rather than required: which of these exists depends on the libmpv
            // build that `scripts/fetch-engines.sh` fetched, and a missing option is a
            // reason to lose tone mapping, not a reason to fail to start a film.
            instance.optionalOption("target-colorspace-hint", "yes")
            instance.optionalOption("tone-mapping", "bt.2390")
            val highPressureSoftwareDolby =
                decoderMode == DecoderMode.Software &&
                    items.any { item ->
                        item.activeVersion?.let { version ->
                            version.dolbyVision &&
                                ((version.sourceWidth ?: 0) >= 3_000 || (version.sourceHeight ?: 0) >= 1_600)
                        } == true
                    }
            instance.optionalOption("hdr-compute-peak", if (highPressureSoftwareDolby) "no" else "yes")
            // The compatibility GPU tier is a real libplacebo renderer, not a label-only route.
            // Keep these optional because the exact mpv/libplacebo option surface is artifact-bound.
            instance.optionalOption("scale", if (highPressureSoftwareDolby) "bilinear" else "ewa_lanczossharp")
            instance.optionalOption("cscale", if (highPressureSoftwareDolby) "bilinear" else "ewa_lanczossharp")
            instance.optionalOption("deband", if (highPressureSoftwareDolby) "no" else "yes")
            instance.optionalOption("dither-depth", "auto")
            instance.optionalOption("gamut-mapping-mode", "perceptual")
            if (highPressureSoftwareDolby || hugeRemoteSource) {
                AppLog.info(
                    category = "player.dolby",
                    event = "local_resource_guard",
                    message = "mpv enabled bounded local Dolby resource settings",
                    attributes =
                        mapOf(
                            "softwareDolbyFastGpu" to highPressureSoftwareDolby.toString(),
                            "hugeRemoteCache" to hugeRemoteSource.toString(),
                            "decoderMode" to decoderMode.name,
                        ),
                )
            }
            instance.init()

            instance.setPropertyDouble("speed", startSpeed.toDouble())
            instance.setPropertyBoolean("pause", !playRequested)

            mpv = instance
            instance.addObserver(observer)
            instance.addLogObserver(logObserver)
            instance.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("vo-configured", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("editions", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("current-edition", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("chapters", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("video-out-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("video-out-params/pixelformat", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("current-vo", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("current-gpu-context", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("audio-codec-name", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("audio-params/channel-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("display-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("estimated-display-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("video-bitrate", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("cache-speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("avsync", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("decoder-frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("vo-drop-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("mistimed-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("secondary-sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            startAudioRouteMonitoring()

            instance.attachSurface(surface)
            instance.setPropertyString("force-window", "yes")
            check(replaceFile(currentUrl())) { "mpv loadfile command failed" }
            safeLogcat(Log.INFO, TAG, "mpv loadfile issued")
            AppLog.info(
                category = "player.mpv",
                event = "load_requested",
                message = "mpv loadfile command was issued",
                attributes =
                    mapOf(
                        "itemIndex" to _state.value.currentIndex.toString(),
                        "decoderMode" to decoderMode.name,
                        "audioPassthrough" to audioPassthroughMode.toString(),
                    ),
            )
        }.onFailure {
            audioRouteJob?.cancel()
            audioRouteJob = null
            mpv = null
            created?.let { failed ->
                runCatching {
                    failed.removeObserver(observer)
                    failed.removeLogObserver(logObserver)
                    failed.destroy()
                }
            }
            safeLogcat(Log.ERROR, TAG, "mpv start failed", it)
            AppLog.error(
                category = "player.mpv",
                event = "start_failed",
                message = "mpv failed to start playback",
                throwable = it,
                attributes = mapOf("itemIndex" to _state.value.currentIndex.toString()),
            )
            markTerminalFailure(
                fallbackMessage = "mpv 启动失败，正在尝试其他播放器",
                details = it.message,
            )
        }
    }

    fun detach() {
        attachedSurface?.let { surface -> clearSurfaceFrameRate(surface) }
        withMpv {
            // Stop the VO before releasing the Android Surface. Detaching a
            // Surface that the GPU context is still using can race/crash.
            it.setPropertyString("vo", "null")
            it.setPropertyString("force-window", "no")
            it.detachSurface()
        }
        attachedSurface = null
        _state.update {
            it.copy(
                diagnostics =
                    it.diagnostics.copy(
                        videoOutput = "视频 Surface 已释放",
                        videoReadiness = PlaybackOutputReadiness.Released,
                        dolbyVisionOutput = false,
                        outputEvidence =
                            it.diagnostics.outputEvidence.copy(
                                videoReadiness = PlaybackOutputReadiness.Released,
                                videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                                outputDynamicRange = "",
                            ),
                    ),
            )
        }
    }

    /** Keep mpv's Android render target in sync with SurfaceView size changes. */
    fun resize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        withMpv { it.setPropertyString("android-surface-size", "${width}x$height") }
    }

    /** Crop-to-fill instead of letterboxing, for the 全屏 toggle. */
    fun setFill(fill: Boolean) {
        withMpv { it.setPropertyDouble("panscan", if (fill) 1.0 else 0.0) }
    }

    fun setScaleMode(mode: VideoScaleMode) {
        withMpv { instance ->
            val properties = mpvScaleModeProperties(mode)
            instance.setPropertyDouble("panscan", properties.panscan)
            instance.setPropertyString("video-aspect-override", "-1")
            instance.setPropertyBoolean("keepaspect", properties.keepAspect)
        }
    }

    override fun play() {
        playRequested = true
        _state.update { it.copy(ended = false) }
        withMpv { it.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        playRequested = false
        withMpv { it.setPropertyBoolean("pause", true) }
    }

    override fun prepareForHandover() {
        withMpv { instance ->
            instance.setPropertyBoolean("pause", true)
            instance.setPropertyBoolean("mute", true)
            instance.setPropertyString("vo", "null")
        }
    }

    override fun seekTo(positionMs: Long) {
        lastPositionMs = positionMs
        _state.update {
            it.copy(
                positionMs = positionMs,
                bufferedPositionMs = positionMs.coerceAtLeast(0L),
                ended = false,
            )
        }
        withMpv { it.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute")) }
    }

    override fun setSpeed(speed: Float) {
        withMpv { it.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun selectAudioTrack(id: String) = selectTrack("aid", id)

    override val supportsAudioDelay: Boolean = true

    override fun setAudioDelayMs(delayMs: Long): Boolean =
        withMpvResult { instance ->
            instance.setPropertyDouble("audio-delay", delayMs.coerceIn(-10_000L, 10_000L) / 1000.0)
        }

    override val supportsAudioEnhancement: Boolean = true

    override fun setAudioEnhancement(mode: AudioEnhancementMode): Boolean =
        withMpvResult { instance ->
            val filter =
                when (mode) {
                    AudioEnhancementMode.Off -> ""
                    AudioEnhancementMode.VolumeBoost -> "lavfi=[volume=1.5]"
                    AudioEnhancementMode.NightVoice ->
                        "lavfi=[acompressor=threshold=-20dB:ratio=4:attack=5:release=200:makeup=5dB]"
                }
            instance.setPropertyString("af", filter)
            // Audio filters operate on PCM. Never claim/pass an encoded Atmos route while boost or
            // night voice is active; turning the feature off restores the configured safe list.
            instance.setPropertyString(
                "audio-spdif",
                if (mode == AudioEnhancementMode.Off) {
                    mpvAudioSpdifOption(audioPassthroughMode, currentDirectAudioFormats()).orEmpty()
                } else {
                    ""
                },
            )
        }

    override fun selectSubtitleTrack(id: String) = selectTrack("sid", id)

    override fun selectDiscTitle(index: Int): Boolean {
        val count = _state.value.discNavigation.effectiveTitleCount
        if (index !in 0 until count) return false
        return withMpvResult { it.setPropertyInt("edition", index) }
    }

    override fun selectDiscChapter(index: Int): Boolean {
        val count = _state.value.discNavigation.effectiveChapterCount
        if (index !in 0 until count) return false
        return withMpvResult { it.setPropertyInt("chapter", index) }
    }

    override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

    override val supportsSecondarySubtitleTrack: Boolean = true

    override val supportsSubtitleOffset: Boolean = true

    override val supportsSubtitleScale: Boolean = true

    override val supportsSubtitleBrightness: Boolean = true

    override val supportsSubtitlePosition: Boolean = true

    override val supportsSubtitleAppearance: Boolean = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean =
        withMpvResult { instance ->
            if (id == EngineTrack.OFF) {
                instance.setPropertyString("secondary-sid", "no")
            } else {
                val ordinal = id.toIntOrNull() ?: error("Invalid mpv subtitle id")
                instance.setPropertyInt("secondary-sid", ordinal)
            }
        }

    override fun setSubtitleOffsetMs(offsetMs: Long): Boolean {
        val instance = mpv ?: return false
        instance.setPropertyDouble("sub-delay", offsetMs / 1000.0)
        return true
    }

    override fun setSubtitleScale(scale: Float): Boolean =
        withMpvResult { instance ->
            val normalized = scale.coerceIn(0.6f, 1.8f).toDouble()
            instance.setPropertyDouble("sub-scale", normalized)
            instance.setPropertyDouble("secondary-sub-scale", normalized)
        }

    override fun setSubtitleBrightness(brightness: Float): Boolean =
        withMpvResult { instance ->
            val luminance = brightness.coerceIn(MIN_SUBTITLE_BRIGHTNESS, 1f)
            instance.setPropertyString("sub-color", subtitleBrightnessMpvColor(luminance))
            // Embedded ASS colours otherwise bypass sub-color. Only force them while the user
            // has deliberately lowered HDR caption luminance; 100% restores authored styling.
            instance.setPropertyString("sub-ass-override", if (luminance < 0.999f) "force" else "yes")
            instance.setPropertyString(
                "secondary-sub-ass-override",
                if (luminance < 0.999f) "force" else "strip",
            )
        }

    override fun setSubtitlePosition(position: Float): Boolean =
        withMpvResult { instance ->
            val percent = position.coerceIn(0.60f, 0.96f) * 100.0
            instance.setPropertyDouble("sub-pos", percent)
            instance.setPropertyDouble("secondary-sub-pos", percent)
        }

    override fun setSubtitleAppearance(appearance: SubtitleAppearance): Boolean =
        withMpvResult { instance ->
            instance.setPropertyString("sub-color", subtitleArgbMpvColor(appearance.textColorArgb))
            instance.setPropertyString("sub-back-color", subtitleArgbMpvColor(appearance.backgroundColorArgb))
            instance.setPropertyString("sub-border-color", subtitleArgbMpvColor(appearance.outlineColorArgb))
            instance.setPropertyDouble("sub-border-size", appearance.outlineWidth.coerceIn(0f, 6f).toDouble())
            instance.setPropertyString("sub-ass-override", "force")
        }

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        pauseAtEndOfCurrentItem = enabled
    }

    override fun selectItem(index: Int) {
        if (index !in items.indices) return
        resetFrameEvidence()
        playRequested = true
        pendingSeekMs = 0L
        lastPositionMs = -PLAYBACK_PROGRESS_STEP_MS
        val transcoding = index in transcodedIndices
        val nextItem = items.getOrNull(index)
        _state.update {
            val mediaChanged = it.currentIndex != index
            it.copy(
                currentIndex = index,
                positionMs = if (mediaChanged) 0L else it.positionMs,
                durationMs = if (mediaChanged) 0L else it.durationMs,
                bufferedPositionMs = if (mediaChanged) 0L else it.bufferedPositionMs,
                videoHeight = nextItem?.sourceVideoHeight(transcoding) ?: 0,
                buffering = true,
                ended = false,
                error = null,
                transcoding = transcoding,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                discNavigation = nextItem.initialDiscNavigation(transcoding),
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "libmpv",
                        decoder = it.diagnostics.decoder,
                        item = nextItem,
                        transcoding = transcoding,
                    ).copy(
                        outputEvidence =
                            it.diagnostics.outputEvidence.nextSession().copy(
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                audioConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            )
        }
        loadFileOrFail(playbackUrl(items[index], index))
    }

    override fun currentPositionMs(): Long = _state.value.positionMs

    override fun retry() {
        resetFrameEvidence()
        val position = _state.value.positionMs
        playRequested = true
        pendingSeekMs = position.coerceAtLeast(0L)
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = position.coerceAtLeast(0L),
                ended = false,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics =
                    it.diagnostics.copy(
                        videoOutput = "等待 mpv 视频输出",
                        audioOutput = "等待音频输出",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Waiting,
                        dolbyVisionOutput = false,
                        immersiveAudioCarrierOutput = false,
                        dolbyAtmosOutput = false,
                        spatialAudioOutput = false,
                        headTrackingAvailable = false,
                        outputEvidence =
                            it.diagnostics.outputEvidence.nextSession().copy(
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                audioConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            )
        }
        if (mpv == null) {
            attachedSurface?.let(::attach)
            return
        }
        loadFileOrFail(currentUrl())
    }

    override fun release() {
        if (released) return
        released = true
        fallbackJob?.cancel()
        fallbackJob = null
        audioRouteJob?.cancel()
        audioRouteJob = null
        cancelFileLoadWatchdog()
        networkProxy?.close()
        val instance = mpv ?: return
        mpv = null
        runCatching {
            instance.removeObserver(observer)
            instance.removeLogObserver(logObserver)
            instance.command(arrayOf("stop"))
            instance.destroy()
        }.onFailure {
            safeLogcat(Log.WARN, TAG, "mpv teardown failed", it)
            AppLog.warning(
                category = "player.mpv",
                event = "teardown_failed",
                message = "mpv teardown failed",
                throwable = it,
            )
        }
    }

    private fun currentUrl(): String =
        items.getOrNull(_state.value.currentIndex)?.let { playbackUrl(it, _state.value.currentIndex) }
            ?: items.firstOrNull()?.let { playbackUrl(it, 0) }.orEmpty()

    /** Whichever step of the fallback chain [index] has been pushed to so far. */
    private fun playbackUrl(
        item: PlayerMediaItem,
        index: Int,
    ): String =
        when {
            index in progressiveIndices && item.fallbackTranscodeUrl.isNotEmpty() ->
                item.fallbackTranscodeUrl
            index in transcodedIndices && item.transcodeUrl.isNotEmpty() -> item.transcodeUrl
            else -> item.url
        }

    /**
     * Steps the current entry down the chain: original file, then the server's HLS
     * transcode, then its progressive MP4. Returns false once the chain is spent, which is
     * what tells the caller to stop retrying and report the failure.
     */
    override fun switchToTranscode(reason: String?): Boolean {
        val index = _state.value.currentIndex
        resetFrameEvidence()
        val item = items.getOrNull(index) ?: return false
        if (index !in transcodedIndices && !item.allowsServerTranscodeFallback(reason)) return false
        val next =
            when {
                index in progressiveIndices -> return false
                index in progressiveTransitionIndices -> return true
                index in transcodedIndices ->
                    if (item.fallbackTranscodeUrl.isEmpty()) return false else Step.Progressive
                item.transcodeUrl.isEmpty() ->
                    if (item.fallbackTranscodeUrl.isEmpty()) return false else Step.Progressive
                else -> Step.Transcode
            }
        when (next) {
            Step.Transcode -> transcodedIndices += index
            Step.Progressive -> {
                transcodedIndices += index
                progressiveTransitionIndices += index
            }
        }
        // Resume where the failure happened rather than from the top; a codec the device
        // can't handle usually fails on the first frame, but a mid-file failure shouldn't
        // cost the user their place.
        pendingSeekMs = _state.value.positionMs.coerceAtLeast(0L)
        AppLog.info(
            category = "player.mpv",
            event = "transcode_fallback",
            message = "Switching mpv to a server-transcoded stream",
            attributes = mapOf("itemIndex" to index.toString(), "step" to next.name),
        )
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = it.positionMs,
                ended = false,
                transcoding = true,
                discNavigation = PlaybackDiscNavigationState(),
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics =
                    it.diagnostics.copy(
                        playMethod = "服务器转码",
                        dynamicRange = "",
                        audioFormat = "",
                        videoOutput = "等待转码视频输出",
                        audioOutput = "等待转码音频输出",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Waiting,
                        fallbackReason =
                            reason ?: when (next) {
                                Step.Transcode -> "直放失败，已切换服务器转码"
                                Step.Progressive -> "HLS 转码不可用，已改用 MP4 转码"
                            },
                        bufferedDurationMs = 0L,
                        outputEvidence =
                            it.diagnostics.outputEvidence.nextSession().copy(
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                audioConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            )
        }
        if (next == Step.Transcode) {
            loadFileOrFail(currentUrl())
            return true
        }

        // Ensure the HLS request is closed and its ffmpeg has actually exited before a
        // progressive request with the same PlaySessionId is allowed to start.
        stopFileForReplacement()
        fallbackJob?.cancel()
        fallbackJob =
            scope.launch {
                val cleaned =
                    item.playSessionId.isBlank() ||
                        withTimeoutOrNull(5_000L) { stopEncoding(item.playSessionId) } == true
                if (released || _state.value.currentIndex != index) return@launch
                progressiveTransitionIndices -= index
                if (!cleaned) {
                    _state.update {
                        it.copy(
                            error = "无法清理旧的服务器转码，正在尝试其他播放器",
                            buffering = false,
                            fallbacksExhausted = true,
                        )
                    }
                    return@launch
                }
                progressiveIndices += index
                loadFileOrFail(currentUrl())
            }
        return true
    }

    private enum class Step { Transcode, Progressive }

    private fun playNextIfAny() {
        val next = _state.value.currentIndex + 1
        if (next < items.size) selectItem(next)
    }

    private fun handleEndFile() {
        if (_state.value.automaticFallbackBlocked) return
        val reachedEof =
            runCatching {
                _state.value.ended || mpv?.getPropertyBoolean("eof-reached") == true
            }.getOrDefault(false)
        if (reachedEof) return

        safeLogcat(Log.ERROR, TAG, "mpv ended playback before reaching EOF")
        AppLog.error(
            category = "player.mpv",
            event = "playback_failed",
            message = "mpv ended playback before reaching EOF",
            attributes = mapOf("itemIndex" to _state.value.currentIndex.toString()),
        )
        // Try the next stream down before saying it can't be played: the common cause is a
        // codec this device has no decoder for, which the server can transcode away.
        if (switchToTranscode()) return
        markTerminalFailure("mpv 无法播放此媒体，服务器也没有可用的转码流")
    }

    private fun selectTrack(
        property: String,
        id: String,
    ) {
        withMpv { instance ->
            if (id == EngineTrack.OFF) {
                instance.setPropertyString(property, "no")
            } else {
                id.toIntOrNull()?.let { instance.setPropertyInt(property, it) }
            }
        }
    }

    /**
     * Reads `track-list` one entry at a time. mpv exposes the list as a node,
     * which the JNI bridge can't hand back whole, but every field is reachable
     * as `track-list/<n>/<field>`.
     */
    private fun readTracks() {
        val instance = mpv ?: return
        runCatching {
            val count = instance.getPropertyInt("track-list/count") ?: 0
            val selectedAudio = instance.getPropertyString("aid")
            val selectedSubtitle = instance.getPropertyString("sid")
            val audio = mutableListOf<EngineTrack>()
            val subtitles = mutableListOf<EngineTrack>()

            for (i in 0 until count) {
                val type = instance.getPropertyString("track-list/$i/type") ?: continue
                if (type != "audio" && type != "sub") continue
                val id = instance.getPropertyInt("track-list/$i/id") ?: continue
                val language = instance.getPropertyString("track-list/$i/lang")
                val title = instance.getPropertyString("track-list/$i/title")
                val codec = instance.getPropertyString("track-list/$i/codec")
                val bucket = if (type == "audio") audio else subtitles
                bucket +=
                    EngineTrack(
                        id = id.toString(),
                        label =
                            title ?: language
                                ?: "${if (type == "audio") "音轨" else "字幕"} ${bucket.size + 1}",
                        language = language,
                        selected = id.toString() == if (type == "audio") selectedAudio else selectedSubtitle,
                        codec = codec,
                    )
            }

            _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
        }.onFailure {
            safeLogcat(Log.WARN, TAG, "reading track-list failed", it)
            AppLog.warning(
                category = "player.mpv",
                event = "track_list_failed",
                message = "Failed to read mpv track list",
                throwable = it,
            )
        }
    }

    private fun readDiscNavigation() {
        val instance = mpv ?: return
        runCatching {
            val previous = _state.value.discNavigation
            if (previous.kind == PlaybackDiscKind.None) return
            val navigation =
                readMpvDiscNavigationMetadata(
                    previous = previous,
                    properties = instance.discPropertyReader(),
                ).copy(
                    menuSupported = false,
                    menuActive = false,
                )
            _state.update { it.copy(discNavigation = navigation) }
        }.onFailure {
            AppLog.info(
                category = "player.mpv",
                event = "disc_navigation_unavailable",
                message = "The bundled mpv build does not expose optical-disc navigation",
            )
        }
    }

    private fun readVideoSize() {
        withMpv { instance ->
            instance.getPropertyInt("video-params/h")?.let { height ->
                _state.update { it.copy(videoHeight = height) }
            }
        }
    }

    private fun readVideoOutput() {
        val instance = mpv ?: return
        runCatching {
            val outputConfigured = instance.getPropertyBoolean("vo-configured") == true
            if (!outputConfigured) {
                _state.update { state ->
                    state.copy(
                        diagnostics =
                            state.diagnostics.copy(
                                videoOutput = "等待 mpv 视频输出",
                                videoReadiness = PlaybackOutputReadiness.Waiting,
                                dolbyVisionOutput = false,
                                outputEvidence =
                                    state.diagnostics.outputEvidence.copy(
                                        videoReadiness = PlaybackOutputReadiness.Waiting,
                                        videoConfidence = PlaybackEvidenceConfidence.Requested,
                                        outputDynamicRange = "",
                                        dynamicRangeOutputMode =
                                            PlaybackDynamicRangeOutputMode.Unknown,
                                        dolbyVisionRpuRendered = false,
                                        dolbyVisionFelComposed = false,
                                    ),
                            ),
                    )
                }
                return@runCatching
            }
            val input =
                instance
                    .getPropertyString("video-params/gamma")
                    ?.let(::mpvDynamicRange)
                    .orEmpty()
            val output =
                instance
                    .getPropertyString("video-out-params/gamma")
                    ?.let(::mpvDynamicRange)
                    .orEmpty()
            val pixelFormat = instance.getPropertyString("video-out-params/pixelformat").orEmpty()
            val currentVo = instance.getPropertyString("current-vo").orEmpty()
            val gpuContext = instance.getPropertyString("current-gpu-context").orEmpty()
            val rendererDetail =
                listOfNotNull(
                    currentVo.takeIf(String::isNotBlank),
                    gpuContext.takeIf(String::isNotBlank),
                    pixelFormat.takeIf(String::isNotBlank),
                ).joinToString(" · ")
            val renderApi =
                if (
                    currentVo.contains("vulkan", ignoreCase = true) ||
                    gpuContext.contains("vulkan", ignoreCase = true)
                ) {
                    PlaybackVideoRenderApi.Vulkan
                } else {
                    PlaybackVideoRenderApi.OpenGl
                }
            val dolbyEvidence = MpvDolbyRuntimeEvidenceRegistry.current()
            val dynamicRangeOutputMode =
                when (activeDolbyVisionPath) {
                    PlaybackDolbyVisionPath.Hdr10BaseLayer ->
                        PlaybackDynamicRangeOutputMode.Hdr10BaseLayer
                    PlaybackDolbyVisionPath.SdrToneMap ->
                        PlaybackDynamicRangeOutputMode.HdrToSdrToneMapped
                    PlaybackDolbyVisionPath.MpvGpuNext ->
                        if (output.equals("SDR", ignoreCase = true)) {
                            PlaybackDynamicRangeOutputMode.HdrToSdrToneMapped
                        } else {
                            PlaybackDynamicRangeOutputMode.Unknown
                        }
                    else -> PlaybackDynamicRangeOutputMode.Unknown
                }
            val label =
                when {
                    input.isNotBlank() && output.isNotBlank() && input != output ->
                        "$input → $output · mpv 色调映射"
                    output.isNotBlank() -> "$output · mpv 视频输出已建立"
                    input.isNotBlank() -> "$input · mpv 渲染已建立，输出范围未知"
                    else -> "mpv 渲染已建立，输出范围未知"
                }
            _state.update { state ->
                state.copy(
                    diagnostics =
                        state.diagnostics.copy(
                            videoOutput = label,
                            videoReadiness = PlaybackOutputReadiness.Rendering,
                            dolbyVisionRpuApplied = dolbyEvidence.rpuRendered,
                            dolbyVisionEnhancementLayerComposed = dolbyEvidence.felComposed,
                            outputEvidence =
                                state.diagnostics.outputEvidence.copy(
                                    videoReadiness = PlaybackOutputReadiness.Rendering,
                                    videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                                    inputDynamicRange = input,
                                    outputDynamicRange = output,
                                    dynamicRangeOutputMode = dynamicRangeOutputMode,
                                    dolbyVisionRpuRendered = dolbyEvidence.rpuRendered,
                                    dolbyVisionFelComposed = dolbyEvidence.felComposed,
                                    renderApi = renderApi,
                                    bitDepth = pixelFormat.mpvPixelFormatBitDepth(),
                                    rendererDetail = rendererDetail,
                                ),
                        ),
                )
            }
        }.onFailure { error ->
            AppLog.warning(
                category = "player.mpv",
                event = "video_output_probe_failed",
                message = "Could not read mpv video output diagnostics",
                throwable = error,
            )
        }
    }

    private fun logAudioOutput() {
        val instance = mpv ?: return
        runCatching {
            val outputDriver = instance.getPropertyString("current-ao")
            val outputFormat = instance.getPropertyString("audio-out-params/format")
            val decoder = instance.getPropertyString("audio-codec-name")
            val readiness = mpvAudioOutputReadiness(outputDriver, outputFormat)
            val passthroughStatus =
                mpvAudioPassthroughStatus(
                    mode = audioPassthroughMode,
                    audioOutputFormat = outputFormat,
                    audioDecoder = decoder,
                )
            _state.update { state ->
                state.copy(
                    diagnostics =
                        state.diagnostics.copy(
                            audioOutput =
                                if (readiness == PlaybackOutputReadiness.Rendering) {
                                    playbackOutputDiagnosticLabel(
                                        status = passthroughStatus,
                                        activeLabel = "源码输出 · ${decoder ?: outputFormat ?: "未知编码"}",
                                    )
                                } else {
                                    "等待音频输出"
                                },
                            audioReadiness = readiness,
                            // mpv names the codec rather than exposing an encoding constant,
                            // so the identifier is matched — a backend codec name, not a
                            // sentence written for the diagnostics panel.
                            immersiveAudioCarrierOutput =
                                readiness == PlaybackOutputReadiness.Rendering &&
                                    passthroughStatus is PlaybackOutputStatus.Active &&
                                    isImmersiveAudioCarrierCodec(decoder ?: outputFormat),
                            dolbyAtmosOutput =
                                readiness == PlaybackOutputReadiness.Rendering &&
                                    passthroughStatus is PlaybackOutputStatus.Active &&
                                    isDolbyObjectAudioCodec(decoder ?: outputFormat),
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            outputEvidence =
                                state.diagnostics.outputEvidence.copy(
                                    audioReadiness = readiness,
                                    audioConfidence =
                                        if (readiness == PlaybackOutputReadiness.Rendering) {
                                            PlaybackEvidenceConfidence.Confirmed
                                        } else {
                                            PlaybackEvidenceConfidence.Requested
                                        },
                                    audioDecoder = decoder.orEmpty(),
                                    audioMode =
                                        if (passthroughStatus is PlaybackOutputStatus.Active) {
                                            PlaybackAudioOutputMode.Passthrough
                                        } else if (readiness == PlaybackOutputReadiness.Rendering) {
                                            PlaybackAudioOutputMode.Pcm
                                        } else {
                                            PlaybackAudioOutputMode.Unknown
                                        },
                                ),
                        ),
                )
            }
            AppLog.info(
                category = "player.mpv",
                event =
                    if (readiness == PlaybackOutputReadiness.Rendering) {
                        "audio_output_configured"
                    } else {
                        "audio_output_pending"
                    },
                message =
                    if (readiness == PlaybackOutputReadiness.Rendering) {
                        "mpv audio output was configured"
                    } else {
                        "mpv audio output is not established yet"
                    },
                attributes =
                    mapOf(
                        "output" to (outputDriver ?: "unknown"),
                        "format" to (outputFormat ?: "unknown"),
                        "codec" to (decoder ?: "unknown"),
                        "track" to (instance.getPropertyString("aid") ?: "unknown"),
                        "passthrough" to passthroughStatus.toString(),
                        "readiness" to readiness.name,
                    ),
            )
        }.onFailure {
            AppLog.warning(
                category = "player.mpv",
                event = "audio_output_probe_failed",
                message = "Could not read mpv audio output diagnostics",
                throwable = it,
            )
        }
    }

    private fun currentDirectAudioFormats() =
        runCatching { capabilityProvider?.current()?.directAudioFormats }
            .getOrNull()
            .orEmpty()

    /** Reconfigure encoded output when HDMI/USB/Bluetooth routing changes during playback. */
    private fun startAudioRouteMonitoring() {
        val provider = capabilityProvider ?: return
        audioRouteJob?.cancel()
        audioRouteJob =
            scope.launch {
                provider.revisions().drop(1).collect {
                    val codecs = mpvAudioSpdifOption(audioPassthroughMode, currentDirectAudioFormats())
                    val applied =
                        withMpvResult { instance ->
                            instance.setPropertyString("audio-spdif", codecs.orEmpty())
                        }
                    AppLog.info(
                        category = "player.mpv",
                        event = "audio_route_reconfigured",
                        message = "mpv encoded-audio policy followed the active Android route",
                        attributes =
                            mapOf(
                                "codecs" to (codecs ?: "pcm"),
                                "applied" to applied.toString(),
                            ),
                    )
                }
            }
    }

    private fun MPVLib.requireOption(
        name: String,
        value: String,
    ) {
        val result = setOptionString(name, value)
        check(result >= 0) { "mpv rejected option $name (error $result)" }
    }

    /**
     * An option worth having and not worth dying for.
     *
     * For anything whose availability depends on the libmpv build rather than on this
     * code being right — a rejection is logged and playback carries on without it.
     */
    private fun MPVLib.optionalOption(
        name: String,
        value: String,
    ) {
        val result = setOptionString(name, value)
        if (result < 0) {
            AppLog.info(
                category = "player.mpv",
                event = "option_unavailable",
                message = "mpv build does not support an optional option; continuing without it",
                attributes = mapOf("option" to name, "code" to result.toString()),
            )
        }
    }

    /** Issues `loadfile replace` and reserves the END_FILE that closes its predecessor. */
    private fun replaceFile(url: String): Boolean {
        val policy = currentFileLoadWatchdogPolicy(url)
        val replacing = endFileTracker.beforeLoad()
        val loaded =
            withMpvResult { instance ->
                instance.configureDolbyVisionRouteForCurrentItem()
                val preparedUrl = instance.prepareDiscUrl(url)
                val index = _state.value.currentIndex
                val item = items.getOrNull(index)
                val usingServerTranscode =
                    index in transcodedIndices || index in progressiveIndices
                val cacheable =
                    item?.persistentPlaybackCacheUrl(usingServerTranscode) == preparedUrl.trim()
                val transportUrl =
                    networkProxy?.localUrl(preparedUrl, cacheable = cacheable) ?: preparedUrl
                instance.command(arrayOf("loadfile", transportUrl))
                AppLog.info(
                    category = "player.mpv.network",
                    event = "transport_selected",
                    message = "mpv media transport was selected",
                    attributes =
                        mapOf(
                            "platformProxy" to (transportUrl != preparedUrl).toString(),
                            "scheme" to preparedUrl.substringBefore(':', "unknown").lowercase(),
                        ),
                )
            }
        if (!loaded) {
            endFileTracker.rollbackLoad(replacing)
            cancelFileLoadWatchdog()
        } else {
            armFileLoadWatchdog(policy)
        }
        return loaded
    }

    /** Keeps MPV's actual frame pipeline aligned with the planner's Dolby decision. */
    private fun MPVLib.configureDolbyVisionRouteForCurrentItem() {
        val item = items.getOrNull(_state.value.currentIndex) ?: return
        val probe = item.playbackMediaProbe(usingServerTranscode = _state.value.transcoding)
        if (!probe.source.dolbyVision) {
            activeDolbyVisionPath = PlaybackDolbyVisionPath.None
            setPropertyString("vf", "")
            return
        }
        val capabilities =
            runCatching { capabilityProvider?.current() }
                .getOrNull()
                ?: PlaybackDeviceCapabilities.conservative()
        val route =
            playbackDolbyVisionRoute(
                source = probe.source,
                capabilities = capabilities,
                runtime = dolbyVisionRuntime,
                requiresNativeDemuxer = probe.requiresNativeDemuxer,
            )
        val videoFilter = mpvDolbyVisionVideoFilter(route.stripDolbyVisionToBaseLayer)
        activeDolbyVisionPath = route.path
        setPropertyString("vf", videoFilter)
        AppLog.info(
            category = "player.dolby",
            event = "mpv_dolby_route_applied",
            message = "MPV applied the planned Dolby Vision frame route",
            attributes =
                mapOf(
                    "path" to route.path.name,
                    "profile" to (probe.source.dolbyVisionProfile?.toString() ?: "unknown"),
                    "stripToBaseLayer" to route.stripDolbyVisionToBaseLayer.toString(),
                    "gpuNextFull" to
                        (route.path == PlaybackDolbyVisionPath.MpvGpuNext).toString(),
                ),
        )
    }

    /** Rebind once in place before a native-window failure is allowed to rotate the engine. */
    private fun recoverVideoSurface(details: String): Boolean {
        if (released) return true
        if (surfaceRecoveryInProgress.get()) return true
        val surface = attachedSurface
        if (surface == null || !surface.isValid) {
            // SurfaceDestroyed and engine switches legitimately tear the window down. A later
            // SurfaceCreated callback will attach the replacement; do not blacklist mpv here.
            return true
        }
        val attempt = surfaceRecoveryAttempts.incrementAndGet()
        if (attempt > MAX_MPV_SURFACE_RECOVERY_ATTEMPTS) return false
        if (!surfaceRecoveryInProgress.compareAndSet(false, true)) return true
        val recovered =
            try {
                withMpvResult { instance ->
                    instance.setPropertyString("vo", "null")
                    instance.detachSurface()
                    instance.attachSurface(surface)
                    instance.setPropertyString("force-window", "yes")
                    instance.setPropertyString("vo", "gpu")
                    instance.command(arrayOf("video-reload"))
                }
            } finally {
                surfaceRecoveryInProgress.set(false)
            }
        _state.update {
            it.copy(
                buffering = true,
                diagnostics =
                    it.diagnostics.copy(
                        videoOutput = "正在恢复 mpv 视频输出",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        dolbyVisionOutput = false,
                        outputEvidence =
                            it.diagnostics.outputEvidence.copy(
                                videoReadiness = PlaybackOutputReadiness.Waiting,
                                surfaceRebuildCount = attempt.toInt(),
                            ),
                    ),
            )
        }
        AppLog.warning(
            category = "player.mpv",
            event = "surface_recovery",
            message = "mpv recovered from a missing native window",
            attributes =
                mapOf(
                    "attempt" to attempt.toString(),
                    "generation" to surfaceGeneration.get().toString(),
                    "reloaded" to recovered.toString(),
                    "nativeSignal" to details.take(120),
                ),
        )
        return recovered
    }

    private fun MPVLib.prepareDiscUrl(url: String): String {
        if (!url.startsWith("file://", ignoreCase = true)) return url
        val item = items.getOrNull(_state.value.currentIndex) ?: return url
        val version = item.activeVersion
        val declaredKind =
            detectPlaybackDiscKind(
                container = version?.container,
                labelHint = version?.label,
                declaredDiscSource = version?.discSource == true,
            )
        val kind = cachedLocalPlaybackDiscKind(url) ?: declaredKind
        val path = Uri.parse(url).path?.takeIf(String::isNotBlank) ?: return url
        return when (kind) {
            PlaybackDiscKind.Dvd -> {
                setPropertyString("dvd-device", path)
                requireNotNull(mpvDiscPlaybackUrl(kind))
            }
            PlaybackDiscKind.BluRay,
            PlaybackDiscKind.Bdmv,
            -> {
                setPropertyString("bluray-device", bluRayDiscRoot(path))
                requireNotNull(mpvDiscPlaybackUrl(kind))
            }
            else -> url
        }
    }

    private fun currentFileLoadWatchdogPolicy(url: String): MpvFileLoadWatchdogPolicy {
        val item = items.getOrNull(_state.value.currentIndex)
        val version = item?.activeVersion
        val transcoding = _state.value.transcoding
        return mpvFileLoadWatchdogPolicy(
            url = url,
            container = version?.container.takeUnless { transcoding },
            discSource = version?.discSource == true && !transcoding,
            sourceVideoCodec = version?.sourceVideoCodec.takeUnless { transcoding },
        )
    }

    private fun armFileLoadWatchdog(policy: MpvFileLoadWatchdogPolicy) {
        val attempt = fileLoadAttempt.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()
        fileLoadStartedAtMs.set(startedAt)
        fileLoadLastProgressMs.set(startedAt)
        fileLoadWatchdogJob?.cancel()
        fileLoadWatchdogJob =
            scope.launch {
                while (true) {
                    delay(policy.pollMs)
                    val snapshot = _state.value
                    val now = SystemClock.elapsedRealtime()
                    val decision =
                        evaluateMpvFileLoadWatchdog(
                            attempt = attempt,
                            activeAttempt = fileLoadAttempt.get(),
                            released = released,
                            buffering = snapshot.buffering,
                            startedAtMs = fileLoadStartedAtMs.get(),
                            lastProgressMs = fileLoadLastProgressMs.get(),
                            nowMs = now,
                            policy = policy,
                        )
                    when (decision) {
                        MpvFileLoadWatchdogDecision.Ignore -> return@launch
                        MpvFileLoadWatchdogDecision.Wait -> Unit
                        MpvFileLoadWatchdogDecision.StallTimeout,
                        MpvFileLoadWatchdogDecision.HardTimeout,
                        -> {
                            AppLog.error(
                                category = "player.mpv",
                                event = "file_load_timeout",
                                message = "mpv media open stopped making progress before FILE_LOADED",
                                attributes =
                                    mapOf(
                                        "itemIndex" to snapshot.currentIndex.toString(),
                                        "decision" to decision.name,
                                        "graceMs" to policy.graceMs.toString(),
                                        "stallMs" to policy.stallMs.toString(),
                                        "hardLimitMs" to policy.hardLimitMs.toString(),
                                        "elapsedMs" to (now - startedAt).coerceAtLeast(0L).toString(),
                                        "transcoding" to snapshot.transcoding.toString(),
                                    ),
                            )
                            markTerminalFailure("mpv 打开媒体长时间无进展，正在尝试其他播放器")
                            return@launch
                        }
                    }
                }
            }
    }

    /** Native log/cache activity is a startup heartbeat, not an excuse to exceed the hard limit. */
    private fun markFileLoadProgress() {
        if (fileLoadStartedAtMs.get() < 0L) return
        fileLoadLastProgressMs.set(SystemClock.elapsedRealtime())
    }

    private fun cancelFileLoadWatchdog() {
        fileLoadAttempt.incrementAndGet()
        fileLoadWatchdogJob?.cancel()
        fileLoadWatchdogJob = null
        fileLoadStartedAtMs.set(-1L)
        fileLoadLastProgressMs.set(-1L)
    }

    private fun loadFileOrFail(url: String): Boolean {
        if (replaceFile(url)) return true
        markTerminalFailure("mpv 无法加载媒体，正在尝试其他播放器")
        return false
    }

    private fun markTerminalFailure(
        fallbackMessage: String,
        details: String? = null,
    ) {
        markTerminalFailure(terminalNativePlaybackFailure(fallbackMessage, details))
    }

    private fun markTerminalFailure(failure: NativePlaybackFailure) {
        cancelFileLoadWatchdog()
        _state.update {
            it.copy(
                playing = false,
                buffering = false,
                ended = false,
                error = failure.message,
                errorKind = failure.kind,
                fallbacksExhausted = true,
                automaticFallbackBlocked = failure.blocksAutomaticFallback,
            )
        }
    }

    /** Stops the current HLS reader without turning that intentional END_FILE into an error. */
    private fun stopFileForReplacement() {
        val stopping = endFileTracker.beforeStop()
        if (!withMpvResult { it.command(arrayOf("stop")) }) {
            endFileTracker.rollbackStop(stopping)
        }
    }

    /** mpv calls throw once the handle is gone; every call site tolerates a miss. */
    private inline fun withMpv(block: (MPVLib) -> Unit) {
        withMpvResult(block)
    }

    /** Same tolerant call path, with success exposed for END_FILE tracker rollback. */
    private inline fun withMpvResult(block: (MPVLib) -> Unit): Boolean {
        val instance = mpv ?: return false
        return runCatching { block(instance) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    safeLogcat(Log.WARN, TAG, "mpv call failed", it)
                    AppLog.warning(
                        category = "player.mpv",
                        event = "engine_call_failed",
                        message = "mpv engine call failed",
                        throwable = it,
                    )
                    false
                },
            )
    }
}

private const val HUGE_REMOTE_MEDIA_BYTES = 64L * 1024L * 1024L * 1024L

private const val MAX_MPV_REPORTED_AV_SYNC_OFFSET_MS = 5_000L
private const val MAX_MPV_SURFACE_RECOVERY_ATTEMPTS = 2L

private fun PlayerMediaItem?.initialDiscNavigation(transcoding: Boolean): PlaybackDiscNavigationState {
    if (this == null || transcoding) return PlaybackDiscNavigationState()
    val version = activeVersion
    return PlaybackDiscNavigationState(
        kind =
            detectPlaybackDiscKind(
                container = version?.container,
                labelHint = version?.label,
                declaredDiscSource = version?.discSource == true,
            ),
    )
}
