package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.AppIconVariant
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.SplashMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SplashSelectionTest {
    @Test
    fun chosen_effect_survives_restart_and_toggling_playback_or_accessibility() {
        for (choice in SplashAnimation.selectable) {
            val settings = MapSettings()
            val prefs = ThemePreferences(settings)
            prefs.setSplashVariant(choice)
            prefs.setSplashAnimation(false)
            prefs.setReduceMotion(true)
            val restored = ThemePreferences(settings)
            assertEquals(choice, restored.splashVariant.value)
            assertFalse(restored.splashAnimation.value)
        }
    }

    @Test
    fun corrupt_and_retired_choices_fall_back_to_default() {
        for (value in listOf("missing", "Still", "CloudWell")) {
            val settings = MapSettings()
            settings.putString("appearance.splashVariant.v3", value)
            assertEquals(SplashAnimation.One, ThemePreferences(settings).splashVariant.value)
        }
    }

    @Test
    fun switching_logos_always_resolves_matching_artwork_without_overwriting_the_saved_effect() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        prefs.setSplashVariant(SplashAnimation.Crayon)
        assertEquals(
            SplashAnimation.Crayon,
            SplashAnimation.forMark(AppIconVariant.Default.splashMark, prefs.splashVariant.value),
        )
        assertEquals(
            SplashAnimation.One,
            SplashAnimation.forMark(AppIconVariant.Classic.splashMark, prefs.splashVariant.value),
        )
        assertEquals(
            SplashAnimation.Cloud,
            SplashAnimation.forMark(AppIconVariant.CloudPlayer.splashMark, prefs.splashVariant.value),
        )
        assertEquals(
            SplashAnimation.Crayon,
            SplashAnimation.forMark(AppIconVariant.WaterOverFire.splashMark, prefs.splashVariant.value),
        )
        assertEquals(SplashAnimation.Crayon, ThemePreferences(settings).splashVariant.value)
    }

    @Test
    fun picker_offers_every_launched_identity_and_sixteen_water_fire_designs_including_mechanisms() {
        assertEquals(
            listOf(
                AppIconVariant.LiquidGlass,
                AppIconVariant.Prism,
                AppIconVariant.Overprint,
                AppIconVariant.Danmaku,
                AppIconVariant.AuroraDark,
                AppIconVariant.AuroraLight,
                AppIconVariant.Default,
                AppIconVariant.Classic,
                AppIconVariant.Graphite,
                AppIconVariant.CloudPlayer,
            ),
            AppIconVariant.selectable,
        )
        assertEquals(16, SplashAnimation.selectable.count { it.mark == SplashMark.WaterOverFire })
        for (craft in listOf(
            SplashAnimation.Crayon,
            SplashAnimation.Beads,
            SplashAnimation.Sand,
            SplashAnimation.Rubbing,
            SplashAnimation.Hologram,
            SplashAnimation.Marble,
            SplashAnimation.Fan,
            SplashAnimation.Domino,
        )) {
            assertEquals(craft, SplashAnimation.forMark(SplashMark.WaterOverFire, craft))
        }
        // Only the one that shows the same artwork as the default stays out of the picker.
        assertEquals(
            setOf(AppIconVariant.WaterOverFire),
            AppIconVariant.entries.toSet() - AppIconVariant.selectable.toSet(),
        )
        for (retired in AppIconVariant.entries - AppIconVariant.selectable.toSet()) {
            assertEquals(AppIconVariant.Default, retired.normalized)
        }
    }
}
