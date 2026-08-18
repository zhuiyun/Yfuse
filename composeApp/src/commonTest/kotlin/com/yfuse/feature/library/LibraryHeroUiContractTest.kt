package com.yfuse.feature.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryHeroUiContractTest {
    @Test
    fun library_hero_matches_the_compact_play_first_contract() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt").readText()
        val hero =
            source
                .substringAfter("private fun HeroCarousel(")
                .substringBefore("private fun ServerSheet(")

        assertTrue("(maxHeight * 0.60f).coerceIn(350.dp, 520.dp)" in source)
        assertTrue("(maxHeight * 0.60f).coerceIn(420.dp, 720.dp)" in source)
        assertTrue("onClickLabel = \"播放影片\"" in hero)
        assertTrue("description = \"查看详情\"" in hero)
        assertFalse("item.overview" in hero)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
