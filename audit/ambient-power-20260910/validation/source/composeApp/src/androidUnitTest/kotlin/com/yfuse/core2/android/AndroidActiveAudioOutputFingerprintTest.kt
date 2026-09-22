package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AndroidActiveAudioOutputFingerprintTest {
    @Test fun only_routed_device_capabilities_and_spatial_state_change_the_identity() {
        val routed = "4:2:2:48000:2"
        val connected = listOf(routed)
        assertEquals("", activeAudioOutputFingerprint("", listOf("9:8:2:48000:2"), "enabled"))
        val original = activeAudioOutputFingerprint(routed, connected, "off")
        assertEquals(original, activeAudioOutputFingerprint(routed, connected + "9:8:2:48000:2", "off"))
        assertNotEquals(original, activeAudioOutputFingerprint(routed, emptyList(), "off"))
        assertNotEquals(original, activeAudioOutputFingerprint(routed, listOf("4:2:2,6:48000:2,6"), "off"))
        assertNotEquals(original, activeAudioOutputFingerprint(routed, connected, "on"))
    }
}
