package com.yfuse.feature.player

import android.util.Log
import android.view.SurfaceView
import com.mediadevkit.sdk.MDKPlayer
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MDK_TAG = "YfuseMdk"
private val mdkRuntimeCadence =
    PlaybackRuntimeCadence(
        activeIntervalMs = 250L,
        idleIntervalMs = 2_000L,
    )

/** Polls to let a freshly-loaded fallback settle before its status is trusted again. */
private const val FALLBACK_SETTLE_POLLS = 12
private const val TRACK_SEPARATOR = '\u001F'
internal const val MDK_SDK_COMPILE_VERSION = "0.37.0"

/** Stable field order shared with MDKPlayer.nativePlaybackEvidence(). */
@Suppress("ktlint:standard:property-naming")
private object MdkEvidenceField {
    const val FirstVideoFrame = 0
    const val VideoDecoder = 1
    const val AudioDecoder = 2
    const val VideoCodec = 3
    const val PixelFormat = 4
    const val VideoWidth = 5
    const val VideoHeight = 6
    const val VideoBitrate = 7
    const val FrameRate = 8
    const val ColorSpace = 9
    const val DolbyVisionProfile = 10
    const val VideoProfile = 11
    const val AudioCodec = 12
    const val AudioChannels = 13
    const val AudioSampleRate = 14
    const val AudioBitrate = 15
    const val ContainerBitrate = 16
    const val EventRevision = 17
    const val RuntimeVersion = 18
    const val Count = 19
}

internal data class MdkPlaybackEvidence(
    val firstVideoFrameRendered: Boolean = false,
    val videoDecoder: String = "",
    val audioDecoder: String = "",
    val videoCodec: String = "",
    val pixelFormat: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoBitrate: Long = 0L,
    val frameRate: Float = 0f,
    val colorSpace: String = "",
    val dolbyVisionProfile: Int = 0,
    val videoProfile: Int = -99,
    val audioCodec: String = "",
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val audioBitrate: Long = 0L,
    val containerBitrate: Long = 0L,
    val eventRevision: Long = 0L,
    val runtimeVersion: String = "",
)

internal fun decodeMdkPlaybackEvidence(fields: Array<String>): MdkPlaybackEvidence {
    if (fields.size < MdkEvidenceField.Count) return MdkPlaybackEvidence()
    return MdkPlaybackEvidence(
        firstVideoFrameRendered = fields[MdkEvidenceField.FirstVideoFrame] == "1",
        videoDecoder = fields[MdkEvidenceField.VideoDecoder],
        audioDecoder = fields[MdkEvidenceField.AudioDecoder],
        videoCodec = fields[MdkEvidenceField.VideoCodec],
        pixelFormat = fields[MdkEvidenceField.PixelFormat],
        videoWidth = fields[MdkEvidenceField.VideoWidth].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        videoHeight = fields[MdkEvidenceField.VideoHeight].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        videoBitrate = fields[MdkEvidenceField.VideoBitrate].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        frameRate = fields[MdkEvidenceField.FrameRate].toFloatOrNull()?.coerceAtLeast(0f) ?: 0f,
        colorSpace = fields[MdkEvidenceField.ColorSpace],
        dolbyVisionProfile =
            fields[MdkEvidenceField.DolbyVisionProfile].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        videoProfile = fields[MdkEvidenceField.VideoProfile].toIntOrNull() ?: -99,
        audioCodec = fields[MdkEvidenceField.AudioCodec],
        audioChannels = fields[MdkEvidenceField.AudioChannels].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        audioSampleRate =
            fields[MdkEvidenceField.AudioSampleRate].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        audioBitrate = fields[MdkEvidenceField.AudioBitrate].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        containerBitrate =
            fields[MdkEvidenceField.ContainerBitrate].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        eventRevision =
            fields[MdkEvidenceField.EventRevision].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        runtimeVersion = fields[MdkEvidenceField.RuntimeVersion].mdkVersionLabel(),
    )
}

