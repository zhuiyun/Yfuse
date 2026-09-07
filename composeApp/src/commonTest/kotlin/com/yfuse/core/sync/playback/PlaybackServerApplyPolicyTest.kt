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
    fun unauthorized_cools_the_server_instead_of_retrying_with_stale_credentials() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.CooldownServer,
            playbackServerApplyFailurePolicy(
                EmbyErrorException(EmbyError.Unauthorized),
            ),
        )
    }

    @Test
    fun cross_server_lookup_never_sends_an_emby_scoped_id() {
        assertEquals(
            listOf("tmdb:123", "imdb:tt456"),
            playbackLookupKeys(
                mediaKey = "emby:origin-item",
                aliases = listOf("tmdb:123", "imdb:tt456", "emby:duplicate"),
                originServerId = "origin",
                targetServerId = "other",
            ),
        )
    }

    @Test
    fun origin_lookup_prefers_its_exact_emby_id() {
        assertEquals(
            listOf("emby:origin-item", "tmdb:123"),
            playbackLookupKeys(
                mediaKey = "tmdb:123",
                aliases = listOf("emby:origin-item"),
                originServerId = "origin",
                targetServerId = "origin",
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
