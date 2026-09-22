package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBackgroundNotifierTest {
    @Test
    fun backgroundNotifiesActiveListenerAndRemovalIsIdempotent() {
        var calls = 0
        val baseline = PlaybackBackgroundNotifier.listenerCount()
        val remove = PlaybackBackgroundNotifier.register { calls++ }

        assertEquals(baseline + 1, PlaybackBackgroundNotifier.listenerCount())
        PlaybackBackgroundNotifier.notifyAppBackground()
        assertEquals(1, calls)

        remove()
        remove()
        assertEquals(baseline, PlaybackBackgroundNotifier.listenerCount())
        PlaybackBackgroundNotifier.notifyAppBackground()
        assertEquals(1, calls)
    }
}
