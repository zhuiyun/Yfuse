package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionHandoffPolicyTest {
    @Test fun visible_rows_start_fifty_five_milliseconds_apart_and_always_finish() {
        for (index in 1..12) {
            assertEquals(0f, skeletonRowProgress((index * 55 - 1f) / 1000f, index))
            assertTrue(skeletonRowProgress((index * 55 + 100f) / 1000f, index) > 0f)
            assertEquals(1f, skeletonRowProgress(1f, index))
        }
    }

    @Test fun launch_is_single_use_and_disposed_cards_are_not_return_targets() {
        val owner = Any()
        val key = MediaSharedElementKey("test", "one-shot")
        val origin = PlayerArtworkOrigin(key, Rect(1f, 2f, 20f, 40f), Rect(0f, 0f, 100f, 100f), listOf("test://poster"))
        try {
            PlayerArtworkOrigins.register(owner, origin)
            PlayerArtworkOrigins.begin(key)
            val token = requireNotNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
            val launch = requireNotNull(PlayerArtworkOrigins.consume(token))
            assertEquals(origin.bounds, launch.hero)
            assertEquals(origin.urls, launch.urls)
            assertEquals(PlayerTransitionStyle.Turn, launch.style)
            assertEquals(HandoffPhase.Leaving, PlayerHandoff.phase)
            assertNull(PlayerArtworkOrigins.consume(token))
            assertNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
        } finally {
            PlayerArtworkOrigins.remove(owner)
            PlayerHandoff.settle()
        }
        assertNull(PlayerArtworkOrigins.resolve(key))
    }

    @Test fun the_plain_fade_never_holds_the_page_and_spends_the_tap() {
        val owner = Any()
        val key = MediaSharedElementKey("test", "plain")
        val origin = PlayerArtworkOrigin(key, Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 100f, 200f), listOf("test://hero"))
        try {
            PlayerArtworkOrigins.register(owner, origin)
            PlayerArtworkOrigins.begin(key)
            assertNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.None))
            assertEquals(HandoffPhase.Idle, PlayerHandoff.phase)
            assertNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
        } finally {
            PlayerArtworkOrigins.remove(owner)
            PlayerHandoff.settle()
        }
    }

    @Test fun the_display_is_asked_at_the_tap_not_on_every_layout() {
        val owner = Any()
        val key = MediaSharedElementKey("test", "asked")
        val display = ScreenGeometry(0, Size(1080f, 2400f), windowOffset = Offset(0f, 40f))
        var asked = 0
        val source =
            ScreenGeometrySource {
                asked++
                display
            }
        val viewport = Rect(0f, 0f, 1080f, 2360f)
        try {
            repeat(3) {
                val origin = PlayerArtworkOrigin(key, Rect(0f, 0f, 1080f, 1150f), viewport, listOf("test://hero"))
                PlayerArtworkOrigins.register(owner, origin, source)
            }
            assertEquals(0, asked)
            val resolved = requireNotNull(PlayerArtworkOrigins.resolve(key))
            assertEquals(1, asked)
            assertEquals(display, resolved.screen)
            assertEquals(Rect(0f, 40f, 1080f, 1190f), resolved.boundsOnScreen)
        } finally {
            PlayerArtworkOrigins.remove(owner)
        }
    }

    @Test fun a_launch_flies_from_the_hero_rather_than_a_poster_with_its_key() {
        val hero = Any()
        val poster = Any()
        val key = MediaSharedElementKey("test", "shared")
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val heroOrigin = PlayerArtworkOrigin(key, Rect(0f, 0f, 1080f, 1150f), viewport, listOf("test://hero"))
        val posterOrigin = PlayerArtworkOrigin(key, Rect(40f, 1900f, 340f, 2350f), viewport, listOf("test://poster"))
        try {
            // Laid out after the hero, which is what used to make it the one a launch flew from.
            PlayerArtworkOrigins.register(hero, heroOrigin)
            PlayerArtworkOrigins.register(poster, posterOrigin)
            assertEquals(heroOrigin, PlayerArtworkOrigins.resolve(key))
        } finally {
            PlayerArtworkOrigins.remove(hero)
            PlayerArtworkOrigins.remove(poster)
        }
    }

    @Test fun artwork_mostly_off_screen_starts_no_transition() {
        val owner = Any()
        val key = MediaSharedElementKey("test", "scrolled")
        val origin =
            PlayerArtworkOrigin(
                key,
                Rect(0f, -90f, 100f, 10f),
                Rect(0f, 0f, 100f, 200f),
                listOf("test://hero"),
            )
        try {
            PlayerArtworkOrigins.register(owner, origin)
            PlayerArtworkOrigins.begin(key)
            assertNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
            assertEquals(HandoffPhase.Idle, PlayerHandoff.phase)
        } finally {
            PlayerArtworkOrigins.remove(owner)
            PlayerHandoff.settle()
        }
    }
}
