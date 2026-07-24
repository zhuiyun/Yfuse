package com.yfuse.feature.player

import android.content.Context
import android.util.Log
import android.view.Surface
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "YfusePlayer"

/** mpv pushes `time-pos` per frame; only forward moves of at least this much. */
private const val POSITION_STEP_MS = 200L

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
    private val items: List<PlayerMediaItem>,
    startIndex: Int,
    private val startPositionMs: Long,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    quality: PlaybackQuality,
) : VideoEngine {

    private val preferTranscode = quality != PlaybackQuality.Auto

    private val _state = MutableStateFlow(
        PlaybackState(currentIndex = startIndex, itemCount = items.size.coerceAtLeast(1)),
    )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var mpv: MPVLib? = null

    @Volatile
    private var released = false

    @Volatile
    private var attachedSurface: Surface? = null

    private var lastPositionMs = -POSITION_STEP_MS

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "track-list/count" -> readTracks()
                "video-params/h" -> _state.update { it.copy(videoHeight = value.toInt()) }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    val ms = (value * 1000).toLong().coerceAtLeast(0L)
                    if (kotlin.math.abs(ms - lastPositionMs) < POSITION_STEP_MS) return
                    lastPositionMs = ms
                    _state.update { it.copy(positionMs = ms) }
                }

                "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
                "speed" -> _state.update { it.copy(speed = value.toFloat()) }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> _state.update { it.copy(playing = !value) }
                "paused-for-cache" -> _state.update { it.copy(buffering = value) }
                // keep-open=always parks mpv on the last frame instead of
                // advancing, so the queue is stepped by hand.
                "eof-reached" -> when {
                    !value -> _state.update { it.copy(ended = false) }
                    autoNext && _state.value.hasNext -> playNextIfAny()
                    else -> _state.update { it.copy(playing = false, buffering = false, ended = true) }
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            // aid/sid are read as strings because either can be "no".
            if (property == "aid" || property == "sid") readTracks()
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE ->
                    _state.update { it.copy(buffering = true, error = null, ended = false) }

                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    // `start` is a global option; clear it so the next episode
                    // does not also jump to the resume point.
                    withMpv { it.setOptionString("start", "none") }
                    _state.update { it.copy(buffering = false) }
                    readTracks()
                    readVideoSize()
                }

                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> readVideoSize()
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
            runCatching { existing.attachSurface(surface) }
                .onFailure { Log.e(TAG, "mpv re-attach failed", it) }
            return
        }

        runCatching {
            val instance = MPVLib.create(context)
            if (instance == null) {
                Log.e(TAG, "MPVLib.create returned null")
                _state.update { it.copy(error = "无法初始化 mpv", buffering = false) }
                return
            }
            mpv = instance
            // Don't read the user's mpv config from disk.
            instance.setOptionString("config", "no")
            instance.setOptionString("vo", "gpu")
            instance.setOptionString("gpu-context", "android")
            instance.setOptionString(
                "hwdec",
                when (decoderMode) {
                    DecoderMode.Hardware -> "auto-safe"
                    DecoderMode.Software -> "no"
                    DecoderMode.Auto -> "auto"
                },
            )
            instance.setOptionString("keep-open", "always")
            instance.setOptionString("cache", "yes")
            if (startPositionMs > 0) {
                instance.setOptionString("start", "+${startPositionMs / 1000}")
            }
            instance.init()

            instance.addObserver(observer)
            instance.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            instance.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            instance.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            instance.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)

            instance.attachSurface(surface)
            instance.setOptionString("force-window", "yes")
            instance.command(arrayOf("loadfile", currentUrl()))
            Log.i(TAG, "mpv loadfile issued")
        }.onFailure {
            Log.e(TAG, "mpv start failed", it)
            _state.update { state -> state.copy(error = "mpv 启动失败", buffering = false) }
        }
    }

    fun detach() {
        withMpv { it.detachSurface() }
        attachedSurface = null
    }

    /** Crop-to-fill instead of letterboxing, for the 全屏 toggle. */
    fun setFill(fill: Boolean) {
        withMpv { it.setPropertyDouble("panscan", if (fill) 1.0 else 0.0) }
    }

    override fun play() {
        _state.update { it.copy(ended = false) }
        withMpv { it.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        withMpv { it.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMs: Long) {
        lastPositionMs = positionMs
        _state.update { it.copy(positionMs = positionMs, ended = false) }
        withMpv { it.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute")) }
    }

    override fun setSpeed(speed: Float) {
        withMpv { it.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun selectAudioTrack(id: String) = selectTrack("aid", id)

    override fun selectSubtitleTrack(id: String) = selectTrack("sid", id)

    override fun selectItem(index: Int) {
        if (index !in items.indices) return
        lastPositionMs = -POSITION_STEP_MS
        _state.update {
            it.copy(
                currentIndex = index,
                positionMs = 0L,
                durationMs = 0L,
                buffering = true,
                ended = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
            )
        }
        withMpv { it.command(arrayOf("loadfile", playbackUrl(items[index]))) }
    }

    override fun currentPositionMs(): Long = _state.value.positionMs

    override fun retry() {
        val position = _state.value.positionMs
        _state.update { it.copy(error = null, buffering = true, ended = false) }
        if (mpv == null) {
            attachedSurface?.let(::attach)
            return
        }
        withMpv { instance ->
            if (position > 0L) {
                instance.setOptionString("start", "+${position / 1000.0}")
            }
            instance.command(arrayOf("loadfile", currentUrl()))
        }
    }

    override fun release() {
        if (released) return
        released = true
        val instance = mpv ?: return
        mpv = null
        runCatching {
            instance.removeObserver(observer)
            instance.command(arrayOf("stop"))
            instance.destroy()
        }.onFailure { Log.w(TAG, "mpv teardown failed", it) }
    }

    private fun currentUrl(): String =
        items.getOrNull(_state.value.currentIndex)?.let(::playbackUrl)
            ?: items.firstOrNull()?.let(::playbackUrl).orEmpty()

    private fun playbackUrl(item: PlayerMediaItem): String =
        if (preferTranscode && item.transcodeUrl.isNotEmpty()) item.transcodeUrl else item.url

    private fun playNextIfAny() {
        val next = _state.value.currentIndex + 1
        if (next < items.size) selectItem(next)
    }

    private fun selectTrack(property: String, id: String) {
        withMpv { instance ->
            if (id == EngineTrack.OFF) instance.setPropertyString(property, "no")
            else id.toIntOrNull()?.let { instance.setPropertyInt(property, it) }
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
            val audio = mutableListOf<EngineTrack>()
            val subtitles = mutableListOf<EngineTrack>()

            for (i in 0 until count) {
                val type = instance.getPropertyString("track-list/$i/type") ?: continue
                if (type != "audio" && type != "sub") continue
                val id = instance.getPropertyInt("track-list/$i/id") ?: continue
                val language = instance.getPropertyString("track-list/$i/lang")
                val title = instance.getPropertyString("track-list/$i/title")
                val bucket = if (type == "audio") audio else subtitles
                bucket += EngineTrack(
                    id = id.toString(),
                    label = title ?: language ?: "${if (type == "audio") "音轨" else "字幕"} ${bucket.size + 1}",
                    language = language,
                    selected = instance.getPropertyBoolean("track-list/$i/selected") ?: false,
                )
            }

            _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
        }.onFailure { Log.w(TAG, "reading track-list failed", it) }
    }

    private fun readVideoSize() {
        withMpv { instance ->
            instance.getPropertyInt("video-params/h")?.let { height ->
                _state.update { it.copy(videoHeight = height) }
            }
        }
    }

    /** mpv calls throw once the handle is gone; every call site tolerates a miss. */
    private inline fun withMpv(block: (MPVLib) -> Unit) {
        val instance = mpv ?: return
        runCatching { block(instance) }.onFailure { Log.w(TAG, "mpv call failed", it) }
    }
}
