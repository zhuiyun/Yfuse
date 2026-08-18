package com.yfuse.feature.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSearchUiContractTest {
    @Test
    fun home_hero_protects_system_bar_contrast_and_has_one_search_entry() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt").readText()
        val carousel =
            source
                .substringAfter("private fun HomeHeroCarousel(")
                .substringBefore("private fun HeroSlide(")
        val header =
            source
                .substringAfter("private fun HeroHeader(")
                .substringBefore("private fun HeroCaption(")

        assertTrue("HomeStatusBarScrim" in source)
        assertTrue("HomeStatusBarScrimHeight" in carousel)
        assertFalse("onOpenSearch" in carousel)
        assertFalse("AppIcons.Search" in header)
    }

    @Test
    fun hero_uses_play_as_primary_action_and_details_as_secondary_action() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt").readText()
        val caption =
            source
                .substringAfter("private fun HeroCaption(")
                .substringBefore("private fun HeroCircleButton(")
        val icons = projectFile("src/commonMain/kotlin/com/yfuse/core/designsystem/AppIcons.kt").readText()
        val info =
            icons
                .substringAfter("val Info =")
                .substringBefore("// ------------------------------------------------- state glyphs")

        assertTrue("onClickLabel = \"播放影片\"" in caption)
        assertTrue("HeroCircleButton(AppIcons.Info, \"查看详情\", onDetails)" in caption)
        assertTrue("verticalLineTo(18.2f)" in info)
        assertTrue("andDots(12f to 5.8f)" in info)
    }

    @Test
    fun search_filters_signal_overflow_and_aggregated_source_badge_is_inline() {
        val filters = projectFile("src/commonMain/kotlin/com/yfuse/feature/search/SearchFilters.kt").readText()
        val search = projectFile("src/commonMain/kotlin/com/yfuse/feature/search/SearchScreen.kt").readText()
        val aggregate =
            search
                .substringAfter("private fun AggregatedResults(")
                .substringBefore("private fun SearchField(")

        assertTrue("horizontalScrollEdgeFade(listState)" in filters)
        assertTrue("state.canScrollForward" in filters)
        assertTrue("sourceSummary =" in aggregate)
        assertFalse("Alignment.TopEnd" in aggregate)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
