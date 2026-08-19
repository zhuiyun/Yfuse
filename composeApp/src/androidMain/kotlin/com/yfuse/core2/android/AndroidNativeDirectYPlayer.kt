package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.sync.YMediaClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * First runnable Core2 player: MediaExtractor → MediaCodec → Surface plus MediaCodec → AudioTrack.
 *
 * This implementation is intentionally not wired as the production default yet. It exists so
 * Phase 1 can harden the native lifecycle and timing behind the stable YPlayer API while Legacy
 * remains the fallback for every user.
 */
internal class AndroidNativeDirectYPlayer(
    context: Context,
    private val request: YPlayerOpenRequest,
) : YPlayer {
    private val appContext = context.applicationContext
    private val mutableState =
        MutableStateFlow(
            YPlayerState(
                phase = YPlaybackPhase.Idle,
                playbackRequested = request.autoPlay,
                buffering = false,
                positionMs = request.startPositionMs,
                currentIndex = request.startIndex,
                itemCount = request.items.size,
                diagnostics =
                    YPlayerDiagnostics(
                        route = YPlaybackRoute.NativeDirect,
                        demuxer = "MediaExtractor",
                        renderer = "SurfaceView / AudioTrack",
                        reason = "YCore 2.0 NativeDirect experimental path",
                    ),
            ),
        )
    override val state: StateFlow<YPlayerState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker: Job = scope.launch { runLoop() }

    @Volatile
    private var released = false

    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    override fun prepare() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                buffering = it.playbackRequested,
                error = null,
            )
        }
        commands.trySend(Command.Prepare)
    }

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released) return false
        if (output != null && output !is AndroidSurfaceVideoOutput) return false
        commands.trySend(Command.SetVideoOutput(output as AndroidSurfaceVideoOutput?))
        return true
    }

    override fun play() {
        if (released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = true,
                buffering = it.phase != YPlaybackPhase.Ended,
                error = null,
            )
        }
        commands.trySend(Command.Play)
    }

    override fun pause() {
        if (released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = false,
                playing = false,
                buffering = false,
            )
        }
        commands.trySend(Command.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val bounded = positionMs.coerceAtLeast(0L)
        mutableState.updateState {
            it.copy(
                positionMs = bounded,
                buffering = it.playbackRequested,
                phase = if (it.phase == YPlaybackPhase.Ended) YPlaybackPhase.Ready else it.phase,
            )
        }
        commands.trySend(Command.Seek(bounded * MICROS_PER_MILLISECOND))
    }

    override fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed <= 0f) return
        mutableState.updateState { it.copy(speed = speed) }
        commands.trySend(Command.SetSpeed(speed))
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (released) return
        when (type) {
            YTrackType.Audio ->
                id.removePrefix(AUDIO_TRACK_PREFIX).toIntOrNull()?.let {
                    commands.trySend(Command.SelectAudioTrack(it))
                }
            YTrackType.Subtitle -> Unit // Phase 6/7 renderer; Legacy remains the fallback today.
        }
    }

    override fun selectItem(index: Int) {
        if (released || index !in request.items.indices) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                playing = false,
                buffering = it.playbackRequested,
                positionMs = 0L,
                currentIndex = index,
                error = null,
            )
        }
        commands.trySend(Command.SelectItem(index))
    }

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                error = null,
                buffering = it.playbackRequested,
            )
        }
        commands.trySend(Command.Prepare)
    }

    override fun release() {
        if (released) return
        released = true
        commands.close()
        worker.cancel()
        scope.cancel()
        mutableState.value =
            mutableState.value.copy(
                phase = YPlaybackPhase.Idle,
                playing = false,
                playbackRequested = false,
                buffering = false,
            )
    }

    private suspend fun runLoop() {
        val session = NativeSession(appContext)
        try {
            while (scope.isActive) {
                var handledCommand = false
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    handledCommand = true
                    runCatching { session.handle(command) }
                        .onFailure { fail(session, it) }
                }

                val canPump = session.canPump
                val didWork =
                    if (canPump) {
                        runCatching { session.pump() }
                            .onFailure { fail(session, it) }
                            .getOrDefault(false)
                    } else {
                        false
                    }

                if (!handledCommand && !didWork) {
                    if (canPump) {
                        delay(PUMP_IDLE_DELAY_MS)
                    } else {
                        val command = commands.receiveCatching().getOrNull() ?: break
                        runCatching { session.handle(command) }
                            .onFailure { fail(session, it) }
                    }
                }
            }
        } finally {
            session.releaseAll()
        }
    }

    private fun fail(
        session: NativeSession,
        throwable: Throwable,
    ) {
        session.releaseMedia()
        val failureType = throwable::class.simpleName ?: "PlaybackFailure"
        mutableState.value =
            mutableState.value.copy(
                phase = YPlaybackPhase.Failed,
                playing = false,
                playbackRequested = false,
                buffering = false,
                error = "YCore 2.0 原生播放失败（$failureType）",
                diagnostics =
                    mutableState.value.diagnostics.copy(
                        videoOutput = "停止",
                        audioOutput = "停止",
                        // Never copy Throwable.message: media/framework exceptions can contain a URL.
                        reason = "NativeDirect failed before route fallback",
                    ),
            )
    }

    private inner class NativeSession(
        context: Context,
    ) {
        private val demux = AndroidMediaExtractorDemuxNode(context)
        private val videoDecoder = AndroidMediaCodecVideoNode()
        private val audioDecoder = AndroidMediaCodecAudioNode()
        private val audioRenderer = AndroidAudioTrackRenderNode()
        private val wallClock = YMediaClock(positionUs = request.startPositionMs * MICROS_PER_MILLISECOND)

        private var currentIndex = request.startIndex
        private var surfaceOutput: AndroidSurfaceVideoOutput? = null
        private var videoTrackIndex: Int? = null
        private var audioTrackIndex: Int? = null
        private var videoFormat: MediaFormat? = null
        private var audioInputFormat: MediaFormat? = null
        private var audioRendererConfigured = false
        private var prepared = false
        private var videoConfigured = false
        private var requestedPlay = request.autoPlay
        private var speed = 1f
        private var sampleBuffer = ByteBuffer.allocateDirect(DEFAULT_SAMPLE_BUFFER_BYTES)
        private var inputEnded = false
        private var videoInputEnded = false
        private var audioInputEnded = false
        private var videoOutputEnded = false
        private var audioOutputEnded = false
        private var pendingVideoOutput: YCodecOutputResult.Buffer? = null
        private var lastQueuedPresentationUs = 0L
        private var lastVideoPresentationUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetVideoUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetAudioUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var firstVideoFrameRendered = false
        private var lastStatePublishNs = 0L

        val canPump: Boolean
            get() =
                prepared &&
                    requestedPlay &&
                    videoConfigured &&
                    surfaceOutput?.surface?.isValid == true &&
                    mutableState.value.phase != YPlaybackPhase.Failed &&
                    !isEnded()

        fun handle(command: Command) {
            when (command) {
                Command.Prepare -> prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                Command.Play -> startPlayback()
                Command.Pause -> pausePlayback()
                is Command.Seek -> seekTo(command.positionUs)
                is Command.SetSpeed -> updateSpeed(command.speed)
                is Command.SetVideoOutput -> setSurface(command.output)
                is Command.SelectAudioTrack -> selectAudioTrack(command.trackIndex)
                is Command.SelectItem -> {
                    currentIndex = command.index
                    prepareCurrent(0L)
                }
            }
        }

        fun pump(): Boolean {
            var didWork = false
            didWork = drainAudio() || didWork
            didWork = drainVideo() || didWork
            didWork = feedInput() || didWork
            publishClockPosition()
            finishIfEnded()
            return didWork
        }

        private fun prepareCurrent(positionUs: Long) {
            releaseMedia()
            val item = request.items[currentIndex]
            mutableState.value =
                mutableState.value.copy(
                    phase = YPlaybackPhase.Preparing,
                    playing = false,
                    buffering = requestedPlay,
                    positionMs = positionUs / MICROS_PER_MILLISECOND,
                    currentIndex = currentIndex,
                    itemCount = request.items.size,
                    error = null,
                )

            demux.open(item.toAndroidSource())
            videoTrackIndex = demux.findFirstTrack(VIDEO_MIME_PREFIX)
            checkNotNull(videoTrackIndex) { "NativeDirect requires a video track" }
            audioTrackIndex = demux.findFirstTrack(AUDIO_MIME_PREFIX)
            videoFormat = demux.trackFormat(requireNotNull(videoTrackIndex))
            audioInputFormat = audioTrackIndex?.let(demux::trackFormat)

            demux.selectTrack(requireNotNull(videoTrackIndex))
            audioTrackIndex?.let(demux::selectTrack)

            val sampleCapacity =
                listOfNotNull(videoFormat, audioInputFormat)
                    .maxOfOrNull { format -> format.maxInputSizeOr(DEFAULT_SAMPLE_BUFFER_BYTES) }
                    ?.coerceIn(MIN_SAMPLE_BUFFER_BYTES, MAX_SAMPLE_BUFFER_BYTES)
                    ?: DEFAULT_SAMPLE_BUFFER_BYTES
            sampleBuffer = ByteBuffer.allocateDirect(sampleCapacity)

            audioInputFormat?.let(audioDecoder::configure)
            surfaceOutput?.surface?.takeIf { it.isValid }?.let { surface ->
                videoDecoder.configure(requireNotNull(videoFormat), surface)
                videoConfigured = true
            }

            prepared = true
            resetEndState()
            val durationUs =
                listOfNotNull(videoFormat, audioInputFormat)
                    .mapNotNull { format -> format.durationUsOrNull() }
                    .maxOrNull()
                    ?: 0L
            val tracks = audioTracks()
            mutableState.value =
                mutableState.value.copy(
                    phase = YPlaybackPhase.Ready,
                    durationMs = durationUs / MICROS_PER_MILLISECOND,
                    audioTracks = tracks,
                    buffering = requestedPlay,
                    playbackRequested = requestedPlay,
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            route = YPlaybackRoute.NativeDirect,
                            demuxer = demux.name,
                            decoder =
                                listOfNotNull(
                                    videoDecoder.decoderName,
                                    audioDecoder.decoderName,
                                ).joinToString(" + "),
                            renderer = "Surface + AudioTrack",
                            dynamicRange = videoFormat.dynamicRangeLabel(),
                            videoOutput = if (videoConfigured) "等待首帧" else "等待 Surface",
                            audioOutput = if (audioInputFormat != null) "等待 PCM 输出" else "无音频轨",
                            reason = "Platform demux + hardware decode + direct Surface",
                        ),
                )

            val targetUs = positionUs.coerceAtLeast(0L)
            if (targetUs > 0L) seekTo(targetUs) else wallClock.seek(0L, System.nanoTime())
            if (requestedPlay) startPlayback()
        }

        private fun setSurface(output: AndroidSurfaceVideoOutput?) {
            val previous = surfaceOutput
            surfaceOutput = output
            val newSurface = output?.surface?.takeIf { it.isValid }
            if (newSurface == null) {
                val positionUs = currentPositionUs()
                videoDecoder.release()
                videoConfigured = false
                pausePlaybackInternal(keepRequested = true)
                wallClock.seek(positionUs, System.nanoTime())
                mutableState.value =
                    mutableState.value.copy(
                        playing = false,
                        buffering = requestedPlay,
                        diagnostics = mutableState.value.diagnostics.copy(videoOutput = "等待 Surface"),
                    )
                return
            }
            if (videoConfigured && previous?.surface?.isValid == true) {
                runCatching { videoDecoder.setOutputSurface(newSurface) }
                    .onSuccess {
                        if (requestedPlay) startPlayback()
                        return
                    }
            }
            if (prepared) {
                val resumeUs = currentPositionUs()
                videoDecoder.configure(requireNotNull(videoFormat), newSurface)
                videoConfigured = true
                seekTo(resumeUs)
                if (requestedPlay) startPlayback()
            }
        }

        private fun startPlayback() {
            requestedPlay = true
            if (!prepared) {
                prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                return
            }
            if (!videoConfigured) {
                mutableState.value =
                    mutableState.value.copy(
                        playbackRequested = true,
                        playing = false,
                        buffering = true,
                    )
                return
            }
            val now = System.nanoTime()
            wallClock.start(currentPositionUs(), now)
            if (audioRendererConfigured) {
                audioRenderer.setSpeed(speed)
                audioRenderer.play()
            }
            mutableState.value =
                mutableState.value.copy(
                    playbackRequested = true,
                    playing = firstVideoFrameRendered,
                    buffering = !firstVideoFrameRendered,
                    phase = if (isEnded()) YPlaybackPhase.Ended else YPlaybackPhase.Ready,
                )
        }

        private fun pausePlayback() {
            requestedPlay = false
            pausePlaybackInternal(keepRequested = false)
        }

        private fun pausePlaybackInternal(keepRequested: Boolean) {
            val positionUs = currentPositionUs()
            wallClock.pause(positionUs, System.nanoTime())
            audioRenderer.pause()
            if (!keepRequested) requestedPlay = false
            mutableState.value =
                mutableState.value.copy(
                    playbackRequested = requestedPlay,
                    playing = false,
                    buffering = false,
                    positionMs = positionUs / MICROS_PER_MILLISECOND,
                )
        }

        private fun seekTo(positionUs: Long) {
            if (!prepared) return
            val targetUs = positionUs.coerceAtLeast(0L)
            demux.seekTo(targetUs)
            if (videoConfigured) videoDecoder.flush()
            if (audioInputFormat != null) audioDecoder.flush()
            if (audioRendererConfigured) audioRenderer.flush()
            resetEndState()
            seekTargetVideoUs = targetUs
            seekTargetAudioUs = targetUs
            lastVideoPresentationUs = targetUs
            lastQueuedPresentationUs = targetUs
            firstVideoFrameRendered = false
            wallClock.seek(targetUs, System.nanoTime())
            mutableState.value =
                mutableState.value.copy(
                    phase = YPlaybackPhase.Ready,
                    playing = false,
                    buffering = requestedPlay,
                    positionMs = targetUs / MICROS_PER_MILLISECOND,
                    error = null,
                )
            if (requestedPlay) startPlayback()
        }

        private fun updateSpeed(value: Float) {
            if (!value.isFinite() || value <= 0f) return
            val positionUs = currentPositionUs()
            speed = value
            wallClock.setSpeed(value, positionUs, System.nanoTime())
            if (audioRendererConfigured) audioRenderer.setSpeed(value)
            mutableState.value = mutableState.value.copy(speed = value)
        }

        private fun selectAudioTrack(trackIndex: Int) {
            if (!prepared || trackIndex == audioTrackIndex || trackIndex !in 0 until demux.trackCount) return
            val format = demux.trackFormat(trackIndex)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith(AUDIO_MIME_PREFIX) != true) return
            val positionUs = currentPositionUs()
            audioTrackIndex?.let(demux::unselectTrack)
            demux.selectTrack(trackIndex)
            audioTrackIndex = trackIndex
            audioInputFormat = format
            audioDecoder.configure(format)
            audioRenderer.release()
            audioRendererConfigured = false
            seekTo(positionUs)
            mutableState.value = mutableState.value.copy(audioTracks = audioTracks())
        }

        private fun feedInput(): Boolean {
            if (inputEnded) {
                var queued = false
                if (!videoInputEnded && videoConfigured) {
                    if (videoDecoder.queueEndOfStream(lastQueuedPresentationUs) == YCodecQueueResult.Queued) {
                        videoInputEnded = true
                        queued = true
                    }
                }
                if (!audioInputEnded && audioInputFormat != null) {
                    if (audioDecoder.queueEndOfStream(lastQueuedPresentationUs) == YCodecQueueResult.Queued) {
                        audioInputEnded = true
                        queued = true
                    }
                }
                return queued
            }

            val positionUs = currentPositionUs()
            if (lastQueuedPresentationUs - positionUs > MAX_INPUT_AHEAD_US) return false

            val sample = demux.readSample(sampleBuffer)
            if (sample == null) {
                inputEnded = true
                return true
            }
            val queued =
                when (sample.trackIndex) {
                    videoTrackIndex ->
                        if (videoConfigured) {
                            videoDecoder.queueAccessUnit(
                                sample.data,
                                sample.presentationTimeUs,
                                sample.flags,
                            )
                        } else {
                            YCodecQueueResult.TryAgain
                        }
                    audioTrackIndex ->
                        audioDecoder.queueAccessUnit(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.flags,
                        )
                    else -> YCodecQueueResult.Queued
                }
            if (queued != YCodecQueueResult.Queued) return false
            lastQueuedPresentationUs = maxOf(lastQueuedPresentationUs, sample.presentationTimeUs)
            demux.advance()
            return true
        }

        private fun drainAudio(): Boolean {
            if (audioInputFormat == null || audioOutputEnded) return false
            return when (val output = audioDecoder.dequeueOutput()) {
                YAudioCodecOutputResult.TryAgain -> false
                is YAudioCodecOutputResult.FormatChanged -> {
                    audioRenderer.configure(output.format)
                    audioRendererConfigured = true
                    audioRenderer.setSpeed(speed)
                    if (requestedPlay) audioRenderer.play()
                    mutableState.value =
                        mutableState.value.copy(
                            diagnostics = mutableState.value.diagnostics.copy(audioOutput = "PCM · AudioTrack"),
                        )
                    true
                }
                is YAudioCodecOutputResult.Buffer -> {
                    try {
                        val configBuffer = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!configBuffer && output.size > 0 && output.presentationTimeUs >= seekTargetAudioUs) {
                            if (!audioRendererConfigured) {
                                // Android normally emits INFO_OUTPUT_FORMAT_CHANGED first; keep the
                                // failure explicit rather than silently dropping audio if an OEM does not.
                                error("Audio output arrived before PCM format")
                            }
                            audioRenderer.write(
                                audioDecoder.outputData(output),
                                output.presentationTimeUs,
                            )
                            seekTargetAudioUs = 0L
                        }
                        if (output.endOfStream) audioOutputEnded = true
                    } finally {
                        audioDecoder.releaseOutput(output)
                    }
                    true
                }
            }
        }

        private fun drainVideo(): Boolean {
            if (!videoConfigured || videoOutputEnded) return false
            val output =
                pendingVideoOutput ?: when (val dequeued = videoDecoder.dequeueOutput()) {
                    YCodecOutputResult.TryAgain -> return false
                    is YCodecOutputResult.FormatChanged -> {
                        mutableState.value =
                            mutableState.value.copy(
                                diagnostics =
                                    mutableState.value.diagnostics.copy(
                                        videoOutput = "硬解已配置 · 等待首帧",
                                    ),
                            )
                        return true
                    }
                    is YCodecOutputResult.Buffer -> dequeued
                }

            val configBuffer = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val renderable = !configBuffer && output.size > 0 && output.presentationTimeUs >= seekTargetVideoUs
            if (renderable) {
                val currentUs = currentPositionUs()
                if (output.presentationTimeUs - currentUs > MAX_VIDEO_SCHEDULE_AHEAD_US) {
                    pendingVideoOutput = output
                    return false
                }
                pendingVideoOutput = null
                val nowNs = System.nanoTime()
                val renderNs =
                    if (audioRendererConfigured) {
                        audioRenderer.presentationTimeNs(output.presentationTimeUs, nowNs)
                    } else {
                        wallClock.presentationTimeNs(output.presentationTimeUs)
                    }.coerceAtLeast(nowNs - LATE_FRAME_IMMEDIATE_NS)
                videoDecoder.releaseOutput(output, render = true, renderTimeNs = renderNs)
                lastVideoPresentationUs = output.presentationTimeUs
                seekTargetVideoUs = 0L
                if (!firstVideoFrameRendered) {
                    firstVideoFrameRendered = true
                    mutableState.value =
                        mutableState.value.copy(
                            phase = YPlaybackPhase.Ready,
                            playing = requestedPlay,
                            buffering = false,
                            diagnostics =
                                mutableState.value.diagnostics.copy(
                                    videoOutput = "Surface 直出",
                                ),
                        )
                }
            } else {
                pendingVideoOutput = null
                videoDecoder.releaseOutput(output, render = false)
            }
            if (output.endOfStream) videoOutputEnded = true
            return true
        }

        private fun publishClockPosition() {
            val nowNs = System.nanoTime()
            if (nowNs - lastStatePublishNs < STATE_PUBLISH_INTERVAL_NS) return
            lastStatePublishNs = nowNs
            val positionUs = currentPositionUs()
            mutableState.value =
                mutableState.value.copy(
                    positionMs = positionUs / MICROS_PER_MILLISECOND,
                    playing = requestedPlay && firstVideoFrameRendered && !isEnded(),
                    buffering = requestedPlay && !firstVideoFrameRendered && !isEnded(),
                )
        }

        private fun currentPositionUs(): Long =
            audioRenderer.clockSnapshot()?.positionUs
                ?: if (requestedPlay) {
                    wallClock.positionUs(System.nanoTime())
                } else {
                    maxOf(
                        mutableState.value.positionMs * MICROS_PER_MILLISECOND,
                        lastVideoPresentationUs,
                    )
                }

        private fun finishIfEnded() {
            if (!isEnded()) return
            val endPositionUs =
                maxOf(
                    lastVideoPresentationUs,
                    mutableState.value.durationMs * MICROS_PER_MILLISECOND,
                )
            requestedPlay = false
            audioRenderer.pause()
            wallClock.pause(endPositionUs, System.nanoTime())
            mutableState.value =
                mutableState.value.copy(
                    phase = YPlaybackPhase.Ended,
                    playing = false,
                    playbackRequested = false,
                    buffering = false,
                    positionMs = endPositionUs / MICROS_PER_MILLISECOND,
                )
        }

        private fun isEnded(): Boolean = videoOutputEnded && (audioInputFormat == null || audioOutputEnded)

        private fun resetEndState() {
            inputEnded = false
            videoInputEnded = false
            audioInputEnded = audioInputFormat == null
            videoOutputEnded = false
            audioOutputEnded = audioInputFormat == null
            pendingVideoOutput = null
        }

        private fun audioTracks(): List<YTrack> =
            (0 until demux.trackCount).mapNotNull { index ->
                val format = demux.trackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                if (!mime.startsWith(AUDIO_MIME_PREFIX)) return@mapNotNull null
                val language = format.getString(MediaFormat.KEY_LANGUAGE)
                YTrack(
                    id = "$AUDIO_TRACK_PREFIX$index",
                    type = YTrackType.Audio,
                    label = language?.takeIf(String::isNotBlank) ?: "Audio ${index + 1}",
                    language = language,
                    codec = mime,
                    selected = index == audioTrackIndex,
                )
            }

        fun releaseMedia() {
            pendingVideoOutput?.let { output ->
                runCatching { videoDecoder.releaseOutput(output, render = false) }
            }
            pendingVideoOutput = null
            runCatching(audioRenderer::release)
            runCatching(audioDecoder::release)
            runCatching(videoDecoder::release)
            runCatching(demux::release)
            prepared = false
            videoConfigured = false
            audioRendererConfigured = false
            videoTrackIndex = null
            audioTrackIndex = null
            videoFormat = null
            audioInputFormat = null
            resetEndState()
        }

        fun releaseAll() {
            releaseMedia()
            surfaceOutput = null
        }
    }

    private sealed interface Command {
        data object Prepare : Command

        data object Play : Command

        data object Pause : Command

        data class Seek(
            val positionUs: Long,
        ) : Command

        data class SetSpeed(
            val speed: Float,
        ) : Command

        data class SetVideoOutput(
            val output: AndroidSurfaceVideoOutput?,
        ) : Command

        data class SelectAudioTrack(
            val trackIndex: Int,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

private fun YMediaItem.toAndroidSource(): YAndroidMediaSource =
    YAndroidMediaSource(
        uri = uri,
        headers = headers,
    )

private fun MediaFormat.durationUsOrNull(): Long? =
    if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else null

private fun MediaFormat?.dynamicRangeLabel(): String {
    val format = this ?: return "Unknown"
    val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase()
    if (mime == DOLBY_VISION_MIME) return "Dolby Vision"
    val transfer =
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
            null
        }
    return when (transfer) {
        COLOR_TRANSFER_ST2084 -> "HDR10/PQ"
        COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR/Unknown"
    }
}

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    value = transform(value)
}

private const val VIDEO_MIME_PREFIX = "video/"
private const val AUDIO_MIME_PREFIX = "audio/"
private const val AUDIO_TRACK_PREFIX = "audio:"
private const val DOLBY_VISION_MIME = "video/dolby-vision"
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
private const val MIN_SAMPLE_BUFFER_BYTES = 256 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
private const val MAX_INPUT_AHEAD_US = 1_500_000L
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
