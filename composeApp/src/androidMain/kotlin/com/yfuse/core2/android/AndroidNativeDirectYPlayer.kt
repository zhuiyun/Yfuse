package com.yfuse.core2.android

import android.content.Context
import android.os.Process
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.diagnosticOrigin
import com.yfuse.core.logging.diagnosticRootCause
import com.yfuse.core.logging.diagnosticTypeName
import com.yfuse.core2.api.YAudioEffect
import com.yfuse.core2.api.YDolbyAtmosOutputMode
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
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.recovery.YPlaybackFailureReporter
import com.yfuse.core2.render.YFrameRateSwitchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * First runnable Core2 player: MediaExtractor → MediaCodec → Surface plus MediaCodec → AudioTrack.
 *
 * This implementation is the production Direct route selected by the adaptive router.
 * Lifecycle, timing and device capability handling remain behind the stable YPlayer API.
 */
internal class AndroidNativeDirectYPlayer(
    context: Context,
    private val request: YPlayerOpenRequest,
    private val decoderName: String? = null,
    private val runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
    private var plannedAudioOutputPath: YAudioOutputPath? = null,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val plannedDolbyVisionConfig: YDolbyVisionConfig? = null,
    private val confirmedDolbyVisionNalIdentity: Boolean = false,
    private val requireDolbyVisionIdentity: Boolean = false,
    private val preferredRemoteBufferTargetUs: Long? = null,
    private val preparedExtractor: ((YMediaItem) -> YPlatformExtractorSource?)? = null,
    private val videoHandoff: AndroidVideoDecoderHandoff? = null,
) : YPlayer,
    AndroidSerializedPlayerRelease,
    AndroidVideoOutputDetach,
    YPlaybackFailureReporter {
    private val sessionControl = NativeDirectSessionControl()

    @Volatile
    override var lastPlaybackFailure: Throwable? = null
        private set

    /** The router calls this only for an adjacent, prepared SDR/PCM direct-play route. */
    fun prepareVideoHandoff() {
        sessionControl.videoHandoffRequested =
            videoHandoff != null &&
            mutableState.value.diagnostics.videoOutputVerified
    }

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

    /** Conflated hint that wakes an idle run loop as soon as a command is queued. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var activeSession: AndroidNativeDirectSession? = null

    private val worker: Job = scope.launch { runLoop() }

    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    override fun prepare() {
        if (sessionControl.released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                buffering = it.playbackRequested,
                error = null,
                diagnostics = it.diagnostics.copy(renderedFrameRate = null),
            )
        }
        submit(Command.Prepare)
    }

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (sessionControl.released) return false
        if (output != null && output !is AndroidSurfaceVideoOutput) return false
        submit(Command.SetVideoOutput(output as AndroidSurfaceVideoOutput?))
        return true
    }

    override fun detachVideoOutput(detached: CompletableDeferred<Unit>) {
        confirmVideoDetachAfterWorkerRelease(worker, this, detached)
        if (sessionControl.released) return
        submit(Command.SetVideoOutput(null, detached))
    }

    override val supportsBackgroundAudio: Boolean get() = true

    @Volatile
    private var videoSuspended = false

    override val videoOutputSuspended: Boolean get() = videoSuspended

    override fun setVideoSuspended(suspended: Boolean) {
        if (sessionControl.released) return
        videoSuspended = suspended
        submit(Command.SetVideoSuspended(suspended))
    }

    override fun play() {
        if (sessionControl.released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = true,
                buffering = it.phase != YPlaybackPhase.Ended,
                error = null,
            )
        }
        submit(Command.Play)
    }

    override fun pause() {
        if (sessionControl.released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = false,
                playing = false,
                buffering = false,
                diagnostics = it.diagnostics.copy(renderedFrameRate = null),
            )
        }
        submit(Command.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (sessionControl.released) return
        val bounded = positionMs.coerceAtLeast(0L)
        mutableState.updateState {
            it.copy(
                positionMs = bounded,
                subtitleCues = emptyList(),
                secondarySubtitleCues = emptyList(),
                bufferedPositionMs = bounded,
                buffering = it.playbackRequested,
                diagnostics = it.diagnostics.copy(renderedFrameRate = null),
                phase = if (it.phase == YPlaybackPhase.Ended) YPlaybackPhase.Ready else it.phase,
            )
        }
        submit(Command.Seek(bounded * MICROS_PER_MILLISECOND))
    }

    override fun setSpeed(speed: Float) {
        if (sessionControl.released || !speed.isFinite() || speed <= 0f) return
        mutableState.updateState { it.copy(speed = speed) }
        submit(Command.SetSpeed(speed))
    }

    override val supportsAudioDelay: Boolean get() = true

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        if (sessionControl.released) return false
        submit(Command.SetAudioDelay(delayMs.coerceIn(-5_000L, 5_000L)))
        return true
    }

    override val supportsAudioEffects: Boolean get() = true

    override fun setAudioEffect(effect: YAudioEffect): Boolean {
        if (sessionControl.released) return false
        submit(Command.SetAudioEffect(effect))
        return true
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (sessionControl.released) return
        when (type) {
            YTrackType.Audio ->
                id.removePrefix(AUDIO_TRACK_PREFIX).toIntOrNull()?.let {
                    submit(Command.SelectAudioTrack(it))
                }
            YTrackType.Subtitle -> {
                when (id) {
                    SUBTITLE_OFF -> submit(Command.SelectSubtitleTrack(null, externalTrackId = null))
                    EXTERNAL_SUBTITLE_TRACK_ID ->
                        submit(
                            Command.SelectSubtitleTrack(
                                null,
                                externalTrackId =
                                    mutableState.value.subtitleTracks
                                        .firstOrNull {
                                            it.id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)
                                        }?.id,
                            ),
                        )
                    else -> {
                        if (id.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX)) {
                            submit(Command.SelectSubtitleTrack(null, externalTrackId = id))
                        } else {
                            val trackIndex = id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return
                            submit(Command.SelectSubtitleTrack(trackIndex, externalTrackId = null))
                        }
                    }
                }
            }
        }
    }

    override val supportsSecondarySubtitleOffset: Boolean = true

    override fun setSecondarySubtitleOffsetMs(offsetMs: Long): Boolean {
        if (sessionControl.released || offsetMs !in -60_000L..60_000L) return false
        mutableState.updateState { it.copy(secondarySubtitleOffsetMs = offsetMs) }
        return true
    }

    override val supportsSecondarySubtitleTrack: Boolean = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        if (sessionControl.released) return false
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
        if (sessionControl.released || index !in request.items.indices) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                playing = false,
                buffering = it.playbackRequested,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                currentIndex = index,
                error = null,
            )
        }
        submit(Command.SelectItem(index))
    }

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() {
        if (sessionControl.released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                error = null,
                buffering = it.playbackRequested,
            )
        }
        submit(Command.Prepare)
    }

    @Volatile
    private var cleanupCompleted = false

    override val releaseCompleted: Boolean get() = worker.isCompleted && cleanupCompleted

    override suspend fun releaseAndJoin() {
        release()
        val completed =
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    worker.join()
                    true
                }
            }
        check(completed == true && cleanupCompleted) {
            "Previous direct decoder did not finish releasing; replacement was not started"
        }
    }

    override fun release() {
        if (sessionControl.released) return
        sessionControl.releasedAtMs = System.nanoTime() / 1_000_000L
        sessionControl.released = true
        try {
            activeSession?.cancelPendingRead()
        } finally {
            commands.close()
            wakeSignal.trySend(Unit)
            worker.invokeOnCompletion { playbackDispatcher.close() }
            worker.cancel()
            scope.cancel()
        }
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Idle,
                playing = false,
                playbackRequested = false,
                buffering = false,
            )
        }
    }

    private suspend fun runLoop() {
        val session =
            AndroidNativeDirectSession(
                context = appContext,
                configuration =
                    NativeDirectSessionConfiguration(
                        request = request,
                        decoderName = decoderName,
                        runtimeCapabilityKey = runtimeCapabilityKey,
                        plannedAudioOutputPath = plannedAudioOutputPath,
                        frameRateSwitchMode = frameRateSwitchMode,
                        plannedDolbyVisionConfig = plannedDolbyVisionConfig,
                        confirmedDolbyVisionNalIdentity = confirmedDolbyVisionNalIdentity,
                        requireDolbyVisionIdentity = requireDolbyVisionIdentity,
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                        preparedExtractor = preparedExtractor,
                        videoHandoff = videoHandoff,
                    ),
                scope = scope,
                mutableState = mutableState,
                control = sessionControl,
                submit = ::submit,
            )
        activeSession = session
        try {
            while (scope.isActive) {
                val pendingCommands = mutableListOf<Command>()
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    pendingCommands += command
                }
                val handledCommand = pendingCommands.isNotEmpty()
                for (command in coalesceNativeDirectCommands(pendingCommands)) {
                    if (sessionControl.released || !scope.isActive) return
                    val failure = runCatching { session.handle(command) }.exceptionOrNull()
                    if (failure != null) {
                        if (failure is CancellationException && (sessionControl.released || !scope.isActive)) return
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
                        if (failure is CancellationException && (sessionControl.released || !scope.isActive)) return
                        failure?.let { fail(session, it) }
                        result.getOrDefault(false)
                    } else {
                        false
                    }

                if (!handledCommand && !didWork) {
                    if (canPump) {
                        // A queued command ends the wait at once; otherwise the pump sleeps for its
                        // interval, which is longer while paused because there is no decoder output
                        // to collect on a schedule.
                        withTimeoutOrNull(session.idleDelayMs()) { wakeSignal.receiveCatching() }
                    } else {
                        val command = commands.receiveCatching().getOrNull() ?: break
                        val failure = runCatching { session.handle(command) }.exceptionOrNull()
                        if (failure is CancellationException && (sessionControl.released || !scope.isActive)) return
                        failure?.let { fail(session, it) }
                    }
                }
            }
        } finally {
            session.releaseAll()
            activeSession = null
            cleanupCompleted = true
        }
    }

    private fun submit(command: Command) {
        commands.trySend(command)
        wakeSignal.trySend(Unit)
    }

    private fun fail(
        session: AndroidNativeDirectSession,
        throwable: Throwable,
    ) {
        if (sessionControl.released || throwable is CancellationException) return
        // Before the Failed state below: the router reads it on that edge.
        lastPlaybackFailure = throwable
        val sourceKept = session.releaseMediaAfterFailure(throwable)
        val typed = throwable as? YPlaybackException
        val codecConfigurationFailure = typed?.cause as? YVideoDecoderConfigurationException
        // An untyped failure used to leave nothing but its class name: one startup
        // IllegalStateException in a diagnostic could not be traced to any line. The deepest cause
        // names what failed (one level down can still be a wrapper) and its first non-library frame
        // is retraceable with the build's mapping. The throwable itself carries the full stack; the
        // exporter redacts hosts.
        val root = throwable.diagnosticRootCause()
        AppLog.error(
            category = "player.core2",
            event = "native_direct_failed",
            message = "YCore NativeDirect failed",
            throwable = throwable,
            attributes =
                mapOf(
                    "category" to (typed?.category?.name ?: YPlaybackFailureCategory.Unknown.name),
                    "stage" to (typed?.stage?.name ?: YPlaybackFailureStage.Unknown.name),
                    "detail" to typed?.safeDetail.orEmpty(),
                    "deterministic" to (typed?.deterministic == true).toString(),
                    "sourceKept" to sourceKept.toString(),
                    "exceptionType" to root.diagnosticTypeName(),
                    "origin" to root.diagnosticOrigin(),
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
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Failed,
                playing = false,
                playbackRequested = false,
                buffering = false,
                error = yCoreNativeDirectFailureMessage(typed),
                errorCategory = typed?.category ?: YPlaybackFailureCategory.Unknown,
                diagnostics =
                    current.diagnostics.copy(
                        recoverableNetworkFailure =
                            typed?.category == YPlaybackFailureCategory.Network &&
                                isRecoverableMediaReadFailure(typed.cause),
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
    }

    internal sealed interface Command {
        data class SetAudioDelay(
            val delayMs: Long,
        ) : Command

        data class SetAudioEffect(
            val effect: YAudioEffect,
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

        data class SetVideoSuspended(
            val suspended: Boolean,
        ) : Command

        data class SetVideoOutput(
            val output: AndroidSurfaceVideoOutput?,
            /** Completed once the decoder no longer renders into the previous Surface. */
            val detached: CompletableDeferred<Unit>? = null,
        ) : Command

        data class SelectAudioTrack(
            val trackIndex: Int,
        ) : Command

        data class SelectSubtitleTrack(
            val trackIndex: Int?,
            val externalTrackId: String?,
            val secondary: Boolean = false,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

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

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    update(transform)
}

private const val AUDIO_TRACK_PREFIX = "audio:"
private const val SUBTITLE_TRACK_PREFIX = "subtitle:"
private const val SUBTITLE_OFF = "off"
private const val MICROS_PER_MILLISECOND = 1_000L
private const val NATIVE_DIRECT_THREAD_NAME = "YCore-NativeDirect"
