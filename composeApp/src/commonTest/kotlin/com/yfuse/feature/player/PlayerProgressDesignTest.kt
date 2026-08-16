package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerProgressDesignTest {
    @Test
    fun skip_boundaries_become_real_progress_nodes() {
        val markers =
            playbackProgressMarkers(
                skip =
                    SkipSegmentState(
                        introStartSeconds = 4L,
                        introEndSeconds = 82L,
                        creditsLeadSeconds = 45L,
                    ),
                durationMs = 3_600_000L,
            )

        assertEquals(listOf(4_000L, 82_000L, 3_555_000L), markers.map { it.positionMs })
        assertEquals(listOf("片头", null, "片尾"), markers.map { it.label })
    }

    @Test
    fun liquid_rail_keeps_motion_and_preview_accessible() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()

        assertTrue("LiquidProgressBlue" in chrome)
        assertTrue("LiquidProgressViolet" in chrome)
        assertTrue("Brush.horizontalGradient" in chrome)
        assertTrue("reduceMotion" in chrome)
        assertTrue("previewX" in chrome)
    }

    @Test
    fun changing_time_digits_roll_up_independently() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()

        assertTrue("time.forEach { character" in chrome)
        assertTrue("RollingTimeGlyph(character)" in chrome)
        assertTrue("targetState = character" in chrome)
        assertTrue("slideInVertically(tween(Motion.STANDARD" in chrome)
        assertTrue("slideOutVertically(tween(Motion.STANDARD" in chrome)
        assertTrue("label = \"player-time-digit-roll\"" in chrome)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp/$moduleRelativePath"),
        ).first(File::exists)
}
