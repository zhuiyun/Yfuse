package com.yfuse.core2.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Build
import androidx.annotation.RequiresApi
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.graph.YAudioRenderNode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

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
    private var audioDelayMs = 0L
    private var writtenBytes = 0L
    private val pcmTail = PcmTailTracker()
    private var zeroWriteCount = 0L
    private var startThresholdFrames = 0
    private var lastTimestampFrames: Long? = null
    private var lastPlaybackHeadFrames = 0L
    private val clockProgressGuard = AndroidAudioClockProgressGuard()
    private val routedOutputProgress = AndroidRoutedOutputProgress()
    private var lastClockSource: YAudioClockFrameSource? = null
    private var staleClockFallback = false
    private val spatialAudioProbe = context?.let(::AndroidSpatialAudioProbe)

    @Volatile
    private var spatialAudioState = AndroidSpatialAudioState()

    @Volatile
    private var configuredFormat: MediaFormat? = null

    private val routingGeneration = AtomicLong()
    private val routeChangeFilter = AudioRouteChangeFilter()
    private val routingListener =
        AudioRouting.OnRoutingChangedListener { router ->
            synchronized(this@AndroidAudioTrackRenderNode) {
                // Pausing the track (every rebuffer does) and resuming it also report "routing
                // changed", with the same device or none. Counting those as route changes invalidated
                // the output evidence and showed "音频路由已变化 · 等待新帧" after each rebuffer
                // (incident F); only a different output device is a route change.
                if (routeChangeFilter.routed(router.routedDeviceIdentity())) routingGeneration.incrementAndGet()
                // Android may change the start threshold when an output device changes.
                track?.let(::configureStartThreshold)
                configuredFormat?.let { format ->
                    spatialAudioState = spatialAudioProbe?.current(format) ?: AndroidSpatialAudioState()
                }
            }
        }

    @get:Synchronized
    val routingChangeGeneration: Long get() = routingGeneration.get()

    @get:Synchronized
    val outputAdvancing: Boolean get() = currentRouteOutputAdvancing()

    /** Numeric sink evidence only: no media URL, credentials, or account identifiers. */
    @Synchronized
    fun outputDiagnostics(): Map<String, String> =
        mapOf(
            "pcmWrittenBytes" to writtenBytes.toString(),
            "pcmZeroWrites" to zeroWriteCount.toString(),
            "audioPlayState" to (track?.playState?.toString() ?: "unconfigured"),
            "audioSampleRate" to sampleRate.toString(),
            "audioChannelCount" to (configuredFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)?.toString() ?: "0"),
            "audioBufferFrames" to (track?.bufferSizeInFrames?.toString() ?: "0"),
            "audioStartThresholdFrames" to startThresholdFrames.toString(),
            "audioPlaybackHeadFrames" to lastPlaybackHeadFrames.toString(),
            "audioTimestampFrames" to (lastTimestampFrames?.toString() ?: "unavailable"),
            "audioClockSource" to clockSource,
            "audioClockStalled" to clockStalled.toString(),
            "audioUnderruns" to underrunCount.toString(),
        )

    @get:Synchronized
    val spatialAudioOutput: Boolean
        get() = currentRouteOutputAdvancing() && spatialAudioState.active

    @get:Synchronized
    val headTrackingAvailable: Boolean
        get() = spatialAudioOutput && spatialAudioState.headTrackerAvailable

    private val routeEvidence: AndroidAudioRouteEvidence
        get() {
            val activeTrack = track ?: return AndroidAudioRouteEvidence()
            return activeTrack.activeRouteEvidence(clockAdvancing = currentRouteOutputAdvancing())
        }

    @get:Synchronized
    val audioRouteLabel: String get() = routeEvidence.label

    @get:Synchronized
    val audioRouteFingerprint: String get() = routeEvidence.fingerprint

    @get:Synchronized
    val audioRouteVerified: Boolean get() = routeEvidence.verified

    @get:Synchronized
    val clockSource: String
        get() = lastClockSource?.name ?: if (staleClockFallback) "WallClockFallback" else "Unavailable"

    @get:Synchronized
    val clockStalled: Boolean
        get() = staleClockFallback

    @Synchronized
    fun configure(format: MediaFormat) {
        release()
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        track =
            createTrack(format).also {
                configureStartThreshold(it)
                it.addOnRoutingChangedListener(routingListener, null)
            }
        configuredFormat = format
        spatialAudioState = spatialAudioProbe?.current(format) ?: AndroidSpatialAudioState()
        basePresentationTimeUs = null
        requestedPlay = false
        speed = 1f
        resetClockProgress()
    }

    @Synchronized
    fun play() {
        requestedPlay = true
        track?.let { audioTrack ->
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play()
        }
    }

    @Synchronized
    fun pause() {
        requestedPlay = false
        track?.let { audioTrack ->
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        }
    }

    @Synchronized
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

    @Synchronized
    fun setAudioDelayMs(value: Long) {
        audioDelayMs = value.coerceIn(-5_000L, 5_000L)
    }

    /** Only for video pacing. Position/progress/route verification keep using the unmodified clock. */
    @Synchronized
    fun videoClockPositionUs(fallbackPositionUs: Long): Long =
        clockSnapshot()?.let { audioDelayVideoPositionUs(it.positionUs, audioDelayMs) } ?: fallbackPositionUs

    /** Writes the complete decoded PCM access unit or throws on an AudioTrack error. */
    @Synchronized
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
            if (written == 0) {
                zeroWriteCount++
                continue
            }
            writtenBytes += written
            pcmTail.record(written)
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
    @Synchronized
    fun writeNonBlocking(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "AudioTrack render node has not been configured" }
        if (!data.hasRemaining()) return 0
        val shouldAnchorClock = basePresentationTimeUs == null
        val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "AudioTrack.write failed with code $written" }
        if (written == 0) {
            zeroWriteCount++
        } else {
            writtenBytes += written
            pcmTail.record(written)
        }
        if (shouldAnchorClock && written > 0) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        return written
    }

    @get:Synchronized
    val underrunCount: Int
        get() = runCatching { track?.underrunCount?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)

    /** Hardware-clock position, not decoder EOS, determines whether submitted PCM remains. */
    @Synchronized
    fun hasPendingPcm(): Boolean {
        val format = configuredFormat ?: return false
        val base = basePresentationTimeUs ?: return false
        if (!pcmTail.hasSamples || sampleRate <= 0) return false
        val encoding =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        val sampleBytes =
            when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> return false
            }
        val frameBytes = sampleBytes * format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val played = clockSnapshot()?.positionUs?.minus(base)?.coerceAtLeast(0L) ?: return true
        return pcmTail.pending(frameBytes, sampleRate, played)
    }

    @Synchronized
    fun clockSnapshot(): YAudioClockSnapshot? {
        val audioTrack = track ?: return null
        val baseUs = basePresentationTimeUs ?: return null
        if (sampleRate <= 0) return null
        val nowNs = System.nanoTime()
        val timestamp = AudioTimestamp()
        val hasTimestamp = runCatching { audioTrack.getTimestamp(timestamp) }.getOrDefault(false)
        lastTimestampFrames = timestamp.framePosition.takeIf { hasTimestamp }
        lastPlaybackHeadFrames =
            runCatching { audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL }.getOrElse { return null }
        val selection =
            clockProgressGuard.select(
                nowNs = nowNs,
                playing = requestedPlay && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING,
                timestampFrames = timestamp.framePosition.takeIf { hasTimestamp },
                timestampRealtimeNs = timestamp.nanoTime.takeIf { hasTimestamp },
                playbackHeadFrames = lastPlaybackHeadFrames,
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

    @Synchronized
    fun presentationTimeNs(
        videoPresentationTimeUs: Long,
        fallbackRealtimeNs: Long,
    ): Long {
        val clock = clockSnapshot() ?: return fallbackRealtimeNs
        return audioDelayVideoReleaseNs(
            videoPresentationUs = videoPresentationTimeUs,
            audioPositionUs = clock.positionUs,
            audioRealtimeNs = clock.realtimeNs,
            speed = speed,
            delayMs = audioDelayMs,
        )
    }

    @Synchronized
    override fun flush() {
        val audioTrack = track ?: return
        val resume = requestedPlay
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        audioTrack.flush()
        pcmTail.reset()
        basePresentationTimeUs = null
        resetClockProgress()
        if (resume) audioTrack.play()
    }

    @Synchronized
    override fun release() {
        val audioTrack = track
        track = null
        configuredFormat = null
        pcmTail.reset()
        requestedPlay = false
        sampleRate = 0
        basePresentationTimeUs = null
        resetClockProgress()
        spatialAudioState = AndroidSpatialAudioState()
        writtenBytes = 0L
        zeroWriteCount = 0L
        startThresholdFrames = 0
        if (audioTrack != null) {
            runCatching { audioTrack.removeOnRoutingChangedListener(routingListener) }
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
    }

    @Synchronized
    private fun resetClockProgress() {
        clockProgressGuard.reset()
        routedOutputProgress.reset()
        lastClockSource = null
        staleClockFallback = false
        lastTimestampFrames = null
        lastPlaybackHeadFrames = 0L
    }

    @Synchronized
    private fun configureStartThreshold(audioTrack: AudioTrack) {
        if (sampleRate <= 0) return
        var target = 0
        val result =
            runCatching {
                target = nativeDirectAudioStartThresholdFrames(sampleRate, audioTrack.bufferCapacityInFrames)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Capacity protects against jitter; it must not also require two seconds of
                    // interleaved PCM before AudioTrack will begin consuming the first frame.
                    audioTrack.setStartThresholdInFrames(target)
                } else {
                    // Pre-31 has no independent threshold. Reduce the effective streaming buffer,
                    // leaving its allocation intact; the platform clamps to its hardware minimum.
                    audioTrack.setBufferSizeInFrames(target)
                }.also { check(it > 0) { "AudioTrack rejected startup threshold: $it" } }
            }
        result.onSuccess { startThresholdFrames = it }.onFailure { error ->
            // A route callback can race release. Never crash the routing callback thread.
            AppLog.warning(
                category = "player.core2",
                event = "audio_start_threshold_failed",
                message = "AudioTrack startup threshold could not be applied",
                throwable = error,
                attributes = mapOf("targetFrames" to target.toString()),
            )
        }
    }

    @Synchronized
    private fun currentRouteOutputAdvancing(): Boolean {
        val audioTrack = track ?: return false
        return routedOutputProgress.observe(
            currentRouteGeneration = routingGeneration.get(),
            clock = clockSnapshot(),
            playing = requestedPlay && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING,
        )
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
        audioTrackChannelMask(
            declaredMask =
                if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
                } else {
                    null
                },
            channelCount = channelCount,
        )
    // A layout this device has no mask for must fail here. Falling back to a stereo mask would let
    // AudioTrack initialise and then reinterpret interleaved multichannel PCM as two channels,
    // which plays as garbled audio at the wrong rate instead of surfacing a route that can be
    // handed to a downmix or to the next backend.
    check(channelMask != AudioFormat.CHANNEL_INVALID) {
        "No AudioTrack channel mask covers $channelCount-channel PCM output"
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

/**
 * Turns AudioTrack routing callbacks into real output-device changes.
 *
 * A callback without a routed device (a paused track) proves nothing, and the first device seen is
 * the initial route rather than a change. Only a device that differs from the last one reported
 * counts, so pause/resume around a rebuffer no longer reads as an audio route change.
 */
internal class AudioRouteChangeFilter {
    private var lastDevice: String? = null

    /** True when [device] is a different output device from the last one a callback reported. */
    fun routed(device: String?): Boolean {
        if (device == null) return false
        val previous = lastDevice
        lastDevice = device
        return previous != null && previous != device
    }
}

/** Stable per connected device: a reconnected headset gets a new id and counts as a new route. */
internal fun AudioRouting.routedDeviceIdentity(): String? =
    runCatching { routedDevice?.let { device -> "${device.type}:${device.id}" } }.getOrNull()

/** Forty milliseconds primes common PCM codecs without filling the resilience buffer. */
internal fun nativeDirectAudioStartThresholdFrames(
    sampleRate: Int,
    capacityFrames: Int,
): Int {
    require(sampleRate > 0 && capacityFrames > 0)
    return (sampleRate.toLong() * 40L / 1_000L).coerceIn(1L, capacityFrames.toLong()).toInt()
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

/**
 * The output mask for decoded PCM: the decoder's own mask when it describes [channelCount] channels,
 * otherwise the standard layout for that count ([channelMaskForCount], which still refuses counts
 * this platform has no layout for).
 *
 * A codec can declare `channel-mask` 0 before it has decoded anything (Codec2's AAC decoder keeps
 * its default until the first frame), and a format synthesized from that state passed 0 straight to
 * the invalid-mask check: an IllegalStateException that failed startup with no stage attached. A mask
 * whose bit count disagrees with the channel count describes some other layout and is ignored the
 * same way, as are the legacy low bits (CHANNEL_OUT_DEFAULT) that AudioFormat.Builder rejects.
 */
internal fun audioTrackChannelMask(
    declaredMask: Int?,
    channelCount: Int,
): Int =
    declaredMask
        ?.takeIf { mask ->
            mask != AudioFormat.CHANNEL_INVALID &&
                mask and LEGACY_CHANNEL_OUT_BITS == 0 &&
                Integer.bitCount(mask) == channelCount
        } ?: channelMaskForCount(channelCount)

/**
 * Maps a decoded PCM channel count to an output mask, or [AudioFormat.CHANNEL_INVALID] when this
 * platform has no layout for it.
 *
 * Returning an invalid mask is deliberate: a silently narrowed mask produces audible corruption
 * rather than a diagnosable failure, and the height layouts only exist from API 32.
 */
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
        10, 12 -> heightChannelMaskForCount(channelCount)
        else -> AudioFormat.CHANNEL_INVALID
    }

/** Keeps the API 32-only height layouts out of the ordinary mask table. */
private fun heightChannelMaskForCount(channelCount: Int): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return AudioFormat.CHANNEL_INVALID
    return Api32HeightChannelMasks.forCount(channelCount)
}

@RequiresApi(Build.VERSION_CODES.S_V2)
private object Api32HeightChannelMasks {
    fun forCount(channelCount: Int): Int =
        when (channelCount) {
            10 -> AudioFormat.CHANNEL_OUT_5POINT1POINT4
            12 -> AudioFormat.CHANNEL_OUT_7POINT1POINT4
            else -> AudioFormat.CHANNEL_INVALID
        }
}

private const val MICROS_PER_SECOND = 1_000_000L

/** CHANNEL_OUT_DEFAULT and the unused bit above it; no real output position lives there. */
private const val LEGACY_CHANNEL_OUT_BITS = 0x3
private const val DEFAULT_AUDIO_BUFFER_BYTES = 64 * 1024
private const val MAX_AUDIO_BUFFER_BYTES = 2 * 1024 * 1024
private const val MINIMUM_AUDIO_BUFFER_MULTIPLIER = 4L
private const val TARGET_AUDIO_BUFFER_SECONDS = 2L

internal fun pcmTailPending(
    writtenBytes: Long,
    frameBytes: Int,
    sampleRate: Int,
    playedUs: Long,
): Boolean {
    if (writtenBytes <= 0L || frameBytes <= 0 || sampleRate <= 0) return false
    val submittedFrames = writtenBytes / frameBytes
    val playedFrames =
        playedUs.coerceAtLeast(0L) / 1_000_000L * sampleRate +
            playedUs.coerceAtLeast(0L) % 1_000_000L * sampleRate / 1_000_000L
    return submittedFrames - playedFrames > 1L
}

/** Drain accounting follows AudioTrack flushes, independently of lifetime diagnostic byte totals. */
internal class PcmTailTracker {
    private var bytes = 0L
    val hasSamples: Boolean get() = bytes > 0L

    fun record(written: Int) {
        if (written > 0) bytes += written
    }

    fun reset() {
        bytes = 0L
    }

    fun pending(
        frameBytes: Int,
        sampleRate: Int,
        playedUs: Long,
    ): Boolean = pcmTailPending(bytes, frameBytes, sampleRate, playedUs)
}