internal fun PlaybackDiagnostics.withMdkPlaybackEvidence(evidence: MdkPlaybackEvidence): PlaybackDiagnostics {
    val reportedRange =
        when {
            evidence.dolbyVisionProfile > 0 -> "Dolby Vision Profile ${evidence.dolbyVisionProfile}"
            evidence.colorSpace.contains("PQ", ignoreCase = true) -> "HDR10 / PQ"
            evidence.colorSpace.contains("HLG", ignoreCase = true) -> "HLG"
            evidence.colorSpace.equals("BT.709", ignoreCase = true) -> "SDR"
            else -> ""
        }
    val activeRange = reportedRange.ifBlank { dynamicRange }
    val codecLabel =
        listOfNotNull(
            evidence.videoCodec.takeIf(String::isNotBlank),
            evidence.pixelFormat.takeIf(String::isNotBlank),
            evidence.videoProfile.takeIf { it >= 0 }?.let { "Profile $it" },
        ).joinToString(" · ")
    val decoderLabel = evidence.videoDecoder.ifBlank { decoder }
    val videoLabel =
        if (evidence.firstVideoFrameRendered) {
            buildString {
                activeRange.takeIf(String::isNotBlank)?.let { append("$it · ") }
                append(decoderLabel.ifBlank { "MDK 解码器" })
                append(" · 首帧已输出")
                if (
                    evidence.dolbyVisionProfile > 0 ||
                    evidence.colorSpace.contains("PQ", ignoreCase = true) ||
                    evidence.colorSpace.contains("HLG", ignoreCase = true)
                ) {
                    append(" · HDR 显示链路未验证")
                }
            }
        } else {
            listOfNotNull(
                activeRange.takeIf(String::isNotBlank),
                decoderLabel.takeIf(String::isNotBlank),
                "等待 MDK 首帧",
            ).joinToString(" · ")
        }
    val audioLabel =
        listOfNotNull(
            evidence.audioCodec.takeIf(String::isNotBlank),
            evidence.audioChannels.takeIf { it > 0 }?.let { "${it}ch" },
            evidence.audioSampleRate.takeIf { it > 0 }?.let { "${it / 1_000f} kHz" },
        ).joinToString(" · ")

    return copy(
        decoder = decoderLabel,
        videoCodec = codecLabel.ifBlank { videoCodec },
        videoWidth = evidence.videoWidth.takeIf { it > 0 } ?: videoWidth,
        dynamicRange = activeRange,
        audioFormat = audioLabel.ifBlank { audioFormat },
        videoOutput = videoLabel,
        audioOutput =
            evidence.audioDecoder.takeIf(String::isNotBlank)?.let {
                "$it 已解码 · 音频输出链路未验证"
            } ?: "MDK 未提供可验证的音频输出状态",
        videoReadiness =
            if (evidence.firstVideoFrameRendered) {
                PlaybackOutputReadiness.Rendering
            } else {
                PlaybackOutputReadiness.Waiting
            },
        audioReadiness = PlaybackOutputReadiness.Unknown,
        // A rendered Dolby source frame does not prove that the Android EGL/display chain
        // remained Dolby Vision rather than mapping it to another range.
        dolbyVisionOutput = false,
        bitrateBitsPerSecond =
            evidence.videoBitrate.takeIf { it > 0L }
                ?: evidence.containerBitrate.takeIf { it > 0L }
                ?: bitrateBitsPerSecond,
        frameRate = evidence.frameRate.takeIf { it > 0f } ?: frameRate,
        avSyncMeasurement = "MDK 0.37 未提供可验证的渲染/音频时钟对",
        outputEvidence =
            outputEvidence.copy(
                videoReadiness =
                    if (evidence.firstVideoFrameRendered) {
                        PlaybackOutputReadiness.Rendering
                    } else {
                        PlaybackOutputReadiness.Waiting
                    },
                audioReadiness = PlaybackOutputReadiness.Unknown,
                videoConfidence =
                    if (evidence.firstVideoFrameRendered) {
                        PlaybackEvidenceConfidence.Confirmed
                    } else {
                        PlaybackEvidenceConfidence.Requested
                    },
                audioConfidence = PlaybackEvidenceConfidence.Unknown,
                videoDecoder = evidence.videoDecoder,
                audioDecoder = evidence.audioDecoder,
                videoCodecProfile = codecLabel,
                bitDepth = evidence.pixelFormat.mdkPixelFormatBitDepth(),
                inputDynamicRange = activeRange,
                // MDK exposes source color and first-frame events, but not the negotiated EGL
                // display colorspace; do not promote source range into output range.
                outputDynamicRange = "",
                renderApi = PlaybackVideoRenderApi.OpenGl,
                audioMode = PlaybackAudioOutputMode.Unknown,
                droppedFramesMeasured = false,
                avSyncMeasured = false,
                rendererDetail =
                    "MDK ${evidence.runtimeVersion.ifBlank { MDK_SDK_COMPILE_VERSION }} · " +
                        "HDR output colorspace unavailable · dropped frames unavailable · " +
                        "codec resets unavailable",
            ),
    )
}

