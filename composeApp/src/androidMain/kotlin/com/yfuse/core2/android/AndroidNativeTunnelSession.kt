package com.yfuse.core2.android

import android.content.Context
import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.render.videoFrameRateHint
import com.yfuse.core2.sync.YMediaClock
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
    private var callbackGeneration = 0L

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
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.SourceOpen,
        ) {
            demuxer.open(source)
        }
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
            val generation = ++callbackGeneration
            videoDecoder.setOnFrameRenderedListener { presentationTimeUs, _ ->
                if (
                    callbackGeneration == generation &&
                    presentationTimeUs >= renderEvidenceFloorUs
                ) {
                    lastRenderedVideoUs = maxOf(lastRenderedVideoUs, presentationTimeUs)
                    firstVideoFrameRendered = true
                    if (!runtimeRenderRecorded) {
                        runtimeRenderRecorded = true
                        runtimeCapabilityKey?.let(runtimeCapabilities::recordRendered)
                    }
                }
            }
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderConfigure,
            ) {
                audioDecoder.configure(audioFormat)
            }
            demuxer.selectTracks(setOf(videoIndex, audioIndex))
        } catch (failure: Throwable) {
            if (!videoConfiguredForProbe) {
                runtimeCapabilityKey?.let(runtimeCapabilities::recordRejected)
            }
            callbackGeneration++
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
            seekTo(startPositionUs)
        } else {
            fallbackClock.seek(0L, System.nanoTime())
        }
    }

    fun play() {
        check(prepared) { "Tunnel session is not prepared" }
        if (ended()) seekTo(0L)
        playing = true
        fallbackClock.start(currentPositionUs(), System.nanoTime())
        audioRenderer?.play()
    }

    fun pause() {
        if (!prepared) return
        val position = currentPositionUs()
        playing = false
        audioRenderer?.pause()
        fallbackClock.pause(position, System.nanoTime())
        lastPositionUs = position
    }

    fun setOutputSurface(next: Surface) {
        require(next.isValid) { "Tunnel Surface is invalid" }
        check(prepared)
        yPlaybackStage(
            category = YPlaybackFailureCategory.Renderer,
            stage = YPlaybackFailureStage.VideoRenderer,
        ) {
            videoDecoder.setOutputSurface(next)
        }
        surface = next
        frameRateManager.reattach(next)
    }

    fun seekTo(positionUs: Long) {
        check(prepared)
        val target = positionUs.coerceAtLeast(0L)
        yPlaybackStage(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Seek,
        ) {
            demuxer.seekTo(target)
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
        lastQueuedUs = target
        lastPositionUs = target
        fallbackClock.seek(target, System.nanoTime())
        if (playing) fallbackClock.start(target, System.nanoTime())
    }

    /** One bounded non-blocking tunnel iteration. */
    fun pump(): Boolean {
        if (!prepared || !playing || ended()) return false
        var didWork = false
        didWork = drainAudio() || didWork
        didWork = feedInput() || didWork
        if (ended()) pauseAtEnd()
        return didWork
    }

    fun snapshot(): YTunnelPlaybackSnapshot {
        val audioReady = audioRenderer?.clockSnapshot() != null
        val isEnded = ended()
        return YTunnelPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = durationUs,
            playing = playing && firstVideoFrameRendered && !isEnded,
            buffering = playing && !firstVideoFrameRendered && !isEnded,
            ended = isEnded,
            videoDecoderName = videoDecoder.decoderName,
            audioDecoderName = audioDecoder.decoderName,
            videoOutputVerified = firstVideoFrameRendered,
            audioClockReady = audioReady,
        )
    }

    fun close() {
        callbackGeneration++
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

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (lastQueuedUs - currentPositionUs() > MAX_INPUT_AHEAD_US) return false
        val sample =
            demuxer.peekSample() ?: run {
                inputEnded = true
                return true
            }
        if (sample.extractorFlags and EXTRACTOR_SAMPLE_ENCRYPTED != 0) {
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
                        videoDecoder.queueAccessUnit(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.extractorFlags,
                        )
                    }
                audioTrackIndex ->
                    yPlaybackStage(
                        category = YPlaybackFailureCategory.Decoder,
                        stage = YPlaybackFailureStage.AudioDecoderQueue,
                    ) {
                        audioDecoder.queueAccessUnit(
                            sample.data,
                            sample.presentationTimeUs,
                            sample.extractorFlags,
                        )
                    }
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) return false
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
                    pendingAudioOutput =
                        PendingAudioOutput(
                            output = output,
                            data = audioDecoder.outputData(output),
                        )
                    drainAudio()
                }
            }
        }
    }

    private fun currentPositionUs(): Long {
        val resolved =
            audioRenderer?.clockSnapshot()?.positionUs
                ?: if (playing) fallbackClock.positionUs(System.nanoTime()) else lastPositionUs
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
        if (!videoInputEnded || !audioOutputEnded) return false
        val targetUs = lastAudioEndUs.takeIf { it > 0L } ?: return false
        val audioPositionUs = audioRenderer?.clockSnapshot()?.positionUs ?: return false
        return audioPositionUs + AUDIO_END_TOLERANCE_US >= targetUs
    }

    private fun resetEndState(positionUs: Long) {
        inputEnded = false
        videoInputEnded = false
        audioInputEnded = false
        audioOutputEnded = false
        firstVideoFrameRendered = false
        runtimeRenderRecorded = false
        lastRenderedVideoUs = positionUs
        renderEvidenceFloorUs = positionUs
        lastAudioEndUs = positionUs
    }

    private data class PendingAudioOutput(
        val output: YAudioCodecOutputResult.Buffer,
        val data: ByteBuffer,
        var bytesWritten: Int = 0,
    )
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
