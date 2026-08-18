package com.yfuse.core2.android

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
    private val createTrack: (MediaFormat) -> AudioTrack = ::buildAudioTrack,
) : YAudioRenderNode {
    override val name: String = "AudioTrack"

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var basePresentationTimeUs: Long? = null
    private var requestedPlay = false
    private var speed = 1f

    fun configure(format: MediaFormat) {
        release()
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        track = createTrack(format)
        basePresentationTimeUs = null
        requestedPlay = false
        speed = 1f
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

    /** Writes one decoded PCM access unit. Returns bytes consumed or throws on AudioTrack error. */
    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "AudioTrack render node has not been configured" }
        if (basePresentationTimeUs == null && data.hasRemaining()) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        val size = data.remaining()
        if (size == 0) return 0
        val written = audioTrack.write(data, size, AudioTrack.WRITE_BLOCKING)
        check(written >= 0) { "AudioTrack.write failed with code $written" }
        return written
    }

    fun clockSnapshot(): YAudioClockSnapshot? {
        val audioTrack = track ?: return null
        val baseUs = basePresentationTimeUs ?: return null
        if (sampleRate <= 0) return null
        val timestamp = AudioTimestamp()
        val hasTimestamp = runCatching { audioTrack.getTimestamp(timestamp) }.getOrDefault(false)
        val frames =
            if (hasTimestamp) {
                timestamp.framePosition
            } else {
                audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            }
        val positionUs = baseUs + frames * MICROS_PER_SECOND / sampleRate
        val realtimeNs = if (hasTimestamp) timestamp.nanoTime else System.nanoTime()
        return YAudioClockSnapshot(
            positionUs = positionUs.coerceAtLeast(0L),
            realtimeNs = realtimeNs,
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
        if (resume) audioTrack.play()
    }

    override fun release() {
        val audioTrack = track
        track = null
        requestedPlay = false
        sampleRate = 0
        basePresentationTimeUs = null
        if (audioTrack != null) {
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
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
    val channelMask = channelMaskForCount(channelCount)
    val minBuffer =
        AudioTrack.getMinBufferSize(
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
        .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(DEFAULT_AUDIO_BUFFER_BYTES))
        .build()
        .also { track ->
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
        }
}

private fun channelMaskForCount(channelCount: Int): Int =
    when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        3 -> AudioFormat.CHANNEL_OUT_2POINT1
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_SURROUND
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_6POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

private const val MICROS_PER_SECOND = 1_000_000L
private const val DEFAULT_AUDIO_BUFFER_BYTES = 64 * 1024
