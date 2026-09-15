package com.yfuse.feature.detail

import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSelectionPresentationTest {
    @Test
    fun recommendation_never_pretends_the_current_manual_selection_changed() {
        val selected = source("manual", 1080)
        val better = source("suggestion", 2160)
        val health =
            mapOf(
                "manual" to ServerHealth(ServerHealthStatus.Healthy, 30),
                "suggestion" to ServerHealth(ServerHealthStatus.Healthy, 30),
            )
        val sorted = rankServerSources(listOf(selected, better), health).map { it.source }
        val state =
            sourceSelectionPresentation(
                sorted,
                "manual",
                "manual-item",
                "My cut",
                health,
                PlaybackNetworkClass.Unknown,
                true,
            )
        assertTrue(state.selectedLabel.contains("manual · My cut"))
        assertEquals("suggestion", state.recommendedServerId)
        assertTrue(state.reason.contains("30ms"))
        assertFalse(state.reason.contains("直连"))
    }

    @Test
    fun all_offline_sources_have_no_recommendation_even_when_metadata_is_high_quality() {
        val source = source("offline", 2160)
        val state =
            sourceSelectionPresentation(
                listOf(source),
                "offline",
                "offline-item",
                null,
                mapOf(
                    "offline" to ServerHealth(ServerHealthStatus.Offline),
                ),
                PlaybackNetworkClass.Offline,
                true,
            )
        assertEquals(null, state.recommendedServerId)
        assertEquals("", state.reason)
    }

    @Test
    fun unmeasured_network_does_not_gain_fictional_speed_or_capability_claims() {
        val source = source("unknown", 1080)
        val state =
            sourceSelectionPresentation(
                listOf(source),
                "unknown",
                "unknown-item",
                null,
                emptyMap(),
                PlaybackNetworkClass.Unknown,
                true,
            )
        assertTrue(state.reason.contains("未测定"))
        assertFalse(state.reason.contains("ms"))
        assertFalse(state.reason.contains("硬解"))
    }

    private fun source(
        id: String,
        height: Int,
    ) = ServerSource(id, id, false, SourceInfo("${height}p", null, null, videoHeight = height), true, "$id-item")
}
