package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
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
