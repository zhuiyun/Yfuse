package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Unified YPlayer wrapper for the Phase-3 NativeEnhanced graph.
 *
 * It is still opt-in: production routing must explicitly choose this player only after the enhanced
 * probe and strategy return an executable NativeEnhanced plan. Legacy remains the recovery path.
 */
internal class AndroidNativeEnhancedYPlayer(
    context: Context,
    private val request: YPlayerOpenRequest,
    private val routeEvaluator: AndroidCore2RouteEvaluator = AndroidCore2RouteEvaluator(context),
    private val allowAudioPassthrough: Boolean = true,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val forcedPlan: YPlaybackPlan? = null,
    private val requireDolbyVisionIdentity: Boolean = false,
    private val preferredRemoteBufferTargetUs: Long? = null,
    private val initialDecision: YCore2RouteDecision? = null,
    private val initialProbeBudget: AndroidProbeBudget? = null,
) : YPlayer,
    AndroidSerializedPlayerRelease {
    private val appContext = context.applicationContext
    private val capabilityProvider = AndroidYCapabilityProvider(context)
    private val externalSubtitleLoader = AndroidExternalSubtitleLoader(context)
    private val mutableState =
        MutableStateFlow(
            YPlayerState(
                phase = YPlaybackPhase.Idle,
                playbackRequested = request.autoPlay,
                positionMs = request.startPositionMs,
                currentIndex = request.startIndex,
                itemCount = request.items.size,
                diagnostics =
                    YPlayerDiagnostics(
                        route = forcedPlan?.route ?: YPlaybackRoute.NativeEnhanced,
                        demuxer = "FFmpeg / libavformat",
                        renderer =
                            if (forcedPlan?.route == YPlaybackRoute.GpuEnhanced) {
                                "Vulkan + AudioTrack"
                            } else {
                                "Surface + AudioTrack"
                            },
                        dynamicRange = forcedPlan?.inputHdrType?.name.orEmpty(),
                        reason = "YCore 2.0 NativeEnhanced opt-in path",
                    ),
            ),
        )
    override val state: StateFlow<YPlayerState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    private val playbackDispatcher = createPlaybackWorkerDispatcher("YCore-NativeEnhanced")
    private val scope = CoroutineScope(SupervisorJob() + playbackDispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Conflated hint that wakes an idle run loop as soon as a command is queued. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job = scope.launch { runLoop() }

    @Volatile
    private var released = false

    @Volatile
    private var releasedAtMs: Long? = null

    @Volatile private var activeSession: AndroidEnhancedPlaybackSession? = null

    @Volatile private var proxy: AndroidYCoreHttpProxy? = null

    @Volatile private var activeProbeBudget: AndroidProbeBudget? = null

    @Volatile private var pendingProbeBudget: AndroidProbeBudget? = initialProbeBudget

    fun retryWithProbeBudget(budget: AndroidProbeBudget?) {
        pendingProbeBudget = budget
        retry()
    }

    override val releaseCompleted: Boolean get() = worker.isCompleted

    override suspend fun releaseAndJoin() {
        release()
        check(
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    worker.join()
                    true
                }
            } == true,
        ) {
            "Previous enhanced decoder did not finish releasing; replacement was not started"
        }
    }

    override fun prepare() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                buffering = it.playbackRequested,
                error = null,
                errorCategory = null,
            )
        }
        submit(Command.Prepare)
    }

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released) return false
        if (output != null && output !is AndroidSurfaceVideoOutput) return false
        submit(Command.SetVideoOutput(output as AndroidSurfaceVideoOutput?))
        return true
    }

    override fun play() {
        if (released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = true,
                buffering = it.phase != YPlaybackPhase.Ended,
                error = null,
                errorCategory = null,
            )
        }
        submit(Command.Play)
    }

    override fun pause() {
        if (released) return
        mutableState.updateState {
            it.copy(playbackRequested = false, playing = false, buffering = false)
        }
        submit(Command.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0L)
        mutableState.updateState {
            it.copy(
                positionMs = target,
                subtitleCues = emptyList(),
                secondarySubtitleCues = emptyList(),
                buffering = it.playbackRequested,
                phase = if (it.phase == YPlaybackPhase.Ended) YPlaybackPhase.Ready else it.phase,
            )
        }
        submit(Command.Seek(target * MICROS_PER_MILLISECOND))
    }

    override fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed <= 0f) return
        mutableState.updateState { it.copy(speed = speed) }
        submit(Command.SetSpeed(speed))
    }

    override val supportsAudioDelay: Boolean get() = true

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        if (released) return false
        submit(Command.SetAudioDelay(delayMs.coerceIn(-5_000L, 5_000L)))
        return true
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (released) return
        when (type) {
            YTrackType.Audio -> {
                val trackId = id.removePrefix(AUDIO_TRACK_PREFIX).toIntOrNull() ?: return
                if (mutableState.value.audioTracks.any { it.id == id && !it.selected }) {
                    submit(Command.SelectAudioTrack(trackId))
                }
            }
            YTrackType.Subtitle -> {
                val command =
                    when (id) {
                        SUBTITLE_OFF -> Command.SelectSubtitleTrack(null, externalTrackId = null)
                        EXTERNAL_SUBTITLE_TRACK_ID ->
                            Command.SelectSubtitleTrack(
                                null,
                                externalTrackId =
                                    mutableState.value.subtitleTracks
                                        .firstOrNull {
                                            it.id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)
                                        }?.id,
                            )
                        else -> {
                            if (id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)) {
                                Command.SelectSubtitleTrack(null, externalTrackId = id)
                            } else {
                                val trackId = id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return
                                Command.SelectSubtitleTrack(trackId, externalTrackId = null)
                            }
                        }
                    }
                if (id == SUBTITLE_OFF || mutableState.value.subtitleTracks.any { it.id == id && !it.selected }) {
                    submit(command)
                }
            }
        }
    }

    override val supportsSecondarySubtitleOffset: Boolean = true

    override fun setSecondarySubtitleOffsetMs(offsetMs: Long): Boolean {
        if (released || offsetMs !in -60_000L..60_000L) return false
        mutableState.updateState { it.copy(secondarySubtitleOffsetMs = offsetMs) }
        return true
    }

    override val supportsSecondarySubtitleTrack: Boolean = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        if (released) return false
        val selected = mutableState.value.subtitleTracks.firstOrNull { it.id == id }
        if (id != SUBTITLE_OFF && (selected == null || selected.selected)) return false
        val external = id.takeIf { it.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX) }
        val embedded =
            if (id == SUBTITLE_OFF || external != null) {
                null
            } else {
                id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return false
            }
        submit(Command.SelectSubtitleTrack(embedded, externalTrackId = external, secondary = true))
        return true
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
                errorCategory = null,
            )
        }
        submit(Command.SelectItem(index))
    }

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                error = null,
                errorCategory = null,
                buffering = it.playbackRequested,
            )
        }
        submit(Command.Prepare)
    }

    override fun release() {
        if (released) return
        releasedAtMs = System.nanoTime() / 1_000_000L
        released = true
        activeProbeBudget?.cancel("released")
        activeSession?.cancelPendingRead()
        proxy?.close()
        commands.close()
        wakeSignal.trySend(Unit)
        worker.invokeOnCompletion { playbackDispatcher.close() }
        worker.cancel()
        scope.cancel()
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Idle,
                playing = false,
                playbackRequested = false,
                buffering = false,
            )
        }
    }

    private fun submit(command: Command) {
        commands.trySend(command)
        wakeSignal.trySend(Unit)
    }

    private suspend fun runLoop() {
        fun newProxy(): AndroidYCoreHttpProxy? =
            runCatching {
                AndroidYCoreHttpProxy(
                    context = appContext,
                    userAgent =
                        request.items
                            .asSequence()
                            .flatMap { it.headers.asSequence() }
                            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                            ?.value
                            .orEmpty(),
                    cacheMaximumBytes = request.items.maxOfOrNull { it.cacheMaximumBytes } ?: 0L,
                    forwardCacheTargetUs = preferredRemoteBufferTargetUs ?: 60_000_000L,
                )
            }.getOrNull()
        val session =
            AndroidEnhancedPlaybackSession(
                context = appContext,
                runtimeCapabilities = AndroidRuntimeCapabilityRegistry(appContext),
                frameRateSwitchMode = frameRateSwitchMode,
                preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
            ).also { activeSession = it }
        var surfaceOutput: AndroidSurfaceVideoOutput? = null
        var currentIndex = request.startIndex
        var requestedPlay = request.autoPlay
        var speed = 1f
        var audioDelayMs = 0L
        var prepared = false
        var lastPublishNs = 0L
        var externalSubtitles = emptyList<AndroidLoadedExternalSubtitle>()
        val externalSubtitleSession =
            AndroidExternalSubtitleSession(
                scope = scope,
                load = { source, headers, id -> externalSubtitleLoader.load(source, headers, id) },
                completed = { submit(Command.ExternalSubtitleReady(it)) },
            )
        var selectedExternalSubtitleId: String? = null
        var secondaryExternalSubtitleId: String? = null
        var secondaryTrackId: String? = null
        var activePlan: YPlaybackPlan? = null
        var activeDolbyProfile: Int? = null
        var adaptiveFeedbackGeneration = 0L
        var pendingInitialDecision = initialDecision
        val rebufferTracker =
            com.yfuse.core2.api
                .YRebufferTracker()

        suspend fun prepareCurrent(positionUs: Long) {
            rebufferTracker.discontinuity(System.nanoTime() / 1_000_000L)
            externalSubtitleSession.close()
            adaptiveFeedbackGeneration++
            proxy?.updatePlaybackFeedback(
                YAdaptivePlaybackFeedback(
                    bufferedDurationUs = 0L,
                    playing = false,
                    speed = speed,
                    generation = adaptiveFeedbackGeneration,
                ),
            )
            val output = surfaceOutput?.surface?.takeIf { it.isValid }
            if (output == null) {
                prepared = false
                mutableState.updateState {
                    it.copy(
                        phase = YPlaybackPhase.Preparing,
                        playing = false,
                        buffering = requestedPlay,
                        diagnostics = it.diagnostics.copy(videoOutput = "等待 Surface"),
                    )
                }
                return
            }
            val item = request.items[currentIndex]
            val inheritedBudget = pendingProbeBudget.also { pendingProbeBudget = null }
            val budget = inheritedBudget ?: AndroidProbeBudget()
            activeProbeBudget = budget
            val cancellation =
                budget.onCancel {
                    session.cancelPendingRead()
                    proxy?.close()
                }
            try {
                budget.ensureActive()
                currentCoroutineContext().ensureActive()
                val decision =
                    pendingInitialDecision
                        .takeIf { currentIndex == request.startIndex }
                        .also { pendingInitialDecision = null } ?: routeEvaluator.evaluate(
                        item,
                        allowAudioPassthrough = allowAudioPassthrough,
                        budget = budget,
                    )
                budget.ensureActive()
                currentCoroutineContext().ensureActive()
                val playbackPlan = forcedPlan ?: decision?.plan
                checkNotNull(playbackPlan) { "Media item has no executable YCore enhanced plan" }
                if (forcedPlan == null) {
                    check(decision?.nativeEnhancedExecutable == true) {
                        "Media item is not eligible for YCore NativeEnhanced"
                    }
                } else {
                    check(playbackPlan.demuxPath == YDemuxPath.Enhanced) {
                        "Forced YCore plan must use the enhanced demux path"
                    }
                    check(
                        playbackPlan.route in
                            setOf(
                                YPlaybackRoute.NativeEnhanced,
                                YPlaybackRoute.GpuEnhanced,
                                YPlaybackRoute.SoftwareFallback,
                            ),
                    ) {
                        "Only YCore enhanced, GPU and software plans may be forced"
                    }
                }
                val preparedDemux = routeEvaluator.takePreparedEnhancedDemux(item)
                if (preparedDemux != null) {
                    session.close()
                    proxy?.close()
                    proxy = preparedDemux.proxy
                } else if (proxy == null) {
                    proxy = newProxy()
                }
                val result =
                    session.open(
                        source = proxy?.enhancedSource(item) ?: enhancedDemuxSource(item),
                        plan = playbackPlan,
                        surface = output,
                        startPositionUs = positionUs.coerceAtLeast(0L),
                        runtimeCapabilityKey = decision?.runtimeCapabilityKey(),
                        requireDolbyVisionIdentity = requireDolbyVisionIdentity,
                        expectedAudio = (item.sourceHints?.audioTrackCount ?: 0) > 0,
                        sourceHints = item.sourceHints,
                        allowAudioPassthrough = allowAudioPassthrough,
                        preparedDemux = preparedDemux,
                        probeBudget = budget,
                    )
                budget.ensureActive()
                currentCoroutineContext().ensureActive()
                session.clearProbeDeadline()
                prepared = true
                secondaryExternalSubtitleId = null
                secondaryTrackId = null
                activePlan = playbackPlan
                speed = mutableState.value.speed
                session.setSpeed(speed)
                session.setAudioDelayMs(audioDelayMs)
                val tracks = result.toAudioTracks(session.selectedAudioTrackId())
                externalSubtitleSession.reset(item.allExternalSubtitles, item.headers)
                externalSubtitles = externalSubtitleSession.tracks
                selectedExternalSubtitleId = externalSubtitleSession.defaultId
                if (selectedExternalSubtitleId != null) session.selectSubtitleTrack(null)
                val subtitleTracks =
                    result.toSubtitleTracks() +
                        externalSubtitles.map { subtitle ->
                            subtitle.track.copy(selected = subtitle.track.id == selectedExternalSubtitleId)
                        }
                val video = result.tracks.firstOrNull { it.type == YDemuxTrackType.Video }?.video
                val audio = result.tracks.firstOrNull { it.id == session.selectedAudioTrackId() }?.audio
                activeDolbyProfile = video?.dolbyVisionConfig?.profile
                mutableState.updateState {
                    it.copy(
                        phase = YPlaybackPhase.Ready,
                        playing = false,
                        buffering = requestedPlay,
                        durationMs = (result.durationUs ?: 0L) / MICROS_PER_MILLISECOND,
                        currentIndex = currentIndex,
                        itemCount = request.items.size,
                        audioTracks = tracks,
                        subtitleTracks = subtitleTracks,
                        secondarySubtitleCues = emptyList(),
                        secondarySubtitleTrackId = null,
                        subtitleCues =
                            selectedExternalSubtitleId
                                ?.let { id -> externalSubtitles.firstOrNull { it.track.id == id }?.cues }
                                .orEmpty(),
                        error = null,
                        errorCategory = null,
                        diagnostics =
                            it.diagnostics.copy(
                                route = playbackPlan.route,
                                container = result.container.name,
                                demuxer = "FFmpeg / libavformat",
                                videoCodec = video?.mimeType.orEmpty(),
                                videoWidth = video?.width ?: 0,
                                videoHeight = video?.height ?: 0,
                                frameRate = video?.frameRate ?: 0f,
                                audioCodec = audio?.mimeType.orEmpty(),
                                bitrateBitsPerSecond = result.bitRateBitsPerSecond ?: 0L,
                                dynamicRange = video?.hdrType?.name.orEmpty(),
                                videoOutput = "等待首帧",
                                audioOutput = if (tracks.isEmpty()) "无音频轨" else "等待 PCM 输出",
                                videoOutputVerified = false,
                                audioOutputVerified = false,
                                dolbyVisionOutput = false,
                                dolbyVisionRpuApplied = false,
                                dolbyVisionEnhancementLayerDelivered = false,
                                dolbyVisionFelComposed = false,
                                immersiveAudioCarrierOutput = false,
                                dolbyAtmosSourceDetected = audio?.codec.isDolbyAtmosSource(),
                                dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                                audioOutputRoute = "",
                                audioOutputRouteVerified = false,
                                dolbyAtmosOutput = false,
                                spatialAudioOutput = false,
                                headTrackingAvailable = false,
                                reason = playbackPlan.reason,
                            ),
                    )
                }
                if (requestedPlay) session.play()
            } finally {
                cancellation.close()
                activeProbeBudget = null
                if (inheritedBudget == null) budget.close()
            }
        }

        var lastAudioDiagnosticNs = 0L
        var lastAudioRendering = false
        var lastSubtitleDiagnosticNs = 0L

        fun publishSnapshot(force: Boolean = false) {
            if (!prepared || released) return
            val now = System.nanoTime()
            if (!force && now - lastPublishNs < STATE_PUBLISH_INTERVAL_NS) return
            lastPublishNs = now
            val snapshot = session.snapshot()
            if (snapshot.subtitleDiagnostics.isNotEmpty() &&
                (force || now - lastSubtitleDiagnosticNs >= 10_000_000_000L)
            ) {
                lastSubtitleDiagnosticNs = now
                AppLog.info(
                    category = "player.core2",
                    event = "subtitle_display_summary",
                    message = "YCore subtitle display state",
                    attributes =
                        snapshot.subtitleDiagnostics +
                            mapOf("positionMs" to (snapshot.positionUs / MICROS_PER_MILLISECOND).toString()),
                )
            }
            val rebuffers =
                rebufferTracker.observe(
                    now / 1_000_000L,
                    requestedPlay && !snapshot.ended,
                    snapshot.buffering,
                    snapshot.firstVideoFrameRendered || snapshot.audioRendering,
                )
            val audioDiagnosticsDue =
                force ||
                    snapshot.audioRendering != lastAudioRendering ||
                    now - lastAudioDiagnosticNs >= 2_000_000_000L
            if (snapshot.audioSinkDiagnostics.isNotEmpty() && audioDiagnosticsDue) {
                lastAudioDiagnosticNs = now
                lastAudioRendering = snapshot.audioRendering
                AppLog.info(
                    category = "player.core2",
                    event = "enhanced_audio_output",
                    message = "YCore enhanced PCM sink progress",
                    attributes =
                        snapshot.audioSinkDiagnostics +
                            mapOf(
                                "route" to "NativeEnhanced",
                                "audioRendering" to snapshot.audioRendering.toString(),
                                "audioFallbacks" to snapshot.audioFallbackCount.toString(),
                                "positionMs" to (snapshot.positionUs / MICROS_PER_MILLISECOND).toString(),
                                "rebufferEvents" to rebuffers.events.toString(),
                                "rebufferDurationMs" to rebuffers.durationMs.toString(),
                                "longestRebufferMs" to rebuffers.longestMs.toString(),
                            ),
                )
            }
            if (snapshot.firstVideoFrameRendered) {
                externalSubtitleSession.request(selectedExternalSubtitleId)
                externalSubtitleSession.request(secondaryExternalSubtitleId)
            }
            proxy?.updatePlaybackFeedback(
                YAdaptivePlaybackFeedback(
                    bufferedDurationUs = snapshot.sourceBufferedUs,
                    playing = snapshot.playing,
                    speed = speed,
                    generation = adaptiveFeedbackGeneration,
                ),
            )
            mutableState.updateState {
                it.copy(
                    phase = if (snapshot.ended) YPlaybackPhase.Ended else YPlaybackPhase.Ready,
                    playing = snapshot.playing,
                    buffering = snapshot.buffering,
                    playbackRequested = requestedPlay && !snapshot.ended,
                    positionMs = snapshot.positionUs / MICROS_PER_MILLISECOND,
                    bufferedPositionMs =
                        (snapshot.positionUs + snapshot.sourceBufferedUs) /
                            MICROS_PER_MILLISECOND,
                    subtitleCues =
                        if (selectedExternalSubtitleId != null) {
                            externalSubtitles.firstOrNull { it.track.id == selectedExternalSubtitleId }?.cues.orEmpty()
                        } else {
                            snapshot.subtitleCues
                        },
                    secondarySubtitleCues =
                        if (secondaryExternalSubtitleId != null) {
                            externalSubtitles.firstOrNull { it.track.id == secondaryExternalSubtitleId }?.cues.orEmpty()
                        } else {
                            snapshot.secondarySubtitleCues
                        },
                    secondarySubtitleTrackId = secondaryTrackId,
                    diagnostics =
                        it.diagnostics.copy(
                            decoder =
                                listOfNotNull(snapshot.videoDecoderName, snapshot.audioDecoderName)
                                    .joinToString(" + "),
                            videoOutput =
                                when {
                                    snapshot.pausedPreviewSubmittedUnconfirmed && !snapshot.firstVideoFrameRendered ->
                                        "暂停定位帧已提交 · 系统未回调确认"
                                    snapshot.nativeGpuFeatureMask != 0L && snapshot.firstVideoFrameRendered ->
                                        nativeGpuOutputLabel(
                                            plan = activePlan,
                                            dolbyProfile = activeDolbyProfile,
                                            gpuFrameDurationNs = snapshot.gpuFrameDurationNs,
                                        )
                                    snapshot.nativeGpuFeatureMask != 0L -> "等待 Vulkan 实测门槛"
                                    snapshot.firstVideoFrameRendered -> "Surface 直出"
                                    else -> "等待首帧"
                                },
                            audioOutput =
                                if (snapshot.audioRendering) {
                                    when (snapshot.dolbyAtmosOutputMode) {
                                        YDolbyAtmosOutputMode.Eac3JocPassthrough ->
                                            "Dolby Atmos · E-AC-3 JOC 原码 · AudioTrack"
                                        YDolbyAtmosOutputMode.TrueHdAtmosPassthrough ->
                                            "Dolby Atmos · TrueHD 原码 · AudioTrack"
                                        YDolbyAtmosOutputMode.TrueHdCarrierPassthrough ->
                                            "TrueHD 载波 · AudioTrack（未验证 Atmos 对象输出）"
                                        YDolbyAtmosOutputMode.CarrierOnly ->
                                            "沉浸音频载波 · AudioTrack（未验证对象输出）"
                                        YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm ->
                                            if (snapshot.headTrackingAvailable) {
                                                "Dolby Atmos 源 · 系统空间音频 · PCM · 头部跟踪可用"
                                            } else {
                                                "Dolby Atmos 源 · 系统空间音频 · PCM"
                                            }
                                        YDolbyAtmosOutputMode.None ->
                                            when {
                                                snapshot.immersiveAudioCarrierOutput ->
                                                    "沉浸音频载波 · AudioTrack（未验证对象输出）"
                                                snapshot.audioPassthrough -> "原码直通 · AudioTrack"
                                                snapshot.spatialAudioOutput && snapshot.headTrackingAvailable ->
                                                    "系统空间音频 · PCM · 头部跟踪可用"
                                                snapshot.spatialAudioOutput -> "系统空间音频 · PCM"
                                                else -> "PCM · AudioTrack"
                                            }
                                    }
                                } else {
                                    "等待实际音频输出"
                                },
                            videoDecoderName = snapshot.videoDecoderName.orEmpty(),
                            audioDecoderName = snapshot.audioDecoderName.orEmpty(),
                            outputEvidenceGeneration = snapshot.outputEvidenceGeneration,
                            outputEvidenceResetReason = snapshot.outputEvidenceResetReason,
                            videoOutputVerified = snapshot.firstVideoFrameRendered,
                            audioOutputVerified = snapshot.audioRendering,
                            // Native DV output claim requires a verified video frame AND a DV source
                            // route. P7 FEL composition remains a separate evidence gate.
                            dolbyVisionOutput =
                                snapshot.firstVideoFrameRendered &&
                                    activePlan?.renderPath == YRenderPath.SurfaceDirect &&
                                    activePlan?.usesHdrFallback == false &&
                                    activeDolbyProfile != null &&
                                    snapshot.outputHdrType == com.yfuse.core2.capability.YHdrType.DolbyVision,
                            dolbyVisionRpuApplied = snapshot.dolbyVisionRpuApplied,
                            dolbyVisionEnhancementLayerDelivered =
                                snapshot.dolbyVisionEnhancementLayerDelivered,
                            dolbyVisionFelComposed = snapshot.dolbyVisionFelComposed,
                            immersiveAudioCarrierOutput = snapshot.immersiveAudioCarrierOutput,
                            dolbyAtmosSourceDetected = snapshot.dolbyAtmosSourceDetected,
                            dolbyAtmosOutputMode = snapshot.dolbyAtmosOutputMode,
                            audioOutputRoute = snapshot.audioOutputRoute,
                            audioOutputFingerprint = snapshot.audioOutputFingerprint,
                            audioOutputRouteVerified = snapshot.audioOutputRouteVerified,
                            dolbyAtmosOutput = snapshot.dolbyAtmosOutput,
                            spatialAudioOutput = snapshot.spatialAudioOutput,
                            headTrackingAvailable = snapshot.headTrackingAvailable,
                            audioUnderrunCount = snapshot.audioUnderrunCount,
                            sourceQueueBytes = snapshot.sourceQueueBytes,
                            sourceBufferedMs = snapshot.sourceBufferedUs / MICROS_PER_MILLISECOND,
                            sourceStarvationCount = snapshot.sourceStarvationCount,
                            networkBitsPerSecond = snapshot.sourceNetworkBitsPerSecond,
                            droppedFrames = snapshot.droppedFrames,
                            bufferEvents = rebuffers.events,
                            rebufferDurationMs = rebuffers.durationMs,
                            longestRebufferMs = rebuffers.longestMs,
                            avSyncOffsetMs = snapshot.avSyncOffsetUs?.div(MICROS_PER_MILLISECOND),
                            avSyncMeasurement =
                                if (snapshot.avSyncOffsetUs != null) {
                                    "MediaCodec 帧渲染 / AudioTrack 时钟"
                                } else {
                                    "等待音视频时钟样本"
                                },
                        ),
                )
            }
        }

        fun finishRebuffer() {
            val stats = rebufferTracker.stop(releasedAtMs ?: System.nanoTime() / 1_000_000L)
            mutableState.updateState {
                it.copy(
                    diagnostics =
                        it.diagnostics.copy(
                            bufferEvents = stats.events,
                            rebufferDurationMs = stats.durationMs,
                            longestRebufferMs = stats.longestMs,
                        ),
                )
            }
        }

        fun publishFailure(failure: Throwable) {
            if (failure is CancellationException) throw failure
            finishRebuffer()
            val typed = failure as? YPlaybackException
            AppLog.error(
                category = "core2.native",
                event = "native_enhanced_failed",
                message = "YCore enhanced playback failed",
                throwable = failure,
                attributes =
                    mapOf(
                        "failureCategory" to (typed?.category?.name ?: "Unknown"),
                        "failureStage" to (typed?.stage?.name ?: "Unknown"),
                        "failureDetail" to typed?.safeDetail.orEmpty(),
                        "itemIndex" to currentIndex.toString(),
                        "sourceScheme" to
                            request.items[currentIndex]
                                .uri
                                .substringBefore(':')
                                .lowercase(),
                    ),
            )
            prepared = false
            runCatching { session.close() }
            // A cancelled private proxy has stopped admitting routes. Never retain that object
            // across Retry, where localUrl would otherwise return the unproxied upstream URI.
            proxy?.close()
            proxy = null
            requestedPlay = false
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = false,
                    buffering = false,
                    error = yCoreEnhancedFailureMessage(typed),
                    // Unknown is deliberately non-penalizing until each native stage has
                    // a typed failure domain. Never infer decoder failure from text.
                    errorCategory = typed?.category ?: YPlaybackFailureCategory.Unknown,
                    diagnostics =
                        it.diagnostics.copy(
                            recoverableNetworkFailure =
                                typed?.category == YPlaybackFailureCategory.Network &&
                                    isRecoverableMediaReadFailure(typed.cause),
                            videoOutput = "停止",
                            audioOutput = "停止",
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            dolbyVisionRpuApplied = false,
                            dolbyVisionEnhancementLayerDelivered = false,
                            dolbyVisionFelComposed = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosSourceDetected = false,
                            dolbyAtmosOutputMode = YDolbyAtmosOutputMode.None,
                            audioOutputRoute = "",
                            audioOutputRouteVerified = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            reason =
                                typed?.stage?.let { stage -> "NativeEnhanced failed at ${stage.name}" }
                                    ?: "NativeEnhanced failed before typed-stage classification",
                        ),
                )
            }
        }

        try {
            while (scope.isActive) {
                val pendingCommands = mutableListOf<Command>()
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    pendingCommands += command
                }
                val handled = pendingCommands.isNotEmpty()
                for (command in coalesceNativeEnhancedCommands(pendingCommands)) {
                    try {
                        when (command) {
                            Command.Prepare ->
                                prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                            Command.Play -> {
                                requestedPlay = true
                                if (!prepared) {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                } else {
                                    session.play()
                                }
                            }
                            Command.Pause -> {
                                requestedPlay = false
                                if (prepared) session.pause()
                            }
                            is Command.Seek -> {
                                rebufferTracker.discontinuity(System.nanoTime() / 1_000_000L)
                                if (prepared) {
                                    adaptiveFeedbackGeneration++
                                    proxy?.updatePlaybackFeedback(
                                        YAdaptivePlaybackFeedback(
                                            bufferedDurationUs = 0L,
                                            playing = false,
                                            speed = speed,
                                            generation = adaptiveFeedbackGeneration,
                                        ),
                                    )
                                    session.seekTo(command.positionUs)
                                }
                            }
                            is Command.SetSpeed -> {
                                speed = command.speed
                                if (prepared) session.setSpeed(speed)
                            }
                            is Command.SetAudioDelay -> {
                                audioDelayMs = command.delayMs
                                session.setAudioDelayMs(audioDelayMs)
                            }
                            is Command.ExternalSubtitleReady -> {
                                if (externalSubtitleSession.accept(command.result)) {
                                    externalSubtitles = externalSubtitleSession.tracks
                                }
                            }
                            is Command.SetVideoOutput -> {
                                val previous = surfaceOutput
                                surfaceOutput = command.output
                                val next = command.output?.surface?.takeIf { it.isValid }
                                if (next == null) {
                                    val position =
                                        if (prepared) {
                                            session.snapshot().positionUs
                                        } else {
                                            mutableState.value.positionMs * MICROS_PER_MILLISECOND
                                        }
                                    session.close()
                                    prepared = false
                                    mutableState.updateState {
                                        it.copy(
                                            playing = false,
                                            buffering = requestedPlay,
                                            positionMs = position / MICROS_PER_MILLISECOND,
                                            diagnostics = it.diagnostics.copy(videoOutput = "等待 Surface"),
                                        )
                                    }
                                } else if (prepared && previous?.surface?.isValid == true) {
                                    runCatching { session.setOutputSurface(next) }
                                        .onFailure {
                                            val position = session.snapshot().positionUs
                                            session.close()
                                            prepared = false
                                            prepareCurrent(position)
                                        }
                                } else if (!prepared) {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                }
                            }
                            is Command.SelectAudioTrack -> {
                                if (prepared) {
                                    session.selectAudioTrack(
                                        YTrackId(command.trackId),
                                        capabilityProvider.current(),
                                    )
                                    val selectedTrackId = "$AUDIO_TRACK_PREFIX${command.trackId}"
                                    mutableState.updateState { state ->
                                        state.copy(
                                            audioTracks =
                                                state.audioTracks.map { track ->
                                                    track.copy(selected = track.id == selectedTrackId)
                                                },
                                            error = null,
                                            errorCategory = null,
                                        )
                                    }
                                }
                            }
                            is Command.SelectSubtitleTrack -> {
                                externalSubtitleSession.retry(command.externalTrackId)
                                if (prepared) {
                                    val selectedId =
                                        command.externalTrackId
                                            ?: command.trackId?.let { "$SUBTITLE_TRACK_PREFIX$it" }
                                    val candidate =
                                        mutableState.value.subtitleTracks.firstOrNull {
                                            it.id == selectedId
                                        }
                                    if (selectedId != null && candidate == null) continue
                                    if (command.secondary && candidate?.selected == true) continue
                                    // Repeated UI restore events must not clear the active channel.
                                    if (command.secondary && selectedId == secondaryTrackId) continue
                                    if (!command.secondary && candidate?.selected == true) continue
                                    session.selectSubtitleTrack(command.trackId?.let(::YTrackId), command.secondary)
                                    if (command.secondary) {
                                        secondaryExternalSubtitleId = command.externalTrackId
                                        secondaryTrackId = selectedId
                                    } else {
                                        selectedExternalSubtitleId = command.externalTrackId
                                        if (selectedId != null && selectedId == secondaryTrackId) {
                                            session.selectSubtitleTrack(null, secondary = true)
                                            secondaryExternalSubtitleId = null
                                            secondaryTrackId = null
                                        }
                                    }
                                    mutableState.updateState { state ->
                                        state.copy(
                                            subtitleTracks =
                                                if (command.secondary) {
                                                    state.subtitleTracks
                                                } else {
                                                    state.subtitleTracks.map { track ->
                                                        track.copy(selected = track.id == selectedId)
                                                    }
                                                },
                                            secondarySubtitleTrackId = secondaryTrackId,
                                            error = null,
                                            errorCategory = null,
                                        )
                                    }
                                }
                            }
                            is Command.SelectItem -> {
                                currentIndex = command.index
                                session.close()
                                prepared = false
                                prepareCurrent(0L)
                            }
                        }
                        publishSnapshot(force = true)
                    } catch (failure: Throwable) {
                        publishFailure(failure)
                        break
                    }
                }

                val didWork =
                    playbackWorkerStep(::publishFailure) {
                        val worked = if (prepared) session.pump() else false
                        publishSnapshot()
                        worked
                    } ?: false
                if (!handled && !didWork) {
                    // A queued command ends the wait at once; a paused session has no pump work
                    // and can sleep longer without delaying command handling.
                    val idleDelayMs =
                        playbackPumpIdleDelayMs(
                            playing = requestedPlay,
                            buffering = mutableState.value.buffering,
                            previewPending = session.previewPending,
                        )
                    withTimeoutOrNull(idleDelayMs) { wakeSignal.receiveCatching() }
                }
            }
        } finally {
            finishRebuffer()
            externalSubtitleSession.close()
            session.release()
            activeSession = null
            proxy?.close()
            proxy = null
        }
    }

    internal sealed interface Command {
        data class SetAudioDelay(
            val delayMs: Long,
        ) : Command

        data class ExternalSubtitleReady(
            val result: AndroidExternalSubtitleSession.Completion,
        ) : Command

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
            val trackId: Int,
        ) : Command

        data class SelectSubtitleTrack(
            val trackId: Int?,
            val externalTrackId: String?,
            val secondary: Boolean = false,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

/** Remote static files need the same redirect/range/credential boundary as adaptive manifests. */
internal fun shouldProxyEnhancedSourceUri(uri: String): Boolean {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (parsed.scheme?.lowercase() !in setOf("http", "https", "webdav", "webdavs")) return false
    return parsed.host?.lowercase() !in setOf("127.0.0.1", "localhost", "::1")
}

internal fun yCoreEnhancedFailureMessage(failure: YPlaybackException?): String =
    when (failure?.category) {
        YPlaybackFailureCategory.Authorization -> "YCore 2.0 片源授权已失效，请刷新播放地址后重试"
        YPlaybackFailureCategory.Drm -> "YCore 2.0 无法建立当前片源的 DRM 会话"
        YPlaybackFailureCategory.Network -> "YCore 2.0 无法连接片源，请检查服务器或网络"
        YPlaybackFailureCategory.Container ->
            if (failure.isHiddenServerAudioTrackFailure()) {
                YCORE_HIDDEN_AUDIO_TRACK_MESSAGE
            } else {
                "YCore 2.0 无法解析当前片源容器"
            }
        YPlaybackFailureCategory.Decoder -> "YCore 2.0 无法启动当前视频解码器"
        YPlaybackFailureCategory.Renderer -> "YCore 2.0 无法建立视频输出"
        YPlaybackFailureCategory.AudioSink -> "YCore 2.0 无法建立音频输出"
        YPlaybackFailureCategory.Unknown,
        null,
        -> "YCore 2.0 实际起播失败，请导出诊断日志"
    }

internal fun coalesceNativeEnhancedCommands(
    commands: List<AndroidNativeEnhancedYPlayer.Command>,
): List<AndroidNativeEnhancedYPlayer.Command> =
    commands.fold(mutableListOf()) { result, command ->
        val previous = result.lastOrNull()
        if (previous != null && previous.canBeReplacedBy(command)) {
            result[result.lastIndex] = command
        } else {
            result += command
        }
        result
    }

private fun AndroidNativeEnhancedYPlayer.Command.canBeReplacedBy(next: AndroidNativeEnhancedYPlayer.Command): Boolean =
    when (this) {
        is AndroidNativeEnhancedYPlayer.Command.ExternalSubtitleReady -> false
        is AndroidNativeEnhancedYPlayer.Command.Seek -> next is AndroidNativeEnhancedYPlayer.Command.Seek
        is AndroidNativeEnhancedYPlayer.Command.SetSpeed -> next is AndroidNativeEnhancedYPlayer.Command.SetSpeed
        is AndroidNativeEnhancedYPlayer.Command.SetAudioDelay ->
            next is AndroidNativeEnhancedYPlayer.Command.SetAudioDelay
        is AndroidNativeEnhancedYPlayer.Command.SetVideoOutput ->
            next is AndroidNativeEnhancedYPlayer.Command.SetVideoOutput
        is AndroidNativeEnhancedYPlayer.Command.SelectAudioTrack ->
            next is AndroidNativeEnhancedYPlayer.Command.SelectAudioTrack
        is AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack ->
            next is AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack && secondary == next.secondary
        is AndroidNativeEnhancedYPlayer.Command.SelectItem -> next is AndroidNativeEnhancedYPlayer.Command.SelectItem
        AndroidNativeEnhancedYPlayer.Command.Prepare -> next == AndroidNativeEnhancedYPlayer.Command.Prepare
        AndroidNativeEnhancedYPlayer.Command.Play -> next == AndroidNativeEnhancedYPlayer.Command.Play
        AndroidNativeEnhancedYPlayer.Command.Pause -> next == AndroidNativeEnhancedYPlayer.Command.Pause
    }

private fun YDemuxOpenResult.toAudioTracks(selectedAudioId: YTrackId?): List<YTrack> {
    return tracks.mapNotNull { track ->
        val audio = track.audio ?: return@mapNotNull null
        YTrack(
            id = "audio:${track.id.value}",
            type = YTrackType.Audio,
            label = track.label ?: track.language ?: "Audio ${track.id.value + 1}",
            language = track.language,
            codec = audio.mimeType,
            selected = track.id == selectedAudioId,
        )
    }
}

private fun YDemuxOpenResult.toSubtitleTracks(): List<YTrack> =
    tracks.mapNotNull { track ->
        val subtitle =
            track.subtitle?.takeIf {
                it.format.textOverlaySupported ||
                    it.format == com.yfuse.core2.subtitle.YSubtitleFormat.Pgs ||
                    it.format == com.yfuse.core2.subtitle.YSubtitleFormat.VobSub
            } ?: return@mapNotNull null
        YTrack(
            id = "$SUBTITLE_TRACK_PREFIX${track.id.value}",
            type = YTrackType.Subtitle,
            label = track.label ?: track.language ?: "Subtitle ${track.id.value + 1}",
            language = track.language,
            codec = subtitle.mimeType,
            selected = false,
        )
    }

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    update(transform)
}

private fun nativeGpuOutputLabel(
    plan: YPlaybackPlan?,
    dolbyProfile: Int?,
    gpuFrameDurationNs: Long,
): String {
    val duration = gpuFrameDurationNs / 1_000_000.0
    return when {
        plan?.usesHdrFallback == true ->
            "DV P${dolbyProfile ?: "7/8"} 兼容基层 ${plan.inputHdrType} → ${plan.outputHdrType} · " +
                "Vulkan $duration ms"
        plan?.inputHdrType == YHdrType.DolbyVision ->
            "DV P${dolbyProfile ?: "5/8"} MediaCodec → Vulkan（非原生 DV 输出）· $duration ms"
        plan != null && plan.inputHdrType != plan.outputHdrType ->
            "${plan.inputHdrType} → ${plan.outputHdrType} · Vulkan $duration ms"
        else -> "Vulkan Swapchain · GPU $duration ms"
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L
private const val AUDIO_TRACK_PREFIX = "audio:"
private const val SUBTITLE_TRACK_PREFIX = "subtitle:"
private const val SUBTITLE_OFF = "off"
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
private const val PUMP_PAUSED_IDLE_DELAY_MS = 20L
