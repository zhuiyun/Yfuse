package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeCrossfadeStateTest {
    @Test
    fun changing_accent_does_not_snap_an_in_progress_background_transition() =
        runTest {
            themeConsumerTest {
                theme.value = dark
                apply()
                advance(100)
                val before = shown.value
                theme.value = dark.copy(accent = resolveAccentColors(Color.Cyan, dark = true))
                apply()
                assertEquals(before, shown.value)
                assertNotEquals(dark.palette.background, shown.value)
                advance(500)
                assertEquals(dark.palette.background, shown.value)
            }
        }

    @Test
    fun reducing_motion_mid_transition_finishes_the_unchanged_target() =
        runTest {
            themeConsumerTest {
                theme.value = dark
                apply()
                advance(100)
                assertNotEquals(dark.palette.background, shown.value)
                reduced.value = true
                repeat(3) { apply() }
                assertEquals(dark.palette.background, shown.value)
            }
        }

    @Test
    fun rapid_theme_reversal_starts_from_the_visible_colour() =
        runTest {
            themeConsumerTest {
                theme.value = dark
                apply()
                advance(100)
                val before = shown.value
                theme.value = light
                apply()
                assertTrue(abs(before.red - shown.value.red) < 0.01f)
                advance(500)
                assertEquals(light.palette.background, shown.value)
            }
        }

    @Test
    fun hidden_routes_take_the_target_without_an_animation_clock() =
        runTest {
            themeConsumerTest {
                visible.value = false
                theme.value = dark
                repeat(3) { apply() }
                assertEquals(dark.palette.background, shown.value)
                val count = compositions
                advance(100)
                assertEquals(count, compositions)
            }
        }
}
