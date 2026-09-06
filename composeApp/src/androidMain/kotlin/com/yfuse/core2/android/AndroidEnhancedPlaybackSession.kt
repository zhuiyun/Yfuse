package com.yfuse.core2.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.api.YDualDolbyEvidenceState
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSampleFlag
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.FailClosedYDolbyVisionFelEvidenceProvider
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.dolby.YDolbyVisionFelCompositionRequest
import com.yfuse.core2.dolby.YDolbyVisionFelEvidenceProvider
import com.yfuse.core2.dolby.dolbyVisionHevcBaseLayerSample
import com.yfuse.core2.dolby.verifyDolbyVisionFelComposition
import com.yfuse.core2.network.YBufferConditions
import com.yfuse.core2.network.YBufferController
import com.yfuse.core2.network.YPlaybackBufferGate
import com.yfuse.core2.recovery.requiresPcmAudioPath
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.render.YScalingFilter
import com.yfuse.core2.render.gpuColorPipelineConfig
import com.yfuse.core2.render.videoFrameRateHint
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import com.yfuse.core2.subtitle.YEmbeddedSubtitleDecoder
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.sync.YAvSync
import com.yfuse.core2.sync.YClockSnapshot
import com.yfuse.core2.sync.YMediaClock

internal data class YEnhancedPlaybackSnapshot(
    val positionUs: Long,
    val durationUs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val firstVideoFrameRendered: Boolean,
    val outputHdrType: YHdrType,
    val softwareVideoToneMapped: Boolean,
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val audioRendering: Boolean,
    val audioPassthrough: Boolean,
    val immersiveAudioCarrierOutput: Boolean,
    val dolbyAtmosSourceDetected: Boolean,
    val dolbyAtmosOutputMode: YDolbyAtmosOutputMode,
    val audioOutputRoute: String,
    val audioOutputRouteVerified: Boolean,
    val dolbyAtmosOutput: Boolean,
    val spatialAudioOutput: Boolean,
    val headTrackingAvailable: Boolean,
    val audioFallbackCount: Int,
    val audioUnderrunCount: Int,
    val audioSinkDiagnostics: Map<String, String>,
    val droppedFrames: Int,
    val avSyncOffsetUs: Long?,
    val sourceQueueBytes: Long,
    val sourceBufferedUs: Long,
    val sourceStarvationCount: Long,
    val sourceNetworkBitsPerSecond: Long,
    val subtitleCues: List<YSubtitleCue>,
    val nativeGpuFeatureMask: Long = 0L,
    val gpuFrameDurationNs: Long = 0L,
    val dolbyVisionRpuApplied: Boolean = false,
    val dolbyVisionEnhancementLayerDelivered: Boolean = false,
    val dolbyVisionFelComposed: Boolean = false,
    val outputEvidenceGeneration: Long = 0L,
    val outputEvidenceResetReason: YOutputEvidenceResetReason = YOutputEvidenceResetReason.Initial,
)

/**
 * Single-owner media worker for the Core2 NativeEnhanced graph.
 *
 * This class intentionally contains no Compose/UI code. The demuxer yields compressed samples;
 * hardware and platform-software codecs go through MediaCodec, while the terminal compatibility
 * route decodes through the optional FFmpeg extension and presents bounded BGRA/PCM frames. All
 * methods must be called serially from one playback worker.
 */
