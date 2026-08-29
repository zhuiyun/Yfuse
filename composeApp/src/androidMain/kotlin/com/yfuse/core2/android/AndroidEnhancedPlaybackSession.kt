package com.yfuse.core2.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.WindowManager
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
import com.yfuse.core2.demux.YSubtitlePacketDecoder
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.dolbyVisionHevcBaseLayerSample
import com.yfuse.core2.network.YBufferConditions
import com.yfuse.core2.network.YBufferController
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
import com.yfuse.core2.subtitle.YSubtitleFormat
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
    val dolbyAtmosOutput: Boolean,
    val spatialAudioOutput: Boolean,
    val headTrackingAvailable: Boolean,
    val audioFallbackCount: Int,
    val droppedFrames: Int,
    val avSyncOffsetUs: Long?,
    val subtitleCues: List<YSubtitleCue>,
    val nativeGpuFeatureMask: Long = 0L,
    val gpuFrameDurationNs: Long = 0L,
    val dolbyVisionRpuApplied: Boolean = false,
    val dolbyVisionEnhancementLayerDelivered: Boolean = false,
    val dolbyVisionFelComposed: Boolean = false,
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
    frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
) {
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
    private var pendingSoftwareVideoOutput: YSoftwareVideoDecodeResult.Frame? = null
    private var softwareDecoder: AndroidFfmpegSoftwareDecoderNode? = null
    private val softwareVideoRenderer = AndroidSoftwareVideoRenderNode()
    private var gpuVideoOutput: AndroidVulkanVideoOutput? = null
    private var softwareVideoActive = false
    private var softwareAudioActive = false
    private var playing = false
    private var prepared = false
    private var audioRendererConfigured = false
    private val rejectedPassthroughTracks = mutableSetOf<YTrackId>()
    private var inputEnded = false
    private var videoInputEnded = false
    private var audioInputEnded = false
    private var videoOutputEnded = false
    private var audioOutputEnded = false
    private var firstVideoFrameRendered = false
    private var seekPrerollVideoOutput: YCodecOutputResult.Buffer? = null
    private var emptyTailSeekRetries = 0
    private var droppedFrames = 0
    private var audioFallbackCount = 0
    private var runtimeRenderRecorded = false
    private var gpuEvidenceRecorded = false
    private var runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null
    private var renderCallbackGeneration = 0
    private var p7RpuQueued = false
    private var p7EnhancementLayerQueued = false

    @Volatile
    private var lastAvSyncOffsetUs: Long? = null

    private var seekVideoTargetUs = 0L
    private var seekAudioTargetUs = 0L
    private var lastQueuedUs = 0L
    private var lastVideoUs = 0L
    private var maxInputAheadUs = DEFAULT_INPUT_AHEAD_US

    @Volatile
    private var speed = 1f

    fun open(
        source: YDemuxSource,
        plan: YPlaybackPlan,
        surface: Surface,
        startPositionUs: Long = 0L,
        runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
    ): YDemuxOpenResult {
        close()
        this.runtimeCapabilityKey = runtimeCapabilityKey
        require(plan.route in setOf(YPlaybackRoute.NativeEnhanced, YPlaybackRoute.GpuEnhanced, YPlaybackRoute.SoftwareFallback)) {
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
                demuxer.open(source)
            }
        val videoTrack =
            result.tracks.firstOrNull { it.type == YDemuxTrackType.Video && it.video != null }
                ?: error("Enhanced demux contains no video track")
        val audioTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Audio && it.audio != null }
        val effectiveVideo = effectiveVideoTrack(requireNotNull(videoTrack.video), plan)
        softwareVideoActive = plan.decodePath == YDecodePath.Software
        softwareAudioActive = audioTrack != null && plan.softwareAudioDecode
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
                when (plan.audioPath) {
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
            demuxer.selectTracks(
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
            runCatching(demuxer::close)
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
            audioOutputPath = plan.audioPath
        }
        this.surface = surface
        maxInputAheadUs =
            YBufferController
                .plan(
                    YBufferConditions(
                        remote = remote,
                        mediaBitRateBitsPerSecond = result.bitRateBitsPerSecond,
                    ),
                ).targetAheadUs
        audioRendererConfigured = audioTrack != null && audioOutputPath == YAudioOutputPath.Passthrough
        prepared = true
        resetEndState()
        if (startPositionUs > 0L) {
            seekTo(startPositionUs)
        } else {
            wallClock.seek(0L, System.nanoTime())
        }
        return result
    }

    fun play() {
        check(prepared) { "Enhanced session is not prepared" }
        if (ended()) {
            seekTo(0L)
        }
        playing = true
        wallClock.start(currentPositionUs(), System.nanoTime())
        if (audioRendererConfigured) {
            playAudio()
        }
    }

    fun pause() {
        if (!prepared) return
        val position = currentPositionUs()
        playing = false
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
        yPlaybackStage(
            category = YPlaybackFailureCategory.Renderer,
            stage = YPlaybackFailureStage.VideoRenderer,
            safeDetail = "Enhanced Surface rebind",
        ) {
            if (softwareVideoActive) {
                softwareVideoRenderer.attach(next)
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

        pauseAudio()
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
        demuxer.selectTracks(selectedTrackIds())
        audioInputEnded = false
        audioOutputEnded = false
        seekAudioTargetUs = currentPositionUs()
        lastAvSyncOffsetUs = null
        if (playing && audioRendererConfigured) playAudio()
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
                                    (format in BITMAP_SUBTITLE_FORMATS && demuxer is YSubtitlePacketDecoder)
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
        demuxer.selectTracks(selectedTrackIds())
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
        val target = positionUs.coerceAtLeast(0L)
        if (!tailRetry) emptyTailSeekRetries = 0
        yPlaybackStage(
            category = sourceFailureCategory(),
            stage = YPlaybackFailureStage.Seek,
            safeDetail = "Enhanced source seek",
        ) {
            demuxer.seekTo(target)
        }
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
        wallClock.seek(target, System.nanoTime())
        if (playing) {
            wallClock.start(target, System.nanoTime())
            if (audioRendererConfigured) playAudio()
        }
    }

    /** Runs one bounded non-blocking media iteration. */
    fun pump(): Boolean {
        if (!prepared || !playing || ended()) return false
        var didWork = false
        didWork = drainAudio() || didWork
        didWork = drainVideo() || didWork
        didWork = feedInput() || didWork
        check(gpuVideoOutput?.measurementFailed != true) {
            "Vulkan decoded-frame output did not satisfy the measured presentation/P010 gate"
        }
        if (ended()) {
            pauseAtEnd()
        }
        return didWork
    }

    fun snapshot(): YEnhancedPlaybackSnapshot {
        val gpu = gpuVideoOutput
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
        return YEnhancedPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = openResult?.durationUs ?: 0L,
            playing = playing && !ended(),
            buffering = playing && !videoVerified && !ended(),
            ended = ended(),
            firstVideoFrameRendered = videoVerified,
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
            audioRendering = audioRendererConfigured && audioClockSnapshot() != null,
            audioPassthrough = isAudioPassthrough(),
            immersiveAudioCarrierOutput = encodedAudioRenderer.immersiveCarrierOutput,
            dolbyAtmosOutput = encodedAudioRenderer.dolbyAtmosOutput,
            spatialAudioOutput = !isAudioPassthrough() && audioRenderer.spatialAudioOutput,
            headTrackingAvailable = !isAudioPassthrough() && audioRenderer.headTrackingAvailable,
            audioFallbackCount = audioFallbackCount,
            droppedFrames = droppedFrames,
            avSyncOffsetUs = lastAvSyncOffsetUs,
            subtitleCues = subtitleCues.toList(),
            nativeGpuFeatureMask = gpu?.currentFeatureMask ?: 0L,
            gpuFrameDurationNs = gpu?.lastGpuFrameDurationNs ?: 0L,
            dolbyVisionRpuApplied =
                videoVerified &&
                    plan?.renderPath == YRenderPath.SurfaceDirect &&
                    plan?.outputHdrType == YHdrType.DolbyVision &&
                    p7RpuQueued,
            dolbyVisionEnhancementLayerDelivered = p7EnhancementLayerQueued,
            // No independent decoded-EL contribution trace exists in YCore yet.
            dolbyVisionFelComposed = false,
        )
    }

    fun close() {
        renderCallbackGeneration++
        pendingVideoOutput?.let { output ->
            runCatching { videoDecoder.releaseOutput(output, render = false) }
        }
        pendingVideoOutput = null
        pendingSoftwareVideoOutput = null
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
        runCatching(demuxer::close)
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

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (lastQueuedUs - currentPositionUs() > maxInputAheadUs) return false

        val sample =
            pendingSample
                ?: yPlaybackStage(
                    category = sourceFailureCategory(),
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "Enhanced compressed sample read",
                ) {
                    demuxer.readSample()
                }
        if (sample == null) {
            inputEnded = true
            return true
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
                audioRenderer.setSpeed(speed)
                if (playing) audioRenderer.play()
                true
            }
            is YAudioCodecOutputResult.Buffer -> {
                try {
                    val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && output.size > 0 && output.presentationTimeUs >= seekAudioTargetUs) {
                        check(audioRendererConfigured) { "PCM output arrived before AudioTrack format" }
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.AudioSink,
                            stage = YPlaybackFailureStage.AudioRenderer,
                            safeDetail = "Enhanced PCM write",
                        ) {
                            audioRenderer.write(audioDecoder.outputData(output), output.presentationTimeUs)
                        }
                        seekAudioTargetUs = 0L
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
        if (videoOutputEnded) return false
        if (softwareVideoActive) return drainSoftwareVideo()
        val output =
            pendingVideoOutput ?: when (val dequeued = videoDecoder.dequeueOutput()) {
                YCodecOutputResult.TryAgain -> return false
                is YCodecOutputResult.FormatChanged -> {
                    gpuVideoOutput?.updateDecodedFormat(dequeued.format)
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

        // Audio is the master when present. Hold the first video buffer until AudioTrack has a real
        // clock rather than starting video early and correcting drift after the fact.
        if (audioTrack != null && audioClockSnapshot() == null) {
            pendingVideoOutput = output
            return false
        }

        val currentUs = currentPositionUs()
        val nowNs = System.nanoTime()
        val desiredRenderNs =
            if (audioTrack != null) {
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

    private fun drainSoftwareAudio(): Boolean =
        when (val output = requireNotNull(softwareDecoder).receiveAudio()) {
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
                    audioRenderer.setSpeed(speed)
                    if (playing) audioRenderer.play()
                }
                if (output.presentationTimeUs >= seekAudioTargetUs) {
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.AudioSink,
                        stage = YPlaybackFailureStage.AudioRenderer,
                        safeDetail = "FFmpeg software PCM write",
                    ) {
                        audioRenderer.write(output.data, output.presentationTimeUs)
                    }
                    seekAudioTargetUs = 0L
                }
                true
            }
        }

    private fun drainSoftwareVideo(): Boolean {
        val output =
            pendingSoftwareVideoOutput
                ?: when (val decoded = requireNotNull(softwareDecoder).receiveVideo()) {
                    YSoftwareVideoDecodeResult.TryAgain -> return false
                    YSoftwareVideoDecodeResult.Ended -> {
                        videoOutputEnded = true
                        return true
                    }
                    is YSoftwareVideoDecodeResult.Frame -> decoded
                }
        if (output.presentationTimeUs < seekVideoTargetUs) {
            pendingSoftwareVideoOutput = null
            return true
        }

        // Audio remains the master clock for software video as well. Holding the reusable frame
        // also naturally back-pressures FFmpeg until AudioTrack has emitted a timestamp.
        if (audioTrack != null && audioClockSnapshot() == null) {
            pendingSoftwareVideoOutput = output
            return false
        }
        val currentUs = currentPositionUs()
        val nowNs = System.nanoTime()
        val desiredRenderNs =
            if (audioTrack != null) {
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
                pendingSoftwareVideoOutput = output
                return false
            }
            YVideoFrameReleaseDecision.Drop -> {
                pendingSoftwareVideoOutput = null
                droppedFrames++
            }
            is YVideoFrameReleaseDecision.Render -> {
                if (decision.releaseTimeNs > nowNs + SOFTWARE_RENDER_EARLY_TOLERANCE_NS) {
                    pendingSoftwareVideoOutput = output
                    return false
                }
                pendingSoftwareVideoOutput = null
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Renderer,
                    stage = YPlaybackFailureStage.VideoRenderer,
                    safeDetail = "FFmpeg software video frame release",
                ) {
                    softwareVideoRenderer.render(output)
                }
                firstVideoFrameRendered = true
                val renderedNs = System.nanoTime()
                lastAvSyncOffsetUs =
                    audioClockSnapshot()?.let { clock ->
                        YAvSync.offsetUs(
                            videoPresentationTimeUs = output.presentationTimeUs,
                            videoRenderedRealtimeNs = renderedNs,
                            master = YClockSnapshot(clock.positionUs, clock.realtimeNs),
                            speed = speed,
                        )
                    }
            }
        }
        lastVideoUs = output.presentationTimeUs
        seekVideoTargetUs = 0L
        return true
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

    private fun currentPositionUs(): Long =
        audioClockSnapshot()?.positionUs
            ?: if (playing) {
                wallClock.positionUs(System.nanoTime())
            } else {
                lastVideoUs.coerceAtLeast(0L)
            }

    private fun pauseAtEnd() {
        val endUs = maxOf(lastVideoUs, openResult?.durationUs ?: 0L)
        playing = false
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
                yPlaybackStage(
                    category = YPlaybackFailureCategory.AudioSink,
                    stage = YPlaybackFailureStage.AudioRenderer,
                    safeDetail = "Enhanced encoded audio write",
                ) {
                    encodedAudioRenderer.write(
                        java.nio.ByteBuffer.wrap(sample.data),
                        sample.presentationTimeUs,
                    )
                }
            } catch (_: Exception) {
                val position = currentPositionUs()
                rejectedPassthroughTracks += requireNotNull(audioTrack).id
                switchPassthroughToPcm(position, countFailure = true)
                return YCodecQueueResult.Queued
            }
            seekAudioTargetUs = 0L
        }
        return YCodecQueueResult.Queued
    }

    private fun queueSubtitleSample(sample: YCompressedSample) {
        val format = subtitleTrack?.subtitle?.format ?: return
        if (format.textOverlaySupported) {
            YEmbeddedSubtitleDecoder
                .decode(
                    data = sample.data,
                    format = format,
                    startUs = sample.presentationTimeUs,
                    durationUs = sample.durationUs,
                    id = "${subtitleTrack?.id?.value}:${sample.presentationTimeUs}",
                )?.let(subtitleCues::add)
        } else {
            val decoder = demuxer as? YSubtitlePacketDecoder ?: return
            val decoded =
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Container,
                    stage = YPlaybackFailureStage.Bitstream,
                    safeDetail = "Enhanced bitmap subtitle decode",
                ) {
                    decoder.decodeSubtitle(sample)
                }
            subtitleCues.addAll(decoded)
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
        seekToInternal(positionUs, tailRetry = false, resetVideoDecoder = false)
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

private fun YCompressedSample.toExtractorFlags(): Int =
    when {
        YSampleFlag.Encrypted in flags -> 2
        YSampleFlag.Sync in flags -> 1
        else -> 0
    }

private fun Boolean.toCodecQueueResult(): YCodecQueueResult = if (this) YCodecQueueResult.Queued else YCodecQueueResult.TryAgain

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

private const val DEFAULT_INPUT_AHEAD_US = 1_500_000L
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val LATE_FRAME_DROP_NS = 100_000_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
private const val SOFTWARE_RENDER_EARLY_TOLERANCE_NS = 2_000_000L
private const val SUBTITLE_HISTORY_US = 60_000_000L
private const val FFMPEG_SOFTWARE_VIDEO_NAME = "FFmpeg software video"
private const val FFMPEG_SOFTWARE_AUDIO_NAME = "FFmpeg software audio"
private val BITMAP_SUBTITLE_FORMATS =
    setOf(
        YSubtitleFormat.Pgs,
        YSubtitleFormat.VobSub,
    )
