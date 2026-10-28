package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.android.AndroidNativeDirectYPlayer.Command
import com.yfuse.core2.api.YAudioEffect
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.invalidateOutputEvidence
import com.yfuse.core2.api.matchingPreference
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.network.YBufferConditions
import com.yfuse.core2.network.YBufferController
import com.yfuse.core2.recovery.passthroughRestorable
import com.yfuse.core2.recovery.requiresPcmAudioPath
import com.yfuse.core2.render.YRenderedFrameRateSampler
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.appendUntimedTextSubtitlePacket
import com.yfuse.core2.sync.YAvSync
import com.yfuse.core2.sync.YClockSnapshot
import com.yfuse.core2.sync.YMediaClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer

internal class AndroidNativeDirectSession(
    context: Context,
    configuration: NativeDirectSessionConfiguration,
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<YPlayerState>,
    private val control: NativeDirectSessionControl,
    private val submit: (Command) -> Unit,
) {
    private val appContext = context
    private val request = configuration.request
    private val decoderName = configuration.decoderName
    private val runtimeCapabilityKey = configuration.runtimeCapabilityKey
    private var plannedAudioOutputPath = configuration.plannedAudioOutputPath
    private val frameRateSwitchMode = configuration.frameRateSwitchMode
    private val plannedDolbyVisionConfig = configuration.plannedDolbyVisionConfig
    private val confirmedDolbyVisionNalIdentity = configuration.confirmedDolbyVisionNalIdentity
    private val requireDolbyVisionIdentity = configuration.requireDolbyVisionIdentity
    private val preferredRemoteBufferTargetUs = configuration.preferredRemoteBufferTargetUs
    private val preparedExtractor = configuration.preparedExtractor
    private val videoHandoff = configuration.videoHandoff
    private val demux =
        AndroidMediaExtractorReadAheadNode(
            context = context,
            onBlockingReadStateChanged = ::onTransportBlockingReadStateChanged,
        )
    private var videoDecoder = AndroidMediaCodecVideoNode()
    private var audioDrainStartedNs: Long? = null
    private val audioDecoder = AndroidMediaCodecAudioNode()
    private val audioRenderer = AndroidAudioTrackRenderNode(context)
    private val encodedAudioRenderer = AndroidEncodedAudioTrackRenderNode()
    private val capabilityProvider = AndroidYCapabilityProvider(context)
    private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)
    private val externalSubtitleLoader = AndroidExternalSubtitleLoader(context)
    private val externalSubtitleSession =
        AndroidExternalSubtitleSession(
            scope = scope,
            load = { source, headers, id -> externalSubtitleLoader.load(source, headers, id) },
            completed = { submit(Command.ExternalSubtitleReady(it)) },
        )
    private val wallClock = YMediaClock(positionUs = request.startPositionMs * MICROS_PER_MILLISECOND)
    private val frameRateManager = AndroidFrameRateManager(context, frameRateSwitchMode)
    private var monotonicPositionFloorUs = request.startPositionMs * MICROS_PER_MILLISECOND

    private var currentIndex = request.startIndex
    private var sourceRemote = false
    private var sourceBitRateBitsPerSecond = 0L
    private var bufferPlan = YBufferController.plan(YBufferConditions(remote = false))
    private var bufferGate =
        com.yfuse.core2.network
            .YPlaybackBufferGate(remote = false, resumePlaybackUs = 0L)
    private var lastBufferReplanNs = 0L
    private var lastBufferGatePositionUs = 0L
    private var surfaceOutput: AndroidSurfaceVideoOutput? = null
    private var videoTrackIndex: Int? = null
    private var audioTrackIndex: Int? = null
    private var videoFormat: MediaFormat? = null
    private var inspectHdr10PlusSamples = false
    private val hdrAccessUnits = AndroidAccessUnitCache<YExtractorSample, ByteArray?>()
    private var audioInputFormat: MediaFormat? = null
    private var subtitleTrackIndex: Int? = null
    private val subtitleCues = mutableListOf<YSubtitleCue>()
    private var secondarySubtitleTrackIndex: Int? = null
    private var secondaryExternalSubtitleId: String? = null
    private val secondarySubtitleCues = mutableListOf<YSubtitleCue>()
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

    /**
     * True once a video decoder has rendered into a real Surface for the current media.
     *
     * Distinguishes "video output was lost" from "video output was never established". Losing
     * it must not stop audio. Initial startup still waits for video unless audio-only output
     * was explicitly requested by the lifecycle.
     *
     * Volatile because the MediaCodec render callback sets it ([markFirstVideoFrameRendered])
     * on its own thread while the playback pump reads it through [audioPumpAllowed] and
     * [videoOutputPending]; without it the pump may keep gating audio on a stale false.
     */
    private val outputLifecycle = NativeDirectOutputLifecycle()
    private val videoOutputEstablished: Boolean get() = outputLifecycle.videoEstablished
    private val videoIntentionallySuspended: Boolean get() = outputLifecycle.videoSuspended && audioTrackIndex != null

    /** Where prepare or the last seek left the source, and whether a sample was consumed since. */
    private var sourcePositionedAtUs = 0L
    private var inputConsumedSinceReposition = false

    /** Item whose extractor and transport stayed open after a decode-side failure (see [fail]). */
    private var retainedSourceIndex: Int? = null

    @Volatile
    private var requestedPlay = request.autoPlay

    @Volatile
    private var speed = 1f

    @Volatile
    private var lastAvSyncOffsetUs: Long? = null

    @Volatile
    private var avSyncMeasurementStatus = "等待音视频时钟样本"

    private var isolateVideoTimestamps = true
    private var inputEnded = false
    private var videoInputEnded = false
    private var audioInputEnded = false
    private var videoOutputEnded = false
    private var audioOutputEnded = false
    private var pendingVideoOutput: YCodecOutputResult.Buffer? = null
    private val pendingAudioOutput = AndroidPendingAudioOutput<YAudioCodecOutputResult.Buffer>(::releaseAudioOutput)
    private var pendingEncodedAudioInput: YPendingEncodedAudioInput? = null
    private var seekPrerollVideoOutput: YCodecOutputResult.Buffer? = null
    private var emptyTailSeekRetries = 0
    private var lastQueuedPresentationUs = 0L
    private var lastVideoPresentationUs = request.startPositionMs * MICROS_PER_MILLISECOND
    private var seekTargetVideoUs = request.startPositionMs * MICROS_PER_MILLISECOND
    private var seekTargetAudioUs = request.startPositionMs * MICROS_PER_MILLISECOND

    @Volatile
    private var firstVideoFrameRendered = false
    private val videoOutputEpoch = AndroidVideoOutputEpoch()
    private val renderedFrameRateSampler = YRenderedFrameRateSampler()
    private val pausedPreview = AndroidPausedVideoPreview()
    private val videoEosGate = AndroidVideoEosGate()
    private val surfaceCompletion = AndroidSurfacePlaybackCompletion()
    private var seekVideoSubmissionPending = false

    /**
     * Sticky for the whole binding, unlike [firstVideoFrameRendered], which an audio route
     * change or a recovery restart clears. Buffering before the very first frame is startup,
     * not a rebuffer; buffering after it is a rebuffer however the pipeline got there.
     *
     * Volatile for the same reason as [videoOutputEstablished]: the render callback writes it
     * and the pump's state publish reads it to classify rebuffers.
     */
    @Volatile
    private var outputHasEverRendered = false

    private val rebufferTracker =
        com.yfuse.core2.api
            .YRebufferTracker()

    @Volatile
    private var transportReadBlocked = false

    @Volatile
    private var transportBufferingVisible = false

    private val bufferWaitClock = AndroidBufferWaitClock()

    @Volatile
    private var transportBlockGeneration = 0L
    private var droppedFrames = 0
    private var runtimeRenderRecorded = false
    private var lastStatePublishNs = 0L
    private val pacingMetrics = NativeDirectPacingMetrics()

    private val hasVideoTrack: Boolean get() = videoTrackIndex != null

    private val videoRenderable: Boolean
        get() = videoConfigured && surfaceOutput?.surface?.isValid == true

    /**
     * Audio may drive the pump once video output has been established, when the media has no
     * video, or while explicitly requested background audio starts a new media session.
     *
     * Requiring a live Surface for the whole pump is what made a destroyed Surface stop the
     * sound with it: background listening, lock-screen playback and the PiP transition all go
     * through exactly that path. Exo's `clearVideoSurface` detaches only the video renderer.
     */
    private val audioPumpAllowed: Boolean
        get() = outputLifecycle.allowsAudioPump(hasVideoTrack, audioTrackIndex != null)

    /** True when the audio path alone is already carrying playback. */
    private val audioCarryingPlayback: Boolean
        get() = audioPumpAllowed && audioRendererConfigured

    /**
     * True while the product is still waiting for the video output this media is meant to have.
     *
     * Once audio is carrying playback a rebuilt video decoder is not a rebuffer - reporting one
     * would make every Surface reattach flash the buffering state over uninterrupted sound.
     */
    private val videoOutputPending: Boolean
        get() =
            hasVideoTrack &&
                !audioCarryingPlayback &&
                (!videoOutputEstablished || (videoRenderable && !firstVideoFrameRendered))

    fun idleDelayMs(): Long =
        playbackPumpIdleDelayMs(
            playing = requestedPlay,
            buffering = mutableState.value.buffering,
            previewPending = pausedPreview.active,
        )

    val canPump: Boolean
        get() =
            prepared &&
                (requestedPlay || pausedPreview.active) &&
                (if (pausedPreview.active) videoRenderable else videoRenderable || audioPumpAllowed) &&
                mutableState.value.phase != YPlaybackPhase.Failed &&
                mutableState.value.phase != YPlaybackPhase.Ended

    fun handle(command: Command) {
        abortIfReleased()
        when (command) {
            Command.Prepare -> prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
            Command.Play -> startPlayback()
            Command.Pause -> pausePlayback()
            is Command.Seek -> seekTo(command.positionUs)
            is Command.SetSpeed -> updateSpeed(command.speed)
            is Command.SetAudioDelay -> updateAudioDelay(command.delayMs)
            is Command.SetAudioEffect -> updateAudioEffect(command.effect)
            is Command.SetVideoSuspended -> setVideoSuspended(command.suspended)
            is Command.SetVideoOutput -> setSurface(command.output).also { command.detached?.complete(Unit) }
            is Command.SelectAudioTrack -> selectAudioTrack(command.trackIndex)
            is Command.SelectSubtitleTrack ->
                selectSubtitleTrack(command.trackIndex, command.externalTrackId, command.secondary)
            is Command.ExternalSubtitleReady -> {
                if (externalSubtitleSession.accept(command.result)) {
                    externalSubtitles = externalSubtitleSession.tracks
                    mutableState.update {
                        it.copy(
                            subtitleTracks = subtitleTracks(),
                            subtitleCues = activeSubtitleCues(),
                            secondarySubtitleCues = activeSecondarySubtitleCues(),
                        )
                    }
                }
            }
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
            // A timed Surface release can finish between ticks without another codec output.
            if (requestedPlay && isEnded()) {
                finishIfEnded()
                return true
            }
            if (requestedPlay && !refreshOutputGate()) {
                publishClockPosition()
                return false
            }
            drmSession?.let { session ->
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Drm,
                    stage = YPlaybackFailureStage.VideoDecoderQueue,
                    safeDetail = "NativeDirect DRM key refresh",
                ) {
                    if (session.pollKeyRenewal()) {
                        resetVideoRenderEvidence()
                        mutableState.update { current ->
                            current.copy(
                                diagnostics =
                                    current.diagnostics
                                        .invalidateOutputEvidence(YOutputEvidenceResetReason.DrmKeysChanged)
                                        .copy(
                                            videoOutput = "DRM 密钥已更新 · 等待首帧",
                                            audioOutput = waitingAudioOutputLabel(),
                                        ),
                            )
                        }
                    }
                }
            }
            didWork = handleAudioRoutingChange() || didWork
            if (requestedPlay) {
                didWork = drainPendingEncodedAudioInput() || didWork
                didWork = drainAudio() || didWork
            }
            if (!pausedPreview.submitted) {
                didWork = drainVideo() || didWork
                if (!pausedPreview.submitted) didWork = feedInput() || didWork
            } else if (videoConfigured) {
                // Older ACodec implementations report render fences while recycling output
                // buffers. Keep returning already decoded frames without displaying a second
                // preview frame or reading farther into the source.
                didWork =
                    pausedPreview.recycleSubmittedOutput(videoDecoder::dequeueOutput) {
                        videoDecoder.releaseOutput(it, render = false)
                    } ||
                    didWork
            }
            if (pausedPreview.active && firstVideoFrameRendered) pausedPreview.frameRendered()
            if (pausedPreview.finishCallbackWait()) {
                mutableState.update { current ->
                    if (current.diagnostics.videoOutputVerified) {
                        current
                    } else {
                        current.copy(
                            diagnostics =
                                current.diagnostics.copy(videoOutput = "暂停定位帧已提交 · 系统未回调确认"),
                        )
                    }
                }
                didWork = true
            }
            if (firstVideoFrameRendered || !hasVideoTrack && mutableState.value.diagnostics.audioOutputVerified) {
                externalSubtitleSession.request(selectedExternalSubtitleId)
                externalSubtitleSession.request(secondaryExternalSubtitleId)
            }
            recoverVideoIfNoSyncSampleArrives()
            publishClockPosition()
            if (requestedPlay) finishIfEnded()
            return didWork
        } catch (failure: CodecTimestampIdentityException) {
            if (!isolateVideoTimestamps) throw failure
            val positionUs = currentPositionUs()
            isolateVideoTimestamps = false
            videoDecoder.release()
            videoConfigured = false
            surfaceOutput?.surface?.takeIf { it.isValid }?.let { configureVideoDecoder(it) }
            seekTo(positionUs, flushVideoDecoder = false)
            AppLog.warning(
                category = "player.core2",
                event = "codec_media_timestamp_retry",
                message = "Retrying hardware decoder with original media timestamps",
            )
            return true
        } finally {
            val durationNs = (System.nanoTime() - pumpStartedNs).coerceAtLeast(0L)
            pacingMetrics.recordPump(durationNs)
        }
    }

    private fun prepareCurrent(positionUs: Long) {
        // Only the item whose decode-side failure kept the source may reuse it; anything else
        // (another item, a source failure, a later prepare) opens the source afresh.
        val reuseSource = retainedSourceIndex == currentIndex && !demux.readsAborted
        retainedSourceIndex = null
        releaseMedia(keepSource = reuseSource)
        monotonicPositionFloorUs = positionUs.coerceAtLeast(0L)
        val item = request.items[currentIndex]
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Preparing,
                playing = false,
                buffering = requestedPlay,
                positionMs = positionUs / MICROS_PER_MILLISECOND,
                currentIndex = currentIndex,
                itemCount = request.items.size,
                error = null,
                diagnostics =
                    current.diagnostics
                        .invalidateOutputEvidence(YOutputEvidenceResetReason.SourceChanged),
            )
        }

        sourceRemote = item.uri.isCore2RemoteMediaUri()
        if (reuseSource) {
            AppLog.info(
                category = "player.core2",
                event = "native_direct_source_reused",
                message = "NativeDirect retried the item on its open extractor and transport",
                attributes = mapOf("positionUs" to positionUs.coerceAtLeast(0L).toString()),
            )
        } else {
            yPlaybackStage(
                category = sourceFailureCategory(),
                stage = YPlaybackFailureStage.SourceOpen,
                safeDetail = "NativeDirect source open",
            ) {
                demux.open(item.toAndroidSource(), preparedExtractor?.invoke(item))
            }
        }
        abortIfReleased()
        externalSubtitleSession.reset(item.allExternalSubtitles, item.headers)
        externalSubtitles = externalSubtitleSession.tracks
        selectedExternalSubtitleId = externalSubtitleSession.defaultId
        if (item.initialTrackSelection?.subtitlesDisabled == true) selectedExternalSubtitleId = null
        val initialSubtitle = subtitleTracks().matchingPreference(item.initialTrackSelection?.subtitle)
        if (initialSubtitle != null && item.initialTrackSelection?.subtitlesDisabled != true) {
            subtitleTrackIndex = initialSubtitle.id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull()
            selectedExternalSubtitleId = initialSubtitle.id.takeIf { subtitleTrackIndex == null }
        }
        prepareInitialTracks(item)
        val sourceBitRateBitsPerSecond =
            maxOf(
                item.sourceHints?.bitrateBitsPerSecond ?: 0L,
                listOfNotNull(videoFormat, audioInputFormat)
                    .sumOf { it.longOrZero(MediaFormat.KEY_BIT_RATE) },
            )
        this.sourceBitRateBitsPerSecond = sourceBitRateBitsPerSecond
        demux.setMediaBitRateBitsPerSecond(sourceBitRateBitsPerSecond)
        applyBufferPlan(measuredThroughputBitsPerSecond = null, force = true)
        bufferGate =
            com.yfuse.core2.network
                .YPlaybackBufferGate(
                    remote = sourceRemote,
                    resumePlaybackUs = bufferPlan.resumePlaybackUs,
                    startupPlaybackUs = bufferPlan.startupPlaybackUs,
                ).also { it.updateThresholds(bufferPlan) }
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

        // The same floor that protects MediaCodec also sizes the extractor's staging buffer;
        // otherwise a large 4K IDR can fail before it ever reaches the codec input guard.
        videoFormat?.applyVideoMaxInputSizeFloor()
        audioInputFormat?.applyAudioMaxInputSizeFloor()
        val sampleCapacity =
            listOfNotNull(videoFormat, audioInputFormat)
                .maxOfOrNull { format -> format.maxInputSizeOr(DEFAULT_SAMPLE_BUFFER_BYTES) }
                ?.coerceIn(MIN_SAMPLE_BUFFER_BYTES, MAX_SAMPLE_BUFFER_BYTES)
                ?: DEFAULT_SAMPLE_BUFFER_BYTES
        demux.configureSampleCapacity(sampleCapacity)
        // Keep the owner free for track metadata and the initial resume seek. Eager filling
        // here downloads samples at 0, then makes those commands wait behind that download.
        demux.selectTracks(
            selectedDemuxTrackIndices(),
            startReadAhead = false,
            bufferingTrackIndices = setOfNotNull(videoTrackIndex, audioTrackIndex),
        )

        if (videoFormat != null && !videoIntentionallySuspended) {
            surfaceOutput?.surface?.takeIf { it.isValid }?.let { surface ->
                configureVideoDecoder(surface)
            }
        }
        abortIfReleased()

        prepared = true
        resetEndState()
        publishPreparedState(item, sourceBitRateBitsPerSecond)

        val targetUs = positionUs.coerceAtLeast(0L)
        // A freshly opened source stands at its start; a reused one wherever the failed attempt
        // left it, so it is always positioned explicitly.
        sourcePositionedAtUs = 0L
        inputConsumedSinceReposition = false
        if (targetUs > 0L || reuseSource) {
            seekTo(targetUs)
        } else {
            // Nothing from the previous item may gate or position this one.
            seekTargetVideoUs = 0L
            seekTargetAudioUs = 0L
            lastVideoPresentationUs = 0L
            lastQueuedPresentationUs = 0L
            wallClock.seek(0L, System.nanoTime())
        }
        demux.startReadAhead()
        if (requestedPlay) startPlayback()
    }

    /** Selects initial tracks and their formats before opening codecs or starting read-ahead. */
    private fun prepareInitialTracks(item: YMediaItem) {
        // Audio-only media is a first-class source here: music, audiobooks and audio-only
        // versions have no video track and must not be rejected at the container stage.
        videoTrackIndex = demux.findFirstTrack(VIDEO_MIME_PREFIX)
        val capabilities = capabilityProvider.current()
        val platformAudioTrackIndices =
            (0 until demux.trackCount).filter { index ->
                demux
                    .trackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                    .startsWith(AUDIO_MIME_PREFIX)
            }
        val discoveredAudio = platformAudioTracks(demux.trackCount, demux::trackFormat)
        val preferredAudioIndex =
            discoveredAudio
                .matchingPreference(item.initialTrackSelection?.audio)
                ?.id
                ?.substringAfter(':')
                ?.toIntOrNull()
                // A preference can land on DTS behind an AAC label, which nothing here can play;
                // choosing among every track then beats failing on the one it named.
                ?.takeUnless { index -> demux.trackFormat(index).aacLabelWithoutConfig() }
        val audioCandidates = preferredAudioIndex?.let(::listOf) ?: platformAudioTrackIndices
        val initialAudioTrack =
            audioCandidates.firstNotNullOfOrNull { index ->
                val format = demux.trackFormat(index)
                if (format.aacLabelWithoutConfig()) {
                    // DTS in an `mp4a` entry: the AAC decoder would play it as silence, so the
                    // real AAC track behind it has to win (toCore2AudioTrackFormat agrees).
                    logAacLabelWithoutConfig("NativeDirect", discoveredAudio, index, item.sourceHints)
                    return@firstNotNullOfOrNull null
                }
                val coreFormat =
                    runCatching { format.toCore2AudioTrackFormat(item.sourceHints) }.getOrNull()
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
        val sourceDeclaresAudio = (item.sourceHints?.audioTrackCount ?: 0) > 0
        if (sourceDeclaresAudio && platformAudioTrackIndices.isEmpty()) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Demux,
                safeDetail =
                    hiddenServerAudioTrackDetail(NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL, item.sourceHints),
            )
        }
        if (platformAudioTrackIndices.isNotEmpty() && initialAudioTrack == null) {
            // Name the codecs. Without them this failure is indistinguishable from a device
            // that simply lacks the decoder, and a diagnostic bundle cannot tell the two apart.
            val rejectedAudioMimes =
                platformAudioTrackIndices
                    .mapNotNull { index ->
                        val format = demux.trackFormat(index)
                        format.getString(MediaFormat.KEY_MIME)?.lowercase()?.let { mime ->
                            if (format.aacLabelWithoutConfig()) "$mime without AudioSpecificConfig" else mime
                        }
                    }.distinct()
                    .joinToString(",")
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Demux,
                safeDetail = "NativeDirect has no playable platform audio path for $rejectedAudioMimes",
            )
        }
        if (videoTrackIndex == null && initialAudioTrack == null) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Demux,
                safeDetail = "NativeDirect source has neither a video nor a playable audio track",
            )
        }
        audioTrackIndex = initialAudioTrack?.first
        logInitialAudioSelection(
            "NativeDirect",
            item.initialTrackSelection?.audio != null,
            discoveredAudio,
            audioTrackIndex?.let { "audio:$it" },
        )
        videoFormat =
            videoTrackIndex
                ?.let(demux::trackFormat)
                ?.also { format ->
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
    }

    private fun publishPreparedState(
        item: YMediaItem,
        sourceBitRateBitsPerSecond: Long,
    ) {
        val durationUs =
            listOfNotNull(videoFormat, audioInputFormat)
                .mapNotNull { format -> format.durationUsOrNull() }
                .maxOrNull()
                ?: 0L
        val tracks = audioTracks()
        // MediaCodec turns the picture by the track's rotation as it draws to the surface, but
        // stretches nothing: the surface itself takes the shape of non-square pixels.
        val (shownWidth, shownHeight) = videoFormat?.shownSize() ?: (0 to 0)
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Ready,
                durationMs = durationUs / MICROS_PER_MILLISECOND,
                audioTracks = tracks,
                subtitleTracks = subtitleTracks(),
                subtitleCues = activeSubtitleCues(),
                secondarySubtitleCues = activeSecondarySubtitleCues(),
                secondarySubtitleTrackId = secondarySubtitleId(),
                buffering = requestedPlay,
                playbackRequested = requestedPlay,
                diagnostics =
                    current.diagnostics.copy(
                        route = YPlaybackRoute.NativeDirect,
                        container = item.containerHint().name,
                        demuxer = demux.name,
                        decoder =
                            listOfNotNull(
                                videoDecoder.decoderName,
                                audioDecoderDiagnosticName(),
                            ).joinToString(" + "),
                        videoDecoderName = videoDecoder.decoderName.orEmpty(),
                        audioDecoderName = audioDecoderDiagnosticName().orEmpty(),
                        recoverableNetworkFailure = false,
                        renderer = if (videoTrackIndex == null) "AudioTrack" else "Surface + AudioTrack",
                        videoCodec = videoFormat?.getString(MediaFormat.KEY_MIME).orEmpty(),
                        videoWidth = shownWidth,
                        videoHeight = shownHeight,
                        frameRate = videoFormat?.floatOrZero(MediaFormat.KEY_FRAME_RATE) ?: 0f,
                        renderedFrameRate = null,
                        audioCodec = audioInputFormat?.getString(MediaFormat.KEY_MIME).orEmpty(),
                        bitrateBitsPerSecond = sourceBitRateBitsPerSecond,
                        dynamicRange = videoFormat.dynamicRangeLabel(),
                        videoOutput =
                            when {
                                videoTrackIndex == null -> "无视频轨 · 纯音频"
                                videoConfigured -> "等待首帧"
                                else -> "等待 Surface"
                            },
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
                        reason =
                            if (videoTrackIndex == null) {
                                "Platform demux + audio decode"
                            } else {
                                "Platform demux + hardware decode + direct Surface"
                            },
                    ),
            )
        }
    }

    private fun setVideoSuspended(suspended: Boolean) {
        if (outputLifecycle.videoSuspended == suspended) return
        outputLifecycle.setVideoSuspended(suspended, audioCarryingPlayback)
        if (!prepared) return
        // Keep the caller's Surface reference for foreground restoration, but stop its decoder
        // while audio-only playback is requested. A new media session can now start without it.
        setSurface(surfaceOutput)
        if (requestedPlay) startPlayback()
    }

    private fun setSurface(output: AndroidSurfaceVideoOutput?) {
        val previous = surfaceOutput
        surfaceOutput = output
        val newSurface = output?.surface?.takeIf { it.isValid && !videoIntentionallySuspended }
        if (newSurface == null) {
            videoOutputEpoch.reset()
            firstVideoFrameRendered = false
            val positionUs = currentPositionUs()
            frameRateManager.clear()
            videoDecoder.release()
            videoConfigured = false
            pendingVideoOutput = null
            seekPrerollVideoOutput = null
            if (audioPumpAllowed) {
                // Detach the video renderer only. The clock, the audio track and the demux
                // pump all keep running, which is what background listening, lock-screen
                // playback and the PiP transition need.
                mutableState.update { current ->
                    current.copy(
                        diagnostics =
                            current.diagnostics
                                .invalidateOutputEvidence(YOutputEvidenceResetReason.SurfaceChanged)
                                .copy(videoOutput = "Surface 已分离 · 音频继续"),
                    )
                }
                return
            }
            pausePlaybackInternal(keepRequested = true)
            wallClock.seek(positionUs, System.nanoTime())
            mutableState.update { current ->
                current.copy(
                    playing = false,
                    buffering = requestedPlay,
                    diagnostics =
                        current.diagnostics
                            .invalidateOutputEvidence(YOutputEvidenceResetReason.SurfaceChanged)
                            .copy(videoOutput = "等待 Surface"),
                )
            }
            return
        }
        if (!hasVideoTrack) return
        if (previous?.surface === newSurface && videoConfigured) return
        val audioContinues = audioCarryingPlayback
        resetVideoRenderEvidence()
        mutableState.update { current ->
            current.copy(
                playing = if (audioContinues) current.playing else false,
                buffering = if (audioContinues) current.buffering else requestedPlay,
                diagnostics =
                    current.diagnostics
                        .invalidateOutputEvidence(YOutputEvidenceResetReason.SurfaceChanged)
                        .copy(videoOutput = "硬解已配置 · 等待首帧"),
            )
        }
        if (videoConfigured && previous?.surface?.isValid == true) {
            runCatching { videoDecoder.setOutputSurface(newSurface) }
                .onSuccess {
                    attachVideoRenderEvidence()
                    frameRateManager.reattach(newSurface)
                    if (requestedPlay) startPlayback()
                    return
                }
        }
        if (!prepared) return
        if (audioContinues && !isEnded()) {
            // Audio never stopped, so the extractor is already at the live position. Rebuild
            // the decoder in place and let it pick up the next sync sample instead of seeking
            // the shared extractor, which would have to rewind audio to a keyframe as well.
            configureVideoDecoder(newSurface)
            outputLifecycle.awaitVideoSync()
            if (requestedPlay) startPlayback()
            return
        }
        val resumeUs = currentPositionUs()
        configureVideoDecoder(newSurface)
        if (nativeDirectSurfaceAttachNeedsSeek(inputConsumedSinceReposition, sourcePositionedAtUs, resumeUs)) {
            seekTo(resumeUs)
        } else if (!requestedPlay) {
            // The source already stands at resumeUs; only the paused frame still has to be shown.
            pausedPreview.begin(resumeUs)
        }
        if (requestedPlay) startPlayback()
    }

    private fun startPlayback() {
        val previewResumeUs = pausedPreview.takeResumePosition()
        requestedPlay = true
        if (prepared && previewResumeUs != null) {
            seekTo(previewResumeUs)
            return
        }
        if (prepared && mutableState.value.phase == YPlaybackPhase.Ended) {
            seekTo(0L)
            return
        }
        if (!prepared) {
            prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
            return
        }
        if (!videoConfigured && !audioPumpAllowed) {
            mutableState.update { current ->
                current.copy(
                    playbackRequested = true,
                    playing = false,
                    buffering = true,
                )
            }
            return
        }
        if (!refreshOutputGate()) {
            mutableState.update { current ->
                current.copy(playbackRequested = true, playing = false, buffering = true)
            }
            return
        }
        val now = System.nanoTime()
        wallClock.start(currentPositionUs(), now)
        if (audioRendererConfigured) {
            playAudio()
        }
        mutableState.update { current ->
            current.copy(
                playbackRequested = true,
                playing = !videoOutputPending && !transportBufferingVisible,
                buffering = videoOutputPending || transportBufferingVisible,
                phase = if (isEnded()) YPlaybackPhase.Ended else YPlaybackPhase.Ready,
            )
        }
    }

    private fun pausePlayback() {
        requestedPlay = false
        pausePlaybackInternal(keepRequested = false)
        demux.updatePlaybackWindow(YTransportPlaybackWindow(playing = false))
    }

    private fun pausePlaybackInternal(keepRequested: Boolean) {
        bufferWaitClock.reset()
        renderedFrameRateSampler.reset()
        val positionUs = currentPositionUs()
        wallClock.pause(positionUs, System.nanoTime())
        pauseAudio()
        if (!keepRequested) requestedPlay = false
        mutableState.update { current ->
            current.copy(
                playbackRequested = requestedPlay,
                playing = false,
                buffering = false,
                positionMs = positionUs / MICROS_PER_MILLISECOND,
                diagnostics = current.diagnostics.copy(renderedFrameRate = null),
            )
        }
    }

    private fun seekTo(
        positionUs: Long,
        tailRetry: Boolean = false,
        flushVideoDecoder: Boolean = true,
    ) {
        if (!prepared) return
        val targetUs = positionUs.coerceAtLeast(0L)
        if (!requestedPlay && hasVideoTrack) pausedPreview.begin(targetUs) else pausedPreview.clear()
        seekVideoSubmissionPending = hasVideoTrack
        AppLog.info(
            category = "player.core2",
            event = if (pausedPreview.active) "paused_preview_seek" else "native_direct_seek",
            message = "NativeDirect started a seek generation",
            attributes =
                mapOf(
                    "targetUs" to targetUs.toString(),
                    "playbackRequested" to requestedPlay.toString(),
                ),
        )
        videoOutputEpoch.reset()
        if (!tailRetry) emptyTailSeekRetries = 0
        pendingEncodedAudioInput = null
        yPlaybackStage(
            category = sourceFailureCategory(),
            stage = YPlaybackFailureStage.Seek,
            safeDetail = "NativeDirect source seek",
        ) {
            val buffered = demux.trySeekBuffered(targetUs, videoTrackIndex)
            if (!buffered) demux.seekTo(targetUs)
            AppLog.info(
                category = "player.core2",
                event = "native_direct_seek_resolved",
                message = "NativeDirect positioned the source",
                attributes = mapOf("targetUs" to targetUs.toString(), "buffered" to buffered.toString()),
            )
        }
        sourcePositionedAtUs = targetUs
        inputConsumedSinceReposition = false
        if (videoConfigured && flushVideoDecoder) {
            if (isolateVideoTimestamps) {
                nativeDirectStage(
                    YPlaybackFailureCategory.Decoder,
                    YPlaybackFailureStage.VideoDecoderQueue,
                    "NativeDirect video decoder flush",
                ) {
                    videoDecoder.flush()
                }
            } else {
                // Original PTS can repeat across seeks; retire the old callback source as well.
                videoDecoder.release()
                videoConfigured = false
                surfaceOutput?.surface?.takeIf { it.isValid }?.let { configureVideoDecoder(it) }
            }
        }
        if (audioInputFormat != null && !isAudioPassthrough()) {
            releasePendingAudioOutput()
            // Rebuilds instead of flushing a decoder that has not reported its PCM format yet.
            nativeDirectStage(
                YPlaybackFailureCategory.Decoder,
                YPlaybackFailureStage.AudioDecoderConfigure,
                "NativeDirect audio decoder seek flush",
            ) {
                audioDecoder.flush()
            }
        }
        if (audioRendererConfigured) {
            nativeDirectStage(
                YPlaybackFailureCategory.AudioSink,
                YPlaybackFailureStage.AudioRenderer,
                "NativeDirect audio sink flush",
            ) {
                flushAudio()
            }
        }
        subtitleCues.clear()
        secondarySubtitleCues.clear()
        resetEndState()
        seekTargetVideoUs = targetUs
        bufferGate.reset()
        lastBufferGatePositionUs = targetUs
        bufferWaitClock.reset()
        rebufferTracker.discontinuity(System.nanoTime() / 1_000_000L)
        hdrAccessUnits.clear()
        seekTargetAudioUs = targetUs
        lastVideoPresentationUs = targetUs
        lastQueuedPresentationUs = targetUs
        monotonicPositionFloorUs = targetUs
        resetVideoRenderEvidence()
        wallClock.seek(targetUs, System.nanoTime())
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Ready,
                playing = false,
                buffering = requestedPlay,
                positionMs = targetUs / MICROS_PER_MILLISECOND,
                bufferedPositionMs = maxOf(targetUs, demux.snapshot().bufferedThroughUs) / MICROS_PER_MILLISECOND,
                subtitleCues = activeSubtitleCues(),
                secondarySubtitleCues = activeSecondarySubtitleCues(),
                secondarySubtitleTrackId = secondarySubtitleId(),
                error = null,
                diagnostics =
                    current.diagnostics
                        .invalidateOutputEvidence(YOutputEvidenceResetReason.Seek)
                        .copy(
                            videoOutput = if (videoConfigured) "硬解已配置 · 等待首帧" else "等待 Surface",
                            audioOutput = waitingAudioOutputLabel(),
                            dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                        ),
            )
        }
        if (requestedPlay) startPlayback()
    }

    private fun updateSpeed(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        val positionUs = currentPositionUs()
        speed = value
        applyBufferPlan(measuredThroughputBitsPerSecond = demux.snapshot().throughputBitsPerSecond, force = true)
        wallClock.setSpeed(value, positionUs, System.nanoTime())
        videoDecoder.setPlaybackSpeed(value)
        if (isAudioPassthrough() && value != 1f) {
            switchPassthroughToPcm(countFailure = false)
            seekTo(positionUs)
            mutableState.update { current -> current.copy(speed = value) }
            return
        }
        if (audioRendererConfigured && !isAudioPassthrough()) audioRenderer.setSpeed(value)
        mutableState.update { current -> current.copy(speed = value) }
        restorePassthroughIfAvailable()
    }

    private var audioDelayMs = 0L

    private fun updateAudioDelay(value: Long) {
        audioDelayMs = value
        audioRenderer.setAudioDelayMs(value)
        if (value != 0L && isAudioPassthrough()) {
            val position = currentPositionUs()
            switchPassthroughToPcm(countFailure = false)
            seekTo(position)
        } else {
            restorePassthroughIfAvailable()
        }
    }

    private var audioEffect = YAudioEffect.Off

    /** Effects process PCM, so a bitstreamed track decodes while one is on. */
    private fun updateAudioEffect(value: YAudioEffect) {
        audioEffect = value
        audioRenderer.setAudioEffect(value)
        if (value != YAudioEffect.Off && isAudioPassthrough()) {
            val position = currentPositionUs()
            switchPassthroughToPcm(countFailure = false)
            seekTo(position)
        } else {
            restorePassthroughIfAvailable()
        }
    }

    /** Gives passthrough back once speed is 1.0 and the delay 0 again; see [passthroughRestorable]. */
    private fun restorePassthroughIfAvailable() {
        val coreFormat = audioTrackFormat ?: return
        if (!prepared || audioInputFormat == null) return
        val devicePath =
            plannedAudioOutputPath
                ?: capabilityProvider.current().audioOutputPath(
                    YAudioRequirement(
                        codec = coreFormat.codec,
                        channelCount = coreFormat.channelCount,
                        sampleRate = coreFormat.sampleRate,
                    ),
                )
        val restorable =
            passthroughRestorable(
                currentPath = audioOutputPath,
                devicePath = devicePath,
                protectedContent = drmBinding != null,
                passthroughRejected = audioTrackIndex?.let(rejectedPassthroughTracks::contains) == true,
                speed = speed,
                audioDelayMs = audioDelayMs,
                audioEffectActive = audioEffect != YAudioEffect.Off,
            )
        if (!restorable) return
        val positionUs = currentPositionUs()
        releaseAudioPath()
        configureAudioPath(audioInputFormat)
        seekTo(positionUs)
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
                        audioOutput = waitingAudioOutputLabel(),
                        audioOutputVerified = false,
                    ),
            )
        }
    }

    private fun selectAudioTrack(trackIndex: Int) {
        if (!prepared || trackIndex == audioTrackIndex || trackIndex !in 0 until demux.trackCount) return
        val format = demux.trackFormat(trackIndex)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith(AUDIO_MIME_PREFIX) != true) return
        val positionUs = currentPositionUs()
        val previousIndex = audioTrackIndex
        val previousFormat = audioInputFormat
        val previousPlannedPath = plannedAudioOutputPath

        fun apply(
            index: Int?,
            selectedFormat: MediaFormat?,
        ) {
            audioTrackIndex = index
            audioInputFormat = selectedFormat
            // A plan selected for the previous audio track is not a capability result for this one.
            plannedAudioOutputPath = null
            demux.selectTracks(
                selectedDemuxTrackIndices(),
                startReadAhead = false,
                bufferingTrackIndices = setOfNotNull(videoTrackIndex, audioTrackIndex),
            )
            releaseAudioPath()
            configureAudioPath(selectedFormat)
            seekTo(positionUs)
        }
        if (!playbackTrackSwitch(
                change = { apply(trackIndex, format) },
                restore = {
                    apply(previousIndex, previousFormat)
                    plannedAudioOutputPath = previousPlannedPath
                },
                rejected = { error ->
                    AppLog.warning(
                        category = "player.core2",
                        event = "audio_track_switch_rejected",
                        message = "Restored previous audio track",
                        throwable = error,
                    )
                },
            )
        ) {
            return
        }
        mutableState.update { current ->
            current.copy(
                audioTracks = audioTracks(),
                diagnostics =
                    current.diagnostics.copy(
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
    }

    private fun selectSubtitleTrack(
        trackIndex: Int?,
        externalTrackId: String?,
        secondary: Boolean,
    ) {
        if (!prepared) return
        externalSubtitleSession.retry(externalTrackId)
        if (externalTrackId != null && externalSubtitles.none { it.track.id == externalTrackId }) return
        val nextFormat = trackIndex?.takeIf { it in 0 until demux.trackCount }?.let(demux::trackFormat)
        if (trackIndex != null && nextFormat?.subtitleFormatOrNull()?.textOverlaySupported != true) return
        if (secondary) {
            if (trackIndex != null &&
                trackIndex == subtitleTrackIndex ||
                externalTrackId != null &&
                externalTrackId == selectedExternalSubtitleId
            ) {
                return
            }
            if (trackIndex == secondarySubtitleTrackIndex && externalTrackId == secondaryExternalSubtitleId) return
        } else if (trackIndex == subtitleTrackIndex && externalTrackId == selectedExternalSubtitleId) {
            return
        }
        val previousEmbedded = selectedDemuxTrackIndices()
        val positionUs = currentPositionUs()
        if (secondary) {
            secondarySubtitleTrackIndex = trackIndex
            secondaryExternalSubtitleId = externalTrackId
            secondarySubtitleCues.clear()
        } else {
            subtitleTrackIndex = trackIndex
            selectedExternalSubtitleId = externalTrackId
            subtitleCues.clear()
            if (trackIndex != null &&
                trackIndex == secondarySubtitleTrackIndex ||
                externalTrackId != null &&
                externalTrackId == secondaryExternalSubtitleId
            ) {
                secondarySubtitleTrackIndex = null
                secondaryExternalSubtitleId = null
                secondarySubtitleCues.clear()
            }
        }
        val selectedEmbedded = selectedDemuxTrackIndices()
        if (selectedEmbedded != previousEmbedded) {
            demux.selectTracks(
                selectedEmbedded,
                bufferingTrackIndices = setOfNotNull(videoTrackIndex, audioTrackIndex),
            )
            // Track selection clears read-ahead, so re-anchor A/V as well as both subtitle streams.
            seekTo(positionUs)
        }
        mutableState.update { current ->
            current.copy(
                subtitleTracks = subtitleTracks(),
                subtitleCues = activeSubtitleCues(),
                secondarySubtitleCues = activeSecondarySubtitleCues(),
                secondarySubtitleTrackId = secondarySubtitleId(),
            )
        }
    }

    /**
     * Falls back to a seek when a container never delivers the sync sample video is waiting for.
     *
     * The seek-free reattach is the good path because it leaves audio untouched, but a stream
     * whose sync flags are missing or wrong would otherwise stay silent-with-no-picture forever.
     */
    private fun recoverVideoIfNoSyncSampleArrives() {
        if (!outputLifecycle.awaitingVideoSync ||
            outputLifecycle.skippedVideoSamples < MAX_VIDEO_SYNC_SAMPLE_DROPS
        ) {
            return
        }
        outputLifecycle.clearVideoSyncWait()
        seekTo(currentPositionUs())
    }

    private fun feedInput(): Boolean {
        if (pendingEncodedAudioInput != null) {
            val audioTrack = audioTrackIndex ?: return false
            val alternate = pollDemuxSample(excludedTrackIndex = audioTrack) ?: return false
            if (queueDemuxSample(alternate)) return true
            demux.returnSample(alternate)
            return false
        }
        if (inputEnded) {
            var queued = false
            if (!videoInputEnded) {
                if (!videoConfigured) {
                    // Detached video output cannot drain an end-of-stream buffer. Retiring the
                    // track here is what lets an audio-only tail still reach Ended.
                    videoInputEnded = true
                    videoOutputEnded = true
                    queued = true
                } else if (
                    videoEosGate.mayQueueEndOfStream(firstVideoFrameRendered) &&
                    nativeDirectStage(
                        YPlaybackFailureCategory.Decoder,
                        YPlaybackFailureStage.VideoDecoderQueue,
                        "NativeDirect video end of stream",
                    ) {
                        videoDecoder.queueEndOfStream(lastQueuedPresentationUs)
                    } == YCodecQueueResult.Queued
                ) {
                    videoInputEnded = true
                    queued = true
                }
            }
            if (!audioInputEnded && audioInputFormat != null) {
                if (isAudioPassthrough()) {
                    audioInputEnded = true
                    audioOutputEnded = true
                    queued = true
                } else if (
                    nativeDirectStage(
                        YPlaybackFailureCategory.Decoder,
                        YPlaybackFailureStage.AudioDecoderQueue,
                        "NativeDirect audio end of stream",
                    ) {
                        audioDecoder.queueEndOfStream(lastQueuedPresentationUs)
                    } == YCodecQueueResult.Queued
                ) {
                    audioInputEnded = true
                    queued = true
                }
            }
            return queued
        }

        val primary = pollDemuxSample() ?: return false
        if (queueDemuxSample(primary)) {
            return true
        }

        val blockedTrack = primary.trackIndex
        // Passthrough may have consumed part of the sample into its own pending buffer. All
        // other TryAgain results leave the sample untouched and safe to return to read-ahead.
        if (pendingEncodedAudioInput == null || blockedTrack != audioTrackIndex) {
            demux.returnSample(primary)
        }

        // A full video codec must not starve the following audio samples (or vice versa).
        val alternate = pollDemuxSample(excludedTrackIndex = blockedTrack) ?: return false
        if (queueDemuxSample(alternate)) return true
        if (pendingEncodedAudioInput == null || alternate.trackIndex != audioTrackIndex) {
            demux.returnSample(alternate)
        }
        return false
    }

    private fun pollDemuxSample(excludedTrackIndex: Int? = null): YExtractorSample? =
        when (val queued = demux.pollSample(excludedTrackIndex)) {
            is YQueuedExtractorResult.Sample -> queued.value
            is YQueuedExtractorResult.Failed ->
                throw YPlaybackException(
                    category = sourceFailureCategory(),
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "NativeDirect compressed sample read",
                    cause = queued.cause,
                )
            YQueuedExtractorResult.Empty -> {
                null
            }
            YQueuedExtractorResult.EndOfInput -> {
                inputEnded = true
                null
            }
        }

    private fun queueDemuxSample(sample: YExtractorSample): Boolean {
        val queued =
            when (sample.trackIndex) {
                videoTrackIndex ->
                    when {
                        !videoConfigured && audioPumpAllowed ->
                            // The Surface went away mid-playback. Holding the sample would
                            // back-pressure the shared queue until audio starved too, so the
                            // video track is discarded until a Surface returns.
                            YCodecQueueResult.Queued
                        !videoConfigured -> YCodecQueueResult.TryAgain
                        outputLifecycle.discardUntilVideoSync(
                            syncSample = sample.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0,
                        ) -> {
                            // A rebuilt decoder cannot start mid-GOP. Dropping to the next sync
                            // sample resumes video without seeking the shared extractor, which
                            // is what would have interrupted the audio that never stopped.
                            YCodecQueueResult.Queued
                        }
                        else -> {
                            applyHdr10PlusMetadata(sample)
                            nativeDirectStage(
                                YPlaybackFailureCategory.Decoder,
                                YPlaybackFailureStage.VideoDecoderQueue,
                                "NativeDirect video access unit",
                            ) {
                                videoDecoder.queueAccessUnit(
                                    sample.data,
                                    sample.presentationTimeUs,
                                    sample.flags,
                                    sample.cryptoInfo,
                                )
                            }.also { result ->
                                if (result == YCodecQueueResult.Queued) videoEosGate.inputQueued()
                            }
                        }
                    }
                audioTrackIndex ->
                    if (pausedPreview.active) {
                        YCodecQueueResult.Queued
                    } else {
                        queueAudioSample(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.flags,
                            sample.cryptoInfo,
                        )
                    }
                subtitleTrackIndex, secondarySubtitleTrackIndex -> {
                    require(sample.cryptoInfo == null) { "Encrypted subtitle samples are not executable" }
                    queueSubtitleSample(sample.trackIndex, sample.data, sample.presentationTimeUs)
                    YCodecQueueResult.Queued
                }
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) return false
        // Accepted or deliberately dropped, the sample has left the source's read position.
        inputConsumedSinceReposition = true
        hdrAccessUnits.queued(sample)
        lastQueuedPresentationUs = maxOf(lastQueuedPresentationUs, sample.presentationTimeUs)
        return true
    }

    /** MediaExtractor does not consistently forward per-frame ST 2094-40 metadata. */
    private fun applyHdr10PlusMetadata(sample: YExtractorSample) {
        val data = sample.data
        if (!inspectHdr10PlusSamples || !data.hasRemaining()) return
        val payload = hdrAccessUnits.getOrPrepare(sample) { extractNativeDirectHdr10PlusPayload(data) } ?: return
        videoDecoder.setHdr10PlusMetadata(payload)
    }

    private fun drainAudio(): Boolean {
        if (audioInputFormat == null || audioOutputEnded) return false
        if (isAudioPassthrough()) return false
        pendingAudioOutput.current?.let { return writePendingAudioOutput(it) }
        // Each codec and sink call carries its own stage: an untyped exception here used to
        // surface as native_direct_failed Unknown/Unknown with nothing to say which call threw.
        val dequeued =
            nativeDirectStage(
                YPlaybackFailureCategory.Decoder,
                YPlaybackFailureStage.AudioDecoderQueue,
                "NativeDirect audio decoder output",
            ) {
                audioDecoder.dequeueOutput()
            }
        return when (val output = dequeued) {
            YAudioCodecOutputResult.TryAgain -> false
            is YAudioCodecOutputResult.FormatChanged -> {
                val rebuilt =
                    nativeDirectStage(
                        YPlaybackFailureCategory.AudioSink,
                        YPlaybackFailureStage.AudioRenderer,
                        "NativeDirect PCM sink configure",
                    ) {
                        audioRenderer.configureIfChanged(output.format).also {
                            audioRendererConfigured = true
                            captureAudioRoutingGeneration()
                            audioRenderer.setSpeed(speed)
                            audioRenderer.setAudioDelayMs(audioDelayMs)
                            if (requestedPlay) audioRenderer.play()
                        }
                    }
                if (rebuilt) {
                    mutableState.update { current ->
                        current.copy(
                            diagnostics =
                                current.diagnostics.copy(
                                    audioOutput = "等待 PCM 输出",
                                    audioOutputVerified = false,
                                ),
                        )
                    }
                }
                true
            }
            is YAudioCodecOutputResult.Buffer -> {
                val configBuffer = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val renderable =
                    !configBuffer &&
                        output.size > 0 &&
                        output.presentationTimeUs >= seekTargetAudioUs
                if (!renderable) {
                    releaseAudioOutput(output)
                    if (output.endOfStream) audioOutputEnded = true
                    true
                } else {
                    val pending =
                        nativeDirectStage(
                            YPlaybackFailureCategory.Decoder,
                            YPlaybackFailureStage.AudioDecoderQueue,
                            "NativeDirect audio decoder output",
                        ) {
                            if (!audioRendererConfigured) {
                                // Android normally emits INFO_OUTPUT_FORMAT_CHANGED first; keep the
                                // failure explicit rather than silently dropping audio if an OEM does not.
                                error("Audio output arrived before PCM format")
                            }
                            pendingAudioOutput.hold(
                                output = output,
                                data = audioDecoder.outputData(output),
                            )
                        }
                    writePendingAudioOutput(pending)
                }
            }
        }
    }

    private fun releaseAudioOutput(output: YAudioCodecOutputResult.Buffer) {
        nativeDirectStage(
            YPlaybackFailureCategory.Decoder,
            YPlaybackFailureStage.AudioDecoderQueue,
            "NativeDirect audio decoder output release",
        ) {
            audioDecoder.releaseOutput(output)
        }
    }

    private fun writePendingAudioOutput(pending: PendingAudioBuffer<YAudioCodecOutputResult.Buffer>): Boolean {
        val writtenBytes =
            nativeDirectStage(
                YPlaybackFailureCategory.AudioSink,
                YPlaybackFailureStage.AudioRenderer,
                "NativeDirect PCM sink write",
            ) {
                audioRenderer.writeNonBlocking(
                    pending.data,
                    pending.output.presentationTimeUs,
                )
            }
        return when (decodedAudioDrainProgress(writtenBytes, pending.data.remaining())) {
            YDecodedAudioDrainProgress.Backpressured -> {
                pacingMetrics.recordAudioBackpressure()
                false
            }
            YDecodedAudioDrainProgress.Pending -> {
                verifyPcmAudioOutput(writtenBytes)
                true
            }
            YDecodedAudioDrainProgress.Complete -> {
                verifyPcmAudioOutput(writtenBytes)
                pendingAudioOutput.complete(pending)
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
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
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
                        audioOutputFingerprint = audioRenderer.audioRouteFingerprint,
                        audioOutputRouteVerified = audioRenderer.audioRouteVerified,
                        dolbyAtmosOutput = false,
                        spatialAudioOutput = spatialized,
                        headTrackingAvailable = audioRenderer.headTrackingAvailable,
                    ),
            )
        }
    }

    private fun releaseVideoOutput(
        output: YCodecOutputResult.Buffer,
        render: Boolean,
        renderTimeNs: Long? = null,
    ) {
        nativeDirectStage(
            YPlaybackFailureCategory.Renderer,
            YPlaybackFailureStage.VideoRenderer,
            "NativeDirect video frame release",
        ) {
            videoDecoder.releaseOutput(output, render, renderTimeNs)
        }
    }

    private fun dequeueVideoOutput(): YCodecOutputResult =
        nativeDirectStage(
            YPlaybackFailureCategory.Decoder,
            YPlaybackFailureStage.VideoDecoderQueue,
            "NativeDirect video decoder output",
        ) {
            videoDecoder.dequeueOutput()
        }

    /**
     * The shown size once the decoder states a pixel aspect ratio, which it reads from the
     * bitstream, when the container (MPEG-TS, AVI) stated none; a container's own ratio stays.
     */
    private fun decodedShownSize(output: MediaFormat): Pair<Int, Int>? {
        val input = videoFormat?.takeIf { it.statedPixelAspectRatio() == null } ?: return null
        val decoded = output.statedPixelAspectRatio() ?: return null
        return input.shownSize(decoded)
    }

    private fun drainVideo(): Boolean {
        if (!videoConfigured || videoOutputEnded) return false
        val output =
            pendingVideoOutput ?: when (val dequeued = dequeueVideoOutput()) {
                YCodecOutputResult.TryAgain -> return false
                is YCodecOutputResult.FormatChanged -> {
                    val shownSize = decodedShownSize(dequeued.format)
                    mutableState.update { current ->
                        current.copy(
                            diagnostics =
                                current.diagnostics.copy(
                                    videoOutput = "硬解已配置 · 等待首帧",
                                    videoWidth = shownSize?.first ?: current.diagnostics.videoWidth,
                                    videoHeight = shownSize?.second ?: current.diagnostics.videoHeight,
                                ),
                        )
                    }
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
            seekPrerollVideoOutput?.let { releaseVideoOutput(it, render = false) }
            seekPrerollVideoOutput = output
            if (!output.endOfStream) return true
            renderSeekPrerollAtEnd()
            return true
        }
        if (output.endOfStream && seekTargetVideoUs > 0L && seekPrerollVideoOutput != null) {
            pendingVideoOutput = null
            releaseVideoOutput(output, render = false)
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
            seekPrerollVideoOutput?.let { releaseVideoOutput(it, render = false) }
            seekPrerollVideoOutput = null
            val currentUs = audioRenderer.videoClockPositionUs(currentPositionUs())
            val nowNs = System.nanoTime()
            val wallClockRenderNs = wallClock.presentationTimeNs(output.presentationTimeUs)
            val desiredRenderNs =
                if (audioRendererConfigured) {
                    audioPresentationTimeNs(output.presentationTimeUs, wallClockRenderNs)
                } else {
                    wallClockRenderNs
                }
            val decision =
                if (pausedPreview.active) {
                    YVideoFrameReleaseDecision.Render(nowNs)
                } else {
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
                }
            when (decision) {
                YVideoFrameReleaseDecision.Hold -> {
                    pendingVideoOutput = output
                    return false
                }
                YVideoFrameReleaseDecision.Drop -> {
                    pendingVideoOutput = null
                    releaseVideoOutput(output, render = false)
                    droppedFrames++
                }
                is YVideoFrameReleaseDecision.Render -> {
                    if (videoDecoder.anime4KActive && decision.tooEarlyToPresentOnArrival(nowNs)) {
                        pendingVideoOutput = output
                        return false
                    }
                    pendingVideoOutput = null
                    videoOutputEpoch.submitted(output.presentationTimeUs)
                    releaseVideoOutput(output, render = true, renderTimeNs = decision.releaseTimeNs)
                    surfaceCompletion.frameReleased(decision.releaseTimeNs)
                    recordSeekVideoSubmission(output)
                    pausedPreview.frameSubmitted()
                    if (pausedPreview.active) {
                        AppLog.info(
                            category = "player.core2",
                            event = "paused_preview_submitted",
                            message = "NativeDirect submitted the paused seek frame to Surface",
                            attributes = mapOf("presentationTimeUs" to output.presentationTimeUs.toString()),
                        )
                    }
                }
            }
            lastVideoPresentationUs = output.presentationTimeUs
            seekTargetVideoUs = 0L
        } else {
            pendingVideoOutput = null
            releaseVideoOutput(output, render = false)
        }
        if (output.endOfStream) videoOutputEnded = true
        return true
    }

    private fun renderSeekPrerollAtEnd() {
        val candidate = seekPrerollVideoOutput ?: return
        seekPrerollVideoOutput = null
        videoOutputEpoch.submitted(candidate.presentationTimeUs)
        releaseVideoOutput(candidate, render = true)
        surfaceCompletion.frameReleased(System.nanoTime())
        recordSeekVideoSubmission(candidate)
        pausedPreview.frameSubmitted()
        lastVideoPresentationUs = candidate.presentationTimeUs
        seekTargetVideoUs = 0L
        videoOutputEnded = true
    }

    private fun recordSeekVideoSubmission(output: YCodecOutputResult.Buffer) {
        if (!seekVideoSubmissionPending) return
        seekVideoSubmissionPending = false
        AppLog.info(
            category = "player.core2",
            event = "native_direct_seek_submitted",
            message = "NativeDirect submitted the first Surface frame after seek",
            attributes =
                mapOf(
                    "presentationTimeUs" to output.presentationTimeUs.toString(),
                    "targetUs" to seekTargetVideoUs.toString(),
                    "outputEvidenceGeneration" to
                        mutableState.value.diagnostics.outputEvidenceGeneration
                            .toString(),
                    "playbackRequested" to requestedPlay.toString(),
                    "inputEnded" to inputEnded.toString(),
                    "outputEndOfStream" to output.endOfStream.toString(),
                    "renderedFrames" to pacingMetrics.renderedFrameCount.toString(),
                ),
        )
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

    /**
     * Re-plans compressed read-ahead from the current shared grant and available link measurements.
     *
     * Mirrors the Enhanced session: a healthy link keeps the queue shallow and latency low,
     * while a shrinking grant lowers the time watermark immediately, even before throughput is known.
     */
    private fun refreshAdaptiveBufferPlan(readAhead: YExtractorReadAheadSnapshot) {
        val memoryBudget = playbackDemuxMemoryBudgetBytes(readAhead.memoryBudgetBytes)
        if (!sourceRemote && memoryBudget == bufferPlan.maximumBytes) return
        val nowNs = System.nanoTime()
        if (memoryBudget >= bufferPlan.maximumBytes && nowNs - lastBufferReplanNs < BUFFER_REPLAN_INTERVAL_NS) return
        lastBufferReplanNs = nowNs
        applyBufferPlan(
            measuredThroughputBitsPerSecond =
                readAhead.throughputBitsPerSecond.takeIf {
                    readAhead.throughputMeasured
                },
        )
    }

    private fun applyBufferPlan(
        measuredThroughputBitsPerSecond: Long?,
        force: Boolean = false,
    ) {
        val next =
            YBufferController.plan(
                YBufferConditions(
                    remote = sourceRemote,
                    mediaBitRateBitsPerSecond = sourceBitRateBitsPerSecond,
                    measuredNetworkBitsPerSecond = measuredThroughputBitsPerSecond,
                    memoryBudgetBytes = playbackDemuxMemoryBudgetBytes(demux.snapshot().memoryBudgetBytes),
                    preferredTargetAheadUs = preferredRemoteBufferTargetUs,
                    speed = speed,
                ),
            )
        if (!force && next == bufferPlan) return
        bufferPlan = next
        bufferGate.updateThresholds(next)
        demux.configureBufferPlan(
            targetAheadUs = next.targetAheadUs,
            maximumBytes = next.maximumBytes,
        )
    }

    /** Only the codec owner changes playback clocks; network completion is not permission to resume. */
    private fun refreshOutputGate(): Boolean {
        val ahead = demux.snapshot()
        refreshAdaptiveBufferPlan(ahead)
        val positionUs = currentPositionUs()
        if (requestedPlay && !transportBufferingVisible) {
            bufferGate.recordPlaybackProgress(positionUs - lastBufferGatePositionUs)
        }
        lastBufferGatePositionUs = positionUs
        if (bufferGate.phase == com.yfuse.core2.network.YPlaybackBufferPhase.Ready &&
            ahead.starved &&
            !ahead.endOfInput &&
            lastQueuedPresentationUs - currentPositionUs() <= 150_000L
        ) {
            bufferGate.markStarved()
        }
        val decision =
            bufferGate.evaluate(
                ahead.bufferedDurationUs,
                ahead.endOfInput,
                ahead.atCapacity,
                rebufferWaitUs =
                    bufferWaitClock.observe(
                        System.nanoTime(),
                        requestedPlay &&
                            bufferGate.phase == com.yfuse.core2.network.YPlaybackBufferPhase.Rebuffering,
                        ahead.generation,
                    ),
            )
        logBufferCapacityLimit(
            "NativeDirect",
            decision,
            ahead.bufferedDurationUs,
            ahead.queuedBytes,
            ahead.queueBudgetBytes,
        )
        if (!decision.outputAllowed && !transportBufferingVisible) {
            val position = currentPositionUs()
            monotonicPositionFloorUs = maxOf(monotonicPositionFloorUs, position)
            wallClock.pause(position, System.nanoTime())
            pauseAudio()
            transportBufferingVisible = true
        } else if (decision.outputAllowed && transportBufferingVisible) {
            transportBufferingVisible = false
            if (requestedPlay) {
                wallClock.start(monotonicPositionFloorUs, System.nanoTime())
                if (audioRendererConfigured) playAudio()
            }
        }
        return decision.outputAllowed
    }

    private fun publishClockPosition() {
        if (control.released) return
        val nowNs = System.nanoTime()
        if (nowNs - lastStatePublishNs < STATE_PUBLISH_INTERVAL_NS) return
        lastStatePublishNs = nowNs
        val positionUs = currentPositionUs()
        val currentState = mutableState.value
        val positionMs =
            (positionUs / MICROS_PER_MILLISECOND).let { measuredMs ->
                if (currentState.durationMs > 0L) measuredMs.coerceAtMost(currentState.durationMs) else measuredMs
            }
        val transportQoe = demux.transportQoeSnapshot()
        val readAhead = demux.snapshot()
        refreshAdaptiveBufferPlan(readAhead)
        demux.updatePlaybackWindow(
            YTransportPlaybackWindow(
                targetAheadUs = bufferPlan.forwardCacheTargetUs,
                speed = speed,
                bufferedUs = readAhead.bufferedDurationUs,
                minimumWarmBufferUs = minOf(bufferPlan.targetAheadUs / 2L, 8_000_000L),
                playing = requestedPlay && !transportBufferingVisible,
            ),
        )
        // A byte/bitrate estimate is not a timestamp range that can be sought in the queue.
        // Only advertise media timestamps present for every required track.
        val bufferedPositionMs =
            if (!sourceRemote && currentState.durationMs > 0L) {
                currentState.durationMs
            } else {
                val endMs = maxOf(positionMs, readAhead.bufferedThroughUs / MICROS_PER_MILLISECOND)
                if (currentState.durationMs > 0L) endMs.coerceAtMost(currentState.durationMs) else endMs
            }
        val playing =
            requestedPlay && !videoOutputPending && !transportBufferingVisible && !isEnded()
        val buffering =
            requestedPlay && (videoOutputPending || transportBufferingVisible) && !isEnded()
        val renderedFrameRate =
            if (requestedPlay && videoTrackIndex != null && firstVideoFrameRendered && !isEnded()) {
                renderedFrameRateSampler.sample(videoOutputEpoch.renderedFrameCount, SystemClock.elapsedRealtime())
            } else {
                renderedFrameRateSampler.reset()
                null
            }
        // Every other engine counts a rebuffer on the playing -> buffering edge. NativeDirect
        // only counted transport starvation, so a stall that never starved the read-ahead queue
        // — a pump blocked on the origin, a runtime recovery restart, an audio route change —
        // reported a clean session and PlaybackHealth graded it as if nothing had happened.
        val rebuffers =
            rebufferTracker.observe(
                nowNs / NANOS_PER_MILLISECOND,
                requestedPlay && !isEnded(),
                buffering,
                outputHasEverRendered,
            )
        mutableState.update { current ->
            current.copy(
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                subtitleCues = activeSubtitleCues(),
                secondarySubtitleCues = activeSecondarySubtitleCues(),
                secondarySubtitleTrackId = secondarySubtitleId(),
                playing = playing,
                buffering = buffering,
                diagnostics =
                    current.diagnostics.copy(
                        renderer =
                            videoDecoder.anime4KDescription?.let { "$it + AudioTrack" }
                                ?: if (videoTrackIndex == null) "AudioTrack" else "Surface + AudioTrack",
                        bufferEvents = rebuffers.events,
                        rebufferDurationMs = rebuffers.durationMs,
                        longestRebufferMs = rebuffers.longestMs,
                        droppedFrames = droppedFrames,
                        renderedFrameRate = renderedFrameRate,
                        videoDecoderName = videoDecoder.decoderName.orEmpty(),
                        audioDecoderName = audioDecoderDiagnosticName().orEmpty(),
                        droppedFramesMeasured = true,
                        // Long forward-cache coverage includes disk bytes, not queued heap input.
                        sourceQueueBytes = readAhead.queuedBytes,
                        sourceBufferedMs = sourceBufferedMs,
                        sourceStarvationCount = readAhead.starvationCount,
                        // The transport already measures the link; NativeDirect never passed it
                        // on, so the player could not tell a slow server from any other wait.
                        networkBitsPerSecond =
                            transportQoe
                                ?.takeIf { it.throughputMeasured }
                                ?.throughputBitsPerSecond
                                ?: current.diagnostics.networkBitsPerSecond,
                        audioUnderrunCount =
                            maxOf(
                                current.diagnostics.audioUnderrunCount,
                                audioRenderer.underrunCount,
                            ),
                        avSyncOffsetMs = lastAvSyncOffsetUs?.div(MICROS_PER_MILLISECOND),
                        avSyncMeasurement =
                            if (lastAvSyncOffsetUs != null) {
                                "MediaCodec 帧渲染 / AudioTrack 时钟"
                            } else {
                                avSyncMeasurementStatus
                            },
                    ),
            )
        }
        publishQoeSnapshot(nowNs, positionUs)
    }

    private fun publishQoeSnapshot(
        nowNs: Long,
        positionUs: Long,
    ) {
        if (control.released) return
        if (!pacingMetrics.beginPublication(nowNs)) return
        val transportQoe = demux.transportQoeSnapshot()
        val readAhead = demux.snapshot()
        AppLog.info(
            category = "player.core2",
            event = "native_direct_qoe",
            message = "YCore sampled device-local playback pacing",
            attributes =
                mapOf(
                    "decoder" to videoDecoder.decoderName.orEmpty(),
                    "positionMs" to (positionUs / MICROS_PER_MILLISECOND).toString(),
                    "renderedFrames" to pacingMetrics.renderedFrameCount.toString(),
                    "droppedFrames" to droppedFrames.toString(),
                    "longRenderGaps" to pacingMetrics.longRenderGapCount.toString(),
                    "maximumRenderGapMs" to
                        (pacingMetrics.maximumRenderGapNs / NANOS_PER_MILLISECOND).toString(),
                    "audioUnderruns" to audioRenderer.underrunCount.toString(),
                    "audioBackpressure" to pacingMetrics.audioBackpressureCount.toString(),
                    "pendingAudioBytes" to (pendingAudioOutput.current?.data?.remaining() ?: 0).toString(),
                    "audioClockSource" to
                        if (isAudioPassthrough()) {
                            "EncodedAudioTrack"
                        } else {
                            audioRenderer.clockSource
                        },
                    "audioClockStalled" to
                        (!isAudioPassthrough() && audioRenderer.clockStalled).toString(),
                    "slowPumps" to pacingMetrics.slowPumpCount.toString(),
                    "maximumPumpMs" to (pacingMetrics.maximumPumpDurationNs / NANOS_PER_MILLISECOND).toString(),
                    "sourcePrefetchDepth" to (transportQoe?.depthBlocks?.toString() ?: ""),
                    "sourcePrefetchHits" to (transportQoe?.hitCount?.toString() ?: ""),
                    "sourceSynchronousLoads" to
                        (transportQoe?.synchronousLoadCount?.toString() ?: ""),
                    "sourceMaximumWaitMs" to
                        (transportQoe?.maximumResolveWaitMs?.toString() ?: ""),
                    "sourceMaximumLoadMs" to
                        (transportQoe?.maximumRemoteLoadMs?.toString() ?: ""),
                    // Without these two a long sourceMaximumWaitMs cannot be attributed: a
                    // zero load time reads identically whether the bytes came from the disk
                    // cache or the wait happened inside YCore rather than on the network.
                    "sourceMaximumCacheMs" to
                        (transportQoe?.maximumCacheLoadMs?.toString() ?: ""),
                    // A pump that froze on a stalled origin used to leave no trace. Read live
                    // rather than from transportQoe: that snapshot is refreshed on the owner
                    // thread, which is the thread the fetch is blocking.
                    "sourceBlockedReadMs" to demux.blockedForegroundReadMs().toString(),
                    "sourcePromotedPrefetches" to
                        (transportQoe?.promotedPrefetchCount?.toString() ?: ""),
                    "demuxQueuedSamples" to readAhead.queuedSamples.toString(),
                    "demuxQueuedBytes" to readAhead.queuedBytes.toString(),
                    "demuxBufferedMs" to
                        (readAhead.bufferedDurationUs / MICROS_PER_MILLISECOND).toString(),
                    "demuxStarvations" to readAhead.starvationCount.toString(),
                    "rebufferDurationMs" to
                        mutableState.value.diagnostics.rebufferDurationMs
                            .toString(),
                    "longestRebufferMs" to
                        mutableState.value.diagnostics.longestRebufferMs
                            .toString(),
                    "sourceMaximumCacheWriteMs" to (transportQoe?.maximumCacheWriteMs?.toString() ?: ""),
                    "sourceFailedCacheWrites" to (transportQoe?.failedCacheWriteCount?.toString() ?: ""),
                    "sourceDroppedCacheWrites" to (transportQoe?.droppedCacheWriteCount?.toString() ?: ""),
                    "avOffsetMs" to (lastAvSyncOffsetUs?.div(MICROS_PER_MILLISECOND)?.toString() ?: ""),
                    "avMeasurement" to avSyncMeasurementStatus,
                ),
        )
        pacingMetrics.finishPublication()
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
        // Decoder EOS means all PCM was submitted, not that AudioTrack played its tail.
        // Explicit credits skips intentionally bypass this natural-end drain.
        if (!isAudioPassthrough() && audioInputFormat != null && audioRenderer.hasPendingPcm()) {
            val now = System.nanoTime()
            val started = audioDrainStartedNs ?: now.also { audioDrainStartedNs = it }
            if (now - started < 2_000_000_000L) return
            AppLog.warning(
                category = "player.core2",
                event = "next_item_audio_drain_timeout",
                message = "PCM tail did not drain within the bounded end-of-item window",
            )
        }
        audioDrainStartedNs = null
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
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Ended,
                playing = false,
                playbackRequested = false,
                buffering = false,
                positionMs = endPositionUs / MICROS_PER_MILLISECOND,
            )
        }
    }

    private fun isEnded(): Boolean =
        surfaceCompletion.ended(
            decoderOutputEnded = videoOutputEnded,
            audioOutputEnded = audioInputFormat == null || audioOutputEnded,
            pausedPreviewPending = pausedPreview.resumePending,
        )

    private fun resetEndState() {
        audioDrainStartedNs = null
        videoEosGate.reset()
        surfaceCompletion.reset()
        inputEnded = false
        videoInputEnded = videoTrackIndex == null
        audioInputEnded = audioInputFormat == null
        videoOutputEnded = videoTrackIndex == null
        audioOutputEnded = audioInputFormat == null
        pendingVideoOutput = null
        pendingAudioOutput.clearAfterDecoderReset()
        seekPrerollVideoOutput = null
        lastAvSyncOffsetUs = null
        avSyncMeasurementStatus = "等待音视频时钟样本"
        pacingMetrics.resetRenderAnchor()
    }

    private fun configureAudioPath(format: MediaFormat?) {
        audioTrackFormat =
            format?.toCore2AudioTrackFormat(request.items[currentIndex].sourceHints)
        val coreFormat = audioTrackFormat
        audioOutputPath =
            if (
                coreFormat != null &&
                (
                    audioDelayMs != 0L ||
                        audioEffect != YAudioEffect.Off ||
                        requiresPcmAudioPath(
                            protectedContent = drmBinding != null,
                            passthroughRejected =
                                audioTrackIndex?.let(rejectedPassthroughTracks::contains) == true,
                            speed = speed,
                        )
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
                } catch (failure: Exception) {
                    logPassthroughFallback("configure", failure)
                    rejectedPassthroughTracks += requireNotNull(audioTrackIndex)
                    switchPassthroughToPcm(countFailure = true)
                }
            }
            audioOutputPath == YAudioOutputPath.DecodePcm -> {
                audioDecoder.configure(
                    format = format,
                    mediaCrypto = drmBinding?.mediaCrypto,
                    trackFormat = requireNotNull(coreFormat),
                )
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
            return nativeDirectStage(
                YPlaybackFailureCategory.Decoder,
                YPlaybackFailureStage.AudioDecoderQueue,
                "NativeDirect audio access unit",
            ) {
                audioDecoder.queueAccessUnit(data, presentationTimeUs, flags, cryptoInfo)
            }
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
                    // Part of the sample is already in the sink: it has been consumed.
                    inputConsumedSinceReposition = true
                    pendingEncodedAudioInput =
                        YPendingEncodedAudioInput(
                            data = copy,
                            presentationTimeUs = presentationTimeUs,
                        )
                    if (written == 0) pacingMetrics.recordAudioBackpressure()
                    return YCodecQueueResult.TryAgain
                }
            } catch (failure: Exception) {
                logPassthroughFallback("write", failure)
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
            if (written == 0) pacingMetrics.recordAudioBackpressure()
            if (pending.data.hasRemaining()) {
                written > 0
            } else {
                pendingEncodedAudioInput = null
                seekTargetAudioUs = 0L
                verifyEncodedAudioOutput()
                lastQueuedPresentationUs =
                    maxOf(lastQueuedPresentationUs, pending.presentationTimeUs)
                true
            }
        } catch (failure: Exception) {
            logPassthroughFallback("pending_write", failure)
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
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
                        audioOutput = activeAudioOutputLabel(),
                        audioOutputVerified = true,
                        immersiveAudioCarrierOutput = encodedAudioRenderer.immersiveCarrierOutput,
                        dolbyAtmosSourceDetected = audioTrackFormat?.codec.isDolbyAtmosSource(),
                        dolbyAtmosOutputMode = outputMode,
                        audioOutputRoute = encodedAudioRenderer.audioRouteLabel,
                        audioOutputFingerprint = encodedAudioRenderer.audioRouteFingerprint,
                        audioOutputRouteVerified = encodedAudioRenderer.audioRouteVerified,
                        dolbyAtmosOutput = outputMode.encodedPassthrough,
                    ),
            )
        }
    }

    /**
     * A refused passthrough sink is recoverable, so playback carries on as PCM. Diagnostics
     * used to show only "原码不可用"; the refusal itself is what explains the downgrade.
     */
    private fun logPassthroughFallback(
        stage: String,
        failure: Exception,
    ) {
        val format = audioTrackFormat
        AppLog.warning(
            category = "player.core2",
            event = "native_direct_passthrough_pcm_fallback",
            message = "NativeDirect audio passthrough failed; continuing as PCM",
            throwable = failure,
            attributes =
                mapOf(
                    "stage" to stage,
                    "codec" to (format?.codec?.name ?: "unknown"),
                    "channelCount" to (format?.channelCount?.toString() ?: "unknown"),
                    "sampleRate" to (format?.sampleRate?.toString() ?: "unknown"),
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
        audioDecoder.configure(
            format = format,
            mediaCrypto = drmBinding?.mediaCrypto,
            trackFormat = requireNotNull(audioTrackFormat),
        )
        captureAudioRoutingGeneration()
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
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
                            current.diagnostics.audioUnderrunCount +
                                if (countFailure) 1 else 0,
                    ),
            )
        }
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
        val preservePausedVideo = !requestedPlay && pausedPreview.resumePending
        if (pausedPreview.resumePending) {
            AppLog.info(
                category = "player.core2",
                event = "paused_preview_audio_route_changed",
                message = "NativeDirect preserved the paused video frame across an audio route callback",
                attributes = mapOf("frameSubmitted" to pausedPreview.submitted.toString()),
            )
        }
        // Pausing AudioTrack can report a route change after the only preview frame has been
        // submitted. Its Surface and decoder have not changed; invalidating that epoch would
        // reject the late callback and leave no second frame eligible for submission.
        if (!preservePausedVideo) resetVideoRenderEvidence()
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    invalidateNativeDirectAudioRoute(current.diagnostics, preservePausedVideo)
                        .copy(
                            videoOutput =
                                if (preservePausedVideo) {
                                    current.diagnostics.videoOutput
                                } else {
                                    "音频路由已变化 · 等待新帧"
                                },
                            audioOutput = waitingAudioOutputLabel(),
                        ),
            )
        }
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
                val reused =
                    if (isolateVideoTimestamps &&
                        drmBinding == null &&
                        !isAudioPassthrough() &&
                        requestedAnime4KMode() == com.yfuse.core.data.Anime4KMode.Off
                    ) {
                        videoHandoff?.take(videoDecoderReuseKey(format, decoderName), surface)
                    } else {
                        null
                    }
                if (reused != null) {
                    videoDecoder.release()
                    videoDecoder = reused
                    videoDecoder.setPlaybackSpeed(speed)
                } else {
                    // A node handed over or freshly created has not seen this player's speed.
                    videoDecoder.setPlaybackSpeed(speed)
                    videoDecoder.configure(
                        format = format,
                        surface = surface,
                        decoderName = decoderName,
                        mediaCrypto = drmBinding?.mediaCrypto,
                        isolateFrameTimestamps = isolateVideoTimestamps,
                        anime4KContext =
                            appContext.takeIf {
                                plannedDolbyVisionConfig == null && anime4KRequestedFor(request.items[currentIndex])
                            },
                    )
                }
            }
            runtimeCapabilityKey?.let(runtimeCapabilities::recordConfigured)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // Only MediaCodec's own refusal is a strike; a handoff or node-side check is not.
            runtimeCapabilityKey?.let { key -> runtimeCapabilities.recordConfigureFailure(key, failure) }
            throw failure
        }
        frameRateManager.attach(surface, format.directFrameRateHint())
        attachVideoRenderEvidence()
        videoConfigured = true
        // A Surface can arrive after prepare(), when only the audio decoder was known.
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
                        decoder =
                            listOfNotNull(
                                videoDecoder.decoderName,
                                audioDecoderDiagnosticName(),
                            ).joinToString(" + "),
                        videoDecoderName = videoDecoder.decoderName.orEmpty(),
                        audioDecoderName = audioDecoderDiagnosticName().orEmpty(),
                    ),
            )
        }
    }

    private fun resetVideoRenderEvidence() {
        renderedFrameRateSampler.reset()
        videoOutputEpoch.reset()
        firstVideoFrameRendered = false
        if (videoConfigured) attachVideoRenderEvidence()
    }

    private fun attachVideoRenderEvidence() {
        renderedFrameRateSampler.reset()
        val generation = videoOutputEpoch.reset()
        firstVideoFrameRendered = false
        var callbackRejectionLogged = false
        videoDecoder.setOnFrameRenderedListener { presentationTimeUs, realtimeNs ->
            if (control.released ||
                !videoOutputEpoch.rendered(
                    generation,
                    presentationTimeUs,
                    realtimeNs,
                    frameIdentityIsolated = videoDecoder.frameTimestampIdentityIsolated,
                ) {
                    markFirstVideoFrameRendered()
                }
            ) {
                if (!control.released && !callbackRejectionLogged) {
                    callbackRejectionLogged = true
                    AppLog.info(
                        category = "player.core2",
                        event = "native_direct_callback_rejected",
                        message = "NativeDirect rejected a rendered callback outside the current output evidence",
                        attributes =
                            mapOf(
                                "presentationTimeUs" to presentationTimeUs.toString(),
                                "realtimeNs" to realtimeNs.toString(),
                                "callbackGeneration" to generation.toString(),
                                "positionMs" to mutableState.value.positionMs.toString(),
                                "playbackRequested" to requestedPlay.toString(),
                                "inputEnded" to inputEnded.toString(),
                                "videoOutputEnded" to videoOutputEnded.toString(),
                            ),
                    )
                }
                return@setOnFrameRenderedListener
            }
            val renderTimeTrusted = videoOutputEpoch.recordRenderTime(generation, realtimeNs)
            pacingMetrics.recordRenderedFrame(realtimeNs, renderTimeTrusted)
            if (pausedPreview.resumePending) {
                AppLog.info(
                    category = "player.core2",
                    event = "paused_preview_rendered",
                    message = "NativeDirect received actual rendering evidence for the paused seek frame",
                    attributes = mapOf("presentationTimeUs" to presentationTimeUs.toString()),
                )
            }
            if (!runtimeRenderRecorded) {
                runtimeRenderRecorded = true
                runtimeCapabilityKey?.let(runtimeCapabilities::recordRendered)
            }
            val audioClock = if (renderTimeTrusted) audioClockSnapshot() else null
            avSyncMeasurementStatus =
                when {
                    !renderTimeTrusted -> "视频渲染时间戳不可用，无法测量音画偏差"
                    audioTrackIndex == null -> "无音频轨，不计算音画偏差"
                    audioClock == null -> "等待有效的 AudioTrack 时钟"
                    else -> "MediaCodec 帧渲染 / AudioTrack 时钟"
                }
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
    }

    private fun markFirstVideoFrameRendered() {
        if (firstVideoFrameRendered || control.released) return
        firstVideoFrameRendered = true
        outputHasEverRendered = true
        outputLifecycle.videoRendered()
        mutableState.updateState { current ->
            if (control.released) {
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
                            videoOutput = videoDecoder.anime4KDescription ?: "Surface 直出",
                            videoOutputVerified = true,
                            dolbyVisionOutput = nativeDolbyVisionOutputVerified(),
                        ),
                )
            }
        }
    }

    private fun onTransportBlockingReadStateChanged(blocked: Boolean) {
        if (control.released) return
        transportReadBlocked = blocked
        ++transportBlockGeneration
    }

    private fun abortIfReleased() {
        if (control.released || !scope.isActive) {
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
            ->
                if (encodedAudioRenderer.immersiveCarrierOutput) {
                    "沉浸音频载波 · AudioTrack（未验证对象输出）"
                } else {
                    "原码直通 · AudioTrack"
                }
        }

    private fun selectedDemuxTrackIndices(): Set<Int> =
        buildSet {
            videoTrackIndex?.let { add(it) }
            audioTrackIndex?.let { add(it) }
            subtitleTrackIndex?.let { add(it) }
            secondarySubtitleTrackIndex?.let { add(it) }
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

    private fun secondarySubtitleId(): String? =
        secondaryExternalSubtitleId
            ?: secondarySubtitleTrackIndex?.let { "$SUBTITLE_TRACK_PREFIX$it" }

    private fun activeSecondarySubtitleCues(): List<YSubtitleCue> =
        secondaryExternalSubtitleId
            ?.let { id -> externalSubtitles.firstOrNull { it.track.id == id }?.cues }
            ?: secondarySubtitleCues.toList()

    private fun sourceFailureCategory(): YPlaybackFailureCategory =
        if (sourceRemote) YPlaybackFailureCategory.Network else YPlaybackFailureCategory.Container

    private fun queueSubtitleSample(
        trackIndex: Int,
        data: ByteBuffer,
        presentationTimeUs: Long,
    ) {
        val format = demux.trackFormat(trackIndex).subtitleFormatOrNull() ?: return
        val cues = if (trackIndex == secondarySubtitleTrackIndex) secondarySubtitleCues else subtitleCues
        val bytes = ByteArray(data.remaining())
        data.duplicate().get(bytes)
        appendUntimedTextSubtitlePacket(
            cues = cues,
            data = bytes,
            format = format,
            presentationTimeUs = presentationTimeUs,
            id = "$trackIndex:$presentationTimeUs",
        )
        val oldestRetainedUs = currentPositionUs() - SUBTITLE_HISTORY_US
        cues.removeAll { cue -> cue.endUs < oldestRetainedUs }
    }

    /**
     * Releases a decode-side failure's pipeline and reports whether the source stayed open.
     *
     * A retry of the same item used to reopen the extractor and a new transport, downloading the
     * head of the file again (2 MB, 1.35 s on the tablet in incident E) for a failure that was the
     * decoder's or the AudioTrack's. Such a failure now keeps the extractor and its transport, and
     * block cache, for [prepareCurrent]; anything that implicates the source still closes it.
     */
    fun releaseMediaAfterFailure(failure: Throwable): Boolean {
        val keepSource = prepared && nativeDirectFailureKeepsSource(failure)
        releaseMedia(keepSource = keepSource)
        retainedSourceIndex = currentIndex.takeIf { keepSource }
        return keepSource
    }

    fun releaseMedia(keepSource: Boolean = false) {
        if (!keepSource) retainedSourceIndex = null
        val stats = rebufferTracker.stop(control.releasedAtMs ?: System.nanoTime() / 1_000_000L)
        mutableState.update {
            it.copy(
                diagnostics =
                    it.diagnostics.copy(
                        bufferEvents = stats.events,
                        rebufferDurationMs = stats.durationMs,
                        longestRebufferMs = stats.longestMs,
                    ),
            )
        }
        externalSubtitleSession.close()
        videoOutputEpoch.reset()
        pausedPreview.clear()
        seekVideoSubmissionPending = false
        pendingVideoOutput?.let { output ->
            runCatching { videoDecoder.releaseOutput(output, render = false) }
        }
        pendingVideoOutput = null
        releasePendingAudioOutput()
        pendingEncodedAudioInput = null
        runCatching(audioRenderer::release)
        runCatching(encodedAudioRenderer::release)
        runCatching(audioDecoder::release)
        val retainedVideo =
            runCatching {
                val surface = surfaceOutput?.surface
                val key = videoFormat?.let { videoDecoderReuseKey(it, decoderName) }
                if (control.videoHandoffRequested &&
                    !videoDecoder.anime4KActive &&
                    isolateVideoTimestamps &&
                    videoConfigured &&
                    drmBinding == null &&
                    !isAudioPassthrough() &&
                    key != null &&
                    surface?.isValid == true &&
                    videoHandoff != null
                ) {
                    videoHandoff.offer(key, surface, videoDecoder)
                    videoDecoder = AndroidMediaCodecVideoNode()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        control.videoHandoffRequested = false
        if (!retainedVideo) runCatching(videoDecoder::release)
        runCatching { drmSession?.close() }
        drmBinding = null
        drmSession = null
        if (!retainedVideo) frameRateManager.clear()
        if (keepSource) runCatching(demux::pauseReadAhead) else runCatching(demux::release)
        prepared = false
        videoConfigured = false
        audioRendererConfigured = false
        videoTrackIndex = null
        audioTrackIndex = null
        subtitleTrackIndex = null
        secondarySubtitleTrackIndex = null
        secondaryExternalSubtitleId = null
        subtitleCues.clear()
        secondarySubtitleCues.clear()
        externalSubtitles = emptyList()
        selectedExternalSubtitleId = null
        sourceRemote = false
        sourceBitRateBitsPerSecond = 0L
        lastBufferReplanNs = 0L
        lastBufferGatePositionUs = 0L
        outputLifecycle.resetMedia()
        videoFormat = null
        inspectHdr10PlusSamples = false
        hdrAccessUnits.clear()
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
        pacingMetrics.resetMedia()
        resetEndState()
        mutableState.update { current ->
            current.copy(
                diagnostics =
                    current.diagnostics.copy(
                        videoOutputVerified = false,
                        audioOutputVerified = false,
                        avSyncOffsetMs = null,
                        avSyncMeasurement = "等待音视频时钟样本",
                    ),
            )
        }
    }

    fun releaseAll() {
        releaseMedia()
        demux.close()
        surfaceOutput = null
    }

    fun cancelPendingRead() = demux.cancelPendingRead()

    fun abortPendingReads() = demux.abortPendingReads()

    private fun releasePendingAudioOutput() {
        pendingAudioOutput.discard()
    }
}

private data class YPendingEncodedAudioInput(
    val data: ByteBuffer,
    val presentationTimeUs: Long,
)

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    update(transform)
}

private const val VIDEO_MIME_PREFIX = "video/"
private const val AUDIO_MIME_PREFIX = "audio/"
private const val AUDIO_TRACK_PREFIX = "audio:"
private const val SUBTITLE_TRACK_PREFIX = "subtitle:"
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
private const val MIN_SAMPLE_BUFFER_BYTES = 256 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val BUFFER_REPLAN_INTERVAL_NS = 2_000_000_000L
private const val MAX_VIDEO_SYNC_SAMPLE_DROPS = 600
private const val LATE_FRAME_DROP_NS = 100_000_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val SUBTITLE_HISTORY_US = 60_000_000L
