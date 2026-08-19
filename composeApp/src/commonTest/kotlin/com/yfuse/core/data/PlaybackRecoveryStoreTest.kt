package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRecoveryStoreTest {
    private val now = 2_000_000_000_000L
    private val server =
        SavedServer(
            id = "server-a",
            baseUrl = "https://emby.example",
            serverName = "家庭服务器",
            userId = "user",
            userName = "User",
            accessToken = "secret",
        )

    @Test
    fun checkpoint_survives_recreation_without_media_url() {
        val settings = MapSettings()
        PlaybackRecoveryStore(settings).record(
            itemId = "episode-7",
            title = "第 7 集",
            serverId = "server-a",
            positionMs = 123_000L,
            durationMs = 2_400_000L,
            engine = "MDK",
            force = true,
        )

        val restored = PlaybackRecoveryStore(settings).snapshot.value

        assertEquals("episode-7", restored?.itemId)
        assertEquals(123_000L, restored?.positionMs)
        assertEquals("server-a", restored?.serverId)
    }

    @Test
    fun clear_removes_persisted_checkpoint() {
        val settings = MapSettings()
        val store = PlaybackRecoveryStore(settings)
        store.record("movie", "Movie", "server", 5_000L, 10_000L, "Exo", true)

        store.clear()

        assertNull(PlaybackRecoveryStore(settings).snapshot.value)
    }

    @Test
    fun eligible_checkpoint_requires_real_progress_age_and_its_original_server() {
        val evaluation =
            evaluatePlaybackRecovery(
                snapshot = snapshot(positionMs = 120_000L, durationMs = 3_600_000L),
                servers = listOf(server),
                nowEpochMs = now,
            )

        assertEquals(PlaybackRecoveryEligibility.Eligible, evaluation.eligibility)
        assertEquals("server-a", evaluation.server?.id)
        assertTrue(evaluation.shouldPrompt)
    }

    @Test
    fun near_end_old_and_removed_server_checkpoints_are_not_offered() {
        val nearEnd =
            evaluatePlaybackRecovery(
                snapshot(positionMs = 590_000L, durationMs = 600_000L),
                listOf(server),
                now,
            )
        val tooOld =
            evaluatePlaybackRecovery(
                snapshot(
                    positionMs = 120_000L,
                    durationMs = 600_000L,
                    updatedAtEpochMs = now - 8L * 24L * 60L * 60L * 1_000L,
                ),
                listOf(server),
                now,
            )
        val missing =
            evaluatePlaybackRecovery(
                snapshot(positionMs = 120_000L, durationMs = 600_000L),
                emptyList(),
                now,
            )

        assertEquals(PlaybackRecoveryEligibility.NearEnd, nearEnd.eligibility)
        assertEquals(PlaybackRecoveryEligibility.TooOld, tooOld.eligibility)
        assertEquals(PlaybackRecoveryEligibility.ServerMissing, missing.eligibility)
        assertFalse(nearEnd.shouldPrompt)
        assertFalse(tooOld.shouldPrompt)
        assertFalse(missing.shouldPrompt)
    }

    @Test
    fun missing_token_is_offered_only_as_a_reauthentication_prompt() {
        val evaluation =
            evaluatePlaybackRecovery(
                snapshot(positionMs = 120_000L, durationMs = 600_000L),
                listOf(server.copy(accessToken = "")),
                now,
            )

        assertEquals(PlaybackRecoveryEligibility.AuthenticationRequired, evaluation.eligibility)
        assertTrue(evaluation.shouldPrompt)
    }

    @Test
    fun checkpoint_with_a_corrupt_future_timestamp_is_invalid() {
        val toleratedClockSkew =
            evaluatePlaybackRecovery(
                snapshot(
                    positionMs = 120_000L,
                    durationMs = 600_000L,
                    updatedAtEpochMs = now + 5L * 60L * 1_000L,
                ),
                listOf(server),
                now,
            )
        val corruptFuture =
            evaluatePlaybackRecovery(
                snapshot(
                    positionMs = 120_000L,
                    durationMs = 600_000L,
                    updatedAtEpochMs = now + 5L * 60L * 1_000L + 1L,
                ),
                listOf(server),
                now,
            )

        assertEquals(PlaybackRecoveryEligibility.Eligible, toleratedClockSkew.eligibility)
        assertEquals(PlaybackRecoveryEligibility.Invalid, corruptFuture.eligibility)
        assertFalse(corruptFuture.shouldPrompt)
    }

    @Test
    fun startup_checkpoint_is_consumed_once_per_store_process_instance() {
        val settings = MapSettings()
        PlaybackRecoveryStore(settings).record(
            itemId = "episode",
            title = "Episode",
            serverId = "server-a",
            positionMs = 120_000L,
            durationMs = 600_000L,
            engine = "Exo",
            force = true,
        )
        val firstProcess = PlaybackRecoveryStore(settings)

        assertTrue(firstProcess.takeStartupEvaluation(listOf(server))?.shouldPrompt == true)
        assertNull(firstProcess.takeStartupEvaluation(listOf(server)))
        assertTrue(
            PlaybackRecoveryStore(settings).takeStartupEvaluation(listOf(server))?.shouldPrompt == true,
            "a recreated process must be allowed to offer the persisted checkpoint",
        )
    }

    private fun snapshot(
        positionMs: Long,
        durationMs: Long,
        updatedAtEpochMs: Long = now - 60_000L,
    ) = PlaybackRecoverySnapshot(
        itemId = "episode",
        title = "Episode",
        serverId = "server-a",
        positionMs = positionMs,
        durationMs = durationMs,
        engine = "Exo",
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