internal fun String.mdkVersionLabel(): String {
    val encoded = toIntOrNull() ?: return ""
    return "${encoded ushr 16 and 0xff}.${encoded ushr 8 and 0xff}.${encoded and 0xff}"
}

private fun String.mdkPixelFormatBitDepth(): Int =
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

/** Official libmdk Android facade adapted to Yfuse's engine-neutral player contract. */
class MdkVideoEngine(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    private val startSpeed: Float,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    private val customUserAgent: String,
    private val scope: CoroutineScope,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {
    private val items = items

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
    private val _state =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex,
                itemCount = items.size.coerceAtLeast(1),
                speed = startSpeed,
                transcoding = startIndex in transcodedIndices,
                videoHeight =
                    items
                        .getOrNull(startIndex)
                        ?.sourceVideoHeight(startIndex in transcodedIndices)
                        ?: 0,
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "MDK",
                        decoder = decoderMode.label,
                        item = items.getOrNull(startIndex),
                    ).copy(
                        videoOutput = "等待 MDK 首帧",
                        audioOutput = "MDK 未提供可验证的音频输出状态",
                        // MDK can now prove a rendered video frame, but its audio sink remains
                        // opaque to the wrapper.
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Unknown,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1L,
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var player: MDKPlayer? = null

    @Volatile
    private var released = false

    @Volatile
    private var playRequested = startPlaybackRequested

    override val playbackRequested: Boolean
        get() = playRequested && !_state.value.ended

    private var attachedView: SurfaceView? = null
    private var pendingSeekMs = startPositionMs.coerceAtLeast(0L)
    private var tracksLoadedForIndex = -1
    private var endHandled = false
    private var fill = false
    private var primarySubtitleOrdinal = 0
    private var secondarySubtitleOrdinal = -1
    private var subtitleScale = 1f
    private var subtitleBrightness = 1f

    @Volatile
    private var pauseAtEndOfCurrentItem = false
    private var wasBuffering = true
    private val fallbackSettleWindow = FallbackSettleWindow(FALLBACK_SETTLE_POLLS)
    private val nativeEventSignals = Channel<Unit>(Channel.CONFLATED)

    private val nativeEventJob: Job =
        scope.launch(Dispatchers.Default) {
            for (ignored in nativeEventSignals) {
                if (!released) poll()
            }
        }

    private val pollJob: Job =
        scope.launch(Dispatchers.Default) {
            while (isActive && !released) {
                poll()
                val current = _state.value
                delay(
                    mdkRuntimeCadence.intervalMs(
                        playing = current.playing,
                        buffering = current.buffering,
                        pendingWork =
                            pendingSeekMs >= 0L ||
                                fallbackJob?.isActive == true ||
                                (playRequested && !current.ended),
                    ),
                )
            }
        }

    fun attach(view: SurfaceView) {
        if (released) return
        attachedView = view
        val instance = ensurePlayer() ?: return
        runCatching {
            instance.setSurfaceView(view)
            if (_state.value.durationMs == 0L && instance.mediaStatus() == 0) {
                loadCurrent(instance)
            }
        }.onFailure {
            safeLogcat(Log.ERROR, MDK_TAG, "MDK surface attach failed", it)
            AppLog.error(
                category = "player.mdk",
                event = "surface_attach_failed",
                message = "MDK surface attach failed",
                throwable = it,
            )
            markTerminalFailure(
                fallbackMessage = "MDK 无法连接视频画面，正在尝试其他播放器",
                details = it.message,
            )
        }
    }

    fun recordFrameRateRequest(
        frameRate: Float,
        status: PlaybackOutputStatus,
    ) {
        _state.update {
            it.copy(
                diagnostics =
                    it.diagnostics.copy(
                        outputEvidence =
                            it.diagnostics.outputEvidence.copy(
                                rendererDetail =
                                    listOf(
                                        it.diagnostics.outputEvidence.rendererDetail,
                                        "Surface frame-rate request=$frameRate (${status::class.simpleName})",
                                    ).filter(String::isNotBlank).joinToString(" · "),
                            ),
                    ),
            )
        }
    }

    fun setFill(enabled: Boolean) {
        fill = enabled
        runMdk { it.setFill(enabled) }
    }

    override fun play() {
        playRequested = true
        _state.update { it.copy(playing = true, ended = false) }
        runMdk { it.setState(MDKPlayer.STATE_PLAYING) }
    }

    override fun pause() {
        playRequested = false
        _state.update { it.copy(playing = false) }
        runMdk { it.setState(MDKPlayer.STATE_PAUSED) }
    }

    override fun prepareForHandover() {
        pause()
        attachedView?.let { view ->
            runCatching { player?.setSurfaceView(null) }
                .onFailure {
                    AppLog.info(
                        category = "player.mdk",
                        event = "handover_surface_detach_unavailable",
                        message = "MDK could not detach its outgoing surface before handover",
                        attributes = mapOf("surfaceAttached" to view.isAttachedToWindow.toString()),
                    )
                }
        }
    }

    override fun seekTo(positionMs: Long) {
        pendingSeekMs = -1L
        _state.update {
            it.copy(
                positionMs = positionMs,
                bufferedPositionMs = positionMs.coerceAtLeast(0L),
                ended = false,
            )
        }
        runMdk { it.seek(positionMs.coerceAtLeast(0L)) }
    }

    override fun setSpeed(speed: Float) {
        runMdk { it.setPlaybackRate(speed) }
        _state.update { it.copy(speed = speed) }
    }

    override fun selectAudioTrack(id: String) {
        val ordinal = id.toIntOrNull() ?: return
        runMdk { it.setActiveTrack(MDKPlayer.MEDIA_TYPE_AUDIO, ordinal) }
        _state.update { state ->
            state.copy(
                audioTracks =
                    state.audioTracks.map {
                        it.copy(selected = it.id == id)
                    },
            )
        }
    }

    override fun selectSubtitleTrack(id: String) {
        val ordinal = if (id == EngineTrack.OFF) -1 else id.toIntOrNull() ?: return
        primarySubtitleOrdinal = ordinal
        if (secondarySubtitleOrdinal == ordinal) secondarySubtitleOrdinal = -1
        applySubtitleTracks()
        _state.update { state ->
            state.copy(
                subtitleTracks =
                    state.subtitleTracks.map {
                        it.copy(selected = ordinal >= 0 && it.id == id)
                    },
            )
        }
    }

    override val supportsSecondarySubtitleTrack: Boolean = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        val ordinal = if (id == EngineTrack.OFF) -1 else id.toIntOrNull() ?: return false
        if (ordinal >= 0 && ordinal == primarySubtitleOrdinal) return false
        secondarySubtitleOrdinal = ordinal
        return applySubtitleTracks()
    }

    override fun setSubtitleScale(scale: Float): Boolean {
        subtitleScale = scale.coerceIn(0.6f, 1.8f)
        val instance = player ?: return true
        return runMdkResult(instance) { it.setProperty("subtitle.scale", subtitleScale.toString()) }
    }

    override fun setSubtitleBrightness(brightness: Float): Boolean {
        subtitleBrightness = brightness.coerceIn(MIN_SUBTITLE_BRIGHTNESS, 1f)
        val instance = player ?: return true
        return runMdkResult(instance) {
            it.setProperty("subtitle.color", subtitleBrightnessRgba(subtitleBrightness))
        }
    }

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        pauseAtEndOfCurrentItem = enabled
    }

    override fun selectItem(index: Int) {
        if (index !in items.indices || released) return
        pendingSeekMs = 0L
        tracksLoadedForIndex = -1
        endHandled = false
        primarySubtitleOrdinal = 0
        secondarySubtitleOrdinal = -1
        val transcoding = index in transcodedIndices
        val nextItem = items.getOrNull(index)
        _state.update {
            val mediaChanged = it.currentIndex != index
            it.copy(
                currentIndex = index,
                playing = true,
                buffering = true,
                positionMs = if (mediaChanged) 0L else it.positionMs,
                durationMs = if (mediaChanged) 0L else it.durationMs,
                bufferedPositionMs = if (mediaChanged) 0L else it.bufferedPositionMs,
                videoHeight = nextItem?.sourceVideoHeight(transcoding) ?: 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                error = null,
                ended = false,
                transcoding = transcoding,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "MDK",
                        decoder = it.diagnostics.decoder,
                        item = nextItem,
                        transcoding = transcoding,
                    ).copy(
                        videoOutput = "等待 MDK 首帧",
                        audioOutput = "MDK 未提供可验证的音频输出状态",
                        // MDK can now prove a rendered video frame, but its audio sink remains
                        // opaque to the wrapper.
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Unknown,
                        outputEvidence =
                            it.diagnostics.outputEvidence.nextSession().copy(
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                    ),
            )
        }
        ensurePlayer()?.let(::loadCurrent)
    }

    override fun currentPositionMs(): Long = runCatching { player?.position() }.getOrNull() ?: _state.value.positionMs

    override fun retry() {
        pendingSeekMs = _state.value.positionMs
        tracksLoadedForIndex = -1
        endHandled = false
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = it.positionMs,
                ended = false,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
            )
        }
        ensurePlayer()?.let(::loadCurrent)
    }

    override fun release() {
        if (released) return
        released = true
        fallbackJob?.cancel()
        fallbackJob = null
        pollJob.cancel()
        nativeEventJob.cancel()
        nativeEventSignals.close()
        val instance = player
        player = null
        attachedView = null
        runCatching {
            instance?.setListener(null)
            instance?.setSurfaceView(null)
            instance?.close()
        }.onFailure {
            safeLogcat(Log.WARN, MDK_TAG, "MDK teardown failed", it)
            AppLog.warning(
                category = "player.mdk",
                event = "teardown_failed",
                message = "MDK teardown failed",
                throwable = it,
            )
        }
    }

    private fun ensurePlayer(): MDKPlayer? {
        player?.let { return it }
        return runCatching {
            MDKPlayer().also { instance ->
                instance.setDecoderMode(decoderMode.ordinal)
                instance.setListener { nativeEventSignals.trySend(Unit) }
                customUserAgent.trim().takeIf { it.isNotEmpty() }?.let { value ->
                    instance.setProperty("avio.user_agent", value)
                }
                instance.setFill(fill)
                instance.setPlaybackRate(startSpeed)
                instance.setProperty("subtitle.scale", subtitleScale.toString())
                instance.setProperty("subtitle.color", subtitleBrightnessRgba(subtitleBrightness))
                player = instance
            }
        }.onFailure {
            safeLogcat(Log.ERROR, MDK_TAG, "MDK initialization failed", it)
            AppLog.error(
                category = "player.mdk",
                event = "initialization_failed",
                message = "MDK initialization failed",
                throwable = it,
            )
            markTerminalFailure(
                fallbackMessage = "无法初始化 MDK 播放器，正在尝试其他播放器",
                details = it.message,
            )
        }.getOrNull()
    }

    private fun loadCurrent(instance: MDKPlayer) {
        val index = _state.value.currentIndex
        val item = items.getOrNull(index) ?: return
        runCatching {
            instance.setMedia(playbackUrl(item, index))
            // MDK otherwise selects its first audio track implicitly while our facade has no
            // active-track getter. Submit the choice explicitly so UI state is never a guess.
            instance.setActiveTrack(MDKPlayer.MEDIA_TYPE_AUDIO, 0)
            instance.setState(
                if (playRequested) MDKPlayer.STATE_PLAYING else MDKPlayer.STATE_PAUSED,
            )
        }.onFailure {
            safeLogcat(Log.ERROR, MDK_TAG, "MDK load failed", it)
            AppLog.error(
                category = "player.mdk",
                event = "load_failed",
                message = "MDK failed to load media",
                throwable = it,
                attributes = mapOf("itemIndex" to _state.value.currentIndex.toString()),
            )
            markTerminalFailure(
                fallbackMessage = "MDK 启动失败，正在尝试其他播放器",
                details = it.message,
            )
        }
    }

    @Synchronized
    private fun poll() {
        val instance = player ?: return
        runCatching {
            val status = instance.mediaStatus()
            val loaded =
                status and (MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED) != 0
            val ended = status and MDKPlayer.STATUS_END != 0
            val invalid = status and MDKPlayer.STATUS_INVALID != 0
            nativePlaybackLogFailure(instance.lastError())?.let { failure ->
                markTerminalFailure(failure)
                return
            }
            val bufferingFlags =
                MDKPlayer.STATUS_LOADING or
                    MDKPlayer.STATUS_STALLED or
                    MDKPlayer.STATUS_BUFFERING or
                    MDKPlayer.STATUS_SEEKING
            val buffering = !loaded || status and bufferingFlags != 0
            val bufferEvent = buffering && !wasBuffering
            wasBuffering = buffering

            if (loaded && pendingSeekMs > 0L) {
                val target = pendingSeekMs
                pendingSeekMs = -1L
                instance.seek(target)
            } else if (loaded && pendingSeekMs == 0L) {
                pendingSeekMs = -1L
            }

            if (loaded && tracksLoadedForIndex != _state.value.currentIndex) {
                refreshTracks(instance)
                tracksLoadedForIndex = _state.value.currentIndex
            }

            // Try the next stream down before calling it unplayable: the common cause is a
            // codec this device has no decoder for, which the server can transcode away.
            //
            // Guarded by a settle window because this is a poll, not an event: MDK keeps
            // reporting the failed status for a while after a new URL is handed to it, and
            // an unguarded check would spend the whole chain in three ticks — before the
            // first fallback had any chance to load.
            fallbackSettleWindow.tick()
            if (
                invalid &&
                !_state.value.fallbacksExhausted &&
                fallbackSettleWindow.ready &&
                switchToTranscode()
            ) {
                return
            }

            if (ended && !endHandled) {
                endHandled = true
                if (!pauseAtEndOfCurrentItem && autoNext && _state.value.hasNext) {
                    selectItem(_state.value.currentIndex + 1)
                    return
                }
            } else if (!ended) {
                endHandled = false
            }

            val positionMs = instance.position().coerceAtLeast(0L)
            val reportedDurationMs = instance.duration().coerceAtLeast(0L)
            val bufferedDurationMs = instance.bufferedDuration().coerceAtLeast(0L)
            val playbackEvidence = decodeMdkPlaybackEvidence(instance.playbackEvidence())
            _state.update { current ->
                val durationMs =
                    reportedDurationMs.takeIf { it > 0L }
                        ?: current.durationMs.coerceAtLeast(0L)
                current.copy(
                    playing =
                        instance.state() == MDKPlayer.STATE_PLAYING && !ended && !invalid,
                    buffering = buffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs =
                        bufferedEndPositionMs(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            bufferedDurationMs = bufferedDurationMs,
                        ),
                    speed = instance.playbackRate(),
                    videoHeight =
                        playbackEvidence.videoHeight.takeIf { it > 0 }
                            ?: instance.videoHeight().coerceAtLeast(0),
                    error =
                        if (invalid) {
                            "MDK 无法播放此媒体，服务器也没有可用的转码流"
                        } else {
                            current.error
                        },
                    fallbacksExhausted = current.fallbacksExhausted || invalid,
                    ended = ended,
                    diagnostics =
                        current.diagnostics
                            .withMdkPlaybackEvidence(playbackEvidence)
                            .copy(
                                bufferedDurationMs = bufferedDurationMs,
                                bufferEvents =
                                    current.diagnostics.bufferEvents + if (bufferEvent) 1 else 0,
                            ),
                )
            }
        }.onFailure {
            if (!released) {
                safeLogcat(Log.WARN, MDK_TAG, "MDK state polling failed", it)
                AppLog.warning(
                    category = "player.mdk",
                    event = "state_poll_failed",
                    message = "MDK state polling failed",
                    throwable = it,
                )
                markTerminalFailure(
                    fallbackMessage = "MDK 播放异常，正在尝试其他播放器",
                    details = it.message,
                )
            }
        }
    }

    private fun refreshTracks(instance: MDKPlayer) {
        val audio =
            decodeTracks(
                rows = instance.tracks(MDKPlayer.MEDIA_TYPE_AUDIO),
                fallback = "音轨",
            )
        val subtitles =
            decodeTracks(
                rows = instance.tracks(MDKPlayer.MEDIA_TYPE_SUBTITLE),
                fallback = "字幕",
            )
        _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
    }

    private fun decodeTracks(
        rows: Array<String>,
        fallback: String,
    ): List<EngineTrack> =
        rows.mapIndexedNotNull { index, row ->
            val fields = row.split(TRACK_SEPARATOR, limit = 4)
            val id = fields.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val language = fields.getOrNull(1)?.takeIf(String::isNotBlank)
            val title = fields.getOrNull(2)?.takeIf(String::isNotBlank)
            EngineTrack(
                id = id,
                label = title ?: language ?: "$fallback ${index + 1}",
                language = language,
                selected = fields.getOrNull(3) == "1",
            )
        }

    private fun applySubtitleTracks(): Boolean {
        val active =
            listOf(primarySubtitleOrdinal, secondarySubtitleOrdinal)
                .filter { it >= 0 }
                .distinct()
                .toIntArray()
        return runMdkResult {
            it.setActiveTracks(
                MDKPlayer.MEDIA_TYPE_SUBTITLE,
                primarySubtitleOrdinal,
                active,
            )
        }
    }

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
        if (released) return false
        val index = _state.value.currentIndex
        val item = items.getOrNull(index) ?: return false
        if (index !in transcodedIndices && !item.allowsServerTranscodeFallback(reason)) return false
        val progressive =
            when {
                index in progressiveIndices -> return false
                index in progressiveTransitionIndices -> return true
                index in transcodedIndices -> true
                item.transcodeUrl.isEmpty() -> true
                else -> false
            }
        if (progressive && item.fallbackTranscodeUrl.isEmpty()) return false
        transcodedIndices += index
        fallbackSettleWindow.restart()
        // Resume where the failure happened rather than from the top; a codec the device
        // can't handle usually fails on the first frame, but a mid-file failure shouldn't
        // cost the user their place.
        pendingSeekMs = _state.value.positionMs.coerceAtLeast(0L)
        tracksLoadedForIndex = -1
        endHandled = false
        AppLog.info(
            category = "player.mdk",
            event = "transcode_fallback",
            message = "Switching MDK to a server-transcoded stream",
            attributes =
                mapOf(
                    "itemIndex" to index.toString(),
                    "step" to if (progressive) "Progressive" else "Transcode",
                ),
        )
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = it.positionMs,
                ended = false,
                transcoding = true,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics =
                    it.diagnostics.copy(
                        playMethod = "服务器转码",
                        dynamicRange = "",
                        audioFormat = "",
                        videoOutput = "等待 MDK 首帧",
                        audioOutput = "MDK 未提供可验证的音频输出状态",
                        // MDK can now prove a rendered video frame, but its audio sink remains
                        // opaque to the wrapper.
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Unknown,
                        outputEvidence =
                            it.diagnostics.outputEvidence.nextSession().copy(
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.OpenGl,
                            ),
                        fallbackReason =
                            reason ?: if (progressive) {
                                "HLS 转码不可用，已改用 MP4 转码"
                            } else {
                                "直放失败，已切换服务器转码"
                            },
                        bufferedDurationMs = 0L,
                    ),
            )
        }
        if (!progressive) {
            ensurePlayer()?.let(::loadCurrent)
            return true
        }

        progressiveTransitionIndices += index
        runMdk { it.setState(MDKPlayer.STATE_STOPPED) }
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
                // The DELETE wait is not part of the new stream's settle window. A slow cleanup can
                // consume all twelve polls while no progressive URL is loaded, making the very next
                // stale STATUS_INVALID tick reject the fresh stream before it gets a chance to open.
                fallbackSettleWindow.restart()
                ensurePlayer()?.let(::loadCurrent)
            }
        return true
    }

    private fun markTerminalFailure(
        fallbackMessage: String,
        details: String? = null,
    ) {
        markTerminalFailure(terminalNativePlaybackFailure(fallbackMessage, details))
    }

    private fun markTerminalFailure(failure: NativePlaybackFailure) {
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

    private inline fun runMdk(block: (MDKPlayer) -> Unit) {
        runMdkResult(block)
    }

    private inline fun runMdkResult(block: (MDKPlayer) -> Unit): Boolean {
        val instance = player ?: return false
        return runMdkResult(instance, block)
    }

    private inline fun runMdkResult(
        instance: MDKPlayer,
        block: (MDKPlayer) -> Unit,
    ): Boolean =
        runCatching { block(instance) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    safeLogcat(Log.WARN, MDK_TAG, "MDK call failed", it)
                    AppLog.warning(
                        category = "player.mdk",
                        event = "engine_call_failed",
                        message = "MDK engine call failed",
                        throwable = it,
                    )
                    false
                },
            )
}
