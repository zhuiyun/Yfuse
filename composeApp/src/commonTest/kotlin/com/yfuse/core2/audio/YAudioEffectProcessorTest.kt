package com.yfuse.core2.audio

import com.yfuse.core2.api.YAudioEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YAudioEffectProcessorTest {
    private val stereo = listOf(YAudioChannelRole.Front, YAudioChannelRole.Front)

    @Test
    fun volume_boost_is_one_and_a_half_times_below_the_ceiling() {
        val processor = YAudioEffectProcessor(YAudioEffect.VolumeBoost, RATE, stereo)
        val samples = floatArrayOf(0.1f, -0.2f, 0.4f, -0.5f)

        processor.process(samples, frames = 2)

        assertEquals(listOf(0.15f, -0.3f, 0.6f, -0.75f), samples.toList())
    }

    @Test
    fun no_mode_lets_a_sample_past_its_ceiling() {
        for (effect in YAudioEffect.entries - YAudioEffect.Off) {
            val processor = YAudioEffectProcessor(effect, RATE, stereo)
            val random = Random(7)
            var peak = 0f
            repeat(200) {
                // Full-scale noise with bursts, the worst case for a limiter without lookahead.
                val chunk =
                    FloatArray(CHUNK_FRAMES * 2) {
                        (random.nextFloat() * 2f - 1f) *
                            if (it % 3 == 0) 1f else 0.3f
                    }
                processor.process(chunk, CHUNK_FRAMES)
                peak = maxOf(peak, chunk.maxOf { abs(it) })
            }
            val ceiling = if (effect == YAudioEffect.LoudnessNormalize) 0.8414f else 0.8913f
            assertTrue(peak <= ceiling + 1e-6f, "$effect peaked at $peak")
        }
    }

    @Test
    fun k_weighting_matches_the_bs1770_coefficients_at_48_khz() {
        val filter = YKWeightingFilter(48_000)

        assertEquals(1.53512485958697, filter.shelf.b0, 1e-9)
        assertEquals(-2.69169618940638, filter.shelf.b1, 1e-9)
        assertEquals(1.19839281085285, filter.shelf.b2, 1e-9)
        assertEquals(-1.69065929318241, filter.shelf.a1, 1e-9)
        assertEquals(0.73248077421585, filter.shelf.a2, 1e-9)
        assertEquals(-1.99004745483398, filter.highPass.a1, 1e-9)
        assertEquals(0.99007225036621, filter.highPass.a2, 1e-9)
    }

    @Test
    fun a_stereo_tone_at_minus_23_lufs_is_brought_to_minus_16() {
        val processor = YAudioEffectProcessor(YAudioEffect.LoudnessNormalize, RATE, stereo)
        val amplitude = decibels(-23.0)

        val peak = processTone(processor, amplitude, seconds = 25.0)

        // EBU Tech 3341: a 1 kHz tone at -23 dBFS on both channels reads -23 LUFS, so +7 dB.
        assertEquals(-16.0, 20 * log10(peak.toDouble()), 0.2)
    }

    @Test
    fun a_loud_scene_is_turned_down_and_the_silence_after_it_does_not_raise_the_gain() {
        val processor = YAudioEffectProcessor(YAudioEffect.LoudnessNormalize, RATE, stereo)

        val loud = processTone(processor, decibels(-6.0), seconds = 12.0)
        assertEquals(-16.0, 20 * log10(loud.toDouble()), 0.2)

        processTone(processor, 0.0, seconds = 15.0)
        // The scene resumes at the gain it left with, not boosted by the pause.
        val resumed = processTone(processor, decibels(-6.0), seconds = 0.05)
        assertEquals(-16.0, 20 * log10(resumed.toDouble()), 0.3)
    }

    @Test
    fun a_quiet_scene_after_a_loud_one_is_brought_back_up_once_the_loud_one_has_passed() {
        val processor = YAudioEffectProcessor(YAudioEffect.LoudnessNormalize, RATE, stereo)
        processTone(processor, decibels(-6.0), seconds = 10.0)

        // -30 LUFS wants +14 dB, capped at +12.
        val quiet = processTone(processor, decibels(-30.0), seconds = 20.0)

        assertEquals(-18.0, 20 * log10(quiet.toDouble()), 0.3)
    }

    @Test
    fun quiet_programme_is_raised_by_at_most_12_db_and_a_seek_keeps_the_gain() {
        val processor = YAudioEffectProcessor(YAudioEffect.LoudnessNormalize, RATE, stereo)
        val amplitude = decibels(-38.0)

        val raised = processTone(processor, amplitude, seconds = 40.0)
        assertEquals(-26.0, 20 * log10(raised.toDouble()), 0.2)

        processor.reset()
        val afterSeek = processTone(processor, amplitude, seconds = 0.05)
        assertEquals(-26.0, 20 * log10(afterSeek.toDouble()), 0.2)
    }

    @Test
    fun night_voice_gives_quiet_passages_the_makeup_gain_and_squeezes_loud_ones() {
        fun steadyPeak(amplitude: Double): Double {
            val processor = YAudioEffectProcessor(YAudioEffect.NightVoice, RATE, stereo)
            return 20 * log10(processTone(processor, amplitude, seconds = 2.0).toDouble())
        }

        // Well under the -20 dB threshold only the +5 dB makeup applies, so 8 dB in is 8 dB out.
        assertEquals(-35.0, steadyPeak(decibels(-40.0)), 0.05)
        assertEquals(-27.0, steadyPeak(decibels(-32.0)), 0.05)
        // Above it, 4:1: the same 8 dB step comes out as about 2 dB.
        val step = steadyPeak(decibels(-4.0)) - steadyPeak(decibels(-12.0))
        assertTrue(step in 1.5..3.0, "8 dB above the threshold came out as $step dB")
    }

    @Test
    fun night_voice_lifts_the_centre_channel_only() {
        val surround =
            listOf(
                YAudioChannelRole.Front,
                YAudioChannelRole.Front,
                YAudioChannelRole.Centre,
                YAudioChannelRole.Lfe,
                YAudioChannelRole.Surround,
                YAudioChannelRole.Surround,
            )
        val processor = YAudioEffectProcessor(YAudioEffect.NightVoice, RATE, surround)
        val frame = floatArrayOf(0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f)

        processor.process(frame, frames = 1)

        val makeup = decibels(5.0).toFloat()
        assertEquals(0.001f * makeup, frame[0], 1e-7f)
        assertEquals(0.001f * makeup * 1.4125376f, frame[2], 1e-7f)
        assertEquals(frame[0], frame[5])
    }

    @Test
    fun a_sample_that_is_not_finite_becomes_silence_instead_of_poisoning_the_state() {
        val processor = YAudioEffectProcessor(YAudioEffect.LoudnessNormalize, RATE, stereo)
        val samples = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.25f, 0.25f)

        processor.process(samples, frames = 2)
        val peak = processTone(processor, decibels(-23.0), seconds = 1.0)

        assertEquals(0f, samples[0])
        assertEquals(0f, samples[1])
        assertTrue(peak.isFinite() && peak > 0f)
    }

    @Test
    fun off_has_no_processor_and_frames_must_fit_the_buffer() {
        assertFailsWith<IllegalArgumentException> { YAudioEffectProcessor(YAudioEffect.Off, RATE, stereo) }
        assertFailsWith<IllegalArgumentException> {
            YAudioEffectProcessor(YAudioEffect.VolumeBoost, RATE, stereo).process(FloatArray(4), frames = 3)
        }
    }

    /** Feeds a 997 Hz stereo tone in 1024-frame chunks; returns the output peak of its last 100 ms. */
    private fun processTone(
        processor: YAudioEffectProcessor,
        amplitude: Double,
        seconds: Double,
    ): Float {
        val totalFrames = (seconds * RATE).toInt()
        var frame = 0
        var tailPeak = 0f
        while (frame < totalFrames) {
            val frames = minOf(CHUNK_FRAMES, totalFrames - frame)
            val chunk = FloatArray(frames * 2)
            for (index in 0 until frames) {
                val value = (amplitude * sin(2 * PI * TONE_HZ * (toneFrame + index) / RATE)).toFloat()
                chunk[index * 2] = value
                chunk[index * 2 + 1] = value
            }
            processor.process(chunk, frames)
            if (frame + frames > totalFrames - RATE / 10) tailPeak = maxOf(tailPeak, chunk.maxOf { abs(it) })
            frame += frames
            toneFrame += frames
        }
        return tailPeak
    }

    private var toneFrame = 0L

    private fun decibels(value: Double): Double = 10.0.pow(value / 20.0)

    private companion object {
        const val RATE = 48_000
        const val CHUNK_FRAMES = 1_024
        const val TONE_HZ = 997.0
    }
}
