package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerArtworkOriginTest {
    private val owner = Any()
    private val viewport = Rect(0f, 0f, 1080f, 2400f)

    // Both objects are process-wide: start from nothing another test left behind, and leave nothing.
    @BeforeTest
    fun startClean() = forgetTheTap()

    @AfterTest
    fun forgetTheTap() {
        PlayerArtworkOrigins.remove(owner)
        PlayerArtworkOrigins.pageTouched()
        PlayerHandoff.settle()
    }

    @Test
    fun aTapThatEndedInAPickerLendsNothingToALaunchFromAnotherTouch() {
        val hero = MediaSharedElementKey("test", "picker-cancelled")
        val origin = PlayerArtworkOrigin(hero, Rect(0f, 0f, 1080f, 1150f), viewport, listOf("test://hero"))
        PlayerArtworkOrigins.register(owner, origin)
        PlayerHandoff.keyPressed(HandoffKey(Rect(40f, 1100f, 1040f, 1152f), 16f, null, null, glass = false))
        PlayerArtworkOrigins.begin(hero)

        // The picker is cancelled, and a related poster is long-pressed and played from its 浮起菜单.
        PlayerArtworkOrigins.pageTouched()

        assertNull(PlayerHandoff.recentKey())
        assertNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
        assertEquals(HandoffPhase.Idle, PlayerHandoff.phase)
    }

    @Test
    fun aTapStillLaunchesFromItsOwnArtworkAndKey() {
        val hero = MediaSharedElementKey("test", "own-tap")
        val origin = PlayerArtworkOrigin(hero, Rect(0f, 0f, 1080f, 1150f), viewport, listOf("test://hero"))
        val pressed = HandoffKey(Rect(40f, 1100f, 1040f, 1152f), 16f, null, null, glass = true)
        PlayerArtworkOrigins.register(owner, origin)

        // The tap's own touch is heard first; then its key records the press and its click begins.
        PlayerArtworkOrigins.pageTouched()
        PlayerHandoff.keyPressed(pressed)
        PlayerArtworkOrigins.begin(hero)

        val token = requireNotNull(PlayerArtworkOrigins.issueLaunch(PlayerTransitionStyle.Turn))
        val launch = requireNotNull(PlayerArtworkOrigins.consume(token))
        assertEquals(origin.bounds, launch.hero)
        assertEquals(pressed, launch.key)
    }
}
