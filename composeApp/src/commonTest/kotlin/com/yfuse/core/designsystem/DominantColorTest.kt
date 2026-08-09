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
}
