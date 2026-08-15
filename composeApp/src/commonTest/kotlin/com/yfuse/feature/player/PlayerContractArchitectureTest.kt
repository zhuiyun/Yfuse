package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerContractArchitectureTest {
    @Test
    fun engine_contract_stays_platform_and_ui_agnostic() {
        val source =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/feature/player/contract/VideoEngine.kt",
            ).readText()

        assertTrue("interface VideoEngine" in source)
        assertTrue("val state: StateFlow<PlaybackState>" in source)
        assertFalse("import android." in source)
        assertFalse("import androidx.compose." in source)
        assertFalse("import io.ktor." in source)
    }

    @Test
    fun every_android_engine_implements_the_shared_contract() {
        listOf("ExoVideoEngine.kt", "MpvVideoEngine.kt", "MdkVideoEngine.kt").forEach { file ->
            val source = projectFile("src/androidMain/kotlin/com/yfuse/feature/player/$file").readText()
            assertTrue(") : VideoEngine" in source, "$file must implement VideoEngine")
        }
    }

    @Test
    fun playback_planning_stays_common_and_player_root_uses_its_ranked_fallbacks() {
        val planner =
            projectFile("src/commonMain/kotlin/com/yfuse/core/playback/PlaybackPlanner.kt").readText()
        val root =
            projectFile("src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt").readText()

        assertTrue("fun planPlayback(" in planner)
        assertFalse("import android." in planner)
        assertFalse("import androidx.compose." in planner)
        assertFalse("import io.ktor." in planner)
        assertTrue("recoveryPlan.engineOrder.firstOrNull" in root)
        assertFalse("PlayerEngine.selectable.firstOrNull { it !in triedEngines }" in root)
    }

    @Test
    fun activity_system_integration_stays_separate_from_player_composition() {
        val activity =
            projectFile("src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt").readText()
        val root =
            projectFile("src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt").readText()

        assertTrue("PlayerRoot(" in activity)
        assertFalse("fun PlayerRoot(" in activity)
        assertTrue("internal fun PlayerRoot(" in root)
        assertTrue("rememberPlayerDanmakuController(" in root)
        assertTrue("rememberPlayerSkipController(" in root)
        assertTrue("PlayerWatchSyncEffects(" in root)
        assertFalse("DanmakuFilter.apply" in root)
        assertFalse("GUEST_RECONCILE_TICK_MS" in root)
        assertFalse("AUTO_SKIP_COUNTDOWN_SECONDS" in root)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