internal class AndroidEnhancedPlaybackSession(
    context: Context,
    private val demuxer: YDemuxer = AndroidFfmpegDemuxer(),
    private val videoDecoder: AndroidMediaCodecVideoNode = AndroidMediaCodecVideoNode(),
    private val audioDecoder: AndroidMediaCodecAudioNode = AndroidMediaCodecAudioNode(),
    private val audioRenderer: AndroidAudioTrackRenderNode = AndroidAudioTrackRenderNode(context),
    private val encodedAudioRenderer: AndroidEncodedAudioTrackRenderNode = AndroidEncodedAudioTrackRenderNode(),
    private val runtimeCapabilities: AndroidRuntimeCapabilityRegistry? = null,
    private val felEvidenceProvider: YDolbyVisionFelEvidenceProvider =
        FailClosedYDolbyVisionFelEvidenceProvider,
    frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val preferredRemoteBufferTargetUs: Long? = null,
) {
    private val demuxReadAhead = AndroidDemuxReadAheadNode(demuxer)
    private val wallClock = YMediaClock()
    private val frameRateManager = AndroidFrameRateManager(context, frameRateSwitchMode)
    private val capabilityProvider = AndroidYCapabilityProvider(context)
    private val gpuEvidenceStore = AndroidYCoreGpuEvidenceStore(context)
    private val displayPeakNits = context.yCoreDisplayPeakNits()

    private var plan: YPlaybackPlan? = null
    private var sourceRemote = false
    private var openResult: YDemuxOpenResult? = null
    private var sourceVideoTrack: YDemuxTrack? = null
    private var effectiveVideoTrack: YVideoTrackFormat? = null
    private var audioTrack: YDemuxTrack? = null
    private var subtitleTrack: YDemuxTrack? = null
    private val subtitleCues = mutableListOf<YSubtitleCue>()
    private var audioOutputPath = YAudioOutputPath.None
    private var surface: Surface? = null
    private var pendingSample: YCompressedSample? = null
    private var pendingVideoOutput: YCodecOutputResult.Buffer? = null
    private var pendingAudioOutput: YEnhancedPendingAudioOutput? = null
    private var pendingEncodedAudioData: java.nio.ByteBuffer? = null
    private var pendingSoftwareVideoOutput: YSoftwareVideoDecodeResult.Frame? = null
    private var pendingSoftwareAudioOutput: YSoftwareAudioDecodeResult.Frame? = null
    private var softwareDecoder: AndroidFfmpegSoftwareDecoderNode? = null
    private val softwareVideoRenderer = AndroidSoftwareVideoRenderNode()
    private var gpuVideoOutput: AndroidVulkanVideoOutput? = null
    private var softwareVideoActive = false
    private var softwareAudioActive = false
    private var playing = false
    private var outputActive = false
    private var prepared = false
    private var audioRendererConfigured = false
    private val rejectedPassthroughTracks = mutableSetOf<YTrackId>()
    private var inputEnded = false
    private var videoInputEnded = false
    private var audioInputEnded = false
    private var videoOutputEnded = false
    private var audioOutputEnded = false
    private var softwareVideoDecodeEnded = false
    private var softwareRenderedFrameCountSeen = 0
    private var firstVideoFrameRendered = false
    private var seekPrerollVideoOutput: YCodecOutputResult.Buffer? = null
    private var emptyTailSeekRetries = 0
    private var droppedFrames = 0
    private var audioFallbackCount = 0
    private var observedAudioRoutingGeneration = 0L
    private var runtimeRenderRecorded = false
    private var gpuEvidenceRecorded = false
    private var runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null
    private var renderCallbackGeneration = 0
    private var p7RpuQueued = false
    private var p7EnhancementLayerQueued = false
    private var dualDolbyEvidence = YDualDolbyEvidenceState()

    @Volatile
    private var lastAvSyncOffsetUs: Long? = null

    private var seekVideoTargetUs = 0L
    private var seekAudioTargetUs = 0L
    private var lastQueuedUs = 0L
    private var lastVideoUs = 0L
    private var maxInputAheadUs = DEFAULT_INPUT_AHEAD_US
    private var bufferPlan = YBufferController.plan(YBufferConditions(remote = false))
    private var bufferGate = YPlaybackBufferGate(remote = false, resumePlaybackUs = 0L)
    private var lastBufferReplanNs = 0L

    @Volatile
    private var speed = 1f

    fun open(
        source: YDemuxSource,
        plan: YPlaybackPlan,
        surface: Surface,
        startPositionUs: Long = 0L,
        runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
        requireDolbyVisionIdentity: Boolean = false,
        expectedAudio: Boolean = false,
        sourceHints: YMediaSourceHints? = null,
    ): YDemuxOpenResult {
        close()
        dualDolbyEvidence = dualDolbyEvidence.invalidate(YOutputEvidenceResetReason.SourceChanged)
        this.runtimeCapabilityKey = runtimeCapabilityKey
        require(
            plan.route in
                setOf(
                    YPlaybackRoute.NativeEnhanced,
                    YPlaybackRoute.GpuEnhanced,
                    YPlaybackRoute.SoftwareFallback,
                ),
        ) {
            "Enhanced session requires an enhanced route"
        }
        require(plan.renderPath in setOf(YRenderPath.SurfaceDirect, YRenderPath.Gpu)) {
            "Enhanced session render path is unsupported"
        }
        require(surface.isValid) { "Enhanced session requires a valid Surface" }

        val remote = source.uri.isCore2RemoteMediaUri()
        val result =
            yPlaybackStage(
                category = if (remote) YPlaybackFailureCategory.Network else YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.SourceOpen,
                safeDetail = "Enhanced source open",
            ) {
                demuxReadAhead.open(source)
            }
        val videoTrack =
            result.tracks.firstOrNull { it.type == YDemuxTrackType.Video && it.video != null }
                ?: throw YPlaybackException(
                    category = YPlaybackFailureCategory.Container,
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "Enhanced demux contains no video track",
                )
        val capabilities = capabilityProvider.current()
        val softwareAudioAvailable = (demuxer as? AndroidFfmpegDemuxer)?.softwareDecodeAvailable == true
        val audioSelection =
            selectEnhancedAudioTrack(
                tracks = result.tracks,
                capabilities = capabilities,
                plan = plan,
                softwareDecodeAvailable = softwareAudioAvailable,
            )
        val audioTrack = audioSelection?.track
        if (expectedAudio && result.tracks.none { it.type == YDemuxTrackType.Audio && it.audio != null }) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Demux,
                safeDetail = hiddenServerAudioTrackDetail(ENHANCED_HIDDEN_AUDIO_DETAIL, sourceHints),
            )
        }
        if (
            result.tracks.any { it.type == YDemuxTrackType.Audio && it.audio != null } &&
            audioTrack == null
        ) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderConfigure,
                safeDetail = "Enhanced route has no playable audio decoder",
            )
        }
        val initialAudioOutputPath = audioSelection?.outputPath ?: YAudioOutputPath.None
        audioSelection?.let { selection ->
            AppLog.info(
                category = "player.core2",
                event = "enhanced_audio_route_resolved",
                message = "YCore resolved audio from the opened demux tracks",
                attributes =
                    mapOf(
                        "codec" to requireNotNull(selection.track.audio).codec.name,
                        "plannedAudioPath" to plan.audioPath.name,
                        "plannedSoftwareAudio" to plan.softwareAudioDecode.toString(),
                        "selectedAudioPath" to selection.outputPath.name,
                        "selectedSoftwareAudio" to selection.preferSoftware.toString(),
                        "softwareAudioAvailable" to softwareAudioAvailable.toString(),
                        "videoDecodePath" to plan.decodePath.name,
                        "inputHdr" to plan.inputHdrType.name,
                    ),
            )
        }
        val sourceVideo = requireNotNull(videoTrack.video)
        validateEnhancedDolbyVisionIdentity(
            required = requireDolbyVisionIdentity,
            config = sourceVideo.dolbyVisionConfig,
        )
        val effectiveVideo =
            yPlaybackStage(
                category = YPlaybackFailureCategory.Container,
                stage = YPlaybackFailureStage.Bitstream,
                safeDetail = "Enhanced video format validation",
            ) {
                effectiveVideoTrack(sourceVideo, plan)
            }
        softwareVideoActive = plan.decodePath == YDecodePath.Software
        softwareAudioActive = audioSelection?.preferSoftware == true
        val softwareNode =
            if (softwareVideoActive || softwareAudioActive) {
                val ffmpegDemuxer =
                    demuxer as? AndroidFfmpegDemuxer
                        ?: error("FFmpeg software decode requires the native FFmpeg demuxer")
                AndroidFfmpegSoftwareDecoderNode(ffmpegDemuxer).also {
                    check(it.available) { "YCore FFmpeg software decoder extension is unavailable" }
                    softwareDecoder = it
                }
            } else {
                null
            }

        var videoConfiguredForProbe = false
        try {
            if (softwareVideoActive) {
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.VideoDecoderConfigure,
                    safeDetail = "FFmpeg software video decoder configure",
                ) {
                    requireNotNull(softwareNode).configureVideo(
                        trackId = videoTrack.id,
                        toneMapHdrToSdr = plan.softwareVideoToneMap,
                    )
                    softwareVideoRenderer.attach(surface)
                }
            } else {
                val decoderSurface =
                    if (plan.route == YPlaybackRoute.GpuEnhanced) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val sourcePeakNits =
                                effectiveVideo.hdrStaticMetadata?.let { metadata ->
                                    maxOf(metadata.maxContentLightLevel, metadata.maxDisplayLuminance)
                                        .takeIf { it > 0 }
                                        ?.toFloat()
                                }
                            AndroidVulkanVideoOutput(
                                width = effectiveVideo.width,
                                height = effectiveVideo.height,
                                target = surface,
                                colorConfig =
                                    gpuColorPipelineConfig(
                                        sourceHdrType = effectiveVideo.hdrType,
                                        outputHdrType = plan.outputHdrType,
                                        bitDepth = effectiveVideo.bitDepth,
                                        staticPeakNits = sourcePeakNits,
                                        displayPeakNits = displayPeakNits,
                                        scalingFilter = YScalingFilter.Lanczos,
                                        sourceRange = effectiveVideo.colorRange,
                                        sourceMatrix = effectiveVideo.colorMatrix,
                                        sourcePrimaries = effectiveVideo.colorPrimaries,
                                        chromaLocation = effectiveVideo.chromaLocation,
                                        geometry = effectiveVideo.geometry,
                                        hdrStaticMetadata = effectiveVideo.hdrStaticMetadata,
                                    ),
                            ).also {
                                check(it.isReady) { "Vulkan swapchain/ImageReader output is unavailable" }
                                gpuVideoOutput = it
                            }.decoderSurface
                        } else {
                            error("GpuEnhanced requires Android 9 HardwareBuffer")
                        }
                    } else {
                        surface
                    }
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.VideoDecoderConfigure,
                    safeDetail = "Enhanced video decoder configure",
                ) {
                    videoDecoder.configure(
                        AndroidMediaFormatFactory.video(effectiveVideo),
                        decoderSurface,
                        plan.decoderName,
                    )
                }
                videoConfiguredForProbe = true
                runtimeCapabilityKey?.let { runtimeCapabilities?.recordConfigured(it) }
                if (plan.route != YPlaybackRoute.GpuEnhanced) attachVideoRenderEvidence()
            }
            frameRateManager.attach(surface, videoFrameRateHint(effectiveVideo.frameRate))
            audioTrack?.audio?.let { format ->
                when (initialAudioOutputPath) {
                    YAudioOutputPath.DecodePcm ->
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.Decoder,
                            stage = YPlaybackFailureStage.AudioDecoderConfigure,
                            safeDetail = "Enhanced audio decoder configure",
                        ) {
                            softwareAudioActive = configurePcmDecoder(audioTrack, softwareAudioActive)
                        }
                    YAudioOutputPath.Passthrough -> {
                        try {
                            yPlaybackStage(
                                category = YPlaybackFailureCategory.AudioSink,
                                stage = YPlaybackFailureStage.AudioRenderer,
                                safeDetail = "Enhanced encoded audio configure",
                            ) {
                                encodedAudioRenderer.configure(
                                    format,
                                    exactDolbyAtmosTransport =
                                        capabilityProvider
                                            .current()
                                            .hasExactDolbyAtmosPassthrough(format.codec),
                                )
                            }
                        } catch (_: Exception) {
                            rejectedPassthroughTracks += audioTrack.id
                            audioFallbackCount++
                            audioOutputPath = YAudioOutputPath.DecodePcm
                            softwareAudioActive =
                                yPlaybackStage(
                                    category = YPlaybackFailureCategory.Decoder,
                                    stage = YPlaybackFailureStage.AudioDecoderConfigure,
                                    safeDetail = "Enhanced PCM fallback decoder configure",
                                ) {
                                    configurePcmDecoder(audioTrack, preferSoftware = false)
                                }
                        }
                    }
                    YAudioOutputPath.None -> error("Enhanced route selected an audio track without an output path")
                }
            }
            demuxReadAhead.selectTracks(
                buildSet {
                    add(videoTrack.id)
                    audioTrack?.let { add(it.id) }
                },
            )
        } catch (throwable: Throwable) {
            if (!softwareVideoActive && !videoConfiguredForProbe) {
                runtimeCapabilityKey?.let { runtimeCapabilities?.recordRejected(it) }
            }
            frameRateManager.clear()
            runCatching(videoDecoder::release)
            runCatching(audioDecoder::release)
            runCatching(encodedAudioRenderer::release)
            runCatching(softwareVideoRenderer::release)
            runCatching { softwareDecoder?.release() }
            softwareDecoder = null
            runCatching(demuxReadAhead::close)
            throw throwable
        }

        this.plan = plan
        sourceRemote = remote
        this.openResult = result
        this.sourceVideoTrack = videoTrack
        this.effectiveVideoTrack = effectiveVideo
        this.audioTrack = audioTrack
        if (audioTrack == null) {
            audioOutputPath = YAudioOutputPath.None
        } else if (audioOutputPath == YAudioOutputPath.None) {
            audioOutputPath = initialAudioOutputPath
        }
        this.surface = surface
        bufferPlan =
            YBufferController
                .plan(
                    YBufferConditions(
                        remote = remote,
                        mediaBitRateBitsPerSecond = result.bitRateBitsPerSecond,
                        preferredTargetAheadUs = preferredRemoteBufferTargetUs,
                    ),
                )
        lastBufferReplanNs = 0L
        maxInputAheadUs = bufferPlan.targetAheadUs
        bufferGate =
            YPlaybackBufferGate(
                remote = remote,
                resumePlaybackUs = bufferPlan.resumePlaybackUs,
            )
        demuxReadAhead.configure(
            targetAheadUs = maxInputAheadUs,
            mediaBitRateBitsPerSecond = result.bitRateBitsPerSecond,
        )
        audioRendererConfigured = audioTrack != null && audioOutputPath == YAudioOutputPath.Passthrough
        captureAudioRoutingGeneration()
        prepared = true
        resetEndState()
        if (startPositionUs > 0L) {
            seekTo(startPositionUs)
        } else {
            wallClock.seek(0L, System.nanoTime())
        }
        return result
    }

    fun selectedAudioTrackId(): YTrackId? = audioTrack?.id

    fun play() {
        check(prepared) { "Enhanced session is not prepared" }
        if (ended()) {
            seekTo(0L)
        }
        playing = true
        refreshOutputGate()
    }

    fun pause() {
        if (!prepared) return
        val position = currentPositionUs()
        playing = false
        outputActive = false
        pauseAudio()
        wallClock.pause(position, System.nanoTime())
    }

    fun setSpeed(value: Float) {
        require(value.isFinite() && value > 0f) { "Playback speed must be finite and positive" }
        val position = currentPositionUs()
        speed = value
        wallClock.setSpeed(value, position, System.nanoTime())
        if (isAudioPassthrough() && requiresPcmAudioPath(false, false, value)) {
            switchPassthroughToPcm(position, countFailure = false)
            return
        }
        if (audioRendererConfigured && !isAudioPassthrough()) audioRenderer.setSpeed(value)
    }

    fun setOutputSurface(next: Surface) {
        require(next.isValid) { "Output Surface is invalid" }
        check(prepared) { "Enhanced session is not prepared" }
        dualDolbyEvidence = dualDolbyEvidence.invalidate(YOutputEvidenceResetReason.SurfaceChanged)
        yPlaybackStage(
            category = YPlaybackFailureCategory.Renderer,
            stage = YPlaybackFailureStage.VideoRenderer,
            safeDetail = "Enhanced Surface rebind",
        ) {
            // A frame proven on the previous Surface cannot prove output on its replacement.
            firstVideoFrameRendered = false
            runtimeRenderRecorded = false
            gpuEvidenceRecorded = false
            p7RpuQueued = false
            p7EnhancementLayerQueued = false
            if (softwareVideoActive) {
                softwareVideoRenderer.attach(next)
                softwareRenderedFrameCountSeen = 0
            } else if (gpuVideoOutput != null) {
                check(requireNotNull(gpuVideoOutput).setTargetSurface(next)) {
                    "Vulkan output Surface recreation failed"
                }
            } else {
                videoDecoder.setOutputSurface(next)
            }
        }
        frameRateManager.reattach(next)
        surface = next
    }

    fun selectAudioTrack(
        trackId: YTrackId,
        capabilities: YDeviceCapabilities,
    ) {
        check(prepared) { "Enhanced session is not prepared" }
        val nextTrack =
            requireNotNull(openResult)
                .tracks
                .firstOrNull { it.id == trackId && it.type == YDemuxTrackType.Audio && it.audio != null }
                ?: error("Selected audio track is unavailable")
        val format = requireNotNull(nextTrack.audio)
        val devicePath =
            capabilities.audioOutputPath(
                YAudioRequirement(
                    codec = format.codec,
                    channelCount = format.channelCount,
                    sampleRate = format.sampleRate,
                ),
            )
        val passthroughRejected = trackId in rejectedPassthroughTracks
        var nextPath =
            if (
                devicePath == YAudioOutputPath.Passthrough &&
                requiresPcmAudioPath(false, passthroughRejected, speed)
            ) {
                YAudioOutputPath.DecodePcm
            } else {
                devicePath
            }
        var nextUsesSoftware = devicePath == YAudioOutputPath.None
        if (nextUsesSoftware) nextPath = YAudioOutputPath.DecodePcm
        require(nextPath != YAudioOutputPath.None) { "Selected audio track has no device output path" }
        if (
            audioTrack?.id == trackId &&
            audioOutputPath == nextPath &&
            softwareAudioActive == nextUsesSoftware
        ) {
            return
        }

        dualDolbyEvidence = dualDolbyEvidence.invalidate(YOutputEvidenceResetReason.AudioTrackChanged)

        pauseAudio()
        demuxReadAhead.pauseReadAhead()
        releasePendingAudioOutput()
        pendingEncodedAudioData = null
        pendingSoftwareAudioOutput = null
        runCatching(audioRenderer::release)
        runCatching(encodedAudioRenderer::release)
        runCatching(audioDecoder::release)
        audioRendererConfigured = false
        when (nextPath) {
            YAudioOutputPath.DecodePcm ->
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.AudioDecoderConfigure,
                    safeDetail = "Enhanced audio track switch decoder configure",
                ) {
                    nextUsesSoftware = configurePcmDecoder(nextTrack, nextUsesSoftware)
                }
            YAudioOutputPath.Passthrough -> {
                try {
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.AudioSink,
                        stage = YPlaybackFailureStage.AudioRenderer,
                        safeDetail = "Enhanced audio track switch sink configure",
                    ) {
                        encodedAudioRenderer.configure(
                            format,
                            exactDolbyAtmosTransport = capabilities.hasExactDolbyAtmosPassthrough(format.codec),
                        )
                    }
                    audioRendererConfigured = true
                } catch (_: Exception) {
                    rejectedPassthroughTracks += trackId
                    audioFallbackCount++
                    nextPath = YAudioOutputPath.DecodePcm
                    nextUsesSoftware =
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.Decoder,
                            stage = YPlaybackFailureStage.AudioDecoderConfigure,
                            safeDetail = "Enhanced switched-track PCM fallback configure",
                        ) {
                            configurePcmDecoder(nextTrack, preferSoftware = false)
                        }
                }
            }
            YAudioOutputPath.None -> error("Selected audio track has no output path")
        }
        audioTrack = nextTrack
        audioOutputPath = nextPath
        softwareAudioActive = nextUsesSoftware
        captureAudioRoutingGeneration()
        demuxReadAhead.selectTracks(selectedTrackIds())
        if (sourceRemote) {
            bufferGate.reset()
            suspendOutputForBuffering()
        }
        audioInputEnded = false
        audioOutputEnded = false
        seekAudioTargetUs = currentPositionUs()
        lastAvSyncOffsetUs = null
        if (outputActive && audioRendererConfigured) playAudio()
    }

    fun selectSubtitleTrack(trackId: YTrackId?) {
        check(prepared) { "Enhanced session is not prepared" }
        val position = currentPositionUs()
        val nextTrack =
            trackId?.let { selectedId ->
                requireNotNull(openResult)
                    .tracks
                    .firstOrNull {
                        it.id == selectedId &&
                            it.type == YDemuxTrackType.Subtitle &&
                            it.subtitle?.format?.let { format ->
                                format.textOverlaySupported ||
                                    demuxReadAhead.supportsSubtitleFormat(format)
                            } == true
                    } ?: error("Selected subtitle track is unavailable for the Core2 text overlay")
            }
        if (subtitleTrack?.id == nextTrack?.id) return
        subtitleTrack = nextTrack
        subtitleCues.clear()
        pendingSample =
            pendingSample?.takeUnless { sample ->
                sample.trackId != sourceVideoTrack?.id && sample.trackId != audioTrack?.id
            }
        demuxReadAhead.selectTracks(selectedTrackIds())
        if (nextTrack != null) {
            seekToInternal(position, tailRetry = false, resetVideoDecoder = false)
        }
    }

    fun seekTo(positionUs: Long) {
        seekToInternal(
            positionUs = positionUs,
            tailRetry = false,
            resetVideoDecoder = false,
        )
    }

    private fun seekToInternal(
        positionUs: Long,
        tailRetry: Boolean,
        resetVideoDecoder: Boolean,
    ) {
        check(prepared) { "Enhanced session is not prepared" }
        dualDolbyEvidence = dualDolbyEvidence.invalidate(YOutputEvidenceResetReason.Seek)
        val target = positionUs.coerceAtLeast(0L)
        if (!tailRetry) emptyTailSeekRetries = 0
        yPlaybackStage(
            category = sourceFailureCategory(),
            stage = YPlaybackFailureStage.Seek,
            safeDetail = "Enhanced source seek",
        ) {
            demuxReadAhead.seekTo(target)
        }
        releasePendingAudioOutput()
        pendingEncodedAudioData = null
        pendingSoftwareAudioOutput = null
        if (softwareVideoActive) softwareVideoRenderer.flush()
        if (softwareVideoActive || softwareAudioActive) softwareDecoder?.flush()
        if (!softwareVideoActive) {
            if (resetVideoDecoder) {
                videoDecoder.release()
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Decoder,
                    stage = YPlaybackFailureStage.VideoDecoderConfigure,
                    safeDetail = "Enhanced video decoder reset after empty seek",
                ) {
                    videoDecoder.configure(
                        AndroidMediaFormatFactory.video(requireNotNull(effectiveVideoTrack)),
                        gpuVideoOutput?.decoderSurface
                            ?: requireNotNull(surface).also { check(it.isValid) },
                        requireNotNull(plan).decoderName,
                    )
                }
                runtimeCapabilityKey?.let { runtimeCapabilities?.recordConfigured(it) }
                if (requireNotNull(plan).route != YPlaybackRoute.GpuEnhanced) {
                    attachVideoRenderEvidence()
                }
            } else {
                videoDecoder.flush()
            }
        }
        if (audioTrack != null && !isAudioPassthrough() && !softwareAudioActive) audioDecoder.flush()
        if (audioRendererConfigured) flushAudio()
        pendingSample = null
        pendingVideoOutput = null
        pendingSoftwareVideoOutput = null
        subtitleCues.clear()
        resetEndState()
        seekVideoTargetUs = target
        seekAudioTargetUs = target
        lastQueuedUs = target
        lastVideoUs = target
        firstVideoFrameRendered = false
        runtimeRenderRecorded = false
        p7RpuQueued = false
        p7EnhancementLayerQueued = false
        wallClock.seek(target, System.nanoTime())
        outputActive = false
        bufferGate.reset()
        if (!playing) wallClock.pause(target, System.nanoTime())
    }

    /** Runs one bounded non-blocking media iteration. */
    fun pump(): Boolean {
        if (!prepared || !playing || ended()) return false
        var didWork = handleAudioRoutingChange()
        if (!refreshOutputGate()) return didWork
        didWork = collectSoftwareRenderProgress() || didWork
        didWork = drainAudio() || didWork
        didWork = drainVideo() || didWork
        didWork = feedInput() || didWork
        check(gpuVideoOutput?.measurementFailed != true) {
            "Vulkan decoded-frame output did not satisfy the measured presentation/P010 gate"
        }
        if (softwareVideoActive) {
            yPlaybackStage(
                category = YPlaybackFailureCategory.Renderer,
                stage = YPlaybackFailureStage.VideoRenderer,
                safeDetail = "FFmpeg software render worker",
            ) {
                softwareVideoRenderer.throwIfFailed()
            }
        }
        if (ended()) {
            pauseAtEnd()
        }
        return didWork
    }

    fun snapshot(): YEnhancedPlaybackSnapshot {
        val gpu = gpuVideoOutput
        val readAhead = demuxReadAhead.snapshot()
        val videoVerified = gpu?.outputVerified ?: firstVideoFrameRendered
        if (videoVerified && !runtimeRenderRecorded) {
            runtimeRenderRecorded = true
            runtimeCapabilityKey?.let { runtimeCapabilities?.recordRendered(it) }
        }
        if (gpu != null && videoVerified && !gpuEvidenceRecorded) {
            gpuEvidenceRecorded = true
            gpuEvidenceStore.recordVerified(
                runtimeCapabilityKey?.toGpuEvidenceKey(requireNotNull(plan)),
                gpu.currentFeatureMask,
            )
        }
        val passthrough = isAudioPassthrough()
        val audioRendering =
            audioRendererConfigured &&
                if (passthrough) encodedAudioRenderer.outputAdvancing else audioRenderer.outputAdvancing
        val sourceCodec = audioTrack?.audio?.codec
        val spatialized = !passthrough && audioRenderer.spatialAudioOutput
        val atmosOutputMode =
            if (passthrough) {
                encodedAudioRenderer.dolbyAtmosOutputMode
            } else if (audioRendering && spatialized && sourceCodec.isDolbyAtmosSource()) {
                YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm
            } else {
                YDolbyAtmosOutputMode.None
            }
        val dolbyVisionOutput =
            videoVerified &&
                plan?.renderPath == YRenderPath.SurfaceDirect &&
                plan?.usesHdrFallback == false &&
                effectiveVideoTrack?.dolbyVisionConfig != null &&
                plan?.outputHdrType == YHdrType.DolbyVision
        dualDolbyEvidence =
            dualDolbyEvidence
                .observeVideo(
                    outputVerified = videoVerified,
                    dolbyVisionVerified = dolbyVisionOutput,
                ).observeAudio(
                    outputVerified = audioRendering,
                    atmosSourceDetected = sourceCodec.isDolbyAtmosSource(),
                    outputMode = atmosOutputMode,
                )
        val outputEvidence = dualDolbyEvidence
        val dolbyVisionConfig = effectiveVideoTrack?.dolbyVisionConfig
        val felComposed =
            dolbyVisionConfig != null &&
                verifyDolbyVisionFelComposition(
                    YDolbyVisionFelCompositionRequest(
                        config = dolbyVisionConfig,
                        decoderName = videoDecoder.decoderName,
                        renderedFrameObserved = outputEvidence.videoOutputVerified,
                        rpuAccessUnitObserved = p7RpuQueued,
                        enhancementLayerAccessUnitObserved = p7EnhancementLayerQueued,
                    ),
                    felEvidenceProvider,
                )
        return YEnhancedPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = openResult?.durationUs ?: 0L,
            playing = playing && outputActive && !ended(),
            buffering = playing && (!outputActive || !videoVerified) && !ended(),
            ended = ended(),
            firstVideoFrameRendered = outputEvidence.videoOutputVerified,
            outputHdrType = plan?.outputHdrType ?: YHdrType.Sdr,
            softwareVideoToneMapped = plan?.softwareVideoToneMap == true,
            videoDecoderName =
                if (softwareVideoActive) FFMPEG_SOFTWARE_VIDEO_NAME else videoDecoder.decoderName,
            audioDecoderName =
                when {
                    isAudioPassthrough() -> encodedAudioRenderer.name
                    softwareAudioActive -> FFMPEG_SOFTWARE_AUDIO_NAME
                    else -> audioDecoder.decoderName
                },
            audioRendering = outputEvidence.audioOutputVerified,
            audioPassthrough = passthrough,
            immersiveAudioCarrierOutput = encodedAudioRenderer.immersiveCarrierOutput,
            dolbyAtmosSourceDetected = outputEvidence.dolbyAtmosSourceDetected,
            dolbyAtmosOutputMode = outputEvidence.dolbyAtmosOutputMode,
            audioOutputRoute =
                if (passthrough) encodedAudioRenderer.audioRouteLabel else audioRenderer.audioRouteLabel,
            audioOutputRouteVerified =
                if (passthrough) encodedAudioRenderer.audioRouteVerified else audioRenderer.audioRouteVerified,
            dolbyAtmosOutput = outputEvidence.dolbyAtmosOutputMode.encodedPassthrough,
            spatialAudioOutput = spatialized,
            headTrackingAvailable = !passthrough && audioRenderer.headTrackingAvailable,
            audioFallbackCount = audioFallbackCount,
            audioUnderrunCount = if (passthrough) encodedAudioRenderer.underrunCount else audioRenderer.underrunCount,
            audioSinkDiagnostics = if (passthrough) emptyMap() else audioRenderer.outputDiagnostics(),
            droppedFrames = droppedFrames,
            avSyncOffsetUs = lastAvSyncOffsetUs,
            sourceQueueBytes = readAhead.queuedBytes,
            sourceBufferedUs = readAhead.bufferedDurationUs,
            sourceStarvationCount = readAhead.starvationCount,
            sourceNetworkBitsPerSecond = readAhead.throughputBitsPerSecond,
            subtitleCues = subtitleCues.toList(),
            nativeGpuFeatureMask = gpu?.currentFeatureMask ?: 0L,
            gpuFrameDurationNs = gpu?.lastGpuFrameDurationNs ?: 0L,
            dolbyVisionRpuApplied =
                videoVerified &&
                    plan?.renderPath == YRenderPath.SurfaceDirect &&
                    plan?.outputHdrType == YHdrType.DolbyVision &&
                    p7RpuQueued,
            dolbyVisionEnhancementLayerDelivered = p7EnhancementLayerQueued,
            dolbyVisionFelComposed = felComposed,
            outputEvidenceGeneration = outputEvidence.generation,
            outputEvidenceResetReason = outputEvidence.lastResetReason,
        )
    }

    fun close() {
        renderCallbackGeneration++
        runCatching(demuxReadAhead::pauseReadAhead)
        pendingVideoOutput?.let { output ->
            runCatching { videoDecoder.releaseOutput(output, render = false) }
        }
        pendingVideoOutput = null
        pendingSoftwareVideoOutput = null
        releasePendingAudioOutput()
        pendingEncodedAudioData = null
        pendingSoftwareAudioOutput = null
        pendingSample = null
        runCatching(audioRenderer::release)
        runCatching(encodedAudioRenderer::release)
        runCatching(audioDecoder::release)
        runCatching(videoDecoder::release)
        runCatching(softwareVideoRenderer::release)
        runCatching { gpuVideoOutput?.close() }
        gpuVideoOutput = null
        runCatching { softwareDecoder?.release() }
        softwareDecoder = null
        frameRateManager.clear()
        runCatching(demuxReadAhead::close)
        plan = null
        runtimeCapabilityKey = null
        runtimeRenderRecorded = false
        gpuEvidenceRecorded = false
        p7RpuQueued = false
        p7EnhancementLayerQueued = false
        sourceRemote = false
        openResult = null
        sourceVideoTrack = null
        effectiveVideoTrack = null
        audioTrack = null
        subtitleTrack = null
        subtitleCues.clear()
        audioOutputPath = YAudioOutputPath.None
        softwareVideoActive = false
        softwareAudioActive = false
        surface = null
        playing = false
        outputActive = false
        prepared = false
        audioRendererConfigured = false
        rejectedPassthroughTracks.clear()
        firstVideoFrameRendered = false
        droppedFrames = 0
        audioFallbackCount = 0
        p7RpuQueued = false
        p7EnhancementLayerQueued = false
        resetEndState()
    }

    fun release() {
        close()
        demuxReadAhead.release()
    }

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (lastQueuedUs - currentPositionUs() > maxInputAheadUs) return false

        val sample =
            pendingSample ?: when (val queued = demuxReadAhead.pollSample()) {
                is YQueuedDemuxResult.Sample -> queued.value
                is YQueuedDemuxResult.Failed ->
                    yPlaybackStage(
                        category = sourceFailureCategory(),
                        stage = YPlaybackFailureStage.Demux,
                        safeDetail = "Enhanced compressed sample read-ahead",
                    ) {
                        throw queued.cause
                    }
                YQueuedDemuxResult.Empty -> {
                    if (
                        outputActive &&
                        lastQueuedUs - currentPositionUs() <= REBUFFER_OUTPUT_MARGIN_US
                    ) {
                        bufferGate.markStarved()
                        suspendOutputForBuffering()
                    }
                    return false
                }
                YQueuedDemuxResult.EndOfInput -> {
                    inputEnded = true
                    return true
                }
            }
        if (YSampleFlag.Encrypted in sample.flags) {
            error("Encrypted enhanced-demux samples require the dedicated DRM route")
        }

        val videoTrack = requireNotNull(sourceVideoTrack)
        val queued =
            when (sample.trackId) {
                videoTrack.id -> {
                    if (softwareVideoActive) {
                        val softwareSample =
                            if (requireNotNull(plan).usesHdrFallback) {
                                sample.copy(data = transformVideoSample(sample.data))
                            } else {
                                sample
                            }
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.Decoder,
                            stage = YPlaybackFailureStage.VideoDecoderQueue,
                            safeDetail = "FFmpeg software video access unit",
                        ) {
                            requireNotNull(softwareDecoder).queueVideo(softwareSample).toCodecQueueResult()
                        }
                    } else {
                        val hdr10PlusPayload =
                            videoTrack.video
                                ?.takeIf { it.hdrType == com.yfuse.core2.capability.YHdrType.Hdr10Plus }
                                ?.samplePacking
                                ?.let { packing -> YBitstream.hdr10PlusItuT35Payload(sample.data, packing) }
                        hdr10PlusPayload?.let(videoDecoder::setHdr10PlusMetadata)
                        hdr10PlusPayload?.let { payload ->
                            gpuVideoOutput?.queueHdr10PlusMetadata(sample.presentationTimeUs, payload)
                        }
                        val transformed =
                            yPlaybackStage(
                                category = YPlaybackFailureCategory.Container,
                                stage = YPlaybackFailureStage.Bitstream,
                                safeDetail = "Enhanced video bitstream normalization",
                            ) {
                                transformVideoSample(sample.data)
                            }
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.Decoder,
                            stage = YPlaybackFailureStage.VideoDecoderQueue,
                            safeDetail = "Enhanced video access unit",
                        ) {
                            videoDecoder.queueAccessUnit(
                                data = java.nio.ByteBuffer.wrap(transformed),
                                presentationTimeUs = sample.presentationTimeUs,
                                flags = sample.toExtractorFlags(),
                            )
                        }
                    }
                }
                audioTrack?.id -> queueAudioSample(sample)
                subtitleTrack?.id -> {
                    queueSubtitleSample(sample)
                    YCodecQueueResult.Queued
                }
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) {
            pendingSample = sample
            return false
        }
        if (sample.trackId == videoTrack.id && !softwareVideoActive) {
            recordDolbyVisionLayerDelivery(sample.data)
        }
        pendingSample = null
        lastQueuedUs = maxOf(lastQueuedUs, sample.presentationTimeUs)
        return true
    }

    private fun queueEndOfStream(): Boolean {
        var queued = false
        if (!videoInputEnded) {
            val videoQueued =
                if (softwareVideoActive) {
                    requireNotNull(softwareDecoder).queueVideo(null)
                } else {
                    videoDecoder.queueEndOfStream(lastQueuedUs) == YCodecQueueResult.Queued
                }
            if (videoQueued) {
                videoInputEnded = true
                queued = true
            }
        }
        if (!audioInputEnded && audioTrack != null) {
            if (isAudioPassthrough()) {
                audioInputEnded = true
                audioOutputEnded = true
                queued = true
            } else if (
                if (softwareAudioActive) {
                    requireNotNull(softwareDecoder).queueAudio(null)
                } else {
                    audioDecoder.queueEndOfStream(lastQueuedUs) == YCodecQueueResult.Queued
                }
            ) {
                audioInputEnded = true
                queued = true
            }
        }
        return queued
    }

    private fun transformVideoSample(data: ByteArray): ByteArray {
        val sourceTrack = requireNotNull(sourceVideoTrack?.video)
        val currentPlan = requireNotNull(plan)
        if (currentPlan.usesHdrFallback) {
            val config =
                requireNotNull(sourceTrack.dolbyVisionConfig) {
                    "HDR fallback requires explicit Dolby Vision configuration"
                }
            require(
                config.profile in setOf(7, 8) &&
                    config.codecFamily == com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Hevc,
            ) {
                "Only HEVC Dolby Vision Profile 7/8 compatible-base fallback is executable"
            }
            require(config.compatibleBaseHdr == currentPlan.inputHdrType) {
                "Dolby compatible base does not match the selected pipeline input HDR route"
            }
            val packing =
                requireNotNull(sourceTrack.samplePacking) {
                    "Dolby HEVC fallback requires known NAL packing"
                }
            return dolbyVisionHevcBaseLayerSample(data, packing)
        }
        return normalizeVideoSampleForMediaCodec(data, sourceTrack)
    }

    /** Records input to the exact Dolby decoder without inferring enhancement-layer composition. */
    private fun recordDolbyVisionLayerDelivery(data: ByteArray) {
        val source = sourceVideoTrack?.video ?: return
        val config = source.dolbyVisionConfig ?: return
        if (config.profile != 7 || plan?.usesHdrFallback == true) return
        val packing = source.samplePacking ?: return
        val evidence = runCatching { YBitstream.dolbyVisionEvidence(data, packing) }.getOrNull() ?: return
        p7RpuQueued = p7RpuQueued || evidence.rpuPresent
        p7EnhancementLayerQueued = p7EnhancementLayerQueued || evidence.enhancementLayerPresent
    }

    private fun drainAudio(): Boolean {
        if (audioTrack == null || audioOutputEnded) return false
        if (isAudioPassthrough()) return false
        if (softwareAudioActive) return drainSoftwareAudio()
        pendingAudioOutput?.let { return writePendingAudioOutput(it) }
        return when (val output = audioDecoder.dequeueOutput()) {
            YAudioCodecOutputResult.TryAgain -> false
            is YAudioCodecOutputResult.FormatChanged -> {
                yPlaybackStage(
                    category = YPlaybackFailureCategory.AudioSink,
                    stage = YPlaybackFailureStage.AudioRenderer,
                    safeDetail = "Enhanced PCM sink configure",
                ) {
                    audioRenderer.configure(output.format)
                }
                audioRendererConfigured = true
                captureAudioRoutingGeneration()
                audioRenderer.setSpeed(speed)
                if (outputActive) audioRenderer.play()
                true
            }
            is YAudioCodecOutputResult.Buffer -> {
                val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val renderable =
                    !config && output.size > 0 && output.presentationTimeUs >= seekAudioTargetUs
                if (!renderable) {
                    audioDecoder.releaseOutput(output)
                    if (output.endOfStream) audioOutputEnded = true
                    true
                } else {
                    check(audioRendererConfigured) { "PCM output arrived before AudioTrack format" }
                    val pending =
                        YEnhancedPendingAudioOutput(
                            output = output,
                            data = audioDecoder.outputData(output),
                        )
                    pendingAudioOutput = pending
                    writePendingAudioOutput(pending)
                }
            }
        }
    }

    private fun writePendingAudioOutput(pending: YEnhancedPendingAudioOutput): Boolean {
        val written =
            yPlaybackStage(
                category = YPlaybackFailureCategory.AudioSink,
                stage = YPlaybackFailureStage.AudioRenderer,
                safeDetail = "Enhanced PCM non-blocking write",
            ) {
                audioRenderer.writeNonBlocking(pending.data, pending.output.presentationTimeUs)
            }
        return when (decodedAudioDrainProgress(written, pending.data.remaining())) {
            YDecodedAudioDrainProgress.Backpressured -> false
            YDecodedAudioDrainProgress.Pending -> true
            YDecodedAudioDrainProgress.Complete -> {
                pendingAudioOutput = null
                audioDecoder.releaseOutput(pending.output)
                seekAudioTargetUs = 0L
                if (pending.output.endOfStream) audioOutputEnded = true
                true
            }
        }
    }

    private fun drainVideo(): Boolean {
        if (videoOutputEnded) return false
        if (softwareVideoActive) return drainSoftwareVideo()
        val output =
            pendingVideoOutput ?: when (val dequeued = videoDecoder.dequeueOutput()) {
                YCodecOutputResult.TryAgain -> return false
                is YCodecOutputResult.FormatChanged -> {
                    gpuVideoOutput?.updateDecodedFormat(dequeued.format) { replacementSurface ->
                        videoDecoder.setOutputSurface(replacementSurface)
                    }
                    return true
                }
                is YCodecOutputResult.Buffer -> dequeued
            }

        val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val renderable = !config && output.size > 0 && output.presentationTimeUs >= seekVideoTargetUs
        val seekPreroll =
            !config &&
                output.size > 0 &&
                seekVideoTargetUs > 0L &&
                output.presentationTimeUs < seekVideoTargetUs
        if (seekPreroll) {
            pendingVideoOutput = null
            seekPrerollVideoOutput?.let { videoDecoder.releaseOutput(it, render = false) }
            seekPrerollVideoOutput = output
            if (!output.endOfStream) return true
            renderSeekPrerollAtEnd()
            return true
        }
        if (output.endOfStream && seekVideoTargetUs > 0L && seekPrerollVideoOutput != null) {
            pendingVideoOutput = null
            videoDecoder.releaseOutput(output, render = false)
            renderSeekPrerollAtEnd()
            return true
        }
        if (
            output.endOfStream &&
            seekVideoTargetUs > 0L &&
            !firstVideoFrameRendered &&
            retryEmptyTailSeek(output)
        ) {
            return true
        }
        if (!renderable) {
            pendingVideoOutput = null
            videoDecoder.releaseOutput(output, render = false)
            if (output.endOfStream) videoOutputEnded = true
            return true
        }
        seekPrerollVideoOutput?.let { videoDecoder.releaseOutput(it, render = false) }
        seekPrerollVideoOutput = null

        // Never hold the interleaved demux pipeline merely because the audio sink is priming
        // or its clock is stale. Video backpressure can prevent the next audio packet from being
        // decoded, so waiting for audio here creates a circular wait. Pace against the media
        // wall clock until a real audio clock is available; the audio fault detector stays active.

        val currentUs = currentPositionUs()
        val nowNs = System.nanoTime()
        val desiredRenderNs =
            if (audioTrack != null) {
                audioPresentationTimeNs(
                    output.presentationTimeUs,
                    wallClock.presentationTimeNs(output.presentationTimeUs),
                )
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
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Renderer,
                    stage = YPlaybackFailureStage.VideoRenderer,
                    safeDetail = "Enhanced video frame release",
                ) {
                    videoDecoder.releaseOutput(output, render = true, renderTimeNs = decision.releaseTimeNs)
                }
                firstVideoFrameRendered = true
            }
        }
        lastVideoUs = output.presentationTimeUs
        seekVideoTargetUs = 0L
        if (output.endOfStream) videoOutputEnded = true
        return true
    }

    private fun drainSoftwareAudio(): Boolean {
        pendingSoftwareAudioOutput?.let { return writePendingSoftwareAudioOutput(it) }
        return when (val output = requireNotNull(softwareDecoder).receiveAudio()) {
            YSoftwareAudioDecodeResult.TryAgain -> false
            YSoftwareAudioDecodeResult.Ended -> {
                audioOutputEnded = true
                true
            }
            is YSoftwareAudioDecodeResult.Frame -> {
                if (!audioRendererConfigured) {
                    val format =
                        MediaFormat
                            .createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_RAW,
                                output.sampleRate,
                                output.channelCount,
                            ).apply {
                                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            }
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.AudioSink,
                        stage = YPlaybackFailureStage.AudioRenderer,
                        safeDetail = "FFmpeg software PCM sink configure",
                    ) {
                        audioRenderer.configure(format)
                    }
                    audioRendererConfigured = true
                    captureAudioRoutingGeneration()
                    audioRenderer.setSpeed(speed)
                    if (outputActive) audioRenderer.play()
                }
                if (output.presentationTimeUs >= seekAudioTargetUs) {
                    pendingSoftwareAudioOutput = output
                    writePendingSoftwareAudioOutput(output)
                } else {
                    true
                }
            }
        }
    }

    private fun writePendingSoftwareAudioOutput(output: YSoftwareAudioDecodeResult.Frame): Boolean {
        val written =
            yPlaybackStage(
                category = YPlaybackFailureCategory.AudioSink,
                stage = YPlaybackFailureStage.AudioRenderer,
                safeDetail = "FFmpeg software PCM non-blocking write",
            ) {
                audioRenderer.writeNonBlocking(output.data, output.presentationTimeUs)
            }
        return when (decodedAudioDrainProgress(written, output.data.remaining())) {
            YDecodedAudioDrainProgress.Backpressured -> false
            YDecodedAudioDrainProgress.Pending -> true
            YDecodedAudioDrainProgress.Complete -> {
                pendingSoftwareAudioOutput = null
                seekAudioTargetUs = 0L
                true
            }
        }
    }

    private fun drainSoftwareVideo(): Boolean {
        if (softwareVideoDecodeEnded) return false
        val output =
            pendingSoftwareVideoOutput
                ?: when (val decoded = requireNotNull(softwareDecoder).receiveVideo()) {
                    YSoftwareVideoDecodeResult.TryAgain -> return false
                    YSoftwareVideoDecodeResult.Ended -> {
                        softwareVideoDecodeEnded = true
                        val render = softwareVideoRenderer.snapshot()
                        if (render.idle) videoOutputEnded = true
                        return true
                    }
                    is YSoftwareVideoDecodeResult.Frame -> decoded
                }
        if (output.presentationTimeUs < seekVideoTargetUs) {
            pendingSoftwareVideoOutput = null
            return true
        }

        // Software video must use the same bounded fallback pacing as hardware video. Holding
        // this reusable FFmpeg frame until audio starts can also block interleaved audio input.
        val currentUs = currentPositionUs()
        val nowNs = System.nanoTime()
        val desiredRenderNs =
            if (audioTrack != null) {
                audioPresentationTimeNs(
                    output.presentationTimeUs,
                    wallClock.presentationTimeNs(output.presentationTimeUs),
                )
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
                pendingSoftwareVideoOutput = output
                return false
            }
            YVideoFrameReleaseDecision.Drop -> {
                pendingSoftwareVideoOutput = null
                droppedFrames++
                lastVideoUs = output.presentationTimeUs
            }
            is YVideoFrameReleaseDecision.Render -> {
                if (decision.releaseTimeNs > nowNs + SOFTWARE_RENDER_EARLY_TOLERANCE_NS) {
                    pendingSoftwareVideoOutput = output
                    return false
                }
                val accepted =
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.Renderer,
                        stage = YPlaybackFailureStage.VideoRenderer,
                        safeDetail = "FFmpeg software video frame queue",
                    ) {
                        softwareVideoRenderer.tryRender(output)
                    }
                if (!accepted) {
                    pendingSoftwareVideoOutput = output
                    return false
                }
                pendingSoftwareVideoOutput = null
            }
        }
        seekVideoTargetUs = 0L
        return true
    }

    private fun collectSoftwareRenderProgress(): Boolean {
        if (!softwareVideoActive) return false
        val render = softwareVideoRenderer.snapshot()
        var changed = false
        if (render.renderedFrameCount > softwareRenderedFrameCountSeen) {
            softwareRenderedFrameCountSeen = render.renderedFrameCount
            firstVideoFrameRendered = true
            lastVideoUs = render.presentationTimeUs
            lastAvSyncOffsetUs =
                audioClockSnapshot()?.let { clock ->
                    YAvSync.offsetUs(
                        videoPresentationTimeUs = render.presentationTimeUs,
                        videoRenderedRealtimeNs = render.renderedRealtimeNs,
                        master = YClockSnapshot(clock.positionUs, clock.realtimeNs),
                        speed = speed,
                    )
                }
            changed = true
        }
        if (softwareVideoDecodeEnded && render.idle && !videoOutputEnded) {
            videoOutputEnded = true
            changed = true
        }
        return changed
    }

    private fun renderSeekPrerollAtEnd() {
        val candidate = seekPrerollVideoOutput ?: return
        seekPrerollVideoOutput = null
        yPlaybackStage(
            category = YPlaybackFailureCategory.Renderer,
            stage = YPlaybackFailureStage.VideoRenderer,
            safeDetail = "Enhanced seek tail frame release",
        ) {
            videoDecoder.releaseOutput(candidate, render = true)
        }
        lastVideoUs = candidate.presentationTimeUs
        seekVideoTargetUs = 0L
        firstVideoFrameRendered = true
        videoOutputEnded = true
    }

    private fun retryEmptyTailSeek(output: YCodecOutputResult.Buffer): Boolean {
        val retryTargetUs =
            emptyTailSeekRetryTarget(
                currentTargetUs = seekVideoTargetUs,
                retryCount = emptyTailSeekRetries,
            ) ?: return false
        pendingVideoOutput = null
        videoDecoder.releaseOutput(output, render = false)
        emptyTailSeekRetries++
        seekToInternal(
            positionUs = retryTargetUs,
            tailRetry = true,
            resetVideoDecoder = true,
        )
        return true
    }

    private fun currentPositionUs(): Long {
        val audio = audioClockSnapshot()
        if (audio != null) {
            // Preserve the last measured audio anchor for a smooth, paced clock fallback.
            wallClock.seek(audio.positionUs, audio.realtimeNs)
            return audio.positionUs
        }
        return if (outputActive) wallClock.positionUs(System.nanoTime()) else lastVideoUs.coerceAtLeast(0L)
    }

    /**
     * Keeps media time frozen during startup/rebuffering and releases both clocks together only
     * after the compressed queue reaches the resume watermark.
     */
    private fun refreshOutputGate(): Boolean {
        val readAhead = demuxReadAhead.snapshot()
        refreshAdaptiveBufferPlan(readAhead)
        val decision =
            bufferGate.evaluate(
                bufferedDurationUs = readAhead.bufferedDurationUs,
                endOfInput = readAhead.endOfInput,
            )
        if (decision.outputAllowed && !outputActive) {
            val position = currentPositionUs()
            outputActive = true
            wallClock.start(position, System.nanoTime())
            if (audioRendererConfigured) playAudio()
        } else if (!decision.outputAllowed && outputActive) {
            suspendOutputForBuffering()
        }
        return decision.outputAllowed
    }

    private fun refreshAdaptiveBufferPlan(readAhead: YDemuxReadAheadSnapshot) {
        if (!sourceRemote || readAhead.throughputBitsPerSecond <= 0L) return
        val nowNs = System.nanoTime()
        if (nowNs - lastBufferReplanNs < BUFFER_REPLAN_INTERVAL_NS) return
        lastBufferReplanNs = nowNs
        val next =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = openResult?.bitRateBitsPerSecond ?: 0L,
                    measuredNetworkBitsPerSecond = readAhead.throughputBitsPerSecond,
                    preferredTargetAheadUs = preferredRemoteBufferTargetUs,
                ),
            )
        if (next == bufferPlan) return
        bufferPlan = next
        maxInputAheadUs = next.targetAheadUs
        bufferGate.updateResumePlaybackUs(next.resumePlaybackUs)
        demuxReadAhead.configure(
            targetAheadUs = next.targetAheadUs,
            mediaBitRateBitsPerSecond = openResult?.bitRateBitsPerSecond,
        )
    }

    private fun suspendOutputForBuffering() {
        if (!outputActive) return
        val position = currentPositionUs()
        outputActive = false
        pauseAudio()
        wallClock.pause(position, System.nanoTime())
    }

    private fun pauseAtEnd() {
        // Keep the last position tied to output that really reached a sink. Inflating an early
        // transport EOF to the container's declared duration hid truncation from the adaptive
        // layer and made it auto-advance as if the episode completed normally.
        val renderedEndUs = maxOf(lastVideoUs, audioClockSnapshot()?.positionUs ?: 0L)
        val endUs =
            openResult
                ?.durationUs
                ?.takeIf { it > 0L }
                ?.let(renderedEndUs::coerceAtMost)
                ?: renderedEndUs
        playing = false
        outputActive = false
        pauseAudio()
        wallClock.pause(endUs, System.nanoTime())
    }

    private fun ended(): Boolean = videoOutputEnded && (audioTrack == null || audioOutputEnded)

    private fun resetEndState() {
        inputEnded = false
        videoInputEnded = false
        audioInputEnded = audioTrack == null
        videoOutputEnded = false
        audioOutputEnded = audioTrack == null
        softwareVideoDecodeEnded = false
        softwareRenderedFrameCountSeen = softwareVideoRenderer.snapshot().renderedFrameCount
        pendingVideoOutput = null
        pendingSoftwareVideoOutput = null
        seekPrerollVideoOutput = null
        lastAvSyncOffsetUs = null
    }

    private fun queueAudioSample(sample: YCompressedSample): YCodecQueueResult {
        if (!isAudioPassthrough()) {
            return yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderQueue,
                safeDetail = "Enhanced audio access unit",
            ) {
                if (softwareAudioActive) {
                    requireNotNull(softwareDecoder).queueAudio(sample).toCodecQueueResult()
                } else {
                    audioDecoder.queueAccessUnit(
                        data = java.nio.ByteBuffer.wrap(sample.data),
                        presentationTimeUs = sample.presentationTimeUs,
                        flags = sample.toExtractorFlags(),
                    )
                }
            }
        }
        if (sample.presentationTimeUs >= seekAudioTargetUs) {
            try {
                val data = pendingEncodedAudioData ?: java.nio.ByteBuffer.wrap(sample.data)
                pendingEncodedAudioData = data
                val written =
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.AudioSink,
                        stage = YPlaybackFailureStage.AudioRenderer,
                        safeDetail = "Enhanced encoded audio non-blocking write",
                    ) {
                        encodedAudioRenderer.writeNonBlocking(data, sample.presentationTimeUs)
                    }
                if (decodedAudioDrainProgress(written, data.remaining()) != YDecodedAudioDrainProgress.Complete) {
                    return YCodecQueueResult.TryAgain
                }
                pendingEncodedAudioData = null
            } catch (_: Exception) {
                pendingEncodedAudioData = null
                val position = currentPositionUs()
                rejectedPassthroughTracks += requireNotNull(audioTrack).id
                switchPassthroughToPcm(position, countFailure = true)
                return YCodecQueueResult.Queued
            }
            seekAudioTargetUs = 0L
        }
        return YCodecQueueResult.Queued
    }

    private fun releasePendingAudioOutput() {
        val pending = pendingAudioOutput ?: return
        pendingAudioOutput = null
        runCatching { audioDecoder.releaseOutput(pending.output) }
    }

    private fun queueSubtitleSample(sample: YCompressedSample) {
        val format = subtitleTrack?.subtitle?.format ?: return
        val nativeDecoder =
            demuxReadAhead.takeIf { it.supportsSubtitleFormat(format) }
        if (nativeDecoder != null) {
            val decoded =
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Container,
                    stage = YPlaybackFailureStage.Bitstream,
                    safeDetail = "Enhanced native subtitle decode",
                ) {
                    nativeDecoder.decodeSubtitle(sample)
                }
            subtitleCues.addAll(decoded)
        } else if (format.textOverlaySupported) {
            YEmbeddedSubtitleDecoder
                .decode(
                    data = sample.data,
                    format = format,
                    startUs = sample.presentationTimeUs,
                    durationUs = sample.durationUs,
                    id = "${subtitleTrack?.id?.value}:${sample.presentationTimeUs}",
                )?.let(subtitleCues::add)
        }
        val oldestRetainedUs = currentPositionUs() - SUBTITLE_HISTORY_US
        subtitleCues.removeAll { cue -> cue.endUs < oldestRetainedUs }
    }

    private fun selectedTrackIds(): Set<YTrackId> =
        buildSet {
            sourceVideoTrack?.let { add(it.id) }
            audioTrack?.let { add(it.id) }
            subtitleTrack?.let { add(it.id) }
        }

    private fun isAudioPassthrough(): Boolean = audioTrack != null && audioOutputPath == YAudioOutputPath.Passthrough

    private fun configurePcmDecoder(
        track: YDemuxTrack,
        preferSoftware: Boolean,
    ): Boolean {
        val format = requireNotNull(track.audio)
        if (!preferSoftware) {
            try {
                audioDecoder.configure(AndroidMediaFormatFactory.audio(format))
                return false
            } catch (failure: Exception) {
                runCatching(audioDecoder::release)
                val software = softwareDecoderOrNull() ?: throw failure
                software.configureAudio(track.id)
                return true
            }
        }
        requireNotNull(softwareDecoderOrNull()).configureAudio(track.id)
        return true
    }

    private fun switchPassthroughToPcm(
        positionUs: Long,
        countFailure: Boolean,
    ) {
        val track = checkNotNull(audioTrack) { "PCM fallback requires an audio track" }
        pauseAudio()
        demuxReadAhead.pauseReadAhead()
        runCatching(encodedAudioRenderer::release)
        runCatching(audioRenderer::release)
        runCatching(audioDecoder::release)
        audioRendererConfigured = false
        softwareAudioActive =
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderConfigure,
                safeDetail = "Enhanced runtime PCM fallback configure",
            ) {
                configurePcmDecoder(track, preferSoftware = false)
            }
        audioOutputPath = YAudioOutputPath.DecodePcm
        if (countFailure) audioFallbackCount++
        captureAudioRoutingGeneration()
        demuxReadAhead.selectTracks(selectedTrackIds())
        seekToInternal(positionUs, tailRetry = false, resetVideoDecoder = false)
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
        dualDolbyEvidence = dualDolbyEvidence.invalidate(YOutputEvidenceResetReason.AudioRouteChanged)
        val activeTrack = audioTrack ?: return false
        val format = activeTrack.audio ?: return false
        if (!isAudioPassthrough()) return true

        val capabilities = capabilityProvider.current()
        val nextPath =
            capabilities.audioOutputPath(
                YAudioRequirement(
                    codec = format.codec,
                    channelCount = format.channelCount,
                    sampleRate = format.sampleRate,
                ),
            )
        if (nextPath != YAudioOutputPath.Passthrough) {
            rejectedPassthroughTracks += activeTrack.id
            switchPassthroughToPcm(currentPositionUs(), countFailure = true)
        } else {
            encodedAudioRenderer.updateExactDolbyAtmosTransport(
                capabilities.hasExactDolbyAtmosPassthrough(format.codec),
            )
        }
        return true
    }

    private fun captureAudioRoutingGeneration() {
        observedAudioRoutingGeneration =
            if (isAudioPassthrough()) {
                encodedAudioRenderer.routingChangeGeneration
            } else {
                audioRenderer.routingChangeGeneration
            }
    }

    private fun softwareDecoderOrNull(): AndroidFfmpegSoftwareDecoderNode? {
        softwareDecoder?.let { return it.takeIf { node -> node.available } }
        val ffmpegDemuxer = demuxer as? AndroidFfmpegDemuxer ?: return null
        if (!ffmpegDemuxer.softwareDecodeAvailable) return null
        return AndroidFfmpegSoftwareDecoderNode(ffmpegDemuxer).also { softwareDecoder = it }
    }

    private fun sourceFailureCategory(): YPlaybackFailureCategory =
        if (sourceRemote) YPlaybackFailureCategory.Network else YPlaybackFailureCategory.Container

    private fun audioClockSnapshot(): YAudioClockSnapshot? =
        if (isAudioPassthrough()) encodedAudioRenderer.clockSnapshot() else audioRenderer.clockSnapshot()

    private fun attachVideoRenderEvidence() {
        val generation = ++renderCallbackGeneration
        videoDecoder.setOnFrameRenderedListener { presentationTimeUs, realtimeNs ->
            if (generation != renderCallbackGeneration) return@setOnFrameRenderedListener
            if (!runtimeRenderRecorded) {
                runtimeRenderRecorded = true
                runtimeCapabilityKey?.let { runtimeCapabilities?.recordRendered(it) }
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

    private fun effectiveVideoTrack(
        source: YVideoTrackFormat,
        plan: YPlaybackPlan,
    ): YVideoTrackFormat {
        if (!plan.usesHdrFallback) return source
        val config =
            requireNotNull(source.dolbyVisionConfig) {
                "HDR fallback route requires Dolby Vision configuration"
            }
        require(config.profile in setOf(7, 8) && config.compatibleBaseHdr == plan.inputHdrType) {
            "Only explicit Profile 7/8 compatible-base fallback is supported"
        }
        require(source.codec == YVideoCodec.H265) { "Profile 7/8 compatible base requires HEVC" }
        return source.copy(
            mimeType = "video/hevc",
            hdrType = plan.inputHdrType,
            samplePacking = YSamplePacking.AnnexB,
            dolbyVisionConfig = null,
        )
    }
}

internal fun validateEnhancedDolbyVisionIdentity(
    required: Boolean,
    config: YDolbyVisionConfig?,
) {
    if (!required || config != null) return
    throw YPlaybackException(
        category = YPlaybackFailureCategory.Container,
        stage = YPlaybackFailureStage.Bitstream,
        safeDetail = "Enhanced demux did not expose a Dolby Vision configuration",
    )
}

private fun YCompressedSample.toExtractorFlags(): Int =
    when {
        YSampleFlag.Encrypted in flags -> 2
        YSampleFlag.Sync in flags -> 1
        else -> 0
    }

private fun Boolean.toCodecQueueResult(): YCodecQueueResult =
    if (this) YCodecQueueResult.Queued else YCodecQueueResult.TryAgain

private fun Context.yCoreDisplayPeakNits(): Float? =
    runCatching {
        val activeDisplay =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                getSystemService(WindowManager::class.java)?.defaultDisplay
            }
        activeDisplay
            ?.hdrCapabilities
            ?.desiredMaxLuminance
            ?.takeIf { it.isFinite() && it > 0f }
    }.getOrNull()

private data class YEnhancedPendingAudioOutput(
    val output: YAudioCodecOutputResult.Buffer,
    val data: java.nio.ByteBuffer,
)

private const val DEFAULT_INPUT_AHEAD_US = 1_500_000L
private const val REBUFFER_OUTPUT_MARGIN_US = 750_000L
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val LATE_FRAME_DROP_NS = 100_000_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
private const val SOFTWARE_RENDER_EARLY_TOLERANCE_NS = 2_000_000L
private const val SUBTITLE_HISTORY_US = 60_000_000L
private const val BUFFER_REPLAN_INTERVAL_NS = 2_000_000_000L
private const val FFMPEG_SOFTWARE_VIDEO_NAME = "FFmpeg software video"
private const val FFMPEG_SOFTWARE_AUDIO_NAME = "FFmpeg software audio"
