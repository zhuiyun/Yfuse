package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.view.Surface
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.api.invalidateOutputEvidence
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YSamplePacking
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors

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
    private val confirmedDolbyVisionNalIdentity: Boolean = false,
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

    private val playbackDispatcher = createNativeDirectPlaybackDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + playbackDispatcher)
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
                    SUBTITLE_OFF -> commands.trySend(Command.SelectSubtitleTrack(null, externalTrackId = null))
                    EXTERNAL_SUBTITLE_TRACK_ID ->
                        commands.trySend(
                            Command.SelectSubtitleTrack(
                                null,
                                externalTrackId = mutableState.value.subtitleTracks.firstOrNull {
                                    it.id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)
                                }?.id,
                            ),
                        )
                    else -> {
                        if (id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)) {
                            commands.trySend(Command.SelectSubtitleTrack(null, externalTrackId = id))
                        } else {
                            val trackIndex = id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return
                            commands.trySend(Command.SelectSubtitleTrack(trackIndex, externalTrackId = null))
                        }
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
        worker.invokeOnCompletion { playbackDispatcher.close() }
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
                for (command in coalesceNativeDirectCommands(pendingCommands)) {
                    if (released || !scope.isActive) return
                    val failure = runCatching { session.handle(command) }.exceptionOrNull()
                    if (failure != null) {
                        if (failure is CancellationException && (released || !scope.isActive)) return
                        fail(session, failure)
                        // Commands in this batch were captured before the failure. Processing a
                        // stale Play/Seek/Surface command after releaseMedia() can partially revive
                        // a failed session and report rendered frames while its phase is Failed.
                        // A later explicit retry arrives in a fresh batch and may prepare safely.
                        break
                    }
                }

                val canPump = session.canPump
                val didWork =
                    if (canPump) {
                        val result = runCatching { session.pump() }
                        val failure = result.exceptionOrNull()
                        if (failure is CancellationException && (released || !scope.isActive)) return
                        failure?.let { fail(session, it) }
                        result.getOrDefault(false)
                    } else {
                        false
                    }

                if (!handledCommand && !didWork) {
                    if (canPump) {
                        delay(PUMP_IDLE_DELAY_MS)
                    } else {
                        val command = commands.receiveCatching().getOrNull() ?: break
                        val failure = runCatching { session.handle(command) }.exceptionOrNull()
                        if (failure is CancellationException && (released || !scope.isActive)) return
                        failure?.let { fail(session, it) }
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
        if (released || throwable is CancellationException) return
        session.releaseMedia()
        val typed = throwable as? YPlaybackException
        val codecConfigurationFailure = typed?.cause as? YVideoDecoderConfigurationException
        AppLog.error(
            category = "player.core2",
            event = "native_direct_failed",
            message = "YCore NativeDirect failed",
            attributes =
                mapOf(
                    "category" to (typed?.category?.name ?: YPlaybackFailureCategory.Unknown.name),
                    "stage" to (typed?.stage?.name ?: YPlaybackFailureStage.Unknown.name),
                    "detail" to typed?.safeDetail.orEmpty(),
                    "codecMime" to codecConfigurationFailure?.mime.orEmpty(),
                    "codecProfile" to (codecConfigurationFailure?.profile?.toString() ?: ""),
                    "decoderAttempts" to
                        codecConfigurationFailure
                            ?.failures
                            ?.joinToString(",") { it.decoderName }
                            .orEmpty(),
                    "decoderErrors" to
                        codecConfigurationFailure
                            ?.failures
                            ?.joinToString(",") { failure -> failure.safeDiagnosticLabel() }
                            .orEmpty(),
                ),
        )
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
                        dolbyAtmosSourceDetected = false,
                        dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                        audioOutputRoute = "",
                        audioOutputRouteVerified = false,
                        dolbyAtmosOutput = false,
                        spatialAudioOutput = false,
                        headTrackingAvailable = false,
                        // Never copy Throwable.message: media/framework exceptions can contain a URL.
                        reason =
                            typed?.let { failure ->
                                buildString {
                                    append("NativeDirect failed at ")
                                    append(failure.stage.name)
                                    failure.safeDetail?.takeIf(String::isNotBlank)?.let { detail ->
                                        append(": ")
                                        append(detail)
                                    }
                                }
                            } ?: "NativeDirect failed before typed-stage classification",
                    ),
            )
    }

    private inner class NativeSession(
        context: Context,
    ) {
        private val demux =
            AndroidMediaExtractorDemuxNode(
                context = context,
                onBlockingReadStateChanged = ::onTransportBlockingReadStateChanged,
            )
        private val videoDecoder = AndroidMediaCodecVideoNode()
        private val audioDecoder = AndroidMediaCodecAudioNode()
        private val audioRenderer = AndroidAudioTrackRenderNode(context)
        private val encodedAudioRenderer = AndroidEncodedAudioTrackRenderNode()
        private val capabilityProvider = AndroidYCapabilityProvider(context)
        private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)
        private val externalSubtitleLoader = AndroidExternalSubtitleLoader(context)
        private val wallClock = YMediaClock(positionUs = request.startPositionMs * MICROS_PER_MILLISECOND)
        private val frameRateManager = AndroidFrameRateManager(context, frameRateSwitchMode)
        private var monotonicPositionFloorUs = request.startPositionMs * MICROS_PER_MILLISECOND

        private var currentIndex = request.startIndex
        private var sourceRemote = false
        private var surfaceOutput: AndroidSurfaceVideoOutput? = null
        private var videoTrackIndex: Int? = null
        private var audioTrackIndex: Int? = null
        private var videoFormat: MediaFormat? = null
        private var inspectHdr10PlusSamples = false
        private var audioInputFormat: MediaFormat? = null
        private var subtitleTrackIndex: Int? = null
        private val subtitleCues = mutableListOf<YSubtitleCue>()
        private var externalSubtitles = emptyList<AndroidLoadedExternalSubtitle>()
        private var selectedExternalSubtitleId: String? = null
        private var audioTrackFormat: YAudioTrackFormat? = null
        private var audioOutputPath = YAudioOutputPath.None
        private var audioRendererConfigured = false
        private var observedAudioRoutingGeneration = 0L
        private val rejectedPassthroughTracks = mutableSetOf<Int>()
        private var drmSession: AndroidYCoreDrmSession? = null
        private var drmBinding: AndroidYCoreDrmBinding? = null
        private var prepared = false
        private var videoConfigured = false

        @Volatile
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
        private var pendingAudioOutput: YPendingDecodedAudioOutput? = null
        private var pendingEncodedAudioInput: YPendingEncodedAudioInput? = null
        private var seekPrerollVideoOutput: YCodecOutputResult.Buffer? = null
        private var emptyTailSeekRetries = 0
        private var lastQueuedPresentationUs = 0L
        private var lastVideoPresentationUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetVideoUs = request.startPositionMs * MICROS_PER_MILLISECOND
        private var seekTargetAudioUs = request.startPositionMs * MICROS_PER_MILLISECOND

        @Volatile
        private var firstVideoFrameRendered = false

        @Volatile
        private var transportReadBlocked = false

        @Volatile
        private var transportBufferingVisible = false

        @Volatile
        private var transportBlockGeneration = 0L
        private var droppedFrames = 0
        private var runtimeRenderRecorded = false
        private var lastStatePublishNs = 0L
        private var lastQoePublishNs = 0L
        private var audioBackpressureCount = 0
        private var slowPumpCount = 0
        private var maximumPumpDurationNs = 0L

        @Volatile
        private var renderedFrameCount = 0L

        @Volatile
        private var longRenderGapCount = 0

        @Volatile
        private var maximumRenderGapNs = 0L

        @Volatile
        private var lastRenderedRealtimeNs = 0L

        val canPump: Boolean
            get() =
                prepared &&
                    requestedPlay &&
                    videoConfigured &&
                    surfaceOutput?.surface?.isValid == true &&
                    mutableState.value.phase != YPlaybackPhase.Failed &&
                    !isEnded()

        fun handle(command: Command) {
            abortIfReleased()
            when (command) {
                Command.Prepare -> prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                Command.Play -> startPlayback()
                Command.Pause -> pausePlayback()
                is Command.Seek -> seekTo(command.positionUs)
                is Command.SetSpeed -> updateSpeed(command.speed)
                is Command.SetVideoOutput -> setSurface(command.output)
                is Command.SelectAudioTrack -> selectAudioTrack(command.trackIndex)
                is Command.SelectSubtitleTrack ->
                    selectSubtitleTrack(command.trackIndex, command.externalTrackId)
                is Command.SelectItem -> {
                    currentIndex = command.index
                    prepareCurrent(0L)
                }
            }
        }

        fun pump(): Boolean {
            val pumpStartedNs = System.nanoTime()
            var didWork = false
            try {
                drmSession?.let { session ->
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.Drm,
                        stage = YPlaybackFailureStage.VideoDecoderQueue,
                        safeDetail = "NativeDirect DRM key refresh",
                    ) {
                        if (session.refreshKeysIfNeeded()) {
                            firstVideoFrameRendered = false
                            mutableState.value =
                                mutableState.value.copy(
                                    diagnostics =
                                        mutableState.value.diagnostics
                                            .invalidateOutputEvidence(YOutputEvidenceResetReason.DrmKeysChanged)
                                            .copy(
                                                videoOutput = "DRM 密钥已更新 · 等待首帧",
                                                audioOutput = waitingAudioOutputLabel(),
                                            ),
                                )
                        }
                    }
                }
                didWork = handleAudioRoutingChange() || didWork
                didWork = drainPendingEncodedAudioInput() || didWork
                didWork = drainAudio() || didWork
                didWork = drainVideo() || didWork
                didWork = feedInput() || didWork
                publishClockPosition()
                finishIfEnded()
                return didWork
            } finally {
                val durationNs = (System.nanoTime() - pumpStartedNs).coerceAtLeast(0L)
                maximumPumpDurationNs = maxOf(maximumPumpDurationNs, durationNs)
                if (durationNs >= SLOW_PUMP_THRESHOLD_NS) slowPumpCount++
            }
        }

        private fun prepareCurrent(positionUs: Long) {
            releaseMedia()
            monotonicPositionFloorUs = positionUs.coerceAtLeast(0L)
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
                    diagnostics =
                        mutableState.value.diagnostics
                            .invalidateOutputEvidence(YOutputEvidenceResetReason.SourceChanged),
                )

            sourceRemote = item.uri.isCore2RemoteMediaUri()
            yPlaybackStage(
                category = sourceFailureCategory(),
                stage = YPlaybackFailureStage.SourceOpen,
                safeDetail = "NativeDirect source open",
            ) {
                demux.open(item.toAndroidSource())
            }
            abortIfReleased()
            val sidecarSources = item.allExternalSubtitles
            externalSubtitles =
                sidecarSources.mapIndexed { index, source ->
                    externalSubtitleLoader.load(
                        source = source,
                        headers = item.headers,
                        trackId = externalSubtitleTrackId(index),
                    )
                }
            abortIfReleased()
            selectedExternalSubtitleId =
                sidecarSources.indexOfFirst { it.forced || it.default }
                    .takeIf { it >= 0 }
                    ?.let(::externalSubtitleTrackId)
                    ?: externalSubtitles.singleOrNull()?.track?.id
            videoTrackIndex =
                demux.findFirstTrack(VIDEO_MIME_PREFIX)
                    ?: throw YPlaybackException(
                        category = YPlaybackFailureCategory.Container,
                        stage = YPlaybackFailureStage.Demux,
                        safeDetail = "NativeDirect source has no video track",
                    )
            val capabilities = capabilityProvider.current()
            val initialAudioTrack =
                (0 until demux.trackCount).firstNotNullOfOrNull { index ->
                    val format = demux.trackFormat(index)
                    if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith(AUDIO_MIME_PREFIX)) {
                        return@firstNotNullOfOrNull null
                    }
                    val coreFormat = runCatching { format.toCore2AudioTrackFormat() }.getOrNull()
                        ?: return@firstNotNullOfOrNull null
                    val requirement =
                        YAudioRequirement(
                            codec = coreFormat.codec,
                            channelCount = coreFormat.channelCount,
                            sampleRate = coreFormat.sampleRate,
                        )
                    val devicePath = capabilities.audioOutputPath(requirement)
                    val playable =
                        if (plannedAudioOutputPath == YAudioOutputPath.DecodePcm) {
                            devicePath == YAudioOutputPath.DecodePcm
                        } else {
                            devicePath != YAudioOutputPath.None
                        }
                    index.takeIf { playable }?.let { it to format }
                }
            audioTrackIndex = initialAudioTrack?.first
            videoFormat =
                demux
                    .trackFormat(requireNotNull(videoTrackIndex))
                    .also { format ->
                        if (plannedDolbyVisionConfig != null) {
                            format.applyDolbyVisionConfiguration(plannedDolbyVisionConfig)
                        } else if (confirmedDolbyVisionNalIdentity) {
                            format.setString(MediaFormat.KEY_MIME, DOLBY_VISION_MIME)
                        }
                    }
            inspectHdr10PlusSamples =
                videoFormat?.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO) == true ||
                    item.sourceHints
                        ?.dynamicRange
                        .orEmpty()
                        .replace(" ", "")
                        .let { range ->
                            range.contains("hdr10+", ignoreCase = true) ||
                                range.contains("hdr10plus", ignoreCase = true)
                        }
            validateNativeDirectDolbyIdentity(
                required = requireDolbyVisionIdentity,
                extractedMime = videoFormat?.getString(MediaFormat.KEY_MIME),
            )
            audioInputFormat = initialAudioTrack?.second
            val sourceBitRateBitsPerSecond =
                maxOf(
                    item.sourceHints?.bitrateBitsPerSecond ?: 0L,
                    listOfNotNull(videoFormat, audioInputFormat)
                        .sumOf { it.longOrZero(MediaFormat.KEY_BIT_RATE) },
                )
            demux.setMediaBitRateBitsPerSecond(sourceBitRateBitsPerSecond)
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
            abortIfReleased()

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
                            bitrateBitsPerSecond = sourceBitRateBitsPerSecond,
                            dynamicRange = videoFormat.dynamicRangeLabel(),
                            videoOutput = if (videoConfigured) "等待首帧" else "等待 Surface",
                            audioOutput = waitingAudioOutputLabel(),
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                            dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                            audioOutputRoute = "",
                            audioOutputRouteVerified = false,
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
                            mutableState.value.diagnostics
                                .invalidateOutputEvidence(YOutputEvidenceResetReason.SurfaceChanged)
                                .copy(videoOutput = "等待 Surface"),
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
                        mutableState.value.diagnostics
                            .invalidateOutputEvidence(YOutputEvidenceResetReason.SurfaceChanged)
                            .copy(videoOutput = "硬解已配置 · 等待首帧"),
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
                    playing = firstVideoFrameRendered && !transportBufferingVisible,
                    buffering = !firstVideoFrameRendered || transportBufferingVisible,
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
            pendingEncodedAudioInput = null
            yPlaybackStage(
                category = sourceFailureCategory(),
                stage = YPlaybackFailureStage.Seek,
                safeDetail = "NativeDirect source seek",
            ) {
                demux.seekTo(targetUs)
            }
            if (videoConfigured && flushVideoDecoder) videoDecoder.flush()
            if (audioInputFormat != null && !isAudioPassthrough()) {
                releasePendingAudioOutput()
                audioDecoder.flush()
            }
            if (audioRendererConfigured) flushAudio()
            subtitleCues.clear()
            resetEndState()
            seekTargetVideoUs = targetUs
            seekTargetAudioUs = targetUs
            lastVideoPresentationUs = targetUs
            lastQueuedPresentationUs = targetUs
            monotonicPositionFloorUs = targetUs
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
                        mutableState.value.diagnostics
                            .invalidateOutputEvidence(YOutputEvidenceResetReason.Seek)
                            .copy(
                            videoOutput = if (videoConfigured) "硬解已配置 · 等待首帧" else "等待 Surface",
                            audioOutput = waitingAudioOutputLabel(),
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
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
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                            dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                            audioOutputRoute = "",
                            audioOutputRouteVerified = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                        ),
                )
        }

        private fun selectSubtitleTrack(
            trackIndex: Int?,
            externalTrackId: String?,
        ) {
            if (!prepared) return
            if (externalTrackId != null && externalSubtitles.none { it.track.id == externalTrackId }) return
            if (externalTrackId == selectedExternalSubtitleId && trackIndex == subtitleTrackIndex) return
            val nextFormat = trackIndex?.takeIf { it in 0 until demux.trackCount }?.let(demux::trackFormat)
            val nextSubtitleFormat = nextFormat?.subtitleFormatOrNull()
            if (trackIndex != null && nextSubtitleFormat?.textOverlaySupported != true) return
            val positionUs = currentPositionUs()
            subtitleTrackIndex?.let(demux::unselectTrack)
            trackIndex?.let(demux::selectTrack)
            subtitleTrackIndex = trackIndex
            selectedExternalSubtitleId = externalTrackId
            subtitleCues.clear()
            seekTo(positionUs)
            mutableState.value =
                mutableState.value.copy(
                    subtitleTracks = subtitleTracks(),
                    subtitleCues = activeSubtitleCues(),
                )
        }

        private fun feedInput(): Boolean {
            if (pendingEncodedAudioInput != null) return false
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
                            applyHdr10PlusMetadata(sample.data)
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

        /** MediaExtractor does not consistently forward per-frame ST 2094-40 metadata. */
        private fun applyHdr10PlusMetadata(data: ByteBuffer) {
            if (!inspectHdr10PlusSamples || !data.hasRemaining()) return
            val payload = extractNativeDirectHdr10PlusPayload(data) ?: return
            videoDecoder.setHdr10PlusMetadata(payload)
        }

        private fun drainAudio(): Boolean {
            if (audioInputFormat == null || audioOutputEnded) return false
            if (isAudioPassthrough()) return false
            pendingAudioOutput?.let { return writePendingAudioOutput(it) }
            return when (val output = audioDecoder.dequeueOutput()) {
                YAudioCodecOutputResult.TryAgain -> false
                is YAudioCodecOutputResult.FormatChanged -> {
                    audioRenderer.configure(output.format)
                    audioRendererConfigured = true
                    captureAudioRoutingGeneration()
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
                    val configBuffer = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val renderable =
                        !configBuffer &&
                            output.size > 0 &&
                            output.presentationTimeUs >= seekTargetAudioUs
                    if (!renderable) {
                        audioDecoder.releaseOutput(output)
                        if (output.endOfStream) audioOutputEnded = true
                        true
                    } else {
                        if (!audioRendererConfigured) {
                            // Android normally emits INFO_OUTPUT_FORMAT_CHANGED first; keep the
                            // failure explicit rather than silently dropping audio if an OEM does not.
                            error("Audio output arrived before PCM format")
                        }
                        val pending =
                            YPendingDecodedAudioOutput(
                                output = output,
                                data = audioDecoder.outputData(output),
                            )
                        pendingAudioOutput = pending
                        writePendingAudioOutput(pending)
                    }
                }
            }
        }

        private fun writePendingAudioOutput(pending: YPendingDecodedAudioOutput): Boolean {
            val writtenBytes =
                audioRenderer.writeNonBlocking(
                    pending.data,
                    pending.output.presentationTimeUs,
                )
            return when (decodedAudioDrainProgress(writtenBytes, pending.data.remaining())) {
                YDecodedAudioDrainProgress.Backpressured -> {
                    audioBackpressureCount++
                    false
                }
                YDecodedAudioDrainProgress.Pending -> {
                    verifyPcmAudioOutput(writtenBytes)
                    true
                }
                YDecodedAudioDrainProgress.Complete -> {
                    verifyPcmAudioOutput(writtenBytes)
                    pendingAudioOutput = null
                    audioDecoder.releaseOutput(pending.output)
                    seekTargetAudioUs = 0L
                    if (pending.output.endOfStream) audioOutputEnded = true
                    true
                }
            }
        }

        private fun verifyPcmAudioOutput(writtenBytes: Int) {
            if (writtenBytes <= 0) return
            val spatialized = audioRenderer.spatialAudioOutput
            val atmosSource = audioTrackFormat?.codec.isDolbyAtmosSource()
            val outputMode =
                if (spatialized && atmosSource) {
                    YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm
                } else {
                    YDolbyAtmosOutputMode.None
                }
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            audioOutput =
                                when {
                                    outputMode == YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm &&
                                        audioRenderer.headTrackingAvailable ->
                                        "Dolby Atmos 源 · 系统空间音频 · PCM · 头部跟踪可用"
                                    outputMode == YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm ->
                                        "Dolby Atmos 源 · 系统空间音频 · PCM"
                                    audioRenderer.spatialAudioOutput && audioRenderer.headTrackingAvailable ->
                                        "系统空间音频 · PCM · 头部跟踪可用"
                                    audioRenderer.spatialAudioOutput -> "系统空间音频 · PCM"
                                    else -> "PCM · AudioTrack"
                                },
                            audioOutputVerified = true,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosSourceDetected = atmosSource,
                            dolbyAtmosOutputMode = outputMode,
                            audioOutputRoute = audioRenderer.audioRouteLabel,
                            audioOutputRouteVerified = audioRenderer.audioRouteVerified,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = spatialized,
                            headTrackingAvailable = audioRenderer.headTrackingAvailable,
                        ),
                )
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
                val wallClockRenderNs = wallClock.presentationTimeNs(output.presentationTimeUs)
                val desiredRenderNs =
                    if (audioRendererConfigured) {
                        audioPresentationTimeNs(output.presentationTimeUs, wallClockRenderNs)
                    } else {
                        wallClockRenderNs
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
                    playing = requestedPlay && firstVideoFrameRendered && !transportBufferingVisible && !isEnded(),
                    buffering = requestedPlay && (!firstVideoFrameRendered || transportBufferingVisible) && !isEnded(),
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            droppedFrames = droppedFrames,
                            droppedFramesMeasured = true,
                            audioUnderrunCount =
                                maxOf(
                                    mutableState.value.diagnostics.audioUnderrunCount,
                                    audioRenderer.underrunCount,
                                ),
                            avSyncOffsetMs = lastAvSyncOffsetUs?.div(MICROS_PER_MILLISECOND),
                            avSyncMeasurement =
                                if (lastAvSyncOffsetUs != null) {
                                    "MediaCodec 帧渲染 / AudioTrack 时钟"
                                } else {
                                    "等待音视频时钟样本"
                                },
                        ),
                )
            publishQoeSnapshot(nowNs, positionUs)
        }

        private fun publishQoeSnapshot(
            nowNs: Long,
            positionUs: Long,
        ) {
            if (released) return
            if (nowNs - lastQoePublishNs < QOE_PUBLISH_INTERVAL_NS) return
            lastQoePublishNs = nowNs
            val transportQoe = demux.transportQoeSnapshot()
            AppLog.info(
                category = "player.core2",
                event = "native_direct_qoe",
                message = "YCore sampled device-local playback pacing",
                attributes =
                    mapOf(
                        "decoder" to videoDecoder.decoderName.orEmpty(),
                        "positionMs" to (positionUs / MICROS_PER_MILLISECOND).toString(),
                        "renderedFrames" to renderedFrameCount.toString(),
                        "droppedFrames" to droppedFrames.toString(),
                        "longRenderGaps" to longRenderGapCount.toString(),
                        "maximumRenderGapMs" to
                            (maximumRenderGapNs / NANOS_PER_MILLISECOND).toString(),
                        "audioUnderruns" to audioRenderer.underrunCount.toString(),
                        "audioBackpressure" to audioBackpressureCount.toString(),
                        "pendingAudioBytes" to (pendingAudioOutput?.data?.remaining() ?: 0).toString(),
                        "audioClockSource" to
                            if (isAudioPassthrough()) {
                                "EncodedAudioTrack"
                            } else {
                                audioRenderer.clockSource
                            },
                        "audioClockStalled" to
                            (!isAudioPassthrough() && audioRenderer.clockStalled).toString(),
                        "slowPumps" to slowPumpCount.toString(),
                        "maximumPumpMs" to (maximumPumpDurationNs / NANOS_PER_MILLISECOND).toString(),
                        "sourcePrefetchDepth" to (transportQoe?.depthBlocks?.toString() ?: ""),
                        "sourcePrefetchHits" to (transportQoe?.hitCount?.toString() ?: ""),
                        "sourceSynchronousLoads" to
                            (transportQoe?.synchronousLoadCount?.toString() ?: ""),
                        "sourceMaximumWaitMs" to
                            (transportQoe?.maximumResolveWaitMs?.toString() ?: ""),
                        "sourceMaximumLoadMs" to
                            (transportQoe?.maximumRemoteLoadMs?.toString() ?: ""),
                        "avOffsetMs" to (lastAvSyncOffsetUs?.div(MICROS_PER_MILLISECOND)?.toString() ?: ""),
                    ),
            )
            maximumPumpDurationNs = 0L
        }

        private fun currentPositionUs(): Long {
            val candidateUs =
                audioClockSnapshot()?.positionUs
                    ?: if (requestedPlay) {
                        wallClock.positionUs(System.nanoTime())
                    } else {
                        maxOf(
                            mutableState.value.positionMs * MICROS_PER_MILLISECOND,
                            lastVideoPresentationUs,
                        )
                    }
            return maxOf(candidateUs, monotonicPositionFloorUs).also { positionUs ->
                monotonicPositionFloorUs = positionUs
            }
        }

        private fun finishIfEnded() {
            if (!isEnded()) return
            // Do not manufacture completion by snapping an early EOF to the declared duration.
            // The router compares this real output position with duration before auto-next.
            val renderedEndUs =
                maxOf(
                    lastVideoPresentationUs,
                    audioClockSnapshot()?.positionUs ?: 0L,
                )
            val declaredDurationUs = mutableState.value.durationMs * MICROS_PER_MILLISECOND
            val endPositionUs =
                if (declaredDurationUs > 0L) {
                    renderedEndUs.coerceAtMost(declaredDurationUs)
                } else {
                    renderedEndUs
                }
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
            pendingAudioOutput = null
            seekPrerollVideoOutput = null
            lastAvSyncOffsetUs = null
            lastRenderedRealtimeNs = 0L
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
            captureAudioRoutingGeneration()
        }

        private fun releaseAudioPath() {
            releasePendingAudioOutput()
            runCatching(audioRenderer::release)
            runCatching(encodedAudioRenderer::release)
            runCatching(audioDecoder::release)
            audioRendererConfigured = false
            audioOutputPath = YAudioOutputPath.None
            audioTrackFormat = null
            pendingEncodedAudioInput = null
            captureAudioRoutingGeneration()
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
                    val copy =
                        ByteBuffer
                            .allocateDirect(data.remaining())
                            .put(data.duplicate())
                            .also(ByteBuffer::flip)
                    val written = encodedAudioRenderer.writeNonBlocking(copy, presentationTimeUs)
                    if (copy.hasRemaining()) {
                        pendingEncodedAudioInput =
                            YPendingEncodedAudioInput(
                                data = copy,
                                presentationTimeUs = presentationTimeUs,
                            )
                        if (written == 0) audioBackpressureCount++
                        return YCodecQueueResult.TryAgain
                    }
                } catch (_: Exception) {
                    val resumeUs = currentPositionUs()
                    rejectedPassthroughTracks += requireNotNull(audioTrackIndex)
                    switchPassthroughToPcm(countFailure = true)
                    seekTo(resumeUs)
                    return YCodecQueueResult.TryAgain
                }
                seekTargetAudioUs = 0L
                verifyEncodedAudioOutput()
            }
            return YCodecQueueResult.Queued
        }

        private fun drainPendingEncodedAudioInput(): Boolean {
            val pending = pendingEncodedAudioInput ?: return false
            return try {
                val written =
                    encodedAudioRenderer.writeNonBlocking(
                        pending.data,
                        pending.presentationTimeUs,
                    )
                if (written == 0) audioBackpressureCount++
                if (pending.data.hasRemaining()) {
                    written > 0
                } else {
                    pendingEncodedAudioInput = null
                    seekTargetAudioUs = 0L
                    verifyEncodedAudioOutput()
                    lastQueuedPresentationUs =
                        maxOf(lastQueuedPresentationUs, pending.presentationTimeUs)
                    demux.advance()
                    true
                }
            } catch (_: Exception) {
                val resumeUs = currentPositionUs()
                audioTrackIndex?.let(rejectedPassthroughTracks::add)
                pendingEncodedAudioInput = null
                switchPassthroughToPcm(countFailure = true)
                seekTo(resumeUs)
                true
            }
        }

        private fun verifyEncodedAudioOutput() {
            val outputMode = encodedAudioRenderer.dolbyAtmosOutputMode
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            audioOutput = activeAudioOutputLabel(),
                            audioOutputVerified = true,
                            immersiveAudioCarrierOutput = encodedAudioRenderer.immersiveCarrierOutput,
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                            dolbyAtmosOutputMode = outputMode,
                            audioOutputRoute = encodedAudioRenderer.audioRouteLabel,
                            audioOutputRouteVerified = encodedAudioRenderer.audioRouteVerified,
                            dolbyAtmosOutput = outputMode.encodedPassthrough,
                        ),
                )
        }

        private fun switchPassthroughToPcm(countFailure: Boolean) {
            val format = checkNotNull(audioInputFormat) { "PCM fallback requires an audio track" }
            runCatching(encodedAudioRenderer::release)
            runCatching(audioRenderer::release)
            runCatching(audioDecoder::release)
            pendingEncodedAudioInput = null
            audioOutputPath = YAudioOutputPath.DecodePcm
            audioRendererConfigured = false
            audioDecoder.configure(format, drmBinding?.mediaCrypto)
            captureAudioRoutingGeneration()
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics.copy(
                            audioOutput = "原码不可用 · 自动回落 PCM",
                            audioOutputVerified = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                            dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                            audioOutputRoute = "",
                            audioOutputRouteVerified = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            audioUnderrunCount =
                                mutableState.value.diagnostics.audioUnderrunCount +
                                    if (countFailure) 1 else 0,
                        ),
                )
        }

        private fun handleAudioRoutingChange(): Boolean {
            val generation =
                if (isAudioPassthrough()) {
                    encodedAudioRenderer.routingChangeGeneration
                } else {
                    audioRenderer.routingChangeGeneration
                }
            if (generation == observedAudioRoutingGeneration) return false
            observedAudioRoutingGeneration = generation
            firstVideoFrameRendered = false
            mutableState.value =
                mutableState.value.copy(
                    diagnostics =
                        mutableState.value.diagnostics
                            .invalidateOutputEvidence(YOutputEvidenceResetReason.AudioRouteChanged)
                            .copy(
                                videoOutput = "音频路由已变化 · 等待新帧",
                                audioOutput = waitingAudioOutputLabel(),
                            ),
                )
            val coreFormat = audioTrackFormat ?: return false
            if (!isAudioPassthrough()) return true

            val capabilities = capabilityProvider.current()
            val nextPath =
                capabilities.audioOutputPath(
                    YAudioRequirement(
                        codec = coreFormat.codec,
                        channelCount = coreFormat.channelCount,
                        sampleRate = coreFormat.sampleRate,
                    ),
                )
            if (nextPath != YAudioOutputPath.Passthrough) {
                val resumeUs = currentPositionUs()
                audioTrackIndex?.let(rejectedPassthroughTracks::add)
                switchPassthroughToPcm(countFailure = true)
                seekTo(resumeUs)
            } else {
                encodedAudioRenderer.updateExactDolbyAtmosTransport(
                    capabilities.hasExactDolbyAtmosPassthrough(coreFormat.codec),
                )
            }
            refreshActiveAudioEvidence()
            return true
        }

        private fun refreshActiveAudioEvidence() {
            if (!mutableState.value.diagnostics.audioOutputVerified) return
            if (isAudioPassthrough()) {
                verifyEncodedAudioOutput()
            } else {
                verifyPcmAudioOutput(writtenBytes = 1)
            }
        }

        private fun captureAudioRoutingGeneration() {
            observedAudioRoutingGeneration =
                if (isAudioPassthrough()) {
                    encodedAudioRenderer.routingChangeGeneration
                } else {
                    audioRenderer.routingChangeGeneration
                }
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
                if (released || generation != renderCallbackGeneration) return@setOnFrameRenderedListener
                val previousRealtimeNs = lastRenderedRealtimeNs
                if (previousRealtimeNs > 0L && realtimeNs > previousRealtimeNs) {
                    val gapNs = realtimeNs - previousRealtimeNs
                    maximumRenderGapNs = maxOf(maximumRenderGapNs, gapNs)
                    if (gapNs >= LONG_RENDER_GAP_NS) longRenderGapCount++
                }
                lastRenderedRealtimeNs = realtimeNs
                renderedFrameCount++
                markFirstVideoFrameRendered()
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

        private fun markFirstVideoFrameRendered() {
            if (firstVideoFrameRendered || released) return
            firstVideoFrameRendered = true
            mutableState.updateState { current ->
                if (released) {
                    current
                } else {
                    current.copy(
                        phase =
                            if (current.phase == YPlaybackPhase.Ended) {
                                YPlaybackPhase.Ended
                            } else {
                                YPlaybackPhase.Ready
                            },
                        playing =
                            requestedPlay &&
                                !transportBufferingVisible &&
                                current.phase != YPlaybackPhase.Ended,
                        buffering = requestedPlay && transportBufferingVisible,
                        diagnostics =
                            current.diagnostics.copy(
                                videoOutput = "Surface 直出",
                                videoOutputVerified = true,
                                dolbyVisionOutput = nativeDolbyVisionOutputVerified(),
                            ),
                    )
                }
            }
        }

        private fun onTransportBlockingReadStateChanged(blocked: Boolean) {
            if (transportReadBlocked == blocked || released) return
            val nowNs = System.nanoTime()
            if (blocked) {
                val frozenPositionUs = currentPositionUs()
                monotonicPositionFloorUs = frozenPositionUs
                wallClock.pause(frozenPositionUs, nowNs)
                transportReadBlocked = true
                val generation = ++transportBlockGeneration
                // MediaExtractor range reads are synchronous. A short cache miss is transport
                // work, not a visible rebuffer, so expose buffering only after the debounce gate.
                scope.launch(Dispatchers.Default) {
                    delay(TRANSPORT_BUFFERING_DEBOUNCE_MS)
                    if (!released && transportReadBlocked && transportBlockGeneration == generation) {
                        transportBufferingVisible = true
                        mutableState.updateState { current ->
                            if (released || current.phase == YPlaybackPhase.Failed || current.phase == YPlaybackPhase.Ended) {
                                current
                            } else {
                                current.copy(playing = false, buffering = requestedPlay)
                            }
                        }
                    }
                }
                return
            }

            transportReadBlocked = false
            ++transportBlockGeneration
            transportBufferingVisible = false
            if (requestedPlay) {
                val resumePositionUs = audioClockSnapshot()?.positionUs ?: wallClock.positionUs(nowNs)
                monotonicPositionFloorUs = maxOf(monotonicPositionFloorUs, resumePositionUs)
                wallClock.start(monotonicPositionFloorUs, nowNs)
            }
            mutableState.updateState { current ->
                if (released || current.phase == YPlaybackPhase.Failed || current.phase == YPlaybackPhase.Ended) {
                    current
                } else {
                    current.copy(
                        playing = requestedPlay && firstVideoFrameRendered,
                        buffering = requestedPlay && !firstVideoFrameRendered,
                    )
                }
            }
        }

        private fun abortIfReleased() {
            if (released || !scope.isActive) {
                throw CancellationException("NativeDirect player was released")
            }
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
            when (encodedAudioRenderer.dolbyAtmosOutputMode) {
                YDolbyAtmosOutputMode.Eac3JocPassthrough -> "Dolby Atmos · E-AC-3 JOC 原码 · AudioTrack"
                YDolbyAtmosOutputMode.TrueHdAtmosPassthrough -> "Dolby Atmos · TrueHD 原码 · AudioTrack"
                YDolbyAtmosOutputMode.TrueHdCarrierPassthrough ->
                    "TrueHD 载波 · AudioTrack（未验证 Atmos 对象输出）"
                YDolbyAtmosOutputMode.CarrierOnly ->
                    "沉浸音频载波 · AudioTrack（未验证对象输出）"
                YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm,
                YDolbyAtmosOutputMode.None,
                -> if (encodedAudioRenderer.immersiveCarrierOutput) {
                    "沉浸音频载波 · AudioTrack（未验证对象输出）"
                } else {
                    "原码直通 · AudioTrack"
                }
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
                        selected = selectedExternalSubtitleId == null && index == subtitleTrackIndex,
                    )
                }
            return embedded +
                externalSubtitles.map { subtitle ->
                    subtitle.track.copy(selected = subtitle.track.id == selectedExternalSubtitleId)
                }
        }

        private fun activeSubtitleCues(): List<YSubtitleCue> =
            selectedExternalSubtitleId
                ?.let { id -> externalSubtitles.firstOrNull { it.track.id == id }?.cues }
                ?: subtitleCues.toList()

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
            releasePendingAudioOutput()
            pendingEncodedAudioInput = null
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
            externalSubtitles = emptyList()
            selectedExternalSubtitleId = null
            sourceRemote = false
            videoFormat = null
            inspectHdr10PlusSamples = false
            audioInputFormat = null
            audioTrackFormat = null
            audioOutputPath = YAudioOutputPath.None
            observedAudioRoutingGeneration = 0L
            rejectedPassthroughTracks.clear()
            firstVideoFrameRendered = false
            transportReadBlocked = false
            transportBufferingVisible = false
            transportBlockGeneration++
            droppedFrames = 0
            runtimeRenderRecorded = false
            renderedFrameCount = 0L
            longRenderGapCount = 0
            maximumRenderGapNs = 0L
            lastRenderedRealtimeNs = 0L
            audioBackpressureCount = 0
            slowPumpCount = 0
            maximumPumpDurationNs = 0L
            lastQoePublishNs = 0L
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

        private fun releasePendingAudioOutput() {
            val pending = pendingAudioOutput ?: return
            pendingAudioOutput = null
            runCatching { audioDecoder.releaseOutput(pending.output) }
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
            val externalTrackId: String?,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

internal enum class YDecodedAudioDrainProgress {
    Backpressured,
    Pending,
    Complete,
}

internal fun decodedAudioDrainProgress(
    writtenBytes: Int,
    remainingBytes: Int,
): YDecodedAudioDrainProgress {
    require(writtenBytes >= 0)
    require(remainingBytes >= 0)
    return when {
        remainingBytes == 0 -> YDecodedAudioDrainProgress.Complete
        writtenBytes == 0 -> YDecodedAudioDrainProgress.Backpressured
        else -> YDecodedAudioDrainProgress.Pending
    }
}

private data class YPendingDecodedAudioOutput(
    val output: YAudioCodecOutputResult.Buffer,
    val data: ByteBuffer,
)

private data class YPendingEncodedAudioInput(
    val data: ByteBuffer,
    val presentationTimeUs: Long,
)

private fun createNativeDirectPlaybackDispatcher(): ExecutorCoroutineDispatcher =
    Executors
        .newSingleThreadExecutor { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    task.run()
                },
                NATIVE_DIRECT_THREAD_NAME,
            )
        }.asCoroutineDispatcher()

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
        YPlaybackFailureCategory.Container ->
            if (failure.stage == YPlaybackFailureStage.Bitstream) {
                "YCore 2.0 无法验证当前片源的杜比视界配置"
            } else {
                "YCore 2.0 原生解封装无法识别当前片源"
            }
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
        credentials = transportCredentials,
        bitrateBitsPerSecond = sourceHints?.bitrateBitsPerSecond ?: 0L,
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

