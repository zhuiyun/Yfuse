package com.yfuse.feature.player

import android.util.Log
import android.view.SurfaceView
import com.mediadevkit.sdk.MDKPlayer
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlaybackMethod
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val MDK_TAG = "YfuseMdk"
private const val MDK_POLL_MS = 250L

/** Polls to let a freshly-loaded fallback settle before its status is trusted again. */
private const val FALLBACK_SETTLE_POLLS = 12
private const val TRACK_SEPARATOR = '\u001F'

/** Thread-safe because MDK polling runs on Default while encoder cleanup resumes on Main. */
internal class FallbackSettleWindow(private val requiredPolls: Int) {
    private val polls = AtomicInteger(Int.MAX_VALUE)

    val ready: Boolean
        get() = polls.get() >= requiredPolls

    fun tick() {
        polls.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) current else current + 1
        }
    }

    fun restart() {
        polls.set(0)
    }
}

/** Official libmdk Android facade adapted to Yfuse's engine-neutral player contract. */
class MdkVideoEngine(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    private val quality: PlaybackQuality,
    private val customUserAgent: String,
    private val scope: CoroutineScope,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {

    private val items = items.map { it.withPlaybackQuality(quality) }

    /** Entries pushed off their original file onto the server's transcode, and past that
     *  onto its progressive MP4. Kept per index so one bad episode doesn't transcode the
     *  rest of the season. */
    private val transcodedIndices = items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
        index.takeIf { item.startsWithServerTranscode(quality) }
    }
    private val progressiveIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private var fallbackJob: Job? = null
    private val _state = MutableStateFlow(
        PlaybackState(
            currentIndex = startIndex,
            itemCount = items.size.coerceAtLeast(1),
            transcoding = startIndex in transcodedIndices,
            videoHeight = items.getOrNull(startIndex)
                ?.sourceVideoHeight(startIndex in transcodedIndices)
                ?: 0,
            diagnostics = initialPlaybackDiagnostics(
                engine = "MDK",
                decoder = decoderMode.label,
                item = items.getOrNull(startIndex),
                quality = quality,
            ),
        ),
    )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var player: MDKPlayer? = null

    @Volatile
    private var released = false

    @Volatile
    private var playRequested = true

    override val playbackRequested: Boolean
        get() = playRequested && !_state.value.ended

    private var attachedView: SurfaceView? = null
    private var pendingSeekMs = startPositionMs.coerceAtLeast(0L)
    private var tracksLoadedForIndex = -1
    private var endHandled = false
    private var fill = false
    private var wasBuffering = true
    private val fallbackSettleWindow = FallbackSettleWindow(FALLBACK_SETTLE_POLLS)

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
        val transcoding = index in transcodedIndices
        val nextItem = items.getOrNull(index)
        _state.update {
            it.copy(
                currentIndex = index,
                playing = true,
                buffering = true,
                positionMs = 0L,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                videoHeight = nextItem?.sourceVideoHeight(transcoding) ?: 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                error = null,
                ended = false,
                transcoding = transcoding,
                fallbacksExhausted = false,
                automaticFallbackBlocked = false,
                diagnostics = initialPlaybackDiagnostics(
                    engine = "MDK",
                    decoder = it.diagnostics.decoder,
                    item = nextItem,
                    quality = quality,
                    transcoding = transcoding,
                ),
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
        val instance = player
        player = null
        attachedView = null
        runCatching {
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
                customUserAgent.trim().takeIf { it.isNotEmpty() }?.let { value ->
                    instance.setProperty("avio.user_agent", value)
                }
                instance.setFill(fill)
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
        playRequested = true
        runCatching {
            instance.setMedia(playbackUrl(item, index))
            instance.setState(MDKPlayer.STATE_PLAYING)
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

    private fun poll() {
        val instance = player ?: return
        runCatching {
            val status = instance.mediaStatus()
            val loaded =
                status and (MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED) != 0
            val ended = status and MDKPlayer.STATUS_END != 0
            val invalid = status and MDKPlayer.STATUS_INVALID != 0
            if (!loaded || invalid) {
                nativePlaybackLogFailure(instance.lastError())?.let { failure ->
                    markTerminalFailure(failure)
                    return
                }
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
                if (autoNext && _state.value.hasNext) {
                    selectItem(_state.value.currentIndex + 1)
                    return
                }
            } else if (!ended) {
                endHandled = false
            }

            val positionMs = instance.position().coerceAtLeast(0L)
            val durationMs = instance.duration().coerceAtLeast(0L)
            val bufferedDurationMs = instance.bufferedDuration().coerceAtLeast(0L)
            _state.update { current ->
                current.copy(
                    playing =
                        instance.state() == MDKPlayer.STATE_PLAYING && !ended && !invalid,
                    buffering = buffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = bufferedEndPositionMs(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedDurationMs = bufferedDurationMs,
                    ),
                    speed = instance.playbackRate(),
                    videoHeight = instance.videoHeight().coerceAtLeast(0),
                    error = if (invalid) {
                        "MDK 无法播放此媒体，服务器也没有可用的转码流"
                    } else {
                        current.error
                    },
                    fallbacksExhausted = current.fallbacksExhausted || invalid,
                    ended = ended,
                    diagnostics = current.diagnostics.copy(
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

    /** Whichever step of the fallback chain [index] has been pushed to so far. */
    private fun playbackUrl(item: PlayerMediaItem, index: Int): String = when {
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
        val progressive = when {
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
            attributes = mapOf(
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
                diagnostics = it.diagnostics.copy(
                    playMethod = "服务器转码",
                    dynamicRange = "",
                    audioFormat = "",
                    fallbackReason = reason ?: if (progressive) {
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
        fallbackJob = scope.launch {
            val cleaned = item.playSessionId.isBlank() ||
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
                fallbacksExhausted = true,
                automaticFallbackBlocked = failure.blocksAutomaticFallback,
            )
        }
    }

    private inline fun runMdk(block: (MDKPlayer) -> Unit) {
        val instance = player ?: return
        runCatching { block(instance) }.onFailure {
            safeLogcat(Log.WARN, MDK_TAG, "MDK call failed", it)
            AppLog.warning(
                category = "player.mdk",
                event = "engine_call_failed",
                message = "MDK engine call failed",
                throwable = it,
            )
        }
    }
}
