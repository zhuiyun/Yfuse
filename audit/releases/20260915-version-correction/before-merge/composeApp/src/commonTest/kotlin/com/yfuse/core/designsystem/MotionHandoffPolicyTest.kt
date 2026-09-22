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

    @Test fun artwork_geometry_remaps_portrait_coordinates_to_the_player_viewport() {
        val origin =
            PlayerArtworkOrigin(
                MediaSharedElementKey("test", "geometry"),
                Rect(100f, 200f, 300f, 600f),
                Rect(0f, 0f, 400f, 800f),
                listOf("test://poster"),
            )
        assertEquals(Rect(200f, 100f, 600f, 300f), playerArtworkRect(origin, 800f, 400f))
    }

    @Test fun launch_is_single_use_and_disposed_cards_are_not_return_targets() {
        val owner = Any()
        val key = MediaSharedElementKey("test", "one-shot")
        val origin = PlayerArtworkOrigin(key, Rect(1f, 2f, 20f, 40f), Rect(0f, 0f, 100f, 100f), listOf("test://poster"))
        try {
            PlayerArtworkOrigins.register(owner, origin)
            PlayerArtworkOrigins.begin(key)
            val token = requireNotNull(PlayerArtworkOrigins.issueLaunch())
            assertEquals(origin, PlayerArtworkOrigins.consume(token))
            assertNull(PlayerArtworkOrigins.consume(token))
            assertNull(PlayerArtworkOrigins.issueLaunch())
        } finally {
            PlayerArtworkOrigins.remove(owner)
        }
        assertNull(PlayerArtworkOrigins.resolve(key))
    }
}
