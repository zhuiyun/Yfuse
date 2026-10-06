package com.yfuse.core2.audio

import com.yfuse.core2.api.YAudioEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** What a PCM channel carries, as far as loudness weighting and the dialogue lift need to know. */
enum class YAudioChannelRole(
    internal val loudnessWeight: Double,
) {
    Front(1.0),
    Centre(1.0),

    /** ITU-R BS.1770 leaves the LFE channel out of loudness. */
    Lfe(0.0),

    /** BS.1770 weights the surrounds by about +1.5 dB. */
    Surround(1.41),
    Height(1.0),
}

/**
 * The DSP behind [YAudioEffect], applied in place to interleaved float PCM.
 *
 * Nothing is delayed: every output sample is its own input sample times a gain, so the audio clock,
 * lip sync and the end of a stream are untouched. One gain moves every channel and keeps the stereo
 * or surround image, apart from NightVoice's centre lift, and every mode ends in a peak limiter, so
 * the added gain never clips. Not thread-safe; one instance per PCM stream.
 */
class YAudioEffectProcessor(
    val effect: YAudioEffect,
    sampleRate: Int,
    channels: List<YAudioChannelRole>,
) {
    init {
        require(effect != YAudioEffect.Off) { "Off has no processor" }
        require(sampleRate > 0 && channels.isNotEmpty()) { "Audio effects need a sample rate and channels" }
    }

    val channelCount: Int = channels.size

    private val centreChannel =
        channels.indexOf(YAudioChannelRole.Centre).takeIf { it >= 0 && effect == YAudioEffect.NightVoice }

    private val stage: GainStage =
        when (effect) {
            YAudioEffect.NightVoice -> NightCompressor(sampleRate, channelCount)
            YAudioEffect.LoudnessNormalize -> LoudnessRider(sampleRate, channels)
            else -> ConstantGain(VOLUME_BOOST_GAIN)
        }

    private val limiter =
        PeakLimiter(
            ceiling = decibelsToGain(if (effect == YAudioEffect.LoudnessNormalize) LOUDNESS_CEILING_DB else CEILING_DB),
            sampleRate = sampleRate,
        )

    /** Processes the first [frames] frames of [samples]; a sample that is not finite becomes silence. */
    fun process(
        samples: FloatArray,
        frames: Int,
    ) {
        require(frames >= 0 && frames.toLong() * channelCount <= samples.size) { "Frames exceed the sample buffer" }
        var offset = 0
        repeat(frames) {
            for (index in offset until offset + channelCount) {
                if (!samples[index].isFinite()) samples[index] = 0f
            }
            val gain = stage.gain(samples, offset)
            var peak = 0f
            for (channel in 0 until channelCount) {
                val index = offset + channel
                val lift = if (channel == centreChannel) CENTRE_LIFT else 1f
                samples[index] *= gain * lift
                peak = maxOf(peak, abs(samples[index]))
            }
            val limit = limiter.gain(peak)
            if (limit < 1f) {
                for (index in offset until offset + channelCount) samples[index] *= limit
            }
            offset += channelCount
        }
    }

    /**
     * After a seek or flush. Detectors and the limiter start over; the loudness gain is kept, since
     * the stream is still the same title and ramping up again after every seek would be audible.
     */
    fun reset() {
        stage.reset()
        limiter.reset()
    }
}

private interface GainStage {
    fun gain(
        samples: FloatArray,
        offset: Int,
    ): Float

    fun reset()
}

private class ConstantGain(
    private val value: Float,
) : GainStage {
    override fun gain(
        samples: FloatArray,
        offset: Int,
    ): Float = value

    override fun reset() = Unit
}

/**
 * FFmpeg's acompressor (af_sidechaincompress.c) with mpv's night settings: RMS detection of the
 * channel average, its default 2.83 (9 dB) soft knee, downward compression, then the makeup gain.
 */
