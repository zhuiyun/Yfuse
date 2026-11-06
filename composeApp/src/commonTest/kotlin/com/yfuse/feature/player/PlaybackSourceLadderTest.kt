package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Source recovery stays within the selected engine: other files, then other servers. */
class PlaybackSourceLadderTest {
    private fun step(
        failure: PlaybackFailureKind,
        nextVersionId: String? = "v2",
        servers: List<PlayerMediaItem> = listOf(ladderItem(serverId = "s2")),
        serversTried: Set<String> = setOf("s1"),
    ) = nextPlaybackRecoveryStep(
        backendFallbackEligible = failure.allowsBackendFallback,
        nextVersionId = nextVersionId,
        serverCandidates = servers,
        serversTried = serversTried,
    )

    @Test
    fun only_transport_and_account_failures_skip_straight_to_another_server() {
        val server = PlaybackRecoveryStep.Server(ladderItem(serverId = "s2"), "s2")
        for (kind in PlaybackFailureKind.entries) {
            val expected =
                when (kind) {
                    PlaybackFailureKind.Network,
                    PlaybackFailureKind.Authorization,
                    PlaybackFailureKind.Drm,
                    -> server
                    else -> PlaybackRecoveryStep.Version("v2")
                }
            assertEquals(expected, step(kind), kind.name)
        }
    }

    @Test
    fun another_server_is_tried_whatever_the_failure() {
        for (kind in PlaybackFailureKind.entries) {
            assertEquals(
                PlaybackRecoveryStep.Server(ladderItem(serverId = "s2"), "s2"),
                step(kind, nextVersionId = null),
                kind.name,
            )
        }
    }

    @Test
    fun servers_are_tried_in_plan_order_and_a_copy_without_a_server_id_is_no_route() {
        val servers = listOf(ladderItem(serverId = null), ladderItem(serverId = "s1"), ladderItem(serverId = "s3"))
        assertEquals(
            PlaybackRecoveryStep.Server(ladderItem(serverId = "s3"), "s3"),
            step(PlaybackFailureKind.Network, servers = servers),
        )
        assertEquals(
            PlaybackRecoveryStep.Exhausted,
            step(PlaybackFailureKind.Network, servers = servers, serversTried = setOf("s1", "s3")),
        )
    }

    @Test
    fun the_widest_then_richest_untried_version_comes_next() {
        val item =
            ladderItem().copy(
                versions =
                    listOf(
                        version("1080", width = 1920, bitrate = 8_000_000),
                        version("4k-small", width = 3840, bitrate = 12_000_000),
                        version("unknown", width = null, bitrate = null),
                        version("4k-remux", width = 3840, bitrate = 70_000_000),
                    ),
                versionId = "4k-remux",
            )
        assertEquals("4k-small", item.nextFallbackVersionId(setOf("4k-remux")))
        assertEquals("1080", item.nextFallbackVersionId(setOf("4k-remux", "4k-small")))
        assertEquals("unknown", item.nextFallbackVersionId(setOf("4k-remux", "4k-small", "1080")))
        assertNull(item.nextFallbackVersionId(setOf("4k-remux", "4k-small", "1080", "unknown")))
    }

    @Test
    fun a_viewers_own_version_choice_starts_a_new_budget_and_recovery_keeps_its_history() {
        assertEquals(setOf("b"), updatedVersionAttempts(tried = setOf("a"), selected = "b", automaticRecovery = false))
        assertEquals(
            setOf("a", "b"),
            updatedVersionAttempts(tried = setOf("a"), selected = "b", automaticRecovery = true),
        )
    }

    private fun version(
        id: String,
        width: Int?,
        bitrate: Int?,
    ) = PlayerMediaVersion(
        id = id,
        label = id,
        detail = "",
        url = "direct/$id",
        transcodeUrl = "hls/$id",
        fallbackTranscodeUrl = "mp4/$id",
        sourceWidth = width,
        sourceBitrateBps = bitrate,
    )
}
