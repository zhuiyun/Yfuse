package com.yfuse.core2.android

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.max

internal data class AndroidTunnelAudioClockSnapshot(
    val positionUs: Long,
    val nanoTime: Long,
)

/**
 * HW-AV-sync AudioTrack used only by NativeTunnel.
 *
 * PCM is written with an explicit media timestamp; the paired tunneled video codec receives the
 * same audio session id through MediaFormat. This node does not perform software A/V correction.
 */
internal class AndroidTunnelAudioTrackRenderNode(
    private val tunnel: AndroidTunnelConfiguration,
) {
    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var playing = false

    fun configure(format: MediaFormat) {
        release()
        sampleRate = format.requirePositiveInt(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = format.requirePositiveInt(MediaFormat.KEY_CHANNEL_COUNT)
        encoding =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        val channelMask = channelMask(channelCount)
        val audioFormat =
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()
        val minBuffer =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                .takeIf { it > 0 }
                ?: fallbackBufferBytes(sampleRate, channelCount, encoding)
        val bufferSize = max(minBuffer, fallbackBufferBytes(sampleRate, channelCount, encoding))
        val created =
            AudioTrack.Builder()
                .setAudioAttributes(tunnel.audioAttributes())
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setSessionId(tunnel.audioSessionId)
                .build()
        check(created.state == AudioTrack.STATE_INITIALIZED) { "HW AV sync AudioTrack failed to initialize" }
        track = created
    }

    fun play() {
        val active = track ?: return
        if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
        playing = true
    }

    fun pause() {
        track?.let { active ->
            if (active.playState == AudioTrack.PLAYSTATE_PLAYING) active.pause()
        }
        playing = false
    }

    fun flush() {
        val active = track ?: return
        val resume = playing
        if (active.playState == AudioTrack.PLAYSTATE_PLAYING) active.pause()
        active.flush()
        if (resume) active.play()
    }

    /**
     * Timestamped non-blocking write. The caller retains the codec output buffer until [data] is
     * fully consumed, so partial writes never drop audio or its presentation timestamp.
     */
    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val active = checkNotNull(track) { "HW AV sync AudioTrack is not configured" }
        if (!data.hasRemaining()) return 0
        val written =
            active.write(
                data,
                data.remaining(),
                AudioTrack.WRITE_NON_BLOCKING,
                presentationTimeUs.coerceAtLeast(0L) * NANOS_PER_MICROSECOND,
            )
        check(written >= 0) { "HW AV sync AudioTrack write failed: $written" }
        return written
    }

    fun clockSnapshot(): AndroidTunnelAudioClockSnapshot? {
        val active = track ?: return null
        val timestamp = AudioTimestamp()
        if (!active.getTimestamp(timestamp)) return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        return AndroidTunnelAudioClockSnapshot(
            positionUs = timestamp.framePosition * MICROS_PER_SECOND / rate,
            nanoTime = timestamp.nanoTime,
        )
    }

    fun release() {
        val active = track
        track = null
        playing = false
        sampleRate = 0
        channelCount = 0
        if (active != null) {
            runCatching {
                if (active.playState == AudioTrack.PLAYSTATE_PLAYING) active.pause()
            }
            runCatching(active::flush)
            runCatching(active::release)
        }
    }
}

private fun MediaFormat.requirePositiveInt(key: String): Int {
    require(containsKey(key)) { "Audio output format is missing $key" }
    return getInteger(key).also { require(it > 0) { "Audio output format has invalid $key" } }
}

private fun channelMask(channelCount: Int): Int =
    when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> error("Unsupported tunnel PCM channel count: $channelCount")
    }

private fun fallbackBufferBytes(
    sampleRate: Int,
    channelCount: Int,
    encoding: Int,
): Int {
    val bytesPerSample =
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
    // About 500 ms of PCM: enough headroom without building a large app-side buffer.
    return (sampleRate.toLong() * channelCount * bytesPerSample / 2L)
        .coerceIn(4_096L, Int.MAX_VALUE.toLong())
        .toInt()
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