private class NightCompressor(
    sampleRate: Int,
    private val channelCount: Int,
) : GainStage {
    private val logThreshold = ln(decibelsToLinear(NIGHT_THRESHOLD_DB))
    private val kneeStart = decibelsToLinear(NIGHT_THRESHOLD_DB) / sqrt(NIGHT_KNEE)
    private val kneeStartSquared = kneeStart * kneeStart
    private val logKneeStart = ln(kneeStart)
    private val logKneeStop = ln(decibelsToLinear(NIGHT_THRESHOLD_DB) * sqrt(NIGHT_KNEE))
    private val compressedKneeStop = (logKneeStop - logThreshold) / NIGHT_RATIO + logThreshold
    private val attack = minOf(1.0, COMPRESSOR_TIME_SCALE / (NIGHT_ATTACK_MS * sampleRate))
    private val release = minOf(1.0, COMPRESSOR_TIME_SCALE / (NIGHT_RELEASE_MS * sampleRate))
    private val makeup = decibelsToLinear(NIGHT_MAKEUP_DB)
    private var level = 0.0

    override fun gain(
        samples: FloatArray,
        offset: Int,
    ): Float {
        var sum = 0.0
        for (index in offset until offset + channelCount) sum += abs(samples[index])
        val average = sum / channelCount
        val detected = average * average
        level += (detected - level) * if (detected > level) attack else release
        val reduction = if (level > kneeStartSquared) compressedGain() else 1.0
        return (reduction * makeup).toFloat()
    }

    private fun compressedGain(): Double {
        val slope = 0.5 * ln(level)
        val gain =
            if (slope < logKneeStop) {
                hermite(slope, logKneeStart, logKneeStop, logKneeStart, compressedKneeStop, 1.0, 1.0 / NIGHT_RATIO)
            } else {
                (slope - logThreshold) / NIGHT_RATIO + logThreshold
            }
        return exp(gain - slope)
    }

    override fun reset() {
        level = 0.0
    }
}

/**
 * Loudness over the last three seconds (BS.1770 K-weighting, gated like its integrated measure)
 * steering a slow gain toward -16 LUFS. mpv's loudnorm looks three seconds ahead; YCore has no delay
 * to spend on that, so the gain follows the measurement instead: down within about a second when a
 * scene gets louder, up over about three when it gets quieter.
 *
 * Only 100 ms blocks above -45 LUFS, and within 10 LU of the window's level, are measured, and the
 * gain moves only on a second or more of them. Pauses and room tone therefore neither pull the level
 * down nor, once the programme around them has left the window, move the gain at all; neither does
 * the tail of the last loud block. A quiet stretch after a loud scene does not swell into the next.
 */
