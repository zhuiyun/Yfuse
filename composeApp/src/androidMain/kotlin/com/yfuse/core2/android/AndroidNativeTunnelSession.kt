package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.yPlaybackStage
import com.yfuse.core2.demux.YDemuxTrackType
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
    val audioClockReady: Boolean,
)

/**
 * Platform-demuxed multimedia tunneling session.
 *
 * Compressed video stays MediaExtractor -> tunneled MediaCodec -> Surface. Audio is decoded to PCM,
 * then timestamp-written to an AudioTrack carrying FLAG_HW_AV_SYNC and the exact same session id.
 * Video output buffers are released immediately to the tunneled codec; hardware owns presentation
 * timing from that point onward.
 */
internal class AndroidNativeTunnelSession(
    private val context: Context,
    private val demuxer: AndroidMediaExtractorDemuxNode = AndroidMediaExtractorDemuxNode(context),
    private val videoDecoder: AndroidMediaCodecVideoNode = AndroidMediaCodecVideoNode(),
    private val audioDecoder: AndroidMediaCodecAudioNode = AndroidMediaCodecAudioNode(),
) {
    private val fallbackClock = YMediaClock()
    private val frameRateManager = AndroidFrameRateManager()

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
    private var videoOutputEnded = false
    private var audioOutputEnded = false
    private var firstVideoBufferReleased = false
    private var pendingAudioOutput: PendingAudioOutput? = null
    private var lastQueuedUs = 0L
    private var lastPositionUs = 0L

    fun open(
        source: YAndroidMediaSource,
        surface: Surface,
        startPositionUs: Long = 0L,
    ) {
        close()
        require(surface.isValid) { "Tunnel session requires a valid Surface" }
        val tunnelConfig =
            AndroidTunnelConfigurationFactory.create(context)
                ?: error("Platform did not provide a valid tunnel audio session id")
        demuxer.open(source)
        val videoIndex = demuxer.findFirstTrack("video/") ?: error("Tunnel source has no video track")
        val audioIndex = demuxer.findFirstTrack("audio/") ?: error("Tunnel source has no audio track")
        val originalVideoFormat = demuxer.trackFormat(videoIndex)
        val videoFormat = tunnelConfig.configureVideoFormat(originalVideoFormat)
        val audioFormat = demuxer.trackFormat(audioIndex)

        try {
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.VideoDecoderConfigure,
            ) {
                videoDecoder.configure(videoFormat, surface)
            }
            yPlaybackStage(
                category = YPlaybackFailureCategory.Decoder,
                stage = YPlaybackFailureStage.AudioDecoderConfigure,
            ) {
                audioDecoder.configure(audioFormat)
            }
            demuxer.selectTracks(setOf(videoIndex, audioIndex))
        } catch (failure: Throwable) {
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
        resetEndState()
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
        demuxer.seekTo(target)
        videoDecoder.flush()
        audioDecoder.flush()
        pendingAudioOutput?.let { pending ->
            runCatching { audioDecoder.releaseOutput(pending.output) }
        }
        pendingAudioOutput = null
        audioRenderer?.flush()
        resetEndState()
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
        didWork = drainVideo() || didWork
        didWork = feedInput() || didWork
        if (ended()) pauseAtEnd()
        return didWork
    }

    fun snapshot(): YTunnelPlaybackSnapshot {
        val audioReady = audioRenderer?.clockSnapshot() != null
        return YTunnelPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = durationUs,
            playing = playing && !ended(),
            buffering = playing && !audioReady && !ended(),
            ended = ended(),
            videoDecoderName = videoDecoder.decoderName,
            audioDecoderName = audioDecoder.decoderName,
            audioClockReady = audioReady,
        )
    }

    fun close() {
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
        surface = null
        videoTrackIndex = null
        audioTrackIndex = null
        durationUs = 0L
        prepared = false
        playing = false
        resetEndState()
    }

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (lastQueuedUs - currentPositionUs() > MAX_INPUT_AHEAD_US) return false
        val sample = demuxer.readSample() ?: run {
            inputEnded = true
            return true
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
                            sample.flags,
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
                            sample.flags,
                        )
                    }
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) {
            demuxer.retainSample(sample)
            return false
        }
        lastQueuedUs = maxOf(lastQueuedUs, sample.presentationTimeUs)
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

    private fun drainVideo(): Boolean =
        when (val output = videoDecoder.dequeueOutput()) {
            YCodecOutputResult.TryAgain -> false
            is YCodecOutputResult.FormatChanged -> true
            is YCodecOutputResult.Buffer -> {
                val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val render = !config && output.size > 0
                yPlaybackStage(
                    category = YPlaybackFailureCategory.Renderer,
                    stage = YPlaybackFailureStage.VideoRenderer,
                ) {
                    videoDecoder.releaseOutput(output, render = render)
                }
                if (render) firstVideoBufferReleased = true
                if (output.endOfStream) videoOutputEnded = true
                true
            }
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
                    renderer.write(pending.data, pending.output.presentationTimeUs)
                }
            if (written == 0) return false
            if (!pending.data.hasRemaining()) {
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
                val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (config || output.size <= 0) {
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

    private fun currentPositionUs(): Long =
        audioRenderer?.clockSnapshot()?.positionUs
            ?: if (playing) fallbackClock.positionUs(System.nanoTime()) else lastPositionUs

    private fun pauseAtEnd() {
        lastPositionUs = maxOf(currentPositionUs(), durationUs)
        playing = false
        audioRenderer?.pause()
        fallbackClock.pause(lastPositionUs, System.nanoTime())
    }

    private fun ended(): Boolean = videoOutputEnded && audioOutputEnded

    private fun resetEndState() {
        inputEnded = false
        videoInputEnded = false
        audioInputEnded = false
        videoOutputEnded = false
        audioOutputEnded = false
        firstVideoBufferReleased = false
    }

    private data class PendingAudioOutput(
        val output: YAudioCodecOutputResult.Buffer,
        val data: ByteBuffer,
    )
}

private fun formatDurationUs(format: MediaFormat): Long? =
    if (format.containsKey(MediaFormat.KEY_DURATION)) {
        runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
    } else {
        null
    }

private const val MAX_INPUT_AHEAD_US = 1_500_000L
