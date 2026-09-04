package com.yfuse.core.sync.playback

import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackServerApplyPolicyTest {
    /**
     * The policy used to look for a 404 on `EmbyError.Server`, which only ever carries 5xx, so a
     * real missing item arrived as an unclassified error and requeued itself on every sync.
     */
    @Test
    fun not_found_is_a_terminal_server_target_failure() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.DropTarget,
            playbackServerApplyFailurePolicy(
                EmbyErrorException(EmbyError.NotFound),
            ),
        )
    }

    @Test
    fun access_denied_cools_the_server_instead_of_retrying_the_same_task() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.CooldownServer,
            playbackServerApplyFailurePolicy(
                EmbyErrorException(EmbyError.AccessDenied("Cloudflare")),
            ),
        )
    }

    @Test
    fun transient_network_and_server_errors_still_retry() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.Retry,
            playbackServerApplyFailurePolicy(EmbyErrorException(EmbyError.Network)),
        )
        assertEquals(
            PlaybackServerApplyFailurePolicy.Retry,
            playbackServerApplyFailurePolicy(EmbyErrorException(EmbyError.Server(503))),
        )
        assertEquals(
            PlaybackServerApplyFailurePolicy.Retry,
            playbackServerApplyFailurePolicy(IllegalStateException("temporary")),
        )
    }
}
