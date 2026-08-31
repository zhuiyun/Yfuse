package com.yfuse.core2.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaFormat
import android.media.PlaybackParams
import com.yfuse.core2.graph.YAudioRenderNode
import java.nio.ByteBuffer

internal data class YAudioClockSnapshot(
    val positionUs: Long,
    val realtimeNs: Long,
)

/**
 * PCM sink and baseline audio-master clock for Core2 NativeDirect.
 *
 * Compressed passthrough is a later route; this node handles decoded PCM without involving Compose
 * or the video renderer. AudioTimestamp is exposed so video can schedule Surface frames against the
 * actual hardware playback clock instead of an unrelated wall clock.
 */
internal class AndroidAudioTrackRenderNode(
    context: Context? = null,
    private val createTrack: (MediaFormat) -> AudioTrack = ::buildAudioTrack,
) : YAudioRenderNode {
    override val name: String = "AudioTrack"

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var basePresentationTimeUs: Long? = null
    private var requestedPlay = false
    private var speed = 1f
    private val clockProgressGuard = AndroidAudioClockProgressGuard()
    private var lastClockSource: YAudioClockFrameSource? = null
    private var staleClockFallback = false
    private val spatialAudioProbe = context?.let(::AndroidSpatialAudioProbe)
    private var spatialAudioState = AndroidSpatialAudioState()

    val spatialAudioOutput: Boolean
        get() = clockSnapshot() != null && spatialAudioState.active

    val headTrackingAvailable: Boolean
        get() = spatialAudioOutput && spatialAudioState.headTrackerAvailable

    val clockSource: String
        get() = lastClockSource?.name ?: if (staleClockFallback) "WallClockFallback" else "Unavailable"

    val clockStalled: Boolean
        get() = staleClockFallback

    fun configure(format: MediaFormat) {
        release()
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        track = createTrack(format)
        spatialAudioState = spatialAudioProbe?.current(format) ?: AndroidSpatialAudioState()
        basePresentationTimeUs = null
        requestedPlay = false
        speed = 1f
        resetClockProgress()
    }

    fun play() {
        requestedPlay = true
        track?.let { audioTrack ->
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play()
        }
    }

    fun pause() {
        requestedPlay = false
        track?.let { audioTrack ->
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        }
    }

    fun setSpeed(value: Float) {
        require(value.isFinite() && value > 0f) { "Audio playback speed must be finite and positive" }
        speed = value
        track?.let { audioTrack ->
            runCatching {
                audioTrack.playbackParams =
                    PlaybackParams()
                        .setSpeed(value)
                        .setPitch(1f)
                        .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
            }
        }
    }

    /** Writes the complete decoded PCM access unit or throws on an AudioTrack error. */
    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "AudioTrack render node has not been configured" }
        if (basePresentationTimeUs == null && data.hasRemaining()) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        var total = 0
        while (data.hasRemaining()) {
            val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "AudioTrack.write failed with code $written" }
            if (written == 0) continue
            total += written
        }
        return total
    }

    /**
     * Writes only the PCM bytes accepted immediately by AudioTrack.
     *
     * NativeDirect owns video pacing and audio delivery on the same worker. Blocking here until
     * the sink has room would also stop MediaCodec video dequeue/release, producing visible bursts
     * and stalls. The caller keeps the codec output buffer until [data] is fully consumed.
     */
    fun writeNonBlocking(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "AudioTrack render node has not been configured" }
        if (!data.hasRemaining()) return 0
        val shouldAnchorClock = basePresentationTimeUs == null
        val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "AudioTrack.write failed with code $written" }
        if (shouldAnchorClock && written > 0) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        return written
    }

    val underrunCount: Int
        get() = track?.underrunCount?.coerceAtLeast(0) ?: 0

    fun clockSnapshot(): YAudioClockSnapshot? {
        val audioTrack = track ?: return null
        val baseUs = basePresentationTimeUs ?: return null
        if (sampleRate <= 0) return null
        val nowNs = System.nanoTime()
        val timestamp = AudioTimestamp()
        val hasTimestamp = runCatching { audioTrack.getTimestamp(timestamp) }.getOrDefault(false)
        val selection =
            clockProgressGuard.select(
                nowNs = nowNs,
                playing = requestedPlay && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING,
                timestampFrames = timestamp.framePosition.takeIf { hasTimestamp },
                timestampRealtimeNs = timestamp.nanoTime.takeIf { hasTimestamp },
                playbackHeadFrames = audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL,
            )
        if (selection == null) {
            lastClockSource = null
            staleClockFallback = true
            return null
        }
        lastClockSource = selection.source
        staleClockFallback = false
        val positionUs = baseUs + selection.framePosition * MICROS_PER_SECOND / sampleRate
        return YAudioClockSnapshot(
            positionUs = positionUs.coerceAtLeast(0L),
            realtimeNs = selection.realtimeNs,
        )
    }

    fun presentationTimeNs(
        videoPresentationTimeUs: Long,
        fallbackRealtimeNs: Long,
    ): Long {
        val clock = clockSnapshot() ?: return fallbackRealtimeNs
        val mediaDeltaUs = videoPresentationTimeUs - clock.positionUs
        val scaledDeltaNs = mediaDeltaUs.toDouble() * 1_000.0 / speed.toDouble()
        return clock.realtimeNs + scaledDeltaNs.toLong()
    }

    override fun flush() {
        val audioTrack = track ?: return
        val resume = requestedPlay
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        audioTrack.flush()
        basePresentationTimeUs = null
        resetClockProgress()
        if (resume) audioTrack.play()
    }

    override fun release() {
        val audioTrack = track
        track = null
        requestedPlay = false
        sampleRate = 0
        basePresentationTimeUs = null
        resetClockProgress()
        spatialAudioState = AndroidSpatialAudioState()
        if (audioTrack != null) {
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
    }

    private fun resetClockProgress() {
        clockProgressGuard.reset()
        lastClockSource = null
        staleClockFallback = false
    }
}

