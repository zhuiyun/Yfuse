package com.yfuse.core2.android

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.graph.YAudioRenderNode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Encoded AudioTrack sink used only after the active output route proves direct support. */
internal class AndroidEncodedAudioTrackRenderNode(
    private val createTrack: (YAudioTrackFormat) -> AudioTrack = ::buildEncodedAudioTrack,
    private val trueHdAtmosEvidenceProvider: AndroidTrueHdAtmosEvidenceProvider =
        FailClosedAndroidTrueHdAtmosEvidenceProvider,
) : YAudioRenderNode {
    override val name: String = "AudioTrack passthrough"

    private var track: AudioTrack? = null
    private var format: YAudioTrackFormat? = null
    private var sinkCodec: YAudioCodec? = null
    private var basePresentationTimeUs: Long? = null
    private var requestedPlay = false
    private val routedOutputProgress = AndroidRoutedOutputProgress()

    @Volatile
    private var exactDolbyAtmosTransport = false

    private val routingGeneration = AtomicLong()
    private val routingListener = AudioRouting.OnRoutingChangedListener { routingGeneration.incrementAndGet() }

    val routingChangeGeneration: Long get() = routingGeneration.get()

    private val routeEvidence: AndroidAudioRouteEvidence
        get() {
            val activeTrack = track ?: return AndroidAudioRouteEvidence()
            return activeTrack.activeRouteEvidence(clockAdvancing = currentRouteOutputAdvancing())
        }

    val dolbyAtmosOutputMode: YDolbyAtmosOutputMode
        get() {
            val sourceCodec = format?.codec
            val route = routeEvidence
            val independentTrueHdAtmosEvidence =
                sourceCodec == YAudioCodec.TrueHdAtmos &&
                    route.verified &&
                    trueHdAtmosEvidenceProvider.isTrueHdAtmosOutputVerified(
                        sourceCodec = sourceCodec,
                        sinkCodec = sinkCodec,
                        route = route,
                    )
            return resolveDolbyAtmosOutputMode(
                sourceCodec = format?.codec,
                sinkCodec = sinkCodec,
                outputAdvancing = currentRouteOutputAdvancing(),
                route = route,
                declaredExactTransport = exactDolbyAtmosTransport,
                independentTrueHdAtmosSinkEvidence = independentTrueHdAtmosEvidence,
            )
        }

    val immersiveCarrierOutput: Boolean
        get() =
            currentRouteOutputAdvancing() &&
                format?.codec in setOf(YAudioCodec.Eac3Joc, YAudioCodec.TrueHdAtmos, YAudioCodec.DtsX)

    val dolbyAtmosOutput: Boolean
        get() = dolbyAtmosOutputMode.encodedPassthrough

    val audioRouteLabel: String get() = routeEvidence.label

    val audioRouteVerified: Boolean get() = routeEvidence.verified

    fun configure(
        format: YAudioTrackFormat,
        exactDolbyAtmosTransport: Boolean = false,
    ) {
        release()
        val sinkFormat =
            format.copy(
                codec = encodedSinkCodec(format.codec, exactDolbyAtmosTransport),
            )
        requireNotNull(androidEncodedAudioEncoding(sinkFormat.codec)) {
            "${format.codec} has no Android encoded AudioTrack mapping"
        }
        track = createTrack(sinkFormat).also { it.addOnRoutingChangedListener(routingListener, null) }
        this.format = format
        this.sinkCodec = sinkFormat.codec
        this.exactDolbyAtmosTransport =
            exactDolbyAtmosTransport &&
            format.codec in setOf(YAudioCodec.Eac3Joc, YAudioCodec.TrueHdAtmos)
        basePresentationTimeUs = null
        requestedPlay = false
        routedOutputProgress.reset()
    }

    fun updateExactDolbyAtmosTransport(value: Boolean) {
        exactDolbyAtmosTransport =
            value &&
            format?.codec in setOf(YAudioCodec.Eac3Joc, YAudioCodec.TrueHdAtmos)
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

    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "Encoded AudioTrack has not been configured" }
        if (basePresentationTimeUs == null && data.hasRemaining()) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        var total = 0
        while (data.hasRemaining()) {
            val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "Encoded AudioTrack.write failed with code $written" }
            if (written == 0) continue
            total += written
        }
        return total
    }

    /**
     * Writes only the bytes accepted immediately by the encoded sink.
     *
     * Passthrough buffers can be much larger than PCM codec fragments and Android may apply HDMI
     * backpressure while the receiver locks to a carrier. Keeping this call non-blocking prevents
     * that device-side wait from stopping video dequeue and Surface release on the playback lane.
     */
    fun writeNonBlocking(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "Encoded AudioTrack has not been configured" }
        if (!data.hasRemaining()) return 0
        val shouldAnchorClock = basePresentationTimeUs == null
        val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "Encoded AudioTrack.write failed with code $written" }
        if (shouldAnchorClock && written > 0) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        return written
    }

    fun clockSnapshot(): YAudioClockSnapshot? {
        val audioTrack = track ?: return null
        val source = format ?: return null
        val baseUs = basePresentationTimeUs ?: return null
        if (source.sampleRate <= 0) return null
        val timestamp = AudioTimestamp()
        val hasTimestamp = runCatching { audioTrack.getTimestamp(timestamp) }.getOrDefault(false)
        val frames =
            if (hasTimestamp) {
                timestamp.framePosition
            } else {
                audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            }
        return YAudioClockSnapshot(
            positionUs = (baseUs + frames * MICROS_PER_SECOND / source.sampleRate).coerceAtLeast(0L),
            realtimeNs = if (hasTimestamp) timestamp.nanoTime else System.nanoTime(),
        )
    }

    fun presentationTimeNs(
        videoPresentationTimeUs: Long,
        fallbackRealtimeNs: Long,
    ): Long {
        val clock = clockSnapshot() ?: return fallbackRealtimeNs
        return clock.realtimeNs + (videoPresentationTimeUs - clock.positionUs) * NANOS_PER_MICROSECOND
    }

    override fun flush() {
        val audioTrack = track ?: return
        val resume = requestedPlay
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        audioTrack.flush()
        basePresentationTimeUs = null
        routedOutputProgress.reset()
        if (resume) audioTrack.play()
    }

    override fun release() {
        val audioTrack = track
        track = null
        format = null
        sinkCodec = null
        requestedPlay = false
        basePresentationTimeUs = null
        exactDolbyAtmosTransport = false
        routedOutputProgress.reset()
        if (audioTrack != null) {
            runCatching { audioTrack.removeOnRoutingChangedListener(routingListener) }
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
    }

    private fun currentRouteOutputAdvancing(): Boolean {
        val audioTrack = track ?: return false
        return routedOutputProgress.observe(
            currentRouteGeneration = routingGeneration.get(),
            clock = clockSnapshot(),
            playing = requestedPlay && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING,
        )
    }
}

