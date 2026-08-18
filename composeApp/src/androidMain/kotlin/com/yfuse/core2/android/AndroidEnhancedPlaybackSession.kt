package com.yfuse.core2.android

import android.media.MediaCodec
import android.view.Surface
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSampleFlag
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.dolbyVisionHevcBaseLayerSample
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import com.yfuse.core2.sync.YMediaClock

internal data class YEnhancedPlaybackSnapshot(
    val positionUs: Long,
    val durationUs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val firstVideoFrameRendered: Boolean,
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val audioRendering: Boolean,
)

/**
 * Single-owner media worker for the Core2 NativeEnhanced graph.
 *
 * This class intentionally contains no Compose/UI code and no FFmpeg decoder. The demuxer yields
 * compressed samples; video goes through MediaCodec directly to Surface, while audio is decoded to
 * PCM and written to AudioTrack. All methods must be called serially from one playback worker.
 */
internal class AndroidEnhancedPlaybackSession(
    private val demuxer: YDemuxer = AndroidFfmpegDemuxer(),
    private val videoDecoder: AndroidMediaCodecVideoNode = AndroidMediaCodecVideoNode(),
    private val audioDecoder: AndroidMediaCodecAudioNode = AndroidMediaCodecAudioNode(),
    private val audioRenderer: AndroidAudioTrackRenderNode = AndroidAudioTrackRenderNode(),
) {
    private val wallClock = YMediaClock()

    private var plan: YPlaybackPlan? = null
    private var openResult: YDemuxOpenResult? = null
    private var sourceVideoTrack: YDemuxTrack? = null
    private var effectiveVideoTrack: YVideoTrackFormat? = null
    private var audioTrack: YDemuxTrack? = null
    private var surface: Surface? = null
    private var pendingSample: YCompressedSample? = null
    private var pendingVideoOutput: YCodecOutputResult.Buffer? = null
    private var playing = false
    private var prepared = false
    private var audioRendererConfigured = false
    private var inputEnded = false
    private var videoInputEnded = false
    private var audioInputEnded = false
    private var videoOutputEnded = false
    private var audioOutputEnded = false
    private var firstVideoFrameRendered = false
    private var seekVideoTargetUs = 0L
    private var seekAudioTargetUs = 0L
    private var lastQueuedUs = 0L
    private var lastVideoUs = 0L
    private var speed = 1f

    fun open(
        source: YDemuxSource,
        plan: YPlaybackPlan,
        surface: Surface,
        startPositionUs: Long = 0L,
    ): YDemuxOpenResult {
        close()
        require(plan.route == YPlaybackRoute.NativeEnhanced) { "Enhanced session requires NativeEnhanced route" }
        require(plan.decodePath == YDecodePath.Hardware) { "Enhanced session requires hardware decode" }
        require(plan.renderPath == YRenderPath.SurfaceDirect) { "Enhanced session currently requires SurfaceDirect" }
        require(surface.isValid) { "Enhanced session requires a valid Surface" }

        val result = demuxer.open(source)
        val videoTrack =
            result.tracks.firstOrNull { it.type == YDemuxTrackType.Video && it.video != null }
                ?: error("Enhanced demux contains no video track")
        val audioTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Audio && it.audio != null }
        val effectiveVideo = effectiveVideoTrack(requireNotNull(videoTrack.video), plan)

        try {
            videoDecoder.configure(AndroidMediaFormatFactory.video(effectiveVideo), surface)
            audioTrack?.audio?.let { audioDecoder.configure(AndroidMediaFormatFactory.audio(it)) }
            demuxer.selectTracks(
                buildSet {
                    add(videoTrack.id)
                    audioTrack?.let { add(it.id) }
                },
            )
        } catch (throwable: Throwable) {
            runCatching(videoDecoder::release)
            runCatching(audioDecoder::release)
            runCatching(demuxer::close)
            throw throwable
        }

        this.plan = plan
        this.openResult = result
        this.sourceVideoTrack = videoTrack
        this.effectiveVideoTrack = effectiveVideo
        this.audioTrack = audioTrack
        this.surface = surface
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
            audioRenderer.setSpeed(speed)
            audioRenderer.play()
        }
    }

    fun pause() {
        if (!prepared) return
        val position = currentPositionUs()
        playing = false
        audioRenderer.pause()
        wallClock.pause(position, System.nanoTime())
    }

    fun setSpeed(value: Float) {
        require(value.isFinite() && value > 0f) { "Playback speed must be finite and positive" }
        val position = currentPositionUs()
        speed = value
        wallClock.setSpeed(value, position, System.nanoTime())
        if (audioRendererConfigured) audioRenderer.setSpeed(value)
    }

    fun setOutputSurface(next: Surface) {
        require(next.isValid) { "Output Surface is invalid" }
        check(prepared) { "Enhanced session is not prepared" }
        videoDecoder.setOutputSurface(next)
        surface = next
    }

    fun seekTo(positionUs: Long) {
        check(prepared) { "Enhanced session is not prepared" }
        val target = positionUs.coerceAtLeast(0L)
        demuxer.seekTo(target)
        videoDecoder.flush()
        if (audioTrack != null) audioDecoder.flush()
        if (audioRendererConfigured) audioRenderer.flush()
        pendingSample = null
        pendingVideoOutput = null
        resetEndState()
        seekVideoTargetUs = target
        seekAudioTargetUs = target
        lastQueuedUs = target
        lastVideoUs = target
        firstVideoFrameRendered = false
        wallClock.seek(target, System.nanoTime())
        if (playing) {
            wallClock.start(target, System.nanoTime())
            if (audioRendererConfigured) audioRenderer.play()
        }
    }

    /** Runs one bounded non-blocking media iteration. */
    fun pump(): Boolean {
        if (!prepared || !playing || ended()) return false
        var didWork = false
        didWork = drainAudio() || didWork
        didWork = drainVideo() || didWork
        didWork = feedInput() || didWork
        if (ended()) {
            pauseAtEnd()
        }
        return didWork
    }

    fun snapshot(): YEnhancedPlaybackSnapshot =
        YEnhancedPlaybackSnapshot(
            positionUs = currentPositionUs(),
            durationUs = openResult?.durationUs ?: 0L,
            playing = playing && !ended(),
            buffering = playing && !firstVideoFrameRendered && !ended(),
            ended = ended(),
            firstVideoFrameRendered = firstVideoFrameRendered,
            videoDecoderName = videoDecoder.decoderName,
            audioDecoderName = audioDecoder.decoderName,
            audioRendering = audioRendererConfigured && audioRenderer.clockSnapshot() != null,
        )

    fun close() {
        pendingVideoOutput?.let { output ->
            runCatching { videoDecoder.releaseOutput(output, render = false) }
        }
        pendingVideoOutput = null
        pendingSample = null
        runCatching(audioRenderer::release)
        runCatching(audioDecoder::release)
        runCatching(videoDecoder::release)
        runCatching(demuxer::close)
        plan = null
        openResult = null
        sourceVideoTrack = null
        effectiveVideoTrack = null
        audioTrack = null
        surface = null
        playing = false
        prepared = false
        audioRendererConfigured = false
        resetEndState()
    }

    private fun feedInput(): Boolean {
        if (inputEnded) return queueEndOfStream()
        if (lastQueuedUs - currentPositionUs() > MAX_INPUT_AHEAD_US) return false

        val sample = pendingSample ?: demuxer.readSample()
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
                    val transformed = transformVideoSample(sample.data)
                    videoDecoder.queueAccessUnit(
                        data = java.nio.ByteBuffer.wrap(transformed),
                        presentationTimeUs = sample.presentationTimeUs,
                        flags = sample.toExtractorFlags(),
                    )
                }
                audioTrack?.id ->
                    audioDecoder.queueAccessUnit(
                        data = java.nio.ByteBuffer.wrap(sample.data),
                        presentationTimeUs = sample.presentationTimeUs,
                        flags = sample.toExtractorFlags(),
                    )
                else -> YCodecQueueResult.Queued
            }
        if (queued != YCodecQueueResult.Queued) {
            pendingSample = sample
            return false
        }
        pendingSample = null
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
        if (!audioInputEnded && audioTrack != null) {
            if (audioDecoder.queueEndOfStream(lastQueuedUs) == YCodecQueueResult.Queued) {
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
            val config = requireNotNull(sourceTrack.dolbyVisionConfig) {
                "HDR fallback requires explicit Dolby Vision configuration"
            }
            require(config.profile == 8 && config.codecFamily == com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Hevc) {
                "Only HEVC Dolby Vision Profile 8 compatible-base fallback is executable"
            }
            require(config.compatibleBaseHdr == currentPlan.outputHdrType) {
                "Dolby compatible base does not match the selected output HDR route"
            }
            val packing = requireNotNull(sourceTrack.samplePacking) {
                "Dolby HEVC fallback requires known NAL packing"
            }
            return dolbyVisionHevcBaseLayerSample(data, packing)
        }
        return normalizeVideoSampleForMediaCodec(data, sourceTrack)
    }

    private fun drainAudio(): Boolean {
        if (audioTrack == null || audioOutputEnded) return false
        return when (val output = audioDecoder.dequeueOutput()) {
            YAudioCodecOutputResult.TryAgain -> false
            is YAudioCodecOutputResult.FormatChanged -> {
                audioRenderer.configure(output.format)
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
                        audioRenderer.write(audioDecoder.outputData(output), output.presentationTimeUs)
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
        val output = pendingVideoOutput ?: when (val dequeued = videoDecoder.dequeueOutput()) {
            YCodecOutputResult.TryAgain -> return false
            is YCodecOutputResult.FormatChanged -> return true
            is YCodecOutputResult.Buffer -> dequeued
        }

        val config = output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val renderable = !config && output.size > 0 && output.presentationTimeUs >= seekVideoTargetUs
        if (!renderable) {
            pendingVideoOutput = null
            videoDecoder.releaseOutput(output, render = false)
            if (output.endOfStream) videoOutputEnded = true
            return true
        }

        // Audio is the master when present. Hold the first video buffer until AudioTrack has a real
        // clock rather than starting video early and correcting drift after the fact.
        if (audioTrack != null && audioRenderer.clockSnapshot() == null) {
            pendingVideoOutput = output
            return false
        }

        val currentUs = currentPositionUs()
        if (output.presentationTimeUs - currentUs > MAX_VIDEO_SCHEDULE_AHEAD_US) {
            pendingVideoOutput = output
            return false
        }
        pendingVideoOutput = null
        val nowNs = System.nanoTime()
        val renderNs =
            if (audioTrack != null) {
                audioRenderer.presentationTimeNs(output.presentationTimeUs, nowNs)
            } else {
                wallClock.presentationTimeNs(output.presentationTimeUs)
            }.coerceAtLeast(nowNs - LATE_FRAME_IMMEDIATE_NS)
        videoDecoder.releaseOutput(output, render = true, renderTimeNs = renderNs)
        lastVideoUs = output.presentationTimeUs
        seekVideoTargetUs = 0L
        firstVideoFrameRendered = true
        if (output.endOfStream) videoOutputEnded = true
        return true
    }

    private fun currentPositionUs(): Long =
        audioRenderer.clockSnapshot()?.positionUs
            ?: if (playing) {
                wallClock.positionUs(System.nanoTime())
            } else {
                lastVideoUs.coerceAtLeast(0L)
            }

    private fun pauseAtEnd() {
        val endUs = maxOf(lastVideoUs, openResult?.durationUs ?: 0L)
        playing = false
        audioRenderer.pause()
        wallClock.pause(endUs, System.nanoTime())
    }

    private fun ended(): Boolean =
        videoOutputEnded && (audioTrack == null || audioOutputEnded)

    private fun resetEndState() {
        inputEnded = false
        videoInputEnded = false
        audioInputEnded = audioTrack == null
        videoOutputEnded = false
        audioOutputEnded = audioTrack == null
        pendingVideoOutput = null
    }

    private fun effectiveVideoTrack(
        source: YVideoTrackFormat,
        plan: YPlaybackPlan,
    ): YVideoTrackFormat {
        if (!plan.usesHdrFallback) return source
        val config = requireNotNull(source.dolbyVisionConfig) {
            "HDR fallback route requires Dolby Vision configuration"
        }
        require(config.profile == 8 && config.compatibleBaseHdr == plan.outputHdrType) {
            "Only explicit Profile 8 compatible-base fallback is supported"
        }
        require(source.codec == YVideoCodec.H265) { "Profile 8 compatible base requires HEVC" }
        return source.copy(
            mimeType = "video/hevc",
            hdrType = plan.outputHdrType,
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

private const val MAX_INPUT_AHEAD_US = 1_500_000L
private const val MAX_VIDEO_SCHEDULE_AHEAD_US = 250_000L
private const val LATE_FRAME_IMMEDIATE_NS = 50_000_000L
