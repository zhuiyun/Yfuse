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
    private var bytesPerFrame = 0
    private var playing = false
    private var mediaBaseUs: Long? = null
    private var frameBase: Long? = null

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
        bytesPerFrame = channelCount * bytesPerSample(encoding)
        val channelMask = channelMask(channelCount)
        val audioFormat =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()
        val minBuffer =
            AudioTrack
                .getMinBufferSize(sampleRate, channelMask, encoding)
                .takeIf { it > 0 }
                ?: fallbackBufferBytes(sampleRate, channelCount, encoding)
        val bufferSize = max(minBuffer, fallbackBufferBytes(sampleRate, channelCount, encoding))
        val created =
            AudioTrack
                .Builder()
                .setAudioAttributes(tunnel.audioAttributes())
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setSessionId(tunnel.audioSessionId)
                .build()
        check(created.state == AudioTrack.STATE_INITIALIZED) { "HW AV sync AudioTrack failed to initialize" }
        track = created
        resetClockAnchor()
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
        resetClockAnchor()
        if (resume) active.play()
    }

    /**
     * Timestamped non-blocking write. [byteOffsetFromAccessUnit] is the number of PCM bytes already
     * accepted from this decoder output buffer, so a partial retry advances the HW_AV_SYNC media
     * timestamp instead of reusing the timestamp of the beginning of the access unit.
     */
    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
        byteOffsetFromAccessUnit: Int = 0,
    ): Int {
        val active = checkNotNull(track) { "HW AV sync AudioTrack is not configured" }
        if (!data.hasRemaining()) return 0
        val adjustedPresentationUs =
            presentationTimeUs.coerceAtLeast(0L) + durationUsForPcmBytes(byteOffsetFromAccessUnit)
        if (mediaBaseUs == null) {
            mediaBaseUs = adjustedPresentationUs
            frameBase = currentFramePosition(active)
        }
        val written =
            active.write(
                data,
                data.remaining(),
                AudioTrack.WRITE_NON_BLOCKING,
                adjustedPresentationUs * NANOS_PER_MICROSECOND,
            )
        check(written >= 0) { "HW AV sync AudioTrack write failed: $written" }
        return written
    }

    fun durationUsForPcmBytes(byteCount: Int): Long {
        if (byteCount <= 0 || bytesPerFrame <= 0 || sampleRate <= 0) return 0L
        val frames = byteCount / bytesPerFrame
        return frames.toLong() * MICROS_PER_SECOND / sampleRate
    }

    fun clockSnapshot(): AndroidTunnelAudioClockSnapshot? {
        val active = track ?: return null
        val baseUs = mediaBaseUs ?: return null
        val baseFrame = frameBase ?: return null
        val timestamp = AudioTimestamp()
        if (!active.getTimestamp(timestamp)) return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        val deltaFrames = (timestamp.framePosition - baseFrame).coerceAtLeast(0L)
        return AndroidTunnelAudioClockSnapshot(
            positionUs = baseUs + deltaFrames * MICROS_PER_SECOND / rate,
            nanoTime = timestamp.nanoTime,
        )
    }

    fun release() {
        val active = track
        track = null
        playing = false
        sampleRate = 0
        channelCount = 0
        bytesPerFrame = 0
        resetClockAnchor()
        if (active != null) {
            runCatching {
                if (active.playState == AudioTrack.PLAYSTATE_PLAYING) active.pause()
            }
            runCatching(active::flush)
            runCatching(active::release)
        }
    }

    private fun resetClockAnchor() {
        mediaBaseUs = null
        frameBase = null
    }

    private fun currentFramePosition(active: AudioTrack): Long {
        val timestamp = AudioTimestamp()
        if (active.getTimestamp(timestamp)) return timestamp.framePosition
        return active.playbackHeadPosition.toLong() and UINT32_MASK
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

private fun bytesPerSample(encoding: Int): Int =
    when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> 2
    }

private fun fallbackBufferBytes(
    sampleRate: Int,
    channelCount: Int,
    encoding: Int,
): Int {
    val bytesPerSample = bytesPerSample(encoding)
    // About 500 ms of PCM: enough headroom without building a large app-side buffer.
    return (sampleRate.toLong() * channelCount * bytesPerSample / 2L)
        .coerceIn(4_096L, Int.MAX_VALUE.toLong())
        .toInt()
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val UINT32_MASK = 0xffff_ffffL