private class LoudnessRider(
    sampleRate: Int,
    channels: List<YAudioChannelRole>,
) : GainStage {
    private val weights = DoubleArray(channels.size) { channels[it].loudnessWeight }
    private val filters = Array(channels.size) { YKWeightingFilter(sampleRate) }
    private val blockFrames = (sampleRate / LOUDNESS_BLOCKS_PER_SECOND).coerceAtLeast(1)
    private val blocks = DoubleArray(SHORT_TERM_BLOCKS)
    private val fall = smoothing(LOUDNESS_FALL_SECONDS, sampleRate)
    private val rise = smoothing(LOUDNESS_RISE_SECONDS, sampleRate)
    private val absoluteGate = loudnessToEnergy(LOUDNESS_GATE_LUFS)
    private var filledBlocks = 0
    private var nextBlock = 0
    private var blockEnergy = 0.0
    private var blockFill = 0
    private var targetDb = 0.0
    private var gainDb = 0.0
    private var gain = 1f

    override fun gain(
        samples: FloatArray,
        offset: Int,
    ): Float {
        for (channel in weights.indices) {
            if (weights[channel] == 0.0) continue
            val weighted = filters[channel].filter(samples[offset + channel].toDouble())
            blockEnergy += weights[channel] * weighted * weighted
        }
        if (++blockFill == blockFrames) closeBlock()
        if (gainDb != targetDb) {
            val distance = targetDb - gainDb
            gainDb =
                if (abs(distance) < GAIN_SNAP_DB) {
                    targetDb
                } else {
                    gainDb + distance * if (distance < 0.0) fall else rise
                }
            gain = decibelsToGain(gainDb)
        }
        return gain
    }

    private fun closeBlock() {
        blocks[nextBlock] = blockEnergy / blockFrames
        nextBlock = (nextBlock + 1) % blocks.size
        filledBlocks = minOf(filledBlocks + 1, blocks.size)
        blockEnergy = 0.0
        blockFill = 0
        val audible = gatedMean(absoluteGate, minimumBlocks = 1) ?: return
        val level = gatedMean(maxOf(absoluteGate, audible * RELATIVE_GATE), MIN_MEASURED_BLOCKS) ?: return
        val loudness = BS1770_OFFSET_DB + 10.0 * log10(level)
        targetDb = (LOUDNESS_TARGET_LUFS - loudness).coerceIn(-LOUDNESS_MAX_CUT_DB, LOUDNESS_MAX_BOOST_DB)
    }

    /** Mean energy of the window's blocks at or above [gate]; null when fewer than [minimumBlocks]. */
    private fun gatedMean(
        gate: Double,
        minimumBlocks: Int,
    ): Double? {
        var sum = 0.0
        var count = 0
        for (index in 0 until filledBlocks) {
            if (blocks[index] >= gate) {
                sum += blocks[index]
                count++
            }
        }
        return if (count < minimumBlocks) null else sum / count
    }

    override fun reset() {
        filters.forEach(YKWeightingFilter::reset)
        filledBlocks = 0
        nextBlock = 0
        blockEnergy = 0.0
        blockFill = 0
    }
}

/** Instant attack, so no sample passes the ceiling, and a smooth release back toward unity. */
private class PeakLimiter(
    private val ceiling: Float,
    sampleRate: Int,
) {
    private val release = smoothing(LIMITER_RELEASE_SECONDS, sampleRate).toFloat()
    private var gain = 1f

    fun gain(peak: Float): Float {
        val wanted = if (peak > ceiling) ceiling / peak else 1f
        gain = if (wanted < gain) wanted else gain + (wanted - gain) * release
        return gain
    }

    fun reset() {
        gain = 1f
    }
}

