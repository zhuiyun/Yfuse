package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassSelectionStyleTest {
    @Test
    fun light_selected_pane_keeps_a_visible_body_under_luminous_edges() {
        val accent = resolveAccentColors(Brand.Primary, dark = false).accent
        for (opaque in listOf(false, true)) {
            val selection = resolveGlassSelectionStyle(LightPalette, accent, reduceTransparency = opaque)
            val tracks =
                listOf(
                    Color.White,
                    LightPalette.card2.compositeOver(LightPalette.background),
                    LightPalette.card3.compositeOver(LightPalette.background),
                )
            for (track in tracks) {
                val body = selection.middle.compositeOver(track)
                val lowerBody = selection.bottom.compositeOver(track)
                assertTrue(artworkPageContrastRatio(body, track) >= 1.15f, "Selected body must separate from its track")
                assertTrue(
                    artworkPageContrastRatio(lowerBody, track) >= 1.25f,
                    "The glass gradient must remain visible",
                )
                for (rim in listOf(selection.rimTop, selection.rimBottom)) {
                    assertTrue(
                        rim.compositeOver(body).luminance() > body.luminance(),
                        "The selected rim must catch light rather than form a dark outline",
                    )
                }
            }
        }
    }

    @Test
    fun light_selection_edges_stay_luminous_even_with_extreme_artwork_accents() {
        for (accent in listOf(Brand.Primary, Color.Black, Color.White, Color(0xFF351940), Color(0xFFFFD044))) {
            for (opaque in listOf(false, true)) {
                val selection = resolveGlassSelectionStyle(LightPalette, accent, reduceTransparency = opaque)
                for (rim in listOf(selection.rimTop, selection.rimBottom)) {
                    val edge = rim.compositeOver(selection.bottom.compositeOver(LightPalette.background))
                    assertTrue(edge.luminance() >= 0.85f, "Light buttons must not acquire a dark accent outline")
                }
            }
        }
    }

    @Test
    fun selected_labels_remain_readable_on_every_gradient_stop_and_host_surface() {
        val palettes =
            listOf(
                LightPalette,
                DarkPalette,
                LightPalette.copy(background = Color(0xFFEADCD7)),
                DarkPalette.copy(background = Color(0xFF233A35)),
            )
        val accents =
            listOf(
                Brand.Primary,
                Color(0xFFFFD044),
                Color(0xFF8ED8A0),
                Color(0xFFEF839C),
                Color(0xFF6D4A99),
                Color.White,
                Color.Black,
            )
        for (palette in palettes) {
            for (accent in accents) {
                for (opaque in listOf(false, true)) {
                    val selection = resolveGlassSelectionStyle(palette, accent, reduceTransparency = opaque)
                    val backdrops =
                        listOf(
                            palette.background,
                            palette.card2.compositeOver(palette.background),
                            palette.card3.compositeOver(palette.background),
                        )
                    for (backdrop in backdrops) {
                        for (stop in listOf(selection.top, selection.middle, selection.bottom)) {
                            val contrast =
                                artworkPageContrastRatio(selection.contentColor, stop.compositeOver(backdrop))
                            assertTrue(
                                contrast >= 4.5f,
                                "dark=${palette.isDark}, accent=$accent, opaque=$opaque: contrast=$contrast",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reduced_transparency_keeps_the_visible_page_tones_while_making_the_pane_opaque() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            val glass = resolveGlassSelectionStyle(palette, Brand.Primary)
            val opaque = resolveGlassSelectionStyle(palette, Brand.Primary, reduceTransparency = true)
            val glassStops = listOf(glass.top, glass.middle, glass.bottom)
            val opaqueStops = listOf(opaque.top, opaque.middle, opaque.bottom)
            glassStops.zip(opaqueStops).forEach { (glassStop, opaqueStop) ->
                assertTrue(glassStop.alpha < 1f)
                assertEquals(1f, opaqueStop.alpha)
                assertEquals(glassStop.compositeOver(palette.background), opaqueStop)
            }
        }
    }
}
