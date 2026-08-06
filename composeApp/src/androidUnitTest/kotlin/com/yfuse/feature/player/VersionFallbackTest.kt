package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionFallbackTest {

    @Test
    fun failed_version_moves_to_best_untried_file() {
        val item = PlayerMediaItem(
            id = "movie",
            url = "direct",
            transcodeUrl = "hls",
            title = "电影",
            versions = listOf(
                version("1080", 1920, 8_000_000),
                version("4k-small", 3840, 12_000_000),
                version("4k-remux", 3840, 70_000_000),
            ),
            versionId = "4k-remux",
        )

        assertEquals(
            "4k-small",
            item.nextFallbackVersionId(setOf("4k-remux")),
        )
        assertEquals(
            "1080",
            item.nextFallbackVersionId(setOf("4k-remux", "4k-small")),
        )
        assertNull(item.nextFallbackVersionId(setOf("4k-remux", "4k-small", "1080")))
    }

    @Test
    fun manual_version_choice_starts_a_new_bounded_recovery_chain() {
        val attempts = updatedVersionAttempts(
            tried = setOf("source-a"),
            selected = "source-b",
            automaticRecovery = false,
        )

        assertEquals(setOf("source-b"), attempts)
    }

    @Test
    fun automatic_version_choice_keeps_prior_attempts_to_prevent_cycles() {
        val attempts = updatedVersionAttempts(
            tried = setOf("source-a"),
            selected = "source-b",
            automaticRecovery = true,
        )

        assertEquals(setOf("source-a", "source-b"), attempts)
    }

    private fun version(id: String, width: Int, bitrate: Int) = PlayerMediaVersion(
        id = id,
        label = id,
        detail = "",
        url = "direct/$id",
        transcodeUrl = "hls/$id",
        fallbackTranscodeUrl = "progressive/$id",
        sourceWidth = width,
        sourceBitrateBps = bitrate,
    )
}
