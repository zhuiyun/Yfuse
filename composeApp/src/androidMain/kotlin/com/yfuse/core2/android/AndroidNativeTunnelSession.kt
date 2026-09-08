package com.yfuse.core2.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.network.YBufferConditions
import com.yfuse.core2.network.YBufferController
import com.yfuse.core2.network.YPlaybackBufferGate
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.render.videoFrameRateHint
import com.yfuse.core2.sync.YMediaClock
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer

internal data class YTunnelPlaybackSnapshot(
    val positionUs: Long,
    val durationUs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val videoOutputVerified: Boolean,
    val audioClockReady: Boolean,
    val tunneledOutput: Boolean = true,
    val audioOutputRoute: String = "",
    val audioOutputRouteVerified: Boolean = false,
    val audioOutputFingerprint: String = "",
    val sourceQueueBytes: Long = 0L,
    val sourceBufferedUs: Long = 0L,
    val sourceStarvationCount: Long = 0L,
    val pausedPreviewSubmittedUnconfirmed: Boolean = false,
)

/**
 * Platform-demuxed multimedia tunneling session.
 *
 * Compressed video stays MediaExtractor -> tunneled MediaCodec -> Surface sideband. The app never
 * dequeues video output buffers in this mode: compliant tunneled decoders may expose zero output
 * buffers and hardware/HWC owns presentation. Audio is decoded to PCM and timestamp-written to an
 * AudioTrack carrying FLAG_HW_AV_SYNC and the exact same session id.
 */
