package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** No monitor, or a server the registry no longer has, must never block the task itself. */
    @Test
    fun a_missing_health_verdict_never_blocks_a_server_apply() {
        assertTrue(playbackSyncAllowsBackgroundApply(null))
    }

    @Test
    fun a_health_verdict_is_honored_either_way() {
        assertTrue(playbackSyncAllowsBackgroundApply(true))
        assertFalse(playbackSyncAllowsBackgroundApply(false))
    }

    @Test
    fun server_backoff_deadlines_survive_a_new_store_instance_over_the_same_settings() {
        val settings = MapSettings()

        PlaybackServerBackoffStore(settings).save(mapOf("server-a" to 1_000L, "server-b" to 2_000L))

        assertEquals(
            mapOf("server-a" to 1_000L, "server-b" to 2_000L),
            PlaybackServerBackoffStore(settings).load(),
        )
    }

    @Test
    fun server_backoff_store_starts_empty_and_survives_corrupt_data() {
        val emptySettings = MapSettings()
        assertEquals(emptyMap(), PlaybackServerBackoffStore(emptySettings).load())

        val corruptSettings = MapSettings()
        corruptSettings.putString("playback_sync.server_backoff", "{not json")
        assertEquals(emptyMap(), PlaybackServerBackoffStore(corruptSettings).load())
    }
}
