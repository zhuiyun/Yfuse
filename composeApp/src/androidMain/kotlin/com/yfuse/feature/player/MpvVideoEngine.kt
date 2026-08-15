package com.yfuse.feature.player

import android.content.Context
import android.util.Log
import android.view.Surface
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "YfusePlayer"

/** mpv pushes `time-pos` per frame; only forward moves of at least this much. */
private const val POSITION_STEP_MS = 200L

/** A native load that cannot reach FILE_LOADED should not strand the fallback chain forever. */
internal const val MPV_FILE_LOAD_TIMEOUT_MS = 8_000L

internal fun shouldFailMpvFileLoad(
    attempt: Long,
    activeAttempt: Long,
    released: Boolean,
    buffering: Boolean,
): Boolean = attempt == activeAttempt && !released && buffering

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
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    private val quality: PlaybackQuality,
    private val customUserAgent: String,
    private val scope: CoroutineScope,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {
    private val items = items.map { it.withPlaybackQuality(quality) }
    private val audioPassthroughMode =
        GlobalContext
            .get()
            .get<PlaybackPreferences>()
            .audioPassthrough.value
            .toPlayerMode()

    /** Entries pushed off their original file onto the server's transcode, and past that
     *  onto its progressive MP4. Kept per index so one bad episode doesn't transcode the
     *  rest of the season. */
    private val transcodedIndices =
        items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
            index.takeIf { item.startsWithServerTranscode(quality) }
        }
    private val progressiveIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private var fallbackJob: Job? = null
    private var fileLoadWatchdogJob: Job? = null
    private val fileLoadAttempt = AtomicLong(0L)
    private val endFileTracker = MpvEndFileTracker()

    @Volatile
    private var pauseAtEndOfCurrentItem = false

    private val _state =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex,
                itemCount = items.size.coerceAtLeast(1),
                transcoding = startIndex in transcodedIndices,
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
                        quality = quality,
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var mpv: MPVLib? = null

    @Volatile
    private var released = false

    @Volatile
    private var playRequested = true

    override val playbackRequested: Boolean
        get() = playRequested && !_state.value.ended

    @Volatile
    private var attachedSurface: Surface? = null

    @Volatile
    private var pendingSeekMs = startPositionMs.coerceAtLeast(0L)

    private var lastPositionMs = -POSITION_STEP_MS
    private var wasBuffering = true

    private val logObserver =
        object : MPVLib.LogObserver {
            override fun logMessage(
                prefix: String,
                level: Int,
                text: String,
            ) {
                if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) return
                val details = text.trim().take(600)
                if (details.isEmpty()) return
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
            override fun eventProperty(property: String) = Unit

            override fun eventProperty(
                property: String,
                value: Long,
            ) {
                when (property) {
                    "track-list/count" -> readTracks()
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
                    "decoder-frame-drop-count" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        droppedFrames = value.toInt().coerceAtLeast(0),
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
                        if (kotlin.math.abs(ms - lastPositionMs) < POSITION_STEP_MS) return
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

                    "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
                    "speed" -> _state.update { it.copy(speed = value.toFloat()) }
                    "estimated-vf-fps" ->
                        _state.update {
                            it.copy(diagnostics = it.diagnostics.copy(frameRate = value.toFloat()))
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
                    "cache-speed" ->
                        _state.update {
                            it.copy(
                                diagnostics =
                                    it.diagnostics.copy(
                                        networkBitsPerSecond = (value * 8.0).toLong().coerceAtLeast(0L),
                                    ),
                            )
                        }
                    "demuxer-cache-duration" ->
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
                            it.copy(diagnostics = it.diagnostics.copy(videoCodec = value))
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
                                    ),
                            )
                        }
                }
            }

            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_START_FILE ->
                        _state.update {
                            it.copy(
                                buffering = true,
                                bufferedPositionMs = it.positionMs,
                                error = null,
                                ended = false,
                                diagnostics = it.diagnostics.copy(bufferedDurationMs = 0L),
                            )
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
                        readTracks()
                        readVideoSize()
                        readVideoOutput()
                        logAudioOutput()
                        AppLog.info(
                            category = "player.mpv",
                            event = "file_loaded",
                            message = "mpv loaded the selected media",
                            attributes =
                                mapOf(
                                    "itemIndex" to _state.value.currentIndex.toString(),
                                    "resumePositionMs" to seekMs.coerceAtLeast(0L).toString(),
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
            // Use Android's media stream deterministically. This keeps native
            // playback on the same STREAM_MUSIC volume path as ExoPlayer/MDK.
            instance.requireOption("ao", "audiotrack")
            mpvAudioSpdifOption(audioPassthroughMode)?.let { codecs ->
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
            instance.optionalOption("hdr-compute-peak", "yes")
            instance.init()

            mpv = instance
            instance.addObserver(observer)
            instance.addLogObserver(logObserver)
            instance.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("audio-codec-name", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("audio-params/channel-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("video-bitrate", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("cache-speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("decoder-frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("secondary-sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)

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
        withMpv {
            // Stop the VO before releasing the Android Surface. Detaching a
            // Surface that the GPU context is still using can race/crash.
            it.setPropertyString("vo", "null")
            it.setPropertyString("force-window", "no")
            it.detachSurface()
        }
        attachedSurface = null
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
            instance.setPropertyDouble("panscan", if (mode == VideoScaleMode.Fill) 1.0 else 0.0)
            instance.setPropertyString(
                "video-aspect-override",
                if (mode == VideoScaleMode.Stretch) "window" else "-1",
            )
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

    override fun selectSubtitleTrack(id: String) = selectTrack("sid", id)

    override val supportsSecondarySubtitleTrack: Boolean = true

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

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        pauseAtEndOfCurrentItem = enabled
    }

    override fun selectItem(index: Int) {
        if (index !in items.indices) return
        playRequested = true
        pendingSeekMs = 0L
        lastPositionMs = -POSITION_STEP_MS
        val transcoding = index in transcodedIndices
        val nextItem = items.getOrNull(index)
        _state.update {
            it.copy(
                currentIndex = index,
                positionMs = 0L,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                videoHeight = nextItem?.sourceVideoHeight(transcoding) ?: 0,
                buffering = true,
                ended = false,
                error = null,
                transcoding = transcoding,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "libmpv",
                        decoder = it.diagnostics.decoder,
                        item = nextItem,
                        quality = quality,
                        transcoding = transcoding,
                    ),
            )
        }
        loadFileOrFail(playbackUrl(items[index], index))
    }

    override fun currentPositionMs(): Long = _state.value.positionMs

    override fun retry() {
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
        cancelFileLoadWatchdog()
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
        val item = items.getOrNull(index) ?: return false
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
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics =
                    it.diagnostics.copy(
                        playMethod = "服务器转码",
                        dynamicRange = "",
                        audioFormat = "",
                        videoOutput = "等待转码视频输出",
                        audioOutput = "等待转码音频输出",
                        fallbackReason =
                            reason ?: when (next) {
                                Step.Transcode -> "直放失败，已切换服务器转码"
                                Step.Progressive -> "HLS 转码不可用，已改用 MP4 转码"
                            },
                        bufferedDurationMs = 0L,
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
            val label =
                when {
                    input.isNotBlank() && output.isNotBlank() && input != output ->
                        "$input → $output · mpv 色调映射"
                    output.isNotBlank() -> "$output · mpv 视频输出已建立"
                    input.isNotBlank() -> "$input · mpv 渲染已建立，输出范围未知"
                    else -> "mpv 渲染已建立，输出范围未知"
                }
            _state.update { state ->
                state.copy(diagnostics = state.diagnostics.copy(videoOutput = label))
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
            val outputFormat = instance.getPropertyString("audio-params/format")
            val decoder = instance.getPropertyString("audio-codec-name")
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
                                playbackOutputDiagnosticLabel(
                                    status = passthroughStatus,
                                    activeLabel = "源码输出 · ${decoder ?: outputFormat ?: "未知编码"}",
                                ),
                        ),
                )
            }
            AppLog.info(
                category = "player.mpv",
                event = "audio_output_configured",
                message = "mpv audio output was configured",
                attributes =
                    mapOf(
                        "output" to (instance.getPropertyString("current-ao") ?: "unknown"),
                        "format" to (outputFormat ?: "unknown"),
                        "codec" to (decoder ?: "unknown"),
                        "track" to (instance.getPropertyString("aid") ?: "unknown"),
                        "passthrough" to passthroughStatus.toString(),
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
        val replacing = endFileTracker.beforeLoad()
        val loaded = withMpvResult { it.command(arrayOf("loadfile", url)) }
        if (!loaded) {
            endFileTracker.rollbackLoad(replacing)
        } else {
            armFileLoadWatchdog()
        }
        return loaded
    }

    private fun armFileLoadWatchdog() {
        val attempt = fileLoadAttempt.incrementAndGet()
        fileLoadWatchdogJob?.cancel()
        fileLoadWatchdogJob =
            scope.launch {
                delay(MPV_FILE_LOAD_TIMEOUT_MS)
                val snapshot = _state.value
                if (
                    !shouldFailMpvFileLoad(
                        attempt = attempt,
                        activeAttempt = fileLoadAttempt.get(),
                        released = released,
                        buffering = snapshot.buffering,
                    )
                ) {
                    return@launch
                }
                AppLog.error(
                    category = "player.mpv",
                    event = "file_load_timeout",
                    message = "mpv did not finish opening media before the startup deadline",
                    attributes =
                        mapOf(
                            "itemIndex" to snapshot.currentIndex.toString(),
                            "timeoutMs" to MPV_FILE_LOAD_TIMEOUT_MS.toString(),
                            "transcoding" to snapshot.transcoding.toString(),
                        ),
                )
                markTerminalFailure("mpv 打开媒体超时，正在尝试其他播放器")
            }
    }

    private fun cancelFileLoadWatchdog() {
        fileLoadAttempt.incrementAndGet()
        fileLoadWatchdogJob?.cancel()
        fileLoadWatchdogJob = null
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