internal class AndroidNativeTunnelSession(
    private val context: Context,
    private val demuxer: AndroidTunnelPlatformDemuxer = AndroidTunnelPlatformDemuxer(context),
    private val videoDecoder: AndroidMediaCodecVideoNode = AndroidMediaCodecVideoNode(),
    private val audioDecoder: AndroidMediaCodecAudioNode = AndroidMediaCodecAudioNode(),
    frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
) {
    private val fallbackClock = YMediaClock()
    private val frameRateManager = AndroidFrameRateManager(context, frameRateSwitchMode)
    private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)

    private var tunnel: AndroidTunnelConfiguration? = null
    private var audioRenderer: AndroidTunnelAudioTrackRenderNode? = null
    private var surface: Surface? = null
    private var videoTrackIndex: Int? = null
    private var audioTrackIndex: Int? = null
    private var durationUs = 0L
    private var prepared = false
    private var playing = false
    private var inputEnded = false
    private var videoInputEnded = false
    private var audioInputEnded = false
    private var audioOutputEnded = false
    private var pendingAudioOutput: PendingAudioOutput? = null
    private var lastQueuedUs = 0L
    private var lastAudioEndUs = 0L
    private var lastPositionUs = 0L
    private val videoOutputEpoch = AndroidVideoOutputEpoch()
    private val pausedPreview = AndroidPausedVideoPreview()
    private val outputWatchdog = AndroidTunnelVideoOutputWatchdog()
    private var videoInputQueued = false
    private var previewDecoder = false
    private var previewPreroll: YCodecOutputResult.Buffer? = null
    private var plannedDecoderName: String? = null
    private var plannedDolbyVisionConfig: YDolbyVisionConfig? = null
    private var sourceRemote = false
    private var outputActive = false
    private var audioSeekTargetUs = 0L
    private var audioSampleRate = 0
    private var audioBytesPerFrame = 0
    private var bufferPlan = YBufferController.plan(YBufferConditions(remote = false))
    private var bufferGate = YPlaybackBufferGate(remote = false, resumePlaybackUs = 0L)

    @Volatile
    private var firstVideoFrameRendered = false
    private var runtimeRenderRecorded = false
    private var runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null

    @Volatile
    private var lastRenderedVideoUs = 0L

    @Volatile
    private var renderEvidenceFloorUs = 0L

    fun open(
        source: YAndroidMediaSource,
        surface: Surface,
        startPositionUs: Long = 0L,
        decoderName: String? = null,
        runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
        dolbyVisionConfig: YDolbyVisionConfig? = null,
    ) {
        close()
        sourceRemote = source.uri.isCore2RemoteMediaUri()
        plannedDecoderName = decoderName
        plannedDolbyVisionConfig = dolbyVisionConfig
        bufferPlan = YBufferController.plan(YBufferConditions(remote = sourceRemote))
        bufferGate = YPlaybackBufferGate(sourceRemote, bufferPlan.resumePlaybackUs, bufferPlan.startupPlaybackUs)
        this.runtimeCapabilityKey = runtimeCapabilityKey
        require(surface.isValid) { "Tunnel session requires a valid Surface" }
        val tunnelConfig =
            yPlaybackStage(
                category = YPlaybackFailureCategory.AudioSink,
                stage = YPlaybackFailureStage.AudioRenderer,
                safeDetail = "Tunnel audio session unavailable",
            ) {
                AndroidTunnelConfigurationFactory.create(context)
                    ?: error("Platform did not provide a valid tunnel audio session id")
            }
        yPlaybackStage(
            category = sourceFailureCategory(),
            stage = YPlaybackFailureStage.SourceOpen,
        ) {
            demuxer.open(source)
        }
        demuxer.configureBufferPlan(bufferPlan.targetAheadUs, bufferPlan.maximumBytes)
        val videoIndex =
            demuxer.findFirstTrack("video/")
                ?: throw YPlaybackException(
                    category = YPlaybackFailureCategory.Container,
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "Tunnel source has no video track",
                )
        val audioIndex =
            demuxer.findFirstTrack("audio/")
                ?: throw YPlaybackException(
                    category = YPlaybackFailureCategory.AudioSink,
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "Tunnel source has no audio track",
                )
        val originalVideoFormat = demuxer.trackFormat(videoIndex)
        dolbyVisionConfig?.let { config ->
            originalVideoFormat.applyDolbyVisionConfiguration(config)
        }
        val videoFormat = tunnelConfig.configureVideoFormat(originalVideoFormat)
        val audioFormat = demuxer.trackFormat(audioIndex)

        var videoConfiguredForProbe = false
        try {
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.VideoDecoderConfigure,
            ) {
                videoDecoder.configure(videoFormat, surface, decoderName)
            }
            videoConfiguredForProbe = true
            runtimeCapabilityKey?.let(runtimeCapabilities::recordConfigured)
            attachVideoRenderEvidence()
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderConfigure,
            ) {
                audioDecoder.configure(audioFormat)
            }
            demuxer.selectTracks(setOf(videoIndex, audioIndex))
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (!videoConfiguredForProbe) {
                runtimeCapabilityKey?.let(runtimeCapabilities::recordRejected)
            }
            frameRateManager.clear()
            runCatching(videoDecoder::release)
            runCatching(audioDecoder::release)
            runCatching(demuxer::release)
            throw failure
        }

        frameRateManager.attach(surface, originalVideoFormat.core2FrameRateHint())
        tunnel = tunnelConfig
        audioRenderer = AndroidTunnelAudioTrackRenderNode(tunnelConfig)
        this.surface = surface
        videoTrackIndex = videoIndex
        audioTrackIndex = audioIndex
        durationUs =
            listOf(originalVideoFormat, audioFormat)
                .mapNotNull(::formatDurationUs)
                .maxOrNull()
                ?: 0L
        prepared = true
        resetEndState(startPositionUs.coerceAtLeast(0L))
        if (startPositionUs > 0L) {
            seekTo(startPositionUs, previewWhilePaused = false)
        } else {
            fallbackClock.seek(0L, System.nanoTime())
        }
        demuxer.startReadAhead()
    }

    fun play() {
        check(prepared) { "Tunnel session is not prepared" }
        val previewResumeUs = pausedPreview.takeResumePosition()
        playing = true
        if (previewDecoder || previewResumeUs != null) {
            seekTo(previewResumeUs ?: lastPositionUs, previewWhilePaused = false)
        } else if (ended()) {
            seekTo(0L, previewWhilePaused = false)
        }
        refreshOutputGate()
    }

    fun pause() {
        if (!prepared) return
        outputWatchdog.suspendWaiting()
        val position = currentPositionUs()
        playing = false
        outputActive = false
        audioRenderer?.pause()
        fallbackClock.pause(position, System.nanoTime())
        lastPositionUs = position
    }

    fun setOutputSurface(next: Surface) {
        require(next.isValid) { "Tunnel Surface is invalid" }
        check(prepared)
        videoOutputEpoch.reset()
        outputWatchdog.reset()
        videoInputQueued = false
        firstVideoFrameRendered = false
        runtimeRenderRecorded = false
        renderEvidenceFloorUs = currentPositionUs()
        yPlaybackStage(
            category = YPlaybackFailureCategory.Renderer,
            stage = YPlaybackFailureStage.VideoRenderer,
        ) {
            videoDecoder.setOutputSurface(next)
        }
        surface = next
        frameRateManager.reattach(next)
        attachVideoRenderEvidence()
    }

    fun seekTo(
        positionUs: Long,
        previewWhilePaused: Boolean = true,
    ) {
        check(prepared)
        val target = positionUs.coerceAtLeast(0L)
        videoOutputEpoch.reset()
        previewPreroll?.let { runCatching { videoDecoder.releaseOutput(it, render = false) } }
        previewPreroll = null
        pendingAudioOutput?.let { runCatching { audioDecoder.releaseOutput(it.output) } }
        pendingAudioOutput = null
        demuxer.pauseReadAhead()
        yPlaybackStage(
            category = sourceFailureCategory(),
            stage = YPlaybackFailureStage.Seek,
        ) {
            demuxer.seekTo(target)
        }
        val nextPreviewDecoder = !playing && previewWhilePaused
        if (nextPreviewDecoder != previewDecoder) {
            previewDecoder = nextPreviewDecoder
            val format = demuxer.trackFormat(requireNotNull(videoTrackIndex))
            plannedDolbyVisionConfig?.let(format::applyDolbyVisionConfiguration)
            if (previewDecoder) {
                format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, false)
            } else {
                requireNotNull(tunnel).configureVideoFormat(format)
            }
            videoDecoder.configure(format, requireNotNull(surface), plannedDecoderName)
        }
        yPlaybackStage(
            category = YPlaybackFailureCategory.Decoder,
            stage = YPlaybackFailureStage.Seek,
        ) {
            videoDecoder.flush()
            audioDecoder.flush()
        }
        pendingAudioOutput?.let { pending ->
            runCatching { audioDecoder.releaseOutput(pending.output) }
        }
        pendingAudioOutput = null
        audioRenderer?.flush()
        resetEndState(target)
        attachVideoRenderEvidence()
        if (nextPreviewDecoder) pausedPreview.begin(target) else pausedPreview.clear()
        outputActive = false
        bufferGate.reset()
        audioSeekTargetUs = target
        lastQueuedUs = target
        lastPositionUs = target
        fallbackClock.seek(target, System.nanoTime())
        if (playing) fallbackClock.start(target, System.nanoTime())
        demuxer.startReadAhead()
    }

    /** One bounded non-blocking tunnel iteration. */
    fun pump(): Boolean {
        if (!prepared || (!playing && !pausedPreview.active) || ended()) return false
        if (playing && !refreshOutputGate()) return false
        var didWork = false
        if (playing) didWork = drainAudio() || didWork
        if (!pausedPreview.submitted) {
            if (previewDecoder) didWork = drainPreviewVideo() || didWork
            if (!pausedPreview.submitted) didWork = feedInput() || didWork
        } else if (previewDecoder) {
            didWork =
                pausedPreview.recycleSubmittedOutput(videoDecoder::dequeueOutput) {
                    videoDecoder.releaseOutput(it, render = false)
                } ||
                didWork
        }
        if (pausedPreview.active && firstVideoFrameRendered) pausedPreview.frameRendered()
        didWork = pausedPreview.finishCallbackWait() || didWork
        if (playing &&
            !previewDecoder &&
            outputWatchdog.observe(
                videoQueued = videoInputQueued,
                videoRendered = firstVideoFrameRendered,
                outputActive = outputActive,
                audioPositionUs = audioRenderer?.clockSnapshot()?.positionUs,
                endOfInput = inputEnded,
                nowNs = System.nanoTime(),
            )
        ) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Renderer,
                stage = YPlaybackFailureStage.VideoRenderer,
                safeDetail = "Tunnel audio progressed but no video render callback arrived within 30 seconds",
            )
        }
        if (playing && ended()) pauseAtEnd()
        return didWork
    }

    fun snapshot(): YTunnelPlaybackSnapshot {
        if (firstVideoFrameRendered && pausedPreview.resumePending) pausedPreview.frameRendered()
        val audioReady = audioRenderer?.clockSnapshot() != null
        val isEnded = ended()
        val readAhead = demuxer.snapshot()
        return YTunnelPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = durationUs,
            playing = playing && outputActive && firstVideoFrameRendered && !isEnded,
            buffering = playing && (!outputActive || !firstVideoFrameRendered) && !isEnded,
            ended = isEnded,
            videoDecoderName = videoDecoder.decoderName,
            audioDecoderName = audioDecoder.decoderName,
            videoOutputVerified = firstVideoFrameRendered,
            pausedPreviewSubmittedUnconfirmed = pausedPreview.submittedUnconfirmed,
            audioClockReady = audioReady,
            tunneledOutput = !previewDecoder,
            audioOutputRoute = audioRenderer?.audioRouteLabel.orEmpty(),
            audioOutputRouteVerified = audioRenderer?.audioRouteVerified == true,
            audioOutputFingerprint = audioRenderer?.audioRouteFingerprint.orEmpty(),
            sourceQueueBytes = readAhead.queuedBytes,
            sourceBufferedUs = readAhead.bufferedDurationUs,
            sourceStarvationCount = readAhead.starvationCount,
        )
    }

    fun close() {
        videoOutputEpoch.reset()
        pausedPreview.clear()
        previewDecoder = false
        previewPreroll = null
        outputActive = false
        pendingAudioOutput?.let { pending ->
            runCatching { audioDecoder.releaseOutput(pending.output) }
        }
        pendingAudioOutput = null
        frameRateManager.clear()
        runCatching { audioRenderer?.release() }
        audioRenderer = null
        runCatching(audioDecoder::release)
        runCatching(videoDecoder::release)
        runCatching(demuxer::release)
        tunnel = null
        runtimeCapabilityKey = null
        runtimeRenderRecorded = false
        surface = null
        videoTrackIndex = null
        audioTrackIndex = null
        durationUs = 0L
        prepared = false
        playing = false
        resetEndState(0L)
    }

    val previewPending: Boolean get() = pausedPreview.active

    fun cancelPendingRead() = demuxer.cancelPendingRead()

    fun release() {
        close()
        demuxer.close()
    }

    private fun sourceFailureCategory() =
        if (sourceRemote) YPlaybackFailureCategory.Network else YPlaybackFailureCategory.Container

    private fun attachVideoRenderEvidence() {
        val generation = videoOutputEpoch.reset()
        val provesTunnelOutput = !previewDecoder
        firstVideoFrameRendered = false
        videoDecoder.setOnFrameRenderedListener { presentationTimeUs, realtimeNs ->
            if (!videoOutputEpoch.rendered(generation, presentationTimeUs, realtimeNs) {
                    firstVideoFrameRendered = true
                }
            ) {
                return@setOnFrameRenderedListener
            }
            lastRenderedVideoUs = maxOf(lastRenderedVideoUs, presentationTimeUs)
            if (provesTunnelOutput && !runtimeRenderRecorded) {
                runtimeRenderRecorded = true
                runtimeCapabilityKey?.let(runtimeCapabilities::recordRendered)
            }
        }
    }

    private fun refreshOutputGate(): Boolean {
        val readAhead = demuxer.snapshot()
        demuxer.updatePlaybackWindow(
            YTransportPlaybackWindow(
                targetAheadUs = bufferPlan.forwardCacheTargetUs,
                bufferedUs = readAhead.bufferedDurationUs,
                minimumWarmBufferUs = minOf(bufferPlan.targetAheadUs / 2L, 8_000_000L),
                playing = playing && outputActive,
            ),
        )
        val decision =
            bufferGate.evaluate(
                bufferedDurationUs = readAhead.bufferedDurationUs,
                endOfInput = readAhead.endOfInput,
                bufferFull = readAhead.atCapacity,
            )
        if (decision.outputAllowed && !outputActive) {
            val position = currentPositionUs()
            outputActive = true
            fallbackClock.start(position, System.nanoTime())
            audioRenderer?.play()
        } else if (!decision.outputAllowed && outputActive) {
            outputWatchdog.suspendWaiting()
            val position = currentPositionUs()
            outputActive = false
            audioRenderer?.pause()
            fallbackClock.pause(position, System.nanoTime())
        }
        return decision.outputAllowed
    }

    /** Paused previews use ordinary hardware output; sideband is restored before audio resumes. */
    private fun drainPreviewVideo(): Boolean =
        when (val output = videoDecoder.dequeueOutput()) {
            YCodecOutputResult.TryAgain -> false
            is YCodecOutputResult.FormatChanged -> true
            is YCodecOutputResult.Buffer -> {
                val renderable = output.size > 0 && output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (renderable && output.presentationTimeUs < renderEvidenceFloorUs) {
                    previewPreroll?.let { videoDecoder.releaseOutput(it, render = false) }
                    previewPreroll = output
                } else {
                    val selected =
                        if (renderable) {
                            output
                        } else if (output.endOfStream) {
                            previewPreroll
                        } else {
                            null
                        }
                    if (selected != null) {
                        previewPreroll?.takeIf { it !== selected }?.let {
                            videoDecoder.releaseOutput(it, render = false)
                        }
                        previewPreroll = null
                        videoOutputEpoch.submitted(selected.presentationTimeUs)
                        videoDecoder.releaseOutput(selected, render = true)
                        pausedPreview.frameSubmitted()
                    }
                    if (selected !== output) videoDecoder.releaseOutput(output, render = false)
                }
                true
            }
        }

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (!pausedPreview.active && lastQueuedUs - currentPositionUs() > MAX_INPUT_AHEAD_US) return false
        val sample =
            when (val next = demuxer.peekSample()) {
                is YQueuedExtractorResult.Sample -> next.value
                is YQueuedExtractorResult.Failed -> throw YPlaybackException(
                    category = sourceFailureCategory(),
                    stage = YPlaybackFailureStage.Demux,
                    safeDetail = "Tunnel compressed sample read-ahead",
                    cause = next.cause,
                )
                YQueuedExtractorResult.Empty -> {
                    if (playing && lastQueuedUs - currentPositionUs() <= 150_000L) {
                        bufferGate.markStarved()
                        refreshOutputGate()
                    }
                    return false
                }
                YQueuedExtractorResult.EndOfInput -> {
                    inputEnded = true
                    return true
                }
            }
        if (sample.flags and EXTRACTOR_SAMPLE_ENCRYPTED != 0) {
            throw YPlaybackException(
                category = YPlaybackFailureCategory.Drm,
                stage = YPlaybackFailureStage.Demux,
                safeDetail = "Encrypted tunnel sample requires MediaCrypto",
            )
        }
        val queued =
            when (sample.trackIndex) {
                videoTrackIndex ->
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.Decoder,
                        stage = YPlaybackFailureStage.VideoDecoderQueue,
                    ) {
                        if (!previewDecoder && sample.presentationTimeUs >= renderEvidenceFloorUs) {
                            videoOutputEpoch.submitted(sample.presentationTimeUs)
                        }
                        videoDecoder.queueAccessUnit(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.flags,
                        )
                    }
                audioTrackIndex ->
                    if (pausedPreview.active) {
                        YCodecQueueResult.Queued
                    } else {
                        yPlaybackStage(
                            category = YPlaybackFailureCategory.Decoder,
                            stage = YPlaybackFailureStage.AudioDecoderQueue,
                        ) {
                            audioDecoder.queueAccessUnit(
                                sample.data,
                                sample.presentationTimeUs,
                                sample.flags,
                            )
                        }
                    }
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) return false
        if (sample.trackIndex == videoTrackIndex) videoInputQueued = true
        lastQueuedUs = maxOf(lastQueuedUs, sample.presentationTimeUs)
        demuxer.advance()
        return true
    }

    private fun queueEndOfStream(): Boolean {
        var queued = false
        if (!videoInputEnded) {
            if (videoDecoder.queueEndOfStream(lastQueuedUs) == YCodecQueueResult.Queued) {
                videoInputEnded = true
                queued = true
            }
        }
        if (!audioInputEnded) {
            if (audioDecoder.queueEndOfStream(lastQueuedUs) == YCodecQueueResult.Queued) {
                audioInputEnded = true
                queued = true
            }
        }
        return queued
    }

    private fun drainAudio(): Boolean {
        val renderer = audioRenderer ?: return false
        val pending = pendingAudioOutput
        if (pending != null) {
            val written =
                yPlaybackStage(
                    category = YPlaybackFailureCategory.AudioSink,
                    stage = YPlaybackFailureStage.AudioRenderer,
                ) {
                    renderer.write(
                        data = pending.data,
                        presentationTimeUs = pending.output.presentationTimeUs,
                        byteOffsetFromAccessUnit = pending.bytesWritten,
                    )
                }
            if (written == 0) return false
            pending.bytesWritten += written
            if (!pending.data.hasRemaining()) {
                val outputEndUs =
                    pending.output.presentationTimeUs.coerceAtLeast(0L) +
                        renderer.durationUsForPcmBytes(pending.output.size)
                lastAudioEndUs = maxOf(lastAudioEndUs, outputEndUs)
                audioDecoder.releaseOutput(pending.output)
                pendingAudioOutput = null
                if (pending.output.endOfStream) audioOutputEnded = true
            }
            return true
        }

        return when (val output = audioDecoder.dequeueOutput()) {
            YAudioCodecOutputResult.TryAgain -> false
            is YAudioCodecOutputResult.FormatChanged -> {
                audioSampleRate = output.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = output.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding =
                    if (output.format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        output.format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                audioBytesPerFrame = channels *
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        AudioFormat.ENCODING_PCM_8BIT -> 1
                        else -> 2
                    }
                yPlaybackStage(
                    category = YPlaybackFailureCategory.AudioSink,
                    stage = YPlaybackFailureStage.AudioRenderer,
                ) {
                    renderer.configure(output.format)
                }
                if (playing) renderer.play()
                true
            }
            is YAudioCodecOutputResult.Buffer -> {
                val codecConfig = output.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (codecConfig || output.size <= 0) {
                    lastAudioEndUs = maxOf(lastAudioEndUs, output.presentationTimeUs.coerceAtLeast(0L))
                    audioDecoder.releaseOutput(output)
                    if (output.endOfStream) audioOutputEnded = true
                    true
                } else {
                    val data = audioDecoder.outputData(output)
                    val skipBytes =
                        tunnelSeekAudioSkipBytes(
                            output.presentationTimeUs,
                            audioSeekTargetUs,
                            audioSampleRate,
                            audioBytesPerFrame,
                            data.remaining(),
                        )
                    data.position(data.position() + skipBytes)
                    if (!data.hasRemaining()) {
                        audioDecoder.releaseOutput(output)
                        if (output.endOfStream) audioOutputEnded = true
                        return true
                    }
                    audioSeekTargetUs = 0L
                    pendingAudioOutput =
                        PendingAudioOutput(
                            output = output,
                            data = data,
                            bytesWritten = skipBytes,
                        )
                    drainAudio()
                }
            }
        }
    }

    private fun currentPositionUs(): Long {
        val resolved =
            audioRenderer?.clockSnapshot()?.positionUs
                ?: if (outputActive) fallbackClock.positionUs(System.nanoTime()) else lastPositionUs
        lastPositionUs = maxOf(lastPositionUs, resolved)
        return resolved
    }

    private fun pauseAtEnd() {
        val clockPosition = currentPositionUs()
        lastPositionUs =
            maxOf(clockPosition, lastAudioEndUs, lastRenderedVideoUs)
                .let { position ->
                    if (durationUs > 0L) position.coerceAtMost(durationUs) else position
                }
        playing = false
        audioRenderer?.pause()
        fallbackClock.pause(lastPositionUs, System.nanoTime())
    }

    private fun ended(): Boolean {
        if (pausedPreview.resumePending || !firstVideoFrameRendered) return false
        if (!videoInputEnded || !audioOutputEnded) return false
        val targetUs = lastAudioEndUs.takeIf { it > 0L } ?: return false
        val audioPositionUs = audioRenderer?.clockSnapshot()?.positionUs ?: return false
        return audioPositionUs + AUDIO_END_TOLERANCE_US >= targetUs
    }

    private fun resetEndState(positionUs: Long) {
        outputWatchdog.reset()
        videoInputQueued = false
        inputEnded = false
        videoInputEnded = false
        audioInputEnded = false
        audioOutputEnded = false
        firstVideoFrameRendered = false
        runtimeRenderRecorded = false
        lastRenderedVideoUs = positionUs
        renderEvidenceFloorUs = positionUs
        lastAudioEndUs = positionUs
        audioSeekTargetUs = positionUs
    }

    private data class PendingAudioOutput(
        val output: YAudioCodecOutputResult.Buffer,
        val data: ByteBuffer,
        var bytesWritten: Int = 0,
    )
}

internal fun tunnelSeekAudioSkipBytes(
    presentationTimeUs: Long,
    targetUs: Long,
    sampleRate: Int,
    bytesPerFrame: Int,
    availableBytes: Int,
): Int {
    if (targetUs <= presentationTimeUs || sampleRate <= 0 || bytesPerFrame <= 0) return 0
    val frames = ((targetUs - presentationTimeUs) * sampleRate + 999_999L) / 1_000_000L
    return frames.coerceAtMost((availableBytes / bytesPerFrame).toLong()).toInt() * bytesPerFrame
}

private fun formatDurationUs(format: MediaFormat): Long? =
    if (format.containsKey(MediaFormat.KEY_DURATION)) {
        runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
    } else {
        null
    }

private fun MediaFormat.core2FrameRateHint() =
    if (containsKey(MediaFormat.KEY_FRAME_RATE)) {
        val frameRate =
            runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
        frameRate?.let(::videoFrameRateHint)
    } else {
        null
    }

private const val EXTRACTOR_SAMPLE_ENCRYPTED = 2
private const val MAX_INPUT_AHEAD_US = 1_500_000L
private const val AUDIO_END_TOLERANCE_US = 30_000L
