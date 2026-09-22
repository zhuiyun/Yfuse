package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AudioOutputDelayPreferencesTest {
    @Test fun device_profiles_are_stable_private_and_have_category_fallbacks() {
        val first = "Bluetooth A2DP · Headset One"
        val second = "Bluetooth A2DP · Headset Two"
        assertEquals(audioOutputDelayProfileKey(first), audioOutputDelayProfileKey(" $first "))
        assertNotEquals(audioOutputDelayProfileKey(first), audioOutputDelayProfileKey(second))
        assertEquals(audioOutputDelayCategoryKey(first), audioOutputDelayCategoryKey(second))
        assertNotEquals(audioOutputDelayCategoryKey(first), audioOutputDelayCategoryKey("Speaker"))
        assertFalse(checkNotNull(audioOutputDelayProfileKey(first)).contains("Headset"))
        assertNull(audioOutputDelayProfileKey(" "))
    }
}
