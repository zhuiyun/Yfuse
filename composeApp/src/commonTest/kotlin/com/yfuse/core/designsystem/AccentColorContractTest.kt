package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentColorContractTest {
    /**
     * 跟随封面 is a mode, not a hue: what it resolves to depends on the page, so it is the one
     * entry that cannot be asked to differ from the rest by its fixed value.
     */
    private val fixedColours = AccentColor.entries.filterNot { it.followsArtwork }

    @Test
    fun everyAccentProducesDistinctInteractiveColorInBothThemes() {
        listOf(false, true).forEach { dark ->
            val resolved = fixedColours.map { it.resolveColors(dark).accent }
            assertEquals(
                fixedColours.size,
                resolved.distinct().size,
                "Every visible accent choice must change the resolved interactive colour",
            )
        }
    }

    @Test
    fun exactlyOneEntryDefersToTheArtwork() {
        assertEquals(1, AccentColor.entries.count { it.followsArtwork })
    }

    /**
     * Its fallback is what pages with no artwork — settings, servers, search — are painted
     * with, so it has to clear the same contrast floor every other choice does.
     */
    @Test
    fun artworkFallbackIsReadableOnBothThemes() {
        val artwork = AccentColor.entries.first { it.followsArtwork }
        listOf(false, true).forEach { dark ->
            val resolved = artwork.resolveColors(dark)
            assertTrue(
                resolved.accent != Color.Unspecified,
                "The 跟随封面 fallback must resolve to a usable colour",
            )
        }
    }
}
