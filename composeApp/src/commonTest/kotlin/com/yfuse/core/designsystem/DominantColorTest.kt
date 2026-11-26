package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class DominantColorTest {
    @Test
    fun nearBlackArtwork_isLiftedIntoAUsableAccentBand() {
        val accent = harmonizeArtworkAccent(Color(0xFF020409), darkTheme = true)
        assertTrue(accent.luminance() >= 0.09f)
    }

    @Test
    fun brightArtwork_isLoweredForWhiteButtonCopy() {
        val accent = harmonizeArtworkAccent(Color(0xFFFFFFC8), darkTheme = false)
        assertTrue(accent.luminance() <= 0.34f)
    }

    @Test
    fun harmonizationPreservesArtworkInfluence() {
        val red = harmonizeArtworkAccent(Color(0xFFC73535), darkTheme = true)
        val blue = harmonizeArtworkAccent(Color(0xFF3569C7), darkTheme = true)
        assertTrue(red != blue)
    }

    @Test
    fun playButtonsStayColouredAndReadableOnNeutralAndTintedArtwork() {
        val swatches = listOf(Color.White, Color.Black, Color.LightGray, Color(0xFFFFF7E8), Brand.Primary, Color(0xFF87381D))
        for (level in 0..255 step 8) {
            val background = Color(level, level, level)
            for (swatch in swatches) {
                for (dark in listOf(false, true)) {
                    val fill = artworkPlayButtonColor(swatch, background, dark)
                    assertTrue(fill.luminance() < 0.70f)
                    assertTrue(artworkPageContrastRatio(fill, background) >= 3f)
                    assertTrue(artworkPageContrastRatio(contentColorForSurface(fill), fill) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun artworkPageSampling_followsTheFadeAndFavoursItsLowerEdge() {
        val weights = (0..100).map { artworkPageSampleWeight(it / 100f) }

        assertTrue(weights.first() == 0f)
        assertTrue(weights.last() == 1f)
        assertTrue(weights.zipWithNext().all { (left, right) -> left <= right })
        assertTrue(artworkPageSampleWeight(0.82f) > artworkPageSampleWeight(0.58f))
    }

    @Test
    fun heroMask_revealsThePageOnlyAtTheLastPixelWithoutAFlatTail() {
        val stops = heroPageFadeMaskStops()

        assertTrue(stops.first().second.alpha == 0f)
        assertTrue(stops.dropLast(1).all { it.second.alpha < 1f })
        assertTrue(stops.last().first == 1f)
        assertTrue(stops.last().second.alpha == 1f)
        assertTrue(
            stops
                .asList()
                .zipWithNext()
                .all { (current, next) -> current.second.alpha <= next.second.alpha },
        )
    }
}
