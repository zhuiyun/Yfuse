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
    fun transient_network_and_server_errors_back_off_the_whole_server() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.BackOffServer,
            playbackServerApplyFailurePolicy(EmbyErrorException(EmbyError.Network)),
        )
        assertEquals(
            PlaybackServerApplyFailurePolicy.BackOffServer,
            playbackServerApplyFailurePolicy(EmbyErrorException(EmbyError.Server(503))),
        )
    }

    @Test
    fun errors_unrelated_to_the_server_retry_only_their_own_task() {
        assertEquals(
            PlaybackServerApplyFailurePolicy.Retry,
            playbackServerApplyFailurePolicy(IllegalStateException("temporary")),
        )
        assertEquals(
            PlaybackServerApplyFailurePolicy.Retry,
            playbackServerApplyFailurePolicy(EmbyErrorException(EmbyError.Unknown("parse"))),
        )
    }

    @Test
    fun server_backoff_grows_from_fifteen_seconds_to_a_sixteen_minute_ceiling() {
        assertEquals(15_000L, playbackServerApplyBackoffMs(1))
        assertEquals(30_000L, playbackServerApplyBackoffMs(2))
        assertEquals(960_000L, playbackServerApplyBackoffMs(7))
        assertEquals(960_000L, playbackServerApplyBackoffMs(40))
    }
}
