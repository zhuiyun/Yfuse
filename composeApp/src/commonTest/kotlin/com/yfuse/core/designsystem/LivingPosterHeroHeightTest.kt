package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivingPosterHeroHeightTest {
    @Test
    fun upright_phones_and_tablets_leave_room_for_the_first_shelf() {
        assertEquals(432f, livingPosterHeroHeight(640.dp, wideLayout = false).value, 0.01f)
        assertEquals(432f, livingPosterHeroHeight(560.dp, wideLayout = false).value, 0.01f)
        assertEquals(560f, livingPosterHeroHeight(915.dp, wideLayout = false).value, 0.01f)
        assertEquals(488f, livingPosterHeroHeight(800.dp, wideLayout = true).value, 0.01f)
        assertEquals(682f, livingPosterHeroHeight(1_500.dp, wideLayout = true).value, 0.01f)
    }

    @Test
    fun a_phone_on_its_side_gets_a_reel_shorter_than_its_screen() {
        // 915 × 412 and 780 × 360 are common phones held sideways; the floor used to make both 480dp.
        assertEquals(329.6f, livingPosterHeroHeight(412.dp, wideLayout = true).value, 0.01f)
        assertEquals(288f, livingPosterHeroHeight(360.dp, wideLayout = true).value, 0.01f)
        // A short window in portrait, such as split screen, follows the same rule.
        assertEquals(360f, livingPosterHeroHeight(450.dp, wideLayout = false).value, 0.01f)
    }

    @Test
    fun only_multiple_posters_reserve_the_indicator_touch_row() {
        val many = livingPosterHeroHeight(720.dp, wideLayout = false, hasIndicator = true)
        val single = livingPosterHeroHeight(720.dp, wideLayout = false, hasIndicator = false)
        val manyPadding = LivingPosterDefaults.captionBottom(hasIndicator = true)
        val singlePadding = LivingPosterDefaults.captionBottom(hasIndicator = false)
        assertEquals(8.dp, singlePadding)
        assertEquals(MinTouchTarget + singlePadding, manyPadding)
        assertEquals(many - manyPadding, single - singlePadding)
    }

    @Test
    fun the_reel_never_outgrows_the_viewport_and_short_ones_keep_a_margin() {
        for (height in 240..1_600) {
            for (wide in listOf(false, true)) {
                val viewport = height.dp
                val hero = livingPosterHeroHeight(viewport, wideLayout = wide)
                assertTrue(hero <= viewport, "$height dp, wide = $wide: $hero")
                assertTrue(viewport - hero >= viewport * 0.2f - 0.01.dp, "$height dp, wide = $wide: $hero")
            }
        }
    }
}