private fun buildAudioTrack(format: MediaFormat): AudioTrack {
    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val encoding =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    val channelMask =
        if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
        } else {
            channelMaskForCount(channelCount)
        }
    val minBuffer =
        AudioTrack
            .getMinBufferSize(
                sampleRate,
                channelMask,
                encoding,
            ).takeIf { it > 0 } ?: DEFAULT_AUDIO_BUFFER_BYTES
    val audioFormat =
        AudioFormat
            .Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
    return AudioTrack
        .Builder()
        .setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        ).setAudioFormat(audioFormat)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            nativeDirectAudioBufferSizeBytes(
                minimumBufferBytes = minBuffer,
                sampleRate = sampleRate,
                channelCount = channelCount,
                encoding = encoding,
            ),
        ).build()
        .also { track ->
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
        }
}

internal fun nativeDirectAudioBufferSizeBytes(
    minimumBufferBytes: Int,
    sampleRate: Int,
    channelCount: Int,
    encoding: Int,
): Int {
    require(minimumBufferBytes > 0 && sampleRate > 0 && channelCount > 0)
    val bytesPerSample =
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_32BIT,
            -> 4
            else -> 2
        }
    val resilientBuffer =
        (sampleRate.toLong() * channelCount * bytesPerSample * TARGET_AUDIO_BUFFER_SECONDS)
            .coerceAtMost(MAX_AUDIO_BUFFER_BYTES.toLong())
            .toInt()
    return maxOf(
        minimumBufferBytes.toLong() * MINIMUM_AUDIO_BUFFER_MULTIPLIER,
        DEFAULT_AUDIO_BUFFER_BYTES.toLong(),
        resilientBuffer.toLong(),
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun channelMaskForCount(channelCount: Int): Int =
    when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        3 ->
            AudioFormat.CHANNEL_OUT_FRONT_LEFT or
                AudioFormat.CHANNEL_OUT_FRONT_RIGHT or
                AudioFormat.CHANNEL_OUT_FRONT_CENTER
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

private const val MICROS_PER_SECOND = 1_000_000L
private const val DEFAULT_AUDIO_BUFFER_BYTES = 64 * 1024
private const val MAX_AUDIO_BUFFER_BYTES = 2 * 1024 * 1024
private const val MINIMUM_AUDIO_BUFFER_MULTIPLIER = 4L
private const val TARGET_AUDIO_BUFFER_SECONDS = 2L
