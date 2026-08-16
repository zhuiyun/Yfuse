package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether YCore hands playback to another backend must not depend on how a label is worded.
 *
 * The runtime observation used to derive videoReady/audioReady by substring-matching the
 * Chinese diagnostic strings. MDK's label says — accurately — that it cannot verify its own
 * output, and because that sentence contains no 等待 it was read as *ready*, which silently
 * disabled StartupTimeout, VideoOutputMissing and AudioOutputMissing on that backend. The
 * same rule read "音频输出已释放" as ready, and the two sides disagreed on `contains` versus
 * `startsWith`. None of that was visible to any test, because the coupling was a string.
 */
class PlaybackOutputReadinessContractTest {
    @Test
    fun readiness_is_never_derived_from_a_diagnostic_label() {
        val runtime = source("androidMain", "feature/player/YCorePlayerRuntime.android.kt")

        assertFalse(
            Regex("""(videoReady|audioReady|videoExpected|audioExpected)\s*=[^,]*diagnostics\.(video|audio)Output""")
                .containsMatchIn(runtime),
            "A backend-switching decision must read the reported state, not the label text",
        )
        assertFalse(
            Regex("""diagnostics\.(video|audio)Output[^,\n]*\.(contains|startsWith|endsWith)""")
                .containsMatchIn(runtime),
            "Matching on the label's wording is what hid the MDK gap",
        )
    }

    @Test
    fun the_observation_reads_the_structured_readiness() {
        val runtime = source("androidMain", "feature/player/YCorePlayerRuntime.android.kt")

        assertTrue("diagnostics.videoReadiness" in runtime)
        assertTrue("diagnostics.audioReadiness" in runtime)
        assertTrue(
            "diagnostics.videoReadiness.verifiable" in runtime &&
                "diagnostics.audioReadiness.verifiable" in runtime,
            "Unknown has to withhold the missing-output judgement rather than pass it",
        )
    }

    /**
     * MDK is the backend the string rule got wrong, so it is the one worth pinning: it must
     * say Unknown explicitly, and it must never claim Rendering it cannot observe.
     */
    @Test
    fun mdk_reports_that_it_cannot_verify_its_output() {
        val mdk = source("androidMain", "feature/player/MdkVideoEngine.kt")

        assertTrue(
            "videoReadiness = PlaybackOutputReadiness.Unknown" in mdk &&
                "audioReadiness = PlaybackOutputReadiness.Unknown" in mdk,
        )
        assertFalse(
            "PlaybackOutputReadiness.Rendering" in mdk,
            "MDK exposes no output callbacks, so it cannot report verified output",
        )
    }

    /** The backends that do observe their output have to say so, or they read as silent. */
    @Test
    fun exo_and_mpv_report_verified_output() {
        listOf("ExoVideoEngine.kt", "MpvVideoEngine.kt").forEach { engine ->
            val text = source("androidMain", "feature/player/$engine")
            assertTrue(
                "videoReadiness = PlaybackOutputReadiness.Rendering" in text ||
                    "PlaybackOutputReadiness.Rendering" in text,
                "$engine never reports rendering output",
            )
            assertTrue(
                "audioReadiness = PlaybackOutputReadiness.Rendering" in text,
                "$engine never reports established audio, so it would read as silent",
            )
        }
    }

    @Test
    fun waiting_is_the_default_so_a_stalled_start_is_still_caught() {
        val contract = source("commonMain", "feature/player/contract/VideoEngine.kt")

        assertTrue(
            "val videoReadiness: PlaybackOutputReadiness = PlaybackOutputReadiness.Waiting" in contract,
            "Defaulting to Unknown would disable the startup timeout for a source that fails " +
                "before its backend reports anything at all",
        )
        assertEquals(
            2,
            Regex("""PlaybackOutputReadiness = PlaybackOutputReadiness\.Waiting""")
                .findAll(contract)
                .count(),
        )
    }

    private fun source(
        sourceSet: String,
        path: String,
    ): String =
        sequenceOf(
            File("src/$sourceSet/kotlin/com/yfuse/$path"),
            File("composeApp/src/$sourceSet/kotlin/com/yfuse/$path"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate $path from ${File(".").absolutePath}")
}
