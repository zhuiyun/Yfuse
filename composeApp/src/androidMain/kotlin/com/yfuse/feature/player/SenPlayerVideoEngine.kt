package com.yfuse.feature.player

import android.view.Display
import android.view.Surface
import com.mplaykit.Player
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackFailureKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native commands, snapshots and teardown share one worker and one session owner. */
internal class SenPlayerVideoEngine(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    startSpeed: Float,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    private val userAgent: String,
) : VideoEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val released = AtomicBoolean(false)
    private var queue = items.toList()
    private var output: Player? = null
    private var surface: Surface? = null
    private var display: Display? = null
    private var source = 0L
    private var stopAtEnd = false
    private var metadataPoll = 0

    @Volatile private var requested = startPlaybackRequested
    private val mutableState =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                itemCount = items.size,
                positionMs = startPositionMs.coerceAtLeast(0),
                speed = startSpeed.takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f,
                diagnostics = PlaybackDiagnostics(engine = "SenPlayer", decoder = "等待初始化"),
            ),
        )
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() = requested

    init {
        worker.scheduleWithFixedDelay({ guarded { sample() } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun guarded(action: () -> Unit) {
        if (released.get()) return
        try {
            action()
        } catch (failure: LinkageError) {
            fail("SenPlayer 运行库无法加载，请使用其他内核", PlaybackFailureKind.Unknown)
        } catch (failure: Exception) {
            // Native messages and URL parse errors may include credentials. Do not expose them.
            fail("SenPlayer 操作失败（${failure.javaClass.simpleName}），可重试或切换内核", PlaybackFailureKind.Unknown)
        }
    }

    private fun command(action: () -> Unit) {
        if (released.get()) return
        try {
            worker.execute { guarded(action) }
        } catch (_: RejectedExecutionException) {
            // A command racing release belongs to the outgoing session.
        }
    }

    fun attachSurface(
        value: Surface,
        targetDisplay: Display?,
    ) = command {
        if (!value.isValid) return@command
        surface = value
        display = targetDisplay
        if (output == null && mutableState.value.error == null) openCurrent() else output?.surface(value, null)
    }

    fun detachSurface(value: Surface) =
        command {
            if (surface !== value) return@command
            surface = null
            output?.surface(null, null)
        }

    private fun openCurrent() {
        val item = queue.getOrNull(mutableState.value.currentIndex) ?: return
        val target = surface?.takeIf { it.isValid } ?: return
        if (item.customHeaders?.values?.isNotEmpty() == true || item.drmConfiguration != null) {
            fail("此片源需要受控鉴权或 DRM，请切换 YCore / ExoPlayer", PlaybackFailureKind.Authorization)
            return
        }
        output?.close()
        output = null
        source = 0L
        mutableState.update {
            it.copy(
                error = null,
                errorKind = null,
                ended = false,
                playing = false,
                buffering = true,
                bufferedPositionMs = 0L,
                bufferedRanges = emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
            )
        }
        val options =
            linkedMapOf(
                "hwdec" to if (decoderMode == DecoderMode.Software) "no" else "auto",
                "android-subtitle-fp16" to "no",
            )
        val created = Player(target, null, display, options)
        output = created
        val openOptions = if (userAgent.isBlank()) emptyMap() else mapOf("user-agent" to userAgent)
        source = created.open(item.url, item.title, openOptions, mutableState.value.positionMs / 1000.0, !requested)
        if (source <= 0L) {
            fail("SenPlayer 拒绝打开媒体，请检查运行许可或切换内核", PlaybackFailureKind.Unknown)
            return
        }
        created.property("speed", mutableState.value.speed.toString())
        metadataPoll = 0
    }

    private fun sample() {
        val player = output ?: return
        if (mutableState.value.error != null) return
        val snapshot = player.state() ?: return
        if (snapshot.source != source) return
        if (snapshot.errorCode != 0 || snapshot.status == "failed") {
            fail("SenPlayer 播放失败（${snapshot.errorCode}），请重试或切换内核", PlaybackFailureKind.Unknown)
            return
        }
        val progress = player.progress()?.takeIf { it.source == source }
        val ended = snapshot.status == "ended"
        mutableState.update { previous ->
            previous.copy(
                playing = requested && !snapshot.paused && snapshot.status == "playing",
                buffering = snapshot.buffering,
                positionMs = secondsToMs(snapshot.position),
                durationMs = secondsToMs(snapshot.duration),
                bufferedPositionMs = progress?.bufferedEnd?.let(::secondsToMs) ?: previous.bufferedPositionMs,
                bufferedRanges =
                    progress
                        ?.ranges
                        ?.toList()
                        ?.chunked(2)
                        ?.mapNotNull { pair ->
                            if (pair.size == 2 && pair.all(Double::isFinite)) {
                                PlaybackBufferedRange(secondsToMs(pair[0]), secondsToMs(pair[1]))
                            } else {
                                null
                            }
                        }?.let { normalizeBufferedRanges(it, secondsToMs(snapshot.duration)) },
                videoHeight = snapshot.height,
                ended = ended,
                diagnostics = previous.diagnostics.copy(videoWidth = snapshot.width),
            )
        }
        if (ended && autoNext && !stopAtEnd && requested && state.value.hasNext) {
            moveTo(state.value.currentIndex + 1)
            return
        }
        if (metadataPoll++ % 8 == 0) runCatching { readTracks(player) }
    }

    private fun readTracks(player: Player) {
        val json = player.media()?.let(::JSONObject) ?: return
        if (json.optLong("source") != source) return
        val tracks = json.optJSONArray("tracks") ?: return
        val audio = mutableListOf<EngineTrack>()
        val subtitles = mutableListOf<EngineTrack>()
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val kind = track.optString("kind")
            val id = track.optLong("id", -1L)
            if (id < 0) continue
            val language = track.optString("language").takeIf(String::isNotBlank)
            val codec = track.optString("codec").takeIf(String::isNotBlank)
            val label =
                track.optString("title").ifBlank {
                    listOfNotNull(language, codec).joinToString(" · ").ifBlank { id.toString() }
                }
            val model = EngineTrack(id.toString(), label, language, track.optBoolean("selected"), codec)
            when (kind) {
                "audio" -> audio += model
                "sub" -> subtitles += model
            }
        }
        mutableState.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
    }

    private fun fail(
        message: String,
        kind: PlaybackFailureKind,
    ) {
        val old = output
        output = null
        source = 0L
        mutableState.update { it.copy(playing = false, buffering = false, error = message, errorKind = kind) }
        runCatching { old?.close() }
    }

    override fun play() {
        requested = true
        command { output?.paused(false) }
    }

    override fun pause() {
        requested = false
        command { output?.paused(true) }
    }

    override fun seekTo(positionMs: Long) =
        command {
            val position = positionMs.coerceAtLeast(0L)
            if (output?.seek(position / 1000.0) == 0) {
                mutableState.update {
                    it.copy(
                        positionMs = position,
                        ended = false,
                        bufferedPositionMs = position,
                        bufferedRanges = emptyList(),
                    )
                }
            }
        }

    override fun setSpeed(speed: Float) =
        command {
            if (!speed.isFinite() || speed !in 0.25f..4f) return@command
            if (output?.property("speed", speed.toString()) == 0) mutableState.update { it.copy(speed = speed) }
        }

    override fun selectAudioTrack(id: String) =
        command {
            if (id == EngineTrack.OFF || state.value.audioTracks.any { it.id == id }) {
                output?.select(source, 0, id.toLongOrNull() ?: 0L, id == EngineTrack.OFF)
                metadataPoll = 0
            }
        }

    override fun selectSubtitleTrack(id: String) =
        command {
            if (id == EngineTrack.OFF || state.value.subtitleTracks.any { it.id == id }) {
                output?.select(source, 1, id.toLongOrNull() ?: 0L, id == EngineTrack.OFF)
                metadataPoll = 0
            }
        }

    override fun selectItem(index: Int) = command { moveTo(index) }

    private fun moveTo(index: Int) {
        if (index !in queue.indices) return
        stopAtEnd = false
        mutableState.update {
            PlaybackState(currentIndex = index, itemCount = queue.size, speed = it.speed, diagnostics = it.diagnostics)
        }
        openCurrent()
    }

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) = command { stopAtEnd = enabled }

    override fun currentPositionMs(): Long = state.value.positionMs

    override fun retry() = command { openCurrent() }

    override fun retryFrom(positionMs: Long) =
        command {
            mutableState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
            openCurrent()
        }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        requested = false
        worker.execute {
            runCatching { output?.close() }
            output = null
            surface = null
        }
        worker.shutdown()
    }
}

internal fun secondsToMs(seconds: Double): Long =
    if (!seconds.isFinite() || seconds <= 0) 0L else (seconds * 1000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