internal fun extractNativeDirectHdr10PlusPayload(data: ByteBuffer): ByteArray? {
    if (!data.hasRemaining()) return null
    val bytes = ByteArray(data.remaining())
    data.duplicate().get(bytes)
    return HDR10_PLUS_SAMPLE_PACKINGS
        .asSequence()
        .mapNotNull { packing ->
            runCatching { YBitstream.hdr10PlusItuT35Payload(bytes, packing) }.getOrNull()
        }.firstOrNull()
}

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    value = transform(value)
}

private fun YVideoDecoderAttemptFailure.safeDiagnosticLabel(): String =
    buildString {
        append(decoderName)
        append(':')
        append(diagnosticInfo ?: errorType)
        errorCode?.let { code ->
            append(':')
            append(code)
        }
        if (recoverable) append(":recoverable")
        if (transient) append(":transient")
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
private const val SLOW_PUMP_THRESHOLD_NS = 20_000_000L
private const val LONG_RENDER_GAP_NS = 100_000_000L
private const val QOE_PUBLISH_INTERVAL_NS = 5_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val SUBTITLE_HISTORY_US = 60_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
private const val TRANSPORT_BUFFERING_DEBOUNCE_MS = 300L
private const val NATIVE_DIRECT_THREAD_NAME = "YCore-NativeDirect"
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
private const val ATMOS_PROFILE = 30

private val HDR10_PLUS_SAMPLE_PACKINGS =
    listOf(
        YSamplePacking.AnnexB,
        YSamplePacking.LengthPrefixed(4),
        YSamplePacking.LengthPrefixed(2),
        YSamplePacking.LengthPrefixed(1),
    )