@SuppressLint("InlinedApi")
internal fun androidEncodedAudioEncoding(
    codec: YAudioCodec,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int? =
    when (codec) {
        YAudioCodec.Ac3 -> AudioFormat.ENCODING_AC3
        YAudioCodec.Eac3 -> AudioFormat.ENCODING_E_AC3
        YAudioCodec.Eac3Joc ->
            if (sdkInt >= Build.VERSION_CODES.P) {
                AudioFormat.ENCODING_E_AC3_JOC
            } else {
                AudioFormat.ENCODING_E_AC3
            }
        YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos -> AudioFormat.ENCODING_DOLBY_TRUEHD
        YAudioCodec.Dts -> AudioFormat.ENCODING_DTS
        YAudioCodec.DtsHd, YAudioCodec.DtsX -> AudioFormat.ENCODING_DTS_HD
        else -> null
    }

/** Selects the encoding actually declared to AudioTrack without promoting carrier support to object audio. */
internal fun encodedSinkCodec(
    sourceCodec: YAudioCodec,
    exactDolbyAtmosTransport: Boolean,
): YAudioCodec =
    when {
        sourceCodec == YAudioCodec.Eac3Joc && !exactDolbyAtmosTransport -> YAudioCodec.Eac3
        sourceCodec == YAudioCodec.TrueHdAtmos && !exactDolbyAtmosTransport -> YAudioCodec.TrueHd
        sourceCodec == YAudioCodec.DtsX -> YAudioCodec.DtsHd
        else -> sourceCodec
    }

private fun buildEncodedAudioTrack(format: YAudioTrackFormat): AudioTrack {
    val encoding = requireNotNull(androidEncodedAudioEncoding(format.codec))
    val sampleRate = format.sampleRate.coerceAtLeast(1)
    val channelMask = encodedChannelMaskForCount(format.channelCount)
    val minBuffer =
        AudioTrack
            .getMinBufferSize(sampleRate, channelMask, encoding)
            .takeIf { it > 0 }
            ?: DEFAULT_ENCODED_AUDIO_BUFFER_BYTES
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
        .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(DEFAULT_ENCODED_AUDIO_BUFFER_BYTES))
        .build()
        .also { track ->
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "Encoded AudioTrack failed to initialize for ${format.codec}"
            }
        }
}

private fun encodedChannelMaskForCount(channelCount: Int): Int =
    when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> if (channelCount > 6) AudioFormat.CHANNEL_OUT_7POINT1_SURROUND else AudioFormat.CHANNEL_OUT_5POINT1
    }

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val DEFAULT_ENCODED_AUDIO_BUFFER_BYTES = 256 * 1024
