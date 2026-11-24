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
    private var baseAnchorRealtimeNs: Long? = null
    private var baseAnchorFrames: Long? = null
    private var lastSubmittedPresentationTimeUs: Long? = null
    private var awaitingTrueHdMajorSync = false
    private var skippedNonMajorTrueHdPackets = 0
    private var requestedPlay = false
    private val routedOutputProgress = AndroidRoutedOutputProgress()
    private val clockProgressGuard = AndroidAudioClockProgressGuard()

    @Volatile
    private var exactDolbyAtmosTransport = false

    private val routingGeneration = AtomicLong()
    private val routeChangeFilter = AudioRouteChangeFilter()

    // Pause/resume also report "routing changed" with the same device; only a new device counts.
    private val routingListener =
        AudioRouting.OnRoutingChangedListener { router ->
            synchronized(this@AndroidEncodedAudioTrackRenderNode) {
                if (routeChangeFilter.routed(router.routedDeviceIdentity())) routingGeneration.incrementAndGet()
            }
        }

    @get:Synchronized
    val routingChangeGeneration: Long get() = routingGeneration.get()

    @get:Synchronized
    val outputAdvancing: Boolean get() = currentRouteOutputAdvancing()

    @get:Synchronized
    val underrunCount: Int get() = runCatching { track?.underrunCount?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)

    private val routeEvidence: AndroidAudioRouteEvidence
        get() {
            val activeTrack = track ?: return AndroidAudioRouteEvidence()
            return activeTrack.activeRouteEvidence(clockAdvancing = currentRouteOutputAdvancing())
        }

    @get:Synchronized
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

    @get:Synchronized
    val immersiveCarrierOutput: Boolean
        get() =
            currentRouteOutputAdvancing() &&
                format?.codec in setOf(YAudioCodec.Eac3Joc, YAudioCodec.TrueHdAtmos, YAudioCodec.DtsX)

    @get:Synchronized
    val dolbyAtmosOutput: Boolean
        get() = dolbyAtmosOutputMode.encodedPassthrough

    @get:Synchronized
    val audioRouteLabel: String get() = routeEvidence.label

    @get:Synchronized
    val audioRouteFingerprint: String get() = routeEvidence.fingerprint

    @get:Synchronized
    val audioRouteVerified: Boolean get() = routeEvidence.verified

    @Synchronized
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
        baseAnchorRealtimeNs = null
        baseAnchorFrames = null
        lastSubmittedPresentationTimeUs = null
        awaitingTrueHdMajorSync = format.codec in setOf(YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos)
        skippedNonMajorTrueHdPackets = 0
        requestedPlay = false
        routedOutputProgress.reset()
        clockProgressGuard.reset()
    }

    @Synchronized
    fun updateExactDolbyAtmosTransport(value: Boolean) {
        exactDolbyAtmosTransport =
            value &&
            format?.codec in setOf(YAudioCodec.Eac3Joc, YAudioCodec.TrueHdAtmos)
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

    /**
     * Writes only the bytes accepted immediately by the encoded sink.
     *
     * Passthrough buffers can be much larger than PCM codec fragments and Android may apply HDMI
     * backpressure while the receiver locks to a carrier. Keeping this call non-blocking prevents
     * that device-side wait from stopping video dequeue and Surface release on the playback lane.
     */
    @Synchronized
    fun writeNonBlocking(
        data: ByteBuffer,
        presentationTimeUs: Long,
    ): Int {
        val audioTrack = checkNotNull(track) { "Encoded AudioTrack has not been configured" }
        if (!data.hasRemaining()) return 0
        if (awaitingTrueHdMajorSync) {
            if (isTrueHdMajorSyncPacket(data)) {
                awaitingTrueHdMajorSync = false
                skippedNonMajorTrueHdPackets = 0
            } else if (skippedNonMajorTrueHdPackets < MAX_SKIPPED_TRUEHD_DELTA_PACKETS) {
                skippedNonMajorTrueHdPackets++
                val skipped = data.remaining()
                data.position(data.limit())
                return skipped
            } else {
                awaitingTrueHdMajorSync = false
            }
        }
        val shouldAnchorClock = basePresentationTimeUs == null
        val written = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "Encoded AudioTrack.write failed with code $written" }
        if (written > 0) {
            lastSubmittedPresentationTimeUs =
                maxOf(lastSubmittedPresentationTimeUs ?: 0L, presentationTimeUs.coerceAtLeast(0L))
        }
        if (shouldAnchorClock && written > 0) {
            basePresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        return written
    }

    @Synchronized
    fun clockSnapshot(): YAudioClockSnapshot? {
        val audioTrack = track ?: return null
        val source = format ?: return null
        val baseUs = basePresentationTimeUs ?: return null
        if (source.sampleRate <= 0) return null
        val timestamp = AudioTimestamp()
        val hasTimestamp = runCatching { audioTrack.getTimestamp(timestamp) }.getOrDefault(false)
        val nowNs = System.nanoTime()
        val head = runCatching { audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL }.getOrElse { return null }
        val selection =
            clockProgressGuard.select(
                nowNs = nowNs,
                playing = requestedPlay && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING,
                timestampFrames = timestamp.framePosition.takeIf { hasTimestamp },
                timestampRealtimeNs = timestamp.nanoTime.takeIf { hasTimestamp },
                playbackHeadFrames = head,
            ) ?: return null
        val anchorNs = baseAnchorRealtimeNs ?: selection.realtimeNs.also { baseAnchorRealtimeNs = it }
        val anchorFrames = baseAnchorFrames ?: selection.framePosition.also { baseAnchorFrames = it }
        val elapsedNs = (selection.realtimeNs - anchorNs).coerceAtLeast(0L)
        val deltaFrames = (selection.framePosition - anchorFrames).coerceAtLeast(0L)
        val frameBasedAdvanceUs = deltaFrames * MICROS_PER_SECOND / source.sampleRate
        val wallElapsedUs = elapsedNs / NANOS_PER_MICROSECOND
        // Offloaded/direct bitstream AudioTracks frequently advance framePosition in raw bytes or
        // 192 kHz IEC61937 carrier frames rather than source PCM sampleRate. When hardware frame
        // rate diverges from plausible PCM rate, anchor position to elapsed monotonic time while
        // hardware progress is actively advancing, bounded by submitted packet timestamps.
        val plausiblePcmFrameUnits =
            wallElapsedUs < 250_000L ||
                frameBasedAdvanceUs in (wallElapsedUs * 4L / 10L)..(wallElapsedUs * 16L / 10L + 100_000L)
        val advanceUs =
            if (plausiblePcmFrameUnits) {
                frameBasedAdvanceUs
            } else {
                val maxPositionAdvanceUs =
                    ((lastSubmittedPresentationTimeUs ?: baseUs) - baseUs + MAX_PASSTHROUGH_CLOCK_LEAD_US)
                        .coerceAtLeast(0L)
                wallElapsedUs.coerceAtMost(maxPositionAdvanceUs)
            }
        return YAudioClockSnapshot(
            positionUs = (baseUs + advanceUs).coerceAtLeast(0L),
            realtimeNs = selection.realtimeNs,
        )
    }

    @Synchronized
    fun presentationTimeNs(
        videoPresentationTimeUs: Long,
        fallbackRealtimeNs: Long,
    ): Long {
        val clock = clockSnapshot() ?: return fallbackRealtimeNs
        return clock.realtimeNs + (videoPresentationTimeUs - clock.positionUs) * NANOS_PER_MICROSECOND
    }

    @Synchronized
    override fun flush() {
        val audioTrack = track ?: return
        val resume = requestedPlay
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
        audioTrack.flush()
        basePresentationTimeUs = null
        baseAnchorRealtimeNs = null
        baseAnchorFrames = null
        lastSubmittedPresentationTimeUs = null
        awaitingTrueHdMajorSync = format?.codec in setOf(YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos)
        skippedNonMajorTrueHdPackets = 0
        routedOutputProgress.reset()
        clockProgressGuard.reset()
        if (resume) audioTrack.play()
    }

    @Synchronized
    override fun release() {
        val audioTrack = track
        track = null
        format = null
        sinkCodec = null
        requestedPlay = false
        basePresentationTimeUs = null
        baseAnchorRealtimeNs = null
        baseAnchorFrames = null
        lastSubmittedPresentationTimeUs = null
        awaitingTrueHdMajorSync = false
        skippedNonMajorTrueHdPackets = 0
        exactDolbyAtmosTransport = false
        routedOutputProgress.reset()
        clockProgressGuard.reset()
        if (audioTrack != null) {
            runCatching { audioTrack.removeOnRoutingChangedListener(routingListener) }
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
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
        YAudioCodec.Ac4 ->
            if (sdkInt >= Build.VERSION_CODES.P) {
                AudioFormat.ENCODING_AC4
            } else {
                null
            }
        YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos -> AudioFormat.ENCODING_DOLBY_TRUEHD
        YAudioCodec.Dts -> AudioFormat.ENCODING_DTS
        YAudioCodec.DtsHd -> AudioFormat.ENCODING_DTS_HD
        YAudioCodec.DtsX ->
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                AudioFormat.ENCODING_DTS_UHD_P1
            } else {
                AudioFormat.ENCODING_DTS_HD
            }
        else -> null
    }

/**
 * The encodings worth trying for a codec, most specific first.
 *
 * Raw TrueHD access units must use `ENCODING_DOLBY_TRUEHD` (not `ENCODING_DOLBY_MAT`, which
 * expects 61,440-byte Dolby MAT frames). For DTS:X on Android 14+, try UHD P2, UHD P1, then
 * compatible `ENCODING_DTS_HD`.
 */
@SuppressLint("InlinedApi")
internal fun androidEncodedAudioEncodingCandidates(
    codec: YAudioCodec,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<Int> =
    when (codec) {
        YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos -> listOf(AudioFormat.ENCODING_DOLBY_TRUEHD)
        YAudioCodec.DtsX ->
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                listOf(
                    AudioFormat.ENCODING_DTS_UHD_P2,
                    AudioFormat.ENCODING_DTS_UHD_P1,
                    AudioFormat.ENCODING_DTS_HD,
                )
            } else {
                listOf(AudioFormat.ENCODING_DTS_HD)
            }
        else -> listOfNotNull(androidEncodedAudioEncoding(codec, sdkInt))
    }

/** Selects the encoding actually declared to AudioTrack without promoting carrier support to object audio. */
internal fun encodedSinkCodec(
    sourceCodec: YAudioCodec,
    exactDolbyAtmosTransport: Boolean,
): YAudioCodec =
    when {
        sourceCodec == YAudioCodec.Eac3Joc && !exactDolbyAtmosTransport -> YAudioCodec.Eac3
        sourceCodec == YAudioCodec.TrueHdAtmos && !exactDolbyAtmosTransport -> YAudioCodec.TrueHd
        else -> sourceCodec
    }

private fun buildEncodedAudioTrack(format: YAudioTrackFormat): AudioTrack {
    val candidates = androidEncodedAudioEncodingCandidates(format.codec)
    require(candidates.isNotEmpty()) { "${format.codec} has no Android encoded AudioTrack mapping" }
    val sampleRate = format.sampleRate.coerceAtLeast(1)
    val channelMask = encodedChannelMaskForCount(format.channelCount)
    val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

    fun audioFormat(encoding: Int): AudioFormat =
        AudioFormat
            .Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
    // Ask the active route which carrier it takes before opening the track; below API 29 there
    // is no such question, and the first candidate is the only one.
    val encoding =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && candidates.size > 1) {
            candidates.firstOrNull { candidate ->
                runCatching { AudioTrack.isDirectPlaybackSupported(audioFormat(candidate), attributes) }
                    .getOrDefault(false)
            } ?: candidates.first()
        } else {
            candidates.first()
        }
    val minBuffer =
        AudioTrack
            .getMinBufferSize(sampleRate, channelMask, encoding)
            .takeIf { it > 0 }
            ?: DEFAULT_ENCODED_AUDIO_BUFFER_BYTES
    return AudioTrack
        .Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(audioFormat(encoding))
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

/** True when [packet] begins with a Dolby TrueHD (`0xF8726FBA`) or MLP (`0xF8726FBB`) major sync header. */
internal fun isTrueHdMajorSyncPacket(packet: ByteBuffer): Boolean {
    val pos = packet.position()
    if (packet.remaining() < 8) return false
    return (packet.get(pos + 4).toInt() and 0xFF) == 0xF8 &&
        (packet.get(pos + 5).toInt() and 0xFF) == 0x72 &&
        (packet.get(pos + 6).toInt() and 0xFF) == 0x6F &&
        ((packet.get(pos + 7).toInt() and 0xFE) == 0xBA)
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val DEFAULT_ENCODED_AUDIO_BUFFER_BYTES = 256 * 1024
private const val MAX_SKIPPED_TRUEHD_DELTA_PACKETS = 64
private const val MAX_PASSTHROUGH_CLOCK_LEAD_US = 250_000L