/** ITU-R BS.1770 K-weighting at any sample rate: libebur128's bilinear forms of its two stages. */
internal class YKWeightingFilter(
    sampleRate: Int,
) {
    val shelf: YBiquad
    val highPass: YBiquad

    init {
        require(sampleRate > 0)
        val shelfK = tan(PI * SHELF_HZ / sampleRate)
        val vh = decibelsToLinear(SHELF_GAIN_DB)
        val vb = vh.pow(SHELF_VB_EXPONENT)
        val shelfA0 = 1.0 + shelfK / SHELF_Q + shelfK * shelfK
        shelf =
            YBiquad(
                b0 = (vh + vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
                b1 = 2.0 * (shelfK * shelfK - vh) / shelfA0,
                b2 = (vh - vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
                a1 = 2.0 * (shelfK * shelfK - 1.0) / shelfA0,
                a2 = (1.0 - shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
            )
        val highPassK = tan(PI * HIGH_PASS_HZ / sampleRate)
        val highPassA0 = 1.0 + highPassK / HIGH_PASS_Q + highPassK * highPassK
        highPass =
            YBiquad(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (highPassK * highPassK - 1.0) / highPassA0,
                a2 = (1.0 - highPassK / HIGH_PASS_Q + highPassK * highPassK) / highPassA0,
            )
    }

    fun filter(sample: Double): Double = highPass.filter(shelf.filter(sample))

    fun reset() {
        shelf.reset()
        highPass.reset()
    }
}

/** Transposed direct form II; state this close to zero is flushed so silence never turns subnormal. */
internal class YBiquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun filter(sample: Double): Double {
        val output = b0 * sample + z1
        z1 = b1 * sample - a1 * output + z2
        z2 = b2 * sample - a2 * output
        if (abs(z1) < STATE_FLOOR) z1 = 0.0
        if (abs(z2) < STATE_FLOOR) z2 = 0.0
        return output
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

private fun hermite(
    x: Double,
    x0: Double,
    x1: Double,
    p0: Double,
    p1: Double,
    m0: Double,
    m1: Double,
): Double {
    val width = x1 - x0
    val t = (x - x0) / width
    val tangent0 = m0 * width
    val tangent1 = m1 * width
    val t2 = t * t
    val t3 = t2 * t
    val c2 = -3.0 * p0 - 2.0 * tangent0 + 3.0 * p1 - tangent1
    val c3 = 2.0 * p0 + tangent0 - 2.0 * p1 + tangent1
    return c3 * t3 + c2 * t2 + tangent0 * t + p0
}

private fun smoothing(
    seconds: Double,
    sampleRate: Int,
): Double = 1.0 - exp(-1.0 / (seconds * sampleRate))

private fun decibelsToLinear(decibels: Double): Double = 10.0.pow(decibels / 20.0)

private fun decibelsToGain(decibels: Double): Float = decibelsToLinear(decibels).toFloat()

private fun loudnessToEnergy(lufs: Double): Double = 10.0.pow((lufs - BS1770_OFFSET_DB) / 10.0)

private const val VOLUME_BOOST_GAIN = 1.5f
private const val CEILING_DB = -1.0
private const val LOUDNESS_CEILING_DB = -1.5
private const val LIMITER_RELEASE_SECONDS = 0.15

// mpv: acompressor=threshold=-20dB:ratio=4:attack=5:release=200:makeup=5dB (FFmpeg's default knee).
private const val NIGHT_THRESHOLD_DB = -20.0
private const val NIGHT_RATIO = 4.0
private const val NIGHT_ATTACK_MS = 5.0
private const val NIGHT_RELEASE_MS = 200.0
private const val NIGHT_MAKEUP_DB = 5.0
private const val NIGHT_KNEE = 2.82843

/** FFmpeg's attack/release coefficient is 1 / (ms * rate / 4000). */
private const val COMPRESSOR_TIME_SCALE = 4_000.0

/** +3 dB on the centre channel, where films keep their dialogue. */
private const val CENTRE_LIFT = 1.4125376f

private const val LOUDNESS_TARGET_LUFS = -16.0
private const val LOUDNESS_GATE_LUFS = -45.0
private const val LOUDNESS_MAX_BOOST_DB = 12.0
private const val LOUDNESS_MAX_CUT_DB = 12.0
private const val LOUDNESS_FALL_SECONDS = 0.8
private const val LOUDNESS_RISE_SECONDS = 3.0
private const val LOUDNESS_BLOCKS_PER_SECOND = 10
private const val SHORT_TERM_BLOCKS = 30
private const val MIN_MEASURED_BLOCKS = 10
private const val GAIN_SNAP_DB = 0.001
private const val BS1770_OFFSET_DB = -0.691

/** BS.1770's relative gate: blocks more than 10 LU under the window's audible level are left out. */
private const val RELATIVE_GATE = 0.1

// BS.1770 K-weighting: a high-frequency shelf, then the RLB high-pass (libebur128's parameters).
private const val SHELF_HZ = 1681.974450955533
private const val SHELF_GAIN_DB = 3.999843853973347
private const val SHELF_Q = 0.7071752369554196
private const val SHELF_VB_EXPONENT = 0.4996667741545416
private const val HIGH_PASS_HZ = 38.13547087602444
private const val HIGH_PASS_Q = 0.5003270373238773
private const val STATE_FLOOR = 1e-20
