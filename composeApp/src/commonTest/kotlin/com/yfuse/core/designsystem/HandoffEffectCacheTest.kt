package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.TileMode
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class HandoffEffectCacheTest {
    @Test
    fun neighbouring_frames_of_a_transition_share_one_blur() {
        val blurs = HandoffBlurs(TileMode.Clamp)
        assertNull(blurs.of(0f))
        assertSame(blurs.of(12f), blurs.of(12.1f))
        assertNotSame(blurs.of(12f), blurs.of(13f))
    }

    @Test
    fun a_constant_saturation_is_built_once() {
        val saturations = HandoffSaturations()
        assertSame(saturations.of(1.35f), saturations.of(1.351f))
        assertNotSame(saturations.of(1.3f), saturations.of(1.35f))
    }
}
