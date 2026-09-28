package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivingPosterHeroHeightTest {
    @Test
    fun upright_phones_and_tablets_keep_the_cinematic_heights() {
        assertEquals(480f, livingPosterHeroHeight(640.dp, wideLayout = false).value, 0.01f)
        assertEquals(480f, livingPosterHeroHeight(560.dp, wideLayout = false).value, 0.01f)
        assertEquals(610f, livingPosterHeroHeight(915.dp, wideLayout = false).value, 0.01f)
        assertEquals(536f, livingPosterHeroHeight(800.dp, wideLayout = true).value, 0.01f)
        assertEquals(730f, livingPosterHeroHeight(1_500.dp, wideLayout = true).value, 0.01f)
    }

    @Test
    fun a_phone_on_its_side_gets_a_reel_shorter_than_its_screen() {
        // 915 × 412 and 780 × 360 are common phones held sideways; the floor used to make both 480dp.
        assertEquals(370.8f, livingPosterHeroHeight(412.dp, wideLayout = true).value, 0.01f)
        assertEquals(324f, livingPosterHeroHeight(360.dp, wideLayout = true).value, 0.01f)
        // A short window in portrait, such as split screen, follows the same rule.
        assertEquals(405f, livingPosterHeroHeight(450.dp, wideLayout = false).value, 0.01f)
    }

    @Test
    fun the_reel_never_outgrows_the_viewport_and_short_ones_keep_a_margin() {
        for (height in 240..1_600) {
            for (wide in listOf(false, true)) {
                val viewport = height.dp
                val hero = livingPosterHeroHeight(viewport, wideLayout = wide)
                assertTrue(hero <= viewport, "$height dp, wide = $wide: $hero")
                if (hero < 480.dp) {
                    // The caption's action row needs this room to stay clear of the floating dock.
                    assertTrue(viewport - hero >= viewport * 0.1f - 0.01.dp, "$height dp, wide = $wide: $hero")
                }
            }
        }
    }
}
