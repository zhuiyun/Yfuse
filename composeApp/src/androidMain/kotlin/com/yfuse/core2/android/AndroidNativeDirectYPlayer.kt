package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.recovery.requiresPcmAudioPath
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.render.videoFrameRateHint
import com.yfuse.core2.subtitle.YEmbeddedSubtitleDecoder
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.sync.YAvSync
import com.yfuse.core2.sync.YClockSnapshot
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
    private val decoderName: String? = null,
    private val runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
    private val plannedAudioOutputPath: YAudioOutputPath? = null,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val plannedDolbyVisionConfig: YDolbyVisionConfig? = null,
    private val requireDolbyVisionIdentity: Boolean = false,
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
            YTrackType.Subtitle -> {
                when (id) {
                    SUBTITLE_OFF -> commands.trySend(Command.SelectSubtitleTrack(null, external = false))
                    EXTERNAL_SUBTITLE_TRACK_ID ->
                        commands.trySend(Command.SelectSubtitleTrack(null, external = true))
                    else -> {
                        val trackIndex = id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return
                        commands.trySend(Command.SelectSubtitleTrack(trackIndex, external = false))
                    }
                }
            }
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
                val pendingCommands = mutableListOf<Command>()
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    pendingCommands += command
                }
                val handledCommand = pendingCommands.isNotEmpty()
                coalesceNativeDirectCommands(pendingCommands).forEach { command ->
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
        val typed = throwable as? YPlaybackException
        mutableState.value =
            mutableState.value.copy(
                phase = YPlaybackPhase.Failed,
                playing = false,
                playbackRequested = false,
                buffering = false,
                error = yCoreNativeDirectFailureMessage(typed),
                errorCategory = typed?.category ?: YPlaybackFailureCategory.Unknown,
                diagnostics =
                    mutableState.value.diagnostics.copy(
                        videoOutput = "停止",
                        audioOutput = "停止",
                        videoOutputVerified = false,
                        audioOutputVerified = false,
                        dolbyVisionOutput = false,
                        immersiveAudioCarrierOutput = false,
                        dolbyAtmosOutput = false,
                        spatialAudioOutput = false,
                        headTrackingAvailable = false,
                        // Never copy Throwable.message: media/framework exceptions can contain a URL.
                        reason =
                            typed?.stage?.let { stage -> "NativeDirect failed at ${stage.name}" }
                                ?: "NativeDirect failed before typed-stage classification",
                    ),
            )
    }

    private inner class NativeSession(
        context: Context,
    ) {
        private val demux = AndroidMediaExtractorDemuxNode(context)
        private val videoDecoder = AndroidMediaCodecVideoNode()
        private val audioDecoder = AndroidMediaCodecAudioNode()
        private val audioRenderer = AndroidAudioTrackRenderNode(context)
        private val encodedAudioRenderer = AndroidEncodedAudioTrackRenderNode()
        private val capabilityProvider = AndroidYCapabilityProvider(context)
        private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)
        private val externalSubtitleLoader = AndroidExternalSubtitleLoader(context)
        private val wallClock = YMediaClock(positionUs = request.startPositionMs * MICROS_PER_MILLISECOND)
        private val frameRateManager = AndroidFrameRateManager(context, frameRateSwitchMode)

        private var currentIndex = request.startIndex
        private var sourceRemote = false
        private var surfaceOutput: AndroidSurfaceVideoOutput? = null
        private var videoTrackIndex: Int? = null
        private var audioTrackIndex: Int? = null
        private var videoFormat: MediaFormat? = null
        private var audioInputFormat: MediaFormat? = null
        private var subtitleTrackIndex: Int? = null
        private val subtitleCues = mutableListOf<YSubtitleCue>()
        private var externalSubtitle: AndroidLoadedExternalSubtitle? = null
        private var externalSubtitleSelected = false
        private var audioTrackFormat: YAudioTrackFormat? = null
        private var audioOutputPath = YAudioOutputPath.None
        private var audioRendererConfigured = false
        private val rejectedPassthroughTracks = mutableSetOf<Int>()
        private var drmSession: AndroidYCoreDrmSession? = null
        private var drmBinding: AndroidYCoreDrmBinding? = null
        private var prepared = false
        private var videoConfigured = false
        private var requestedPlay = request.autoPlay

        @Volatile
        private var speed = 1f

        @Volatile
        private var renderCallbackGeneration = 0

        @Volatile
        private var lastAvSyncOffsetUs: Long? = null

        private var sampleBuffer = ByteBuffer.allocateDirect(DEFAULT_SAMPLE_BUFFER_BYTES)
        private var inputEnded = false
        private var videoInputEnded = false
        private var audioInputEnded = false
        private var videoOutputEnded = false
        private var audioOutputEnded = false
        private var pendingVideoOutput: YCodecOutputResult.Buffer? = null
        private var seekPrerollVideoOutput: YCodecOutputResult.Buffer? = null
        private var emptyTailSeekRetries = 0
        private var lastQueuedPresentationUs = 0L
        private var lastVideoPresentationUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetVideoUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetAudioUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var firstVideoFrameRendered = false
        private var droppedFrames = 0
        private var runtimeRenderRecorded = false
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
                is Command.SelectSubtitleTrack -> selectSubtitleTrack(command.trackIndex, command.external)
                is Command.SelectItem -> {
                    currentIndex = command.index
                    prepareCurrent(0L)
                }
            }
        }

        fun pump(): Boolean {
            var didWork = false
            drmSession?.let { session ->
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Drm,
                    stage = YPlaybackFailureStage.VideoDecoderQueue,
                    safeDetail = "NativeDirect DRM key refresh",
                ) {
                    session.refreshKeysIfNeeded()
                }
            }
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

            sourceRemote = item.uri.isCore2RemoteMediaUri()
            yPlaybackStage(
                category = sourceFailureCategory(),
                stage = YPlaybackFailureStage.SourceOpen,
                safeDetail = "NativeDirect source open",
            ) {
                demux.open(item.toAndroidSource())
            }
            externalSubtitle =
                item.externalSubtitle?.let { source ->
                    runCatching { externalSubtitleLoader.load(source, item.headers) }.getOrNull()
                }
            externalSubtitleSelected = externalSubtitle != null
            videoTrackIndex =
                demux.findFirstTrack(VIDEO_MIME_PREFIX)
                    ?: throw YPlaybackException(
                        category = YPlaybackFailureCategory.Container,
                        stage = YPlaybackFailureStage.Demux,
                        safeDetail = "NativeDirect source has no video track",
                    )
            audioTrackIndex = demux.findFirstTrack(AUDIO_MIME_PREFIX)
            videoFormat =
                demux
                    .trackFormat(requireNotNull(videoTrackIndex))
                    .also { format ->
                        plannedDolbyVisionConfig?.let { config ->
                            format.applyDolbyVisionConfiguration(config)
                        }
                    }
            validateNativeDirectDolbyIdentity(
                required = requireDolbyVisionIdentity,
                extractedMime = videoFormat?.getString(MediaFormat.KEY_MIME),
            )
            audioInputFormat = audioTrackIndex?.let(demux::trackFormat)
            item.drmConfiguration?.let { configuration ->
                val initializationData =
                    checkNotNull(demux.drmInitializationData(configuration.scheme.yCorePlatformUuid())) {
                        "NativeDirect DRM initialization data is unavailable"
                    }
                val videoMime =
                    checkNotNull(videoFormat?.getString(MediaFormat.KEY_MIME)) {
                        "NativeDirect DRM video MIME type is unavailable"
                    }
                val session = AndroidYCoreDrmSession(configuration)
                drmSession = session
                drmBinding =
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.Drm,
                        stage = YPlaybackFailureStage.SourceOpen,
                        safeDetail = "NativeDirect DRM session open",
                    ) {
                        session.open(initializationData, videoMime)
                    }
            }
            configureAudioPath(audioInputFormat)

            demux.selectTrack(requireNotNull(videoTrackIndex))
            audioTrackIndex?.let(demux::selectTrack)

            val sampleCapacity =
                listOfNotNull(videoFormat, audioInputFormat)
                    .maxOfOrNull { format -> format.maxInputSizeOr(DEFAULT_SAMPLE_BUFFER_BYTES) }
                    ?.coerceIn(MIN_SAMPLE_BUFFER_BYTES, MAX_SAMPLE_BUFFER_BYTES)
                    ?: DEFAULT_SAMPLE_BUFFER_BYTES
            sampleBuffer = ByteBuffer.allocateDirect(sampleCapacity)

            surfaceOutput?.surface?.takeIf { it.isValid }?.let { surface ->
                configureVideoDecoder(surface)
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
                    subtitleTracks = subtitleTracks(),
                    subtitleCues = activeSubtitleCues(),
                    buffering = requestedPlay,
                    playbackRequested = requestedPlay,
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            route = YPlaybackRoute.NativeDirect,
                            container = item.containerHint().name,
                            demuxer = demux.name,
                            decoder =
                                listOfNotNull(
                                    videoDecoder.decoderName,
                                    audioDecoderDiagnosticName(),
                                ).joinToString(" + "),
                            renderer = "Surface + AudioTrack",
                            videoCodec = videoFormat?.getString(MediaFormat.KEY_MIME).orEmpty(),
                            videoWidth = videoFormat?.intOrZero(MediaFormat.KEY_WIDTH) ?: 0,
                            videoHeight = videoFormat?.intOrZero(MediaFormat.KEY_HEIGHT) ?: 0,
                            frameRate = videoFormat?.floatOrZero(MediaFormat.KEY_FRAME_RATE) ?: 0f,
                            audioCodec = audioInputFormat?.getString(MediaFormat.KEY_MIME).orEmpty(),
                            bitrateBitsPerSecond =
                                listOfNotNull(videoFormat, audioInputFormat)
                                    .sumOf { it.longOrZero(MediaFormat.KEY_BIT_RATE) },
                            dynamicRange = videoFormat.dynamicRangeLabel(),
                            videoOutput = if (videoConfigured) "等待首帧" else "等待 Surface",
                            audioOutput = waitingAudioOutputLabel(),
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
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
                frameRateManager.clear()
                videoDecoder.release()
                videoConfigured = false
                pausePlaybackInternal(keepRequested = true)
                wallClock.seek(positionUs, System.nanoTime())
                mutableState.value =
                    mutableState.value.copy(
                        playing = false,
                        buffering = requestedPlay,
                        diagnostics =
                            mutableState.value.diagnostics.copy(
                                videoOutput = "等待 Surface",
                                videoOutputVerified = false,
                                dolbyVisionOutput = false,
                            ),
                    )
                return
            }
            if (previous?.surface === newSurface && videoConfigured) return
            firstVideoFrameRendered = false
            mutableState.value =
                mutableState.value.copy(
                    playing = false,
                    buffering = requestedPlay,
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            videoOutput = "硬解已配置 · 等待首帧",
                            videoOutputVerified = false,
                            dolbyVisionOutput = false,
                        ),
                )
            if (videoConfigured && previous?.surface?.isValid == true) {
                runCatching { videoDecoder.setOutputSurface(newSurface) }
                    .onSuccess {
                        frameRateManager.reattach(newSurface)
                        if (requestedPlay) startPlayback()
                        return
                    }
            }
            if (prepared) {
                val resumeUs = currentPositionUs()
                configureVideoDecoder(newSurface)
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
                playAudio()
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
            pauseAudio()
            if (!keepRequested) requestedPlay = false
            mutableState.value =
                mutableState.value.copy(
                    playbackRequested = requestedPlay,
                    playing = false,
                    buffering = false,
                    positionMs = positionUs / MICROS_PER_MILLISECOND,
                )
        }

        private fun seekTo(
            positionUs: Long,
            tailRetry: Boolean = false,
            flushVideoDecoder: Boolean = true,
        ) {
            if (!prepared) return
            val targetUs = positionUs.coerceAtLeast(0L)
            if (!tailRetry) emptyTailSeekRetries = 0
            yPlaybackStage(
                category = sourceFailureCategory(),
                stage = YPlaybackFailureStage.Seek,
                safeDetail = "NativeDirect source seek",
            ) {
                demux.seekTo(targetUs)
            }
            if (videoConfigured && flushVideoDecoder) videoDecoder.flush()
            if (audioInputFormat != null && !isAudioPassthrough()) audioDecoder.flush()
            if (audioRendererConfigured) flushAudio()
            subtitleCues.clear()
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
                    subtitleCues = activeSubtitleCues(),
                    error = null,
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            videoOutput = if (videoConfigured) "硬解已配置 · 等待首帧" else "等待 Surface",
                            audioOutput = waitingAudioOutputLabel(),
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                        ),
                )
            if (requestedPlay) startPlayback()
        }

        private fun updateSpeed(value: Float) {
            if (!value.isFinite() || value <= 0f) return
            val positionUs = currentPositionUs()
            speed = value
            wallClock.setSpeed(value, positionUs, System.nanoTime())
            if (isAudioPassthrough() && value != 1f) {
                switchPassthroughToPcm(countFailure = false)
                seekTo(positionUs)
                mutableState.value = mutableState.value.copy(speed = value)
                return
            }
            if (audioRendererConfigured && !isAudioPassthrough()) audioRenderer.setSpeed(value)
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
            releaseAudioPath()
            configureAudioPath(format)
            seekTo(positionUs)
            mutableState.value =
                mutableState.value.copy(
                    audioTracks = audioTracks(),
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            audioOutput = waitingAudioOutputLabel(),
                            audioOutputVerified = false,
                        ),
                )
        }

        private fun selectSubtitleTrack(
            trackIndex: Int?,
            external: Boolean,
        ) {
            if (!prepared || (external && externalSubtitle == null)) return
            if (external == externalSubtitleSelected && trackIndex == subtitleTrackIndex) return
            val nextFormat = trackIndex?.takeIf { it in 0 until demux.trackCount }?.let(demux::trackFormat)
            val nextSubtitleFormat = nextFormat?.subtitleFormatOrNull()
            if (trackIndex != null && nextSubtitleFormat?.textOverlaySupported != true) return
            val positionUs = currentPositionUs()
            subtitleTrackIndex?.let(demux::unselectTrack)
            trackIndex?.let(demux::selectTrack)
            subtitleTrackIndex = trackIndex
            externalSubtitleSelected = external
            subtitleCues.clear()
            seekTo(positionUs)
            mutableState.value =
                mutableState.value.copy(
                    subtitleTracks = subtitleTracks(),
                    subtitleCues = activeSubtitleCues(),
                )
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
                    if (isAudioPassthrough()) {
                        audioInputEnded = true
                        audioOutputEnded = true
                        queued = true
                    } else if (audioDecoder.queueEndOfStream(lastQueuedPresentationUs) == YCodecQueueResult.Queued) {
                        audioInputEnded = true
                        queued = true
                    }
                }
                return queued
            }

            val positionUs = currentPositionUs()
            if (lastQueuedPresentationUs - positionUs > MAX_INPUT_AHEAD_US) return false

            val sample =
                yPlaybackStage(
                    category = sourceFailureCategory(),
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "NativeDirect compressed sample read",
                ) {
                    demux.readSample(sampleBuffer)
                }
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
                                sample.cryptoInfo,
                            )
                        } else {
                            YCodecQueueResult.TryAgain
                        }
                    audioTrackIndex ->
                        queueAudioSample(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.flags,
                            sample.cryptoInfo,
                        )
                    subtitleTrackIndex -> {
                        require(sample.cryptoInfo == null) { "Encrypted subtitle samples are not executable" }
                        queueSubtitleSample(sample.data, sample.presentationTimeUs)
                        YCodecQueueResult.Queued
                    }
                    else -> YCodecQueueResult.Queued
                }
            if (queued != YCodecQueueResult.Queued) return false
            lastQueuedPresentationUs = maxOf(lastQueuedPresentationUs, sample.presentationTimeUs)
            demux.advance()
            return true
        }

        private fun drainAudio(): Boolean {
            if (audioInputFormat == null || audioOutputEnded) return false
            if (isAudioPassthrough()) return false
            return when (val output = audioDecoder.dequeueOutput()) {
                YAudioCodecOutputResult.TryAgain -> false
                is YAudioCodecOutputResult.FormatChanged -> {
                    audioRenderer.configure(output.format)
                    audioRendererConfigured = true
                    audioRenderer.setSpeed(speed)
                    if (requestedPlay) audioRenderer.play()
                    mutableState.value =
                        mutableState.value.copy(
                            diagnostics =
                                mutableState.value.diagnostics.copy(
                                    audioOutput = "等待 PCM 输出",
                                    audioOutputVerified = false,
                                ),
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
                            val writtenBytes =
                                audioRenderer.write(
                                    audioDecoder.outputData(output),
                                    output.presentationTimeUs,
                                )
                            if (writtenBytes > 0 && !mutableState.value.diagnostics.audioOutputVerified) {
                                mutableState.value =
                                    mutableState.value.copy(
                                        diagnostics =
                                            mutableState.value.diagnostics.copy(
                                                audioOutput =
                                                    when {
                                                        audioRenderer.spatialAudioOutput &&
                                                            audioRenderer.headTrackingAvailable ->
                                                            "系统空间音频 · PCM · 头部跟踪可用"
                                                        audioRenderer.spatialAudioOutput ->
                                                            "系统空间音频 · PCM"
                                                        else -> "PCM · AudioTrack"
                                                    },
                                                audioOutputVerified = true,
                                                immersiveAudioCarrierOutput = false,
                                                dolbyAtmosOutput = false,
                                                spatialAudioOutput = audioRenderer.spatialAudioOutput,
                                                headTrackingAvailable = audioRenderer.headTrackingAvailable,
                                            ),
                                    )
                            }
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
            val seekPreroll =
                !configBuffer &&
                    output.size > 0 &&
                    seekTargetVideoUs > 0L &&
                    output.presentationTimeUs < seekTargetVideoUs
            if (seekPreroll) {
                pendingVideoOutput = null
                seekPrerollVideoOutput?.let { videoDecoder.releaseOutput(it, render = false) }
                seekPrerollVideoOutput = output
                if (!output.endOfStream) return true
                renderSeekPrerollAtEnd()
                return true
            }
            if (output.endOfStream && seekTargetVideoUs > 0L && seekPrerollVideoOutput != null) {
                pendingVideoOutput = null
                videoDecoder.releaseOutput(output, render = false)
                renderSeekPrerollAtEnd()
                return true
            }
            if (
                output.endOfStream &&
                seekTargetVideoUs > 0L &&
                !firstVideoFrameRendered &&
                retryEmptyTailSeek(output)
            ) {
                return true
            }
            if (renderable) {
                seekPrerollVideoOutput?.let { videoDecoder.releaseOutput(it, render = false) }
                seekPrerollVideoOutput = null
                val currentUs = currentPositionUs()
                val nowNs = System.nanoTime()
                val desiredRenderNs =
                    if (audioRendererConfigured) {
                        audioPresentationTimeNs(output.presentationTimeUs, nowNs)
                    } else {
                        wallClock.presentationTimeNs(output.presentationTimeUs)
                    }
                val decision =
                    preserveFirstVideoFrame(
                        decision =
                            videoFrameReleaseDecision(
                                presentationTimeUs = output.presentationTimeUs,
                                masterPositionUs = currentUs,
                                desiredReleaseTimeNs = desiredRenderNs,
                                nowNs = nowNs,
                                maximumScheduleAheadUs = MAX_VIDEO_SCHEDULE_AHEAD_US,
                                lateDropThresholdNs = LATE_FRAME_DROP_NS,
                                lateImmediateAllowanceNs = LATE_FRAME_IMMEDIATE_NS,
                            ),
                        firstFrameRendered = firstVideoFrameRendered,
                        nowNs = nowNs,
                    )
                when (decision) {
                    YVideoFrameReleaseDecision.Hold -> {
                        pendingVideoOutput = output
                        return false
                    }
                    YVideoFrameReleaseDecision.Drop -> {
                        pendingVideoOutput = null
                        videoDecoder.releaseOutput(output, render = false)
                        droppedFrames++
                    }
                    is YVideoFrameReleaseDecision.Render -> {
                        pendingVideoOutput = null
                        videoDecoder.releaseOutput(output, render = true, renderTimeNs = decision.releaseTimeNs)
                    }
                }
                lastVideoPresentationUs = output.presentationTimeUs
                seekTargetVideoUs = 0L
                if (decision is YVideoFrameReleaseDecision.Render && !firstVideoFrameRendered) {
                    firstVideoFrameRendered = true
                    mutableState.value =
                        mutableState.value.copy(
                            phase = YPlaybackPhase.Ready,
                            playing = requestedPlay,
                            buffering = false,
                            diagnostics =
                                mutableState.value.diagnostics.copy(
                                    videoOutput = "Surface 直出",
                                    videoOutputVerified = true,
                                    dolbyVisionOutput = nativeDolbyVisionOutputVerified(),
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

        private fun renderSeekPrerollAtEnd() {
            val candidate = seekPrerollVideoOutput ?: return
            seekPrerollVideoOutput = null
            videoDecoder.releaseOutput(candidate, render = true)
            lastVideoPresentationUs = candidate.presentationTimeUs
            seekTargetVideoUs = 0L
            videoOutputEnded = true
            if (!firstVideoFrameRendered) {
                firstVideoFrameRendered = true
                mutableState.value =
                    mutableState.value.copy(
                        diagnostics =
                            mutableState.value.diagnostics.copy(
                                videoOutput = "Surface 直出 · 尾段最近帧",
                                videoOutputVerified = true,
                                dolbyVisionOutput = nativeDolbyVisionOutputVerified(),
                            ),
                    )
            }
        }

        private fun retryEmptyTailSeek(output: YCodecOutputResult.Buffer): Boolean {
            val retryTargetUs =
                emptyTailSeekRetryTarget(
                    currentTargetUs = seekTargetVideoUs,
                    retryCount = emptyTailSeekRetries,
                ) ?: return false
            val activeSurface = surfaceOutput?.surface?.takeIf { it.isValid } ?: return false
            pendingVideoOutput = null
            videoDecoder.releaseOutput(output, render = false)
            emptyTailSeekRetries++
            videoDecoder.release()
            videoConfigured = false
            configureVideoDecoder(activeSurface)
            seekTo(
                positionUs = retryTargetUs,
                tailRetry = true,
                flushVideoDecoder = false,
            )
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
                    subtitleCues = activeSubtitleCues(),
                    playing = requestedPlay && firstVideoFrameRendered && !isEnded(),
                    buffering = requestedPlay && !firstVideoFrameRendered && !isEnded(),
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            droppedFrames = droppedFrames,
                            avSyncOffsetMs = lastAvSyncOffsetUs?.div(MICROS_PER_MILLISECOND),
                            avSyncMeasurement =
                                if (lastAvSyncOffsetUs != null) {
                                    "MediaCodec 帧渲染 / AudioTrack 时钟"
                                } else {
                                    "等待音视频时钟样本"
                                },
                        ),
                )
        }

        private fun currentPositionUs(): Long =
            audioClockSnapshot()?.positionUs
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
            pauseAudio()
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
            seekPrerollVideoOutput = null
            lastAvSyncOffsetUs = null
        }

        private fun configureAudioPath(format: MediaFormat?) {
            audioTrackFormat = format?.toCore2AudioTrackFormat()
            val coreFormat = audioTrackFormat
            audioOutputPath =
                if (
                    coreFormat != null &&
                    requiresPcmAudioPath(
                        protectedContent = drmBinding != null,
                        passthroughRejected =
                            audioTrackIndex?.let(rejectedPassthroughTracks::contains) == true,
                        speed = speed,
                    )
                ) {
                    YAudioOutputPath.DecodePcm
                } else {
                    coreFormat?.let {
                        plannedAudioOutputPath
                            ?: capabilityProvider
                                .current()
                                .audioOutputPath(
                                    YAudioRequirement(
                                        codec = it.codec,
                                        channelCount = it.channelCount,
                                        sampleRate = it.sampleRate,
                                    ),
                                )
                    } ?: YAudioOutputPath.None
                }
            when {
                format == null -> audioRendererConfigured = false
                audioOutputPath == YAudioOutputPath.Passthrough -> {
                    try {
                        encodedAudioRenderer.configure(
                            requireNotNull(coreFormat),
                            exactDolbyAtmosTransport =
                                capabilityProvider
                                    .current()
                                    .hasExactDolbyAtmosPassthrough(coreFormat.codec),
                        )
                        audioRendererConfigured = true
                    } catch (_: Exception) {
                        rejectedPassthroughTracks += requireNotNull(audioTrackIndex)
                        switchPassthroughToPcm(countFailure = true)
                    }
                }
                audioOutputPath == YAudioOutputPath.DecodePcm -> {
                    audioDecoder.configure(format, drmBinding?.mediaCrypto)
                    audioRendererConfigured = false
                }
                else -> error("Selected NativeDirect audio track has no platform output path")
            }
        }

        private fun releaseAudioPath() {
            runCatching(audioRenderer::release)
            runCatching(encodedAudioRenderer::release)
            runCatching(audioDecoder::release)
            audioRendererConfigured = false
            audioOutputPath = YAudioOutputPath.None
            audioTrackFormat = null
        }

        private fun queueAudioSample(
            data: ByteBuffer,
            presentationTimeUs: Long,
            flags: Int,
            cryptoInfo: YExtractorCryptoInfo?,
        ): YCodecQueueResult {
            if (!isAudioPassthrough()) {
                return audioDecoder.queueAccessUnit(data, presentationTimeUs, flags, cryptoInfo)
            }
            require(cryptoInfo == null) { "Encrypted audio cannot use passthrough" }
            if (presentationTimeUs >= seekTargetAudioUs) {
                try {
                    encodedAudioRenderer.write(data, presentationTimeUs)
                } catch (_: Exception) {
                    val resumeUs = currentPositionUs()
                    rejectedPassthroughTracks += requireNotNull(audioTrackIndex)
                    switchPassthroughToPcm(countFailure = true)
                    seekTo(resumeUs)
                    return YCodecQueueResult.TryAgain
                }
                seekTargetAudioUs = 0L
                if (!mutableState.value.diagnostics.audioOutputVerified) {
                    mutableState.value =
                        mutableState.value.copy(
                            diagnostics =
                                mutableState.value.diagnostics.copy(
                                    audioOutput = activeAudioOutputLabel(),
                                    audioOutputVerified = true,
                                    immersiveAudioCarrierOutput =
                                        encodedAudioRenderer.immersiveCarrierOutput,
                                    dolbyAtmosOutput = encodedAudioRenderer.dolbyAtmosOutput,
                                ),
                        )
                }
            }
            return YCodecQueueResult.Queued
        }

        private fun switchPassthroughToPcm(countFailure: Boolean) {
            val format = checkNotNull(audioInputFormat) { "PCM fallback requires an audio track" }
            runCatching(encodedAudioRenderer::release)
            runCatching(audioRenderer::release)
            runCatching(audioDecoder::release)
            audioOutputPath = YAudioOutputPath.DecodePcm
            audioRendererConfigured = false
            audioDecoder.configure(format, drmBinding?.mediaCrypto)
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            audioOutput = "原码不可用 · 自动回落 PCM",
                            audioOutputVerified = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            audioUnderrunCount =
                                mutableState.value.diagnostics.audioUnderrunCount +
                                    if (countFailure) 1 else 0,
                        ),
                )
        }

        private fun isAudioPassthrough(): Boolean =
            audioInputFormat != null && audioOutputPath == YAudioOutputPath.Passthrough

        private fun nativeDolbyVisionOutputVerified(): Boolean =
            plannedDolbyVisionConfig != null &&
                videoFormat?.getString(MediaFormat.KEY_MIME) == DOLBY_VISION_MIME &&
                capabilityProvider
                    .current()
                    .supportsDisplayHdr(com.yfuse.core2.capability.YHdrType.DolbyVision)

        private fun audioClockSnapshot(): YAudioClockSnapshot? =
            if (isAudioPassthrough()) encodedAudioRenderer.clockSnapshot() else audioRenderer.clockSnapshot()

        private fun configureVideoDecoder(surface: Surface) {
            val format = requireNotNull(videoFormat)
            check(
                secureSurfaceRequirementSatisfied(
                    protectedContent = drmBinding != null,
                    outputSecure = surfaceOutput?.protectedContent == true,
                ),
            ) {
                "Protected playback requires a secure SurfaceView output"
            }
            try {
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.VideoDecoderConfigure,
                    safeDetail = "NativeDirect MediaCodec configure",
                ) {
                    videoDecoder.configure(
                        format = format,
                        surface = surface,
                        decoderName = decoderName,
                        mediaCrypto = drmBinding?.mediaCrypto,
                    )
                }
                runtimeCapabilityKey?.let(runtimeCapabilities::recordConfigured)
            } catch (failure: Throwable) {
                runtimeCapabilityKey?.let(runtimeCapabilities::recordRejected)
                throw failure
            }
            frameRateManager.attach(surface, format.directFrameRateHint())
            val generation = ++renderCallbackGeneration
            videoDecoder.setOnFrameRenderedListener { presentationTimeUs, realtimeNs ->
                if (generation != renderCallbackGeneration) return@setOnFrameRenderedListener
                if (!runtimeRenderRecorded) {
                    runtimeRenderRecorded = true
                    runtimeCapabilityKey?.let(runtimeCapabilities::recordRendered)
                }
                val audioClock = audioClockSnapshot()
                lastAvSyncOffsetUs =
                    audioClock?.let { clock ->
                        YAvSync.offsetUs(
                            videoPresentationTimeUs = presentationTimeUs,
                            videoRenderedRealtimeNs = realtimeNs,
                            master = YClockSnapshot(clock.positionUs, clock.realtimeNs),
                            speed = speed,
                        )
                    }
            }
            videoConfigured = true
        }

        private fun audioPresentationTimeNs(
            presentationTimeUs: Long,
            fallbackRealtimeNs: Long,
        ): Long =
            if (isAudioPassthrough()) {
                encodedAudioRenderer.presentationTimeNs(presentationTimeUs, fallbackRealtimeNs)
            } else {
                audioRenderer.presentationTimeNs(presentationTimeUs, fallbackRealtimeNs)
            }

        private fun playAudio() {
            if (isAudioPassthrough()) {
                encodedAudioRenderer.play()
            } else {
                audioRenderer.setSpeed(speed)
                audioRenderer.play()
            }
        }

        private fun pauseAudio() {
            if (isAudioPassthrough()) encodedAudioRenderer.pause() else audioRenderer.pause()
        }

        private fun flushAudio() {
            if (isAudioPassthrough()) encodedAudioRenderer.flush() else audioRenderer.flush()
        }

        private fun audioDecoderDiagnosticName(): String? =
            when (audioOutputPath) {
                YAudioOutputPath.Passthrough -> encodedAudioRenderer.name
                YAudioOutputPath.DecodePcm -> audioDecoder.decoderName
                YAudioOutputPath.None -> null
            }

        private fun waitingAudioOutputLabel(): String =
            when (audioOutputPath) {
                YAudioOutputPath.Passthrough -> "等待原码输出"
                YAudioOutputPath.DecodePcm -> "等待 PCM 输出"
                YAudioOutputPath.None -> "无音频轨"
            }

        private fun activeAudioOutputLabel(): String =
            when {
                encodedAudioRenderer.dolbyAtmosOutput -> "Dolby Atmos 原码 · AudioTrack"
                encodedAudioRenderer.immersiveCarrierOutput ->
                    "沉浸音频载波 · AudioTrack（未验证对象输出）"
                else -> "原码直通 · AudioTrack"
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

        private fun subtitleTracks(): List<YTrack> {
            val embedded =
                (0 until demux.trackCount).mapNotNull { index ->
                    val format = demux.trackFormat(index)
                    val subtitleFormat =
                        format.subtitleFormatOrNull()?.takeIf { it.textOverlaySupported }
                            ?: return@mapNotNull null
                    val language = format.getString(MediaFormat.KEY_LANGUAGE)
                    YTrack(
                        id = "$SUBTITLE_TRACK_PREFIX$index",
                        type = YTrackType.Subtitle,
                        label = language?.takeIf(String::isNotBlank) ?: "Subtitle ${index + 1}",
                        language = language,
                        codec = format.getString(MediaFormat.KEY_MIME) ?: subtitleFormat.name,
                        selected = !externalSubtitleSelected && index == subtitleTrackIndex,
                    )
                }
            return embedded +
                listOfNotNull(
                    externalSubtitle?.track?.copy(selected = externalSubtitleSelected),
                )
        }

        private fun activeSubtitleCues(): List<YSubtitleCue> =
            if (externalSubtitleSelected) externalSubtitle?.cues.orEmpty() else subtitleCues.toList()

        private fun sourceFailureCategory(): YPlaybackFailureCategory =
            if (sourceRemote) YPlaybackFailureCategory.Network else YPlaybackFailureCategory.Container

        private fun queueSubtitleSample(
            data: ByteBuffer,
            presentationTimeUs: Long,
        ) {
            val format = subtitleTrackIndex?.let(demux::trackFormat)?.subtitleFormatOrNull() ?: return
            val bytes = ByteArray(data.remaining())
            data.duplicate().get(bytes)
            YEmbeddedSubtitleDecoder
                .decode(
                    data = bytes,
                    format = format,
                    startUs = presentationTimeUs,
                    durationUs = null,
                    id = "${subtitleTrackIndex ?: -1}:$presentationTimeUs",
                )?.let(subtitleCues::add)
            val oldestRetainedUs = currentPositionUs() - SUBTITLE_HISTORY_US
            subtitleCues.removeAll { cue -> cue.endUs < oldestRetainedUs }
        }

        fun releaseMedia() {
            renderCallbackGeneration++
            pendingVideoOutput?.let { output ->
                runCatching { videoDecoder.releaseOutput(output, render = false) }
            }
            pendingVideoOutput = null
            runCatching(audioRenderer::release)
            runCatching(encodedAudioRenderer::release)
            runCatching(audioDecoder::release)
            runCatching(videoDecoder::release)
            runCatching { drmSession?.close() }
            drmBinding = null
            drmSession = null
            frameRateManager.clear()
            runCatching(demux::release)
            prepared = false
            videoConfigured = false
            audioRendererConfigured = false
            videoTrackIndex = null
            audioTrackIndex = null
            subtitleTrackIndex = null
            subtitleCues.clear()
            externalSubtitle = null
            externalSubtitleSelected = false
            sourceRemote = false
            videoFormat = null
            audioInputFormat = null
            audioTrackFormat = null
            audioOutputPath = YAudioOutputPath.None
            rejectedPassthroughTracks.clear()
            firstVideoFrameRendered = false
            droppedFrames = 0
            runtimeRenderRecorded = false
            resetEndState()
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            avSyncOffsetMs = null,
                            avSyncMeasurement = "等待音视频时钟样本",
                        ),
                )
        }

        fun releaseAll() {
            releaseMedia()
            surfaceOutput = null
        }
    }

    internal sealed interface Command {
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

        data class SelectSubtitleTrack(
            val trackIndex: Int?,
            val external: Boolean,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

internal fun validateNativeDirectDolbyIdentity(
    required: Boolean,
    extractedMime: String?,
) {
    if (!required || extractedMime.equals(DOLBY_VISION_MIME, ignoreCase = true)) return
    throw YPlaybackException(
        category = YPlaybackFailureCategory.Container,
        stage = YPlaybackFailureStage.Bitstream,
        safeDetail = "NativeDirect source did not expose a Dolby Vision track identity",
    )
}

internal fun yCoreNativeDirectFailureMessage(failure: YPlaybackException?): String =
    when (failure?.category) {
        YPlaybackFailureCategory.Authorization -> "YCore 2.0 片源授权已失效，请刷新播放地址后重试"
        YPlaybackFailureCategory.Drm -> "YCore 2.0 无法建立当前片源的 DRM 会话"
        YPlaybackFailureCategory.Network -> "YCore 2.0 无法连接片源，请检查服务器或网络"
        YPlaybackFailureCategory.Container -> "YCore 2.0 原生解封装无法识别当前片源"
        YPlaybackFailureCategory.Decoder -> "YCore 2.0 无法启动当前视频解码器"
        YPlaybackFailureCategory.Renderer -> "YCore 2.0 无法建立视频输出"
        YPlaybackFailureCategory.AudioSink -> "YCore 2.0 无法建立音频输出"
        YPlaybackFailureCategory.Unknown,
        null,
        -> "YCore 2.0 原生播放失败，请导出诊断日志"
    }

internal fun secureSurfaceRequirementSatisfied(
    protectedContent: Boolean,
    outputSecure: Boolean,
): Boolean = !protectedContent || outputSecure

/**
 * Drops superseded control work before it reaches MediaExtractor/MediaCodec. This turns a scrub
 * gesture into one seek/flush operation while preserving barriers such as pause, item switch and
 * track selection.
 */
internal fun coalesceNativeDirectCommands(
    commands: List<AndroidNativeDirectYPlayer.Command>,
): List<AndroidNativeDirectYPlayer.Command> =
    commands.fold(mutableListOf()) { result, command ->
        val previous = result.lastOrNull()
        if (previous != null && previous.canBeReplacedBy(command)) {
            result[result.lastIndex] = command
        } else {
            result += command
        }
        result
    }

private fun AndroidNativeDirectYPlayer.Command.canBeReplacedBy(next: AndroidNativeDirectYPlayer.Command): Boolean =
    when (this) {
        is AndroidNativeDirectYPlayer.Command.Seek -> next is AndroidNativeDirectYPlayer.Command.Seek
        is AndroidNativeDirectYPlayer.Command.SetSpeed -> next is AndroidNativeDirectYPlayer.Command.SetSpeed
        is AndroidNativeDirectYPlayer.Command.SetVideoOutput ->
            next is AndroidNativeDirectYPlayer.Command.SetVideoOutput
        is AndroidNativeDirectYPlayer.Command.SelectAudioTrack ->
            next is AndroidNativeDirectYPlayer.Command.SelectAudioTrack
        is AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack ->
            next is AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack
        is AndroidNativeDirectYPlayer.Command.SelectItem -> next is AndroidNativeDirectYPlayer.Command.SelectItem
        AndroidNativeDirectYPlayer.Command.Prepare -> next == AndroidNativeDirectYPlayer.Command.Prepare
        AndroidNativeDirectYPlayer.Command.Play -> next == AndroidNativeDirectYPlayer.Command.Play
        AndroidNativeDirectYPlayer.Command.Pause -> next == AndroidNativeDirectYPlayer.Command.Pause
    }

private fun YMediaItem.toAndroidSource(): YAndroidMediaSource =
    YAndroidMediaSource(
        uri = uri,
        headers = headers,
        cacheIdentity = cacheIdentity,
        cacheMaximumBytes = cacheMaximumBytes,
    )

private fun MediaFormat.toCore2AudioTrackFormat(): YAudioTrackFormat {
    val mime = requireNotNull(getString(MediaFormat.KEY_MIME)).lowercase()
    val profile =
        if (containsKey(MediaFormat.KEY_PROFILE)) {
            runCatching { getInteger(MediaFormat.KEY_PROFILE) }.getOrDefault(0)
        } else {
            0
        }
    val baseCodec = requireNotNull(mime.toYAudioCodec()) { "Unsupported NativeDirect audio MIME: $mime" }
    val codec =
        when {
            baseCodec == YAudioCodec.Eac3 && profile == ATMOS_PROFILE -> YAudioCodec.Eac3Joc
            baseCodec == YAudioCodec.TrueHd && profile == ATMOS_PROFILE -> YAudioCodec.TrueHdAtmos
            else -> baseCodec
        }
    return YAudioTrackFormat(
        codec = codec,
        mimeType = mime,
        channelCount = getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
        sampleRate = getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1),
    )
}

private fun MediaFormat.durationUsOrNull(): Long? =
    if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else null

private fun MediaFormat.intOrZero(key: String): Int =
    if (containsKey(key)) {
        runCatching {
            getInteger(key)
        }.getOrDefault(0)
    } else {
        0
    }

private fun MediaFormat.longOrZero(key: String): Long =
    if (containsKey(key)) {
        runCatching { getLong(key) }
            .recoverCatching { getInteger(key).toLong() }
            .getOrDefault(0L)
    } else {
        0L
    }

private fun MediaFormat.floatOrZero(key: String): Float =
    if (containsKey(key)) {
        runCatching { getFloat(key) }
            .recoverCatching { getInteger(key).toFloat() }
            .getOrDefault(0f)
    } else {
        0f
    }

private fun MediaFormat.directFrameRateHint() =
    if (containsKey(MediaFormat.KEY_FRAME_RATE)) {
        val frameRate =
            runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
        frameRate?.let(::videoFrameRateHint)
    } else {
        null
    }

internal fun MediaFormat.subtitleFormatOrNull(): YSubtitleFormat? = mediaSubtitleFormat(getString(MediaFormat.KEY_MIME))

internal fun mediaSubtitleFormat(mimeType: String?): YSubtitleFormat? =
    when (mimeType?.lowercase()) {
        "application/x-subrip" -> YSubtitleFormat.Srt
        "text/vtt" -> YSubtitleFormat.WebVtt
        "text/x-ssa", "text/x-ass" -> YSubtitleFormat.Ass
        "application/pgs" -> YSubtitleFormat.Pgs
        "application/vobsub" -> YSubtitleFormat.VobSub
        "application/x-quicktime-tx3g" -> YSubtitleFormat.Tx3g
        else -> null
    }

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
private const val SUBTITLE_TRACK_PREFIX = "subtitle:"
private const val SUBTITLE_OFF = "off"
private const val DOLBY_VISION_MIME = "video/dolby-vision"
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
private const val MIN_SAMPLE_BUFFER_BYTES = 256 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
private const val MAX_INPUT_AHEAD_US = 1_500_000L
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val LATE_FRAME_DROP_NS = 100_000_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
private const val SUBTITLE_HISTORY_US = 60_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
private const val ATMOS_PROFILE = 30
