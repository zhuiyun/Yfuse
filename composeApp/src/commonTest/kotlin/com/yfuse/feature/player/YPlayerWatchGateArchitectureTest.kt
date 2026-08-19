package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlayerWatchGateArchitectureTest {
    @Test
    fun `watch control gate targets YPlayer internally while retaining Legacy construction`() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/WatchGatedPlayback.kt").readText()
        assertTrue("LegacyYPlayerAdapter" in source)
        assertTrue("private inline fun gated(action: (YPlayer) -> Unit)" in source)
        assertTrue("gated(YPlayer::retry)" in source)
        assertFalse("private inline fun gated(action: (VideoEngine) -> Unit)" in source)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
