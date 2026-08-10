package com.yfuse.feature.player

import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailoverPolicyTest {
    @Test
    fun network_and_5xx_can_fail_over() {
        assertTrue(EmbyErrorException(EmbyError.Network).isPlaybackFailoverEligible())
        assertTrue(EmbyErrorException(EmbyError.Server(503)).isPlaybackFailoverEligible())
    }

    @Test
    fun auth_and_client_errors_never_fail_over() {
        assertFalse(EmbyErrorException(EmbyError.Unauthorized).isPlaybackFailoverEligible())
        assertFalse(EmbyErrorException(EmbyError.Server(404)).isPlaybackFailoverEligible())
    }
}
