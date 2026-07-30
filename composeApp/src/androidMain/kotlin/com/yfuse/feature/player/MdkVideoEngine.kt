package com.yfuse.feature.player

import android.util.Log
import android.view.SurfaceView
import com.mediadevkit.sdk.MDKPlayer
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val MDK_TAG = "YfuseMdk"
private const val MDK_POLL_MS = 250L
private const val TRACK_SEPARATOR = '\u001F'

/** Official libmdk Android facade adapted to Yfuse's engine-neutral player contract. */
class MdkVideoEngine(
    private val items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    quality: PlaybackQuality,
    private val customUserAgent: String,
    scope: CoroutineScope,
) : VideoEngine {

    // Resolution switching is disabled; MDK always consumes the original source.
    private val preferTranscode = false
    private val _state = MutableStateFlow(
        PlaybackState(
            currentIndex = startIndex,
            itemCount = items.size.coerceAtLeast(1),
            diagnostics = PlaybackDiagnostics(
                engine = "MDK",
                decoder = decoderMode.label,
                playMethod = "直播放",
            ),
        ),
    )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var player: MDKPlayer? = null

    @Volatile
    private var released = false

    private var attachedView: SurfaceView? = null
    private var pendingSeekMs = startPositionMs.coerceAtLeast(0L)
    private var tracksLoadedForIndex = -1
    private var endHandled = false
    private var fill = false
    private var wasBuffering = true

    private val pollJob: Job = scope.launch(Dispatchers.Default) {
        while (isActive && !released) {
            poll()
            delay(MDK_POLL_MS)
        }
    }

    fun attach(view: SurfaceView) {
        if (released) return
        attachedView = view
        val instance = ensurePlayer() ?: return
        instance.setSurfaceView(view)
        if (_state.value.durationMs == 0L && instance.mediaStatus() == 0) {
            loadCurrent(instance)
        }
    }

    fun setFill(enabled: Boolean) {
        fill = enabled
        runMdk { it.setFill(enabled) }
    }

    override fun play() {
        _state.update { it.copy(playing = true, ended = false) }
        runMdk { it.setState(MDKPlayer.STATE_PLAYING) }
    }

    override fun pause() {
        _state.update { it.copy(playing = false) }
        runMdk { it.setState(MDKPlayer.STATE_PAUSED) }
    }

    override fun seekTo(positionMs: Long) {
        pendingSeekMs = -1L
        _state.update { it.copy(positionMs = positionMs, ended = false) }
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
                audioTracks = state.audioTracks.map {
                    it.copy(selected = it.id == id)
                },
            )
        }
    }

    override fun selectSubtitleTrack(id: String) {
        val ordinal = if (id == EngineTrack.OFF) -1 else id.toIntOrNull() ?: return
        runMdk { it.setActiveTrack(MDKPlayer.MEDIA_TYPE_SUBTITLE, ordinal) }
        _state.update { state ->
            state.copy(
                subtitleTracks = state.subtitleTracks.map {
                    it.copy(selected = ordinal >= 0 && it.id == id)
                },
            )
        }
    }

    override fun selectItem(index: Int) {
        if (index !in items.indices || released) return
        pendingSeekMs = 0L
        tracksLoadedForIndex = -1
        endHandled = false
        _state.update {
            it.copy(
                currentIndex = index,
                playing = true,
                buffering = true,
                positionMs = 0L,
                durationMs = 0L,
                videoHeight = 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                error = null,
                ended = false,
            )
        }
        ensurePlayer()?.let(::loadCurrent)
    }

    override fun currentPositionMs(): Long =
        runCatching { player?.position() }.getOrNull() ?: _state.value.positionMs

    override fun retry() {
        pendingSeekMs = _state.value.positionMs
        tracksLoadedForIndex = -1
        endHandled = false
        _state.update { it.copy(error = null, buffering = true, ended = false) }
        ensurePlayer()?.let(::loadCurrent)
    }

    override fun release() {
        if (released) return
        released = true
        pollJob.cancel()
        val instance = player
        player = null
        attachedView = null
        runCatching {
            instance?.setSurfaceView(null)
            instance?.close()
        }.onFailure { Log.w(MDK_TAG, "MDK teardown failed", it) }
    }

    private fun ensurePlayer(): MDKPlayer? {
        player?.let { return it }
        return runCatching {
            MDKPlayer().also { instance ->
                instance.setDecoderMode(decoderMode.ordinal)
                customUserAgent.trim().takeIf { it.isNotEmpty() }?.let { value ->
                    instance.setProperty("avio.user_agent", value)
                }
                instance.setFill(fill)
                player = instance
            }
        }.onFailure {
            Log.e(MDK_TAG, "MDK initialization failed", it)
            _state.update { state ->
                state.copy(error = "无法初始化 MDK 播放器", buffering = false)
            }
        }.getOrNull()
    }

    private fun loadCurrent(instance: MDKPlayer) {
        val item = items.getOrNull(_state.value.currentIndex) ?: return
        runCatching {
            instance.setMedia(playbackUrl(item))
            instance.setState(MDKPlayer.STATE_PLAYING)
        }.onFailure {
            Log.e(MDK_TAG, "MDK load failed", it)
            _state.update { state ->
                state.copy(error = "MDK 启动失败", buffering = false)
            }
        }
    }

    private fun poll() {
        val instance = player ?: return
        runCatching {
            val status = instance.mediaStatus()
            val loaded =
                status and (MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED) != 0
            val ended = status and MDKPlayer.STATUS_END != 0
            val invalid = status and MDKPlayer.STATUS_INVALID != 0
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

            if (ended && !endHandled) {
                endHandled = true
                if (autoNext && _state.value.hasNext) {
                    selectItem(_state.value.currentIndex + 1)
                    return
                }
            } else if (!ended) {
                endHandled = false
            }

            _state.update { current ->
                current.copy(
                    playing =
                        instance.state() == MDKPlayer.STATE_PLAYING && !ended && !invalid,
                    buffering = buffering,
                    positionMs = instance.position().coerceAtLeast(0L),
                    durationMs = instance.duration().coerceAtLeast(0L),
                    speed = instance.playbackRate(),
                    videoHeight = instance.videoHeight().coerceAtLeast(0),
                    error = if (invalid) "MDK 无法播放此媒体" else current.error,
                    ended = ended,
                    diagnostics = current.diagnostics.copy(
                        bufferEvents =
                            current.diagnostics.bufferEvents + if (bufferEvent) 1 else 0,
                    ),
                )
            }
        }.onFailure {
            if (!released) {
                Log.w(MDK_TAG, "MDK state polling failed", it)
                _state.update { state ->
                    state.copy(error = "MDK 播放异常", buffering = false)
                }
            }
        }
    }

    private fun refreshTracks(instance: MDKPlayer) {
        val audio = decodeTracks(
            rows = instance.tracks(MDKPlayer.MEDIA_TYPE_AUDIO),
            fallback = "音轨",
        )
        val subtitles = decodeTracks(
            rows = instance.tracks(MDKPlayer.MEDIA_TYPE_SUBTITLE),
            fallback = "字幕",
        )
        _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
    }

    private fun decodeTracks(rows: Array<String>, fallback: String): List<EngineTrack> =
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

    private fun playbackUrl(item: PlayerMediaItem): String =
        if (preferTranscode && item.transcodeUrl.isNotEmpty()) item.transcodeUrl else item.url

    private inline fun runMdk(block: (MDKPlayer) -> Unit) {
        val instance = player ?: return
        runCatching { block(instance) }.onFailure {
            Log.w(MDK_TAG, "MDK call failed", it)
        }
    }
}
