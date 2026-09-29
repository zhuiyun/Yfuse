package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSurfaceStatusTest {
    private val slowLink = PlaybackDiagnostics(networkBitsPerSecond = 2_080_000L, bitrateBitsPerSecond = 13_347_310L)
    private val fastLink = PlaybackDiagnostics(networkBitsPerSecond = 18_000_000L, bitrateBitsPerSecond = 6_224_456L)

    @Test
    fun a_recovering_network_is_announced_before_anything_else() {
        val line =
            playbackContinuityLine(
                live = PlaybackState(currentIndex = 1, positionMs = 0L, diagnostics = slowLink),
                networkRecoveryPending = true,
                startIndex = 0,
            )
        assertEquals(PlaybackStatusLine("网络已恢复，正在续播"), line)
    }

    @Test
    fun the_first_seconds_of_another_item_say_the_next_episode_is_being_joined() {
        assertEquals(
            PlaybackStatusLine("正在衔接下一集"),
            playbackContinuityLine(PlaybackState(currentIndex = 1, positionMs = 2_999L), false, startIndex = 0),
        )
        // The item it opened on, or later in the next one, is plain preparation.
        assertEquals(
            PlaybackStatusLine("正在准备画面"),
            playbackContinuityLine(PlaybackState(currentIndex = 0, positionMs = 0L), false, startIndex = 0),
        )
        assertEquals(
            PlaybackStatusLine("正在准备画面"),
            playbackContinuityLine(PlaybackState(currentIndex = 1, positionMs = 3_000L), false, startIndex = 0),
        )
    }

    @Test
    fun a_link_slower_than_the_source_is_named_while_the_picture_is_prepared() {
        assertEquals(
            PlaybackStatusLine("网速低于片源码率", "网速约 2.1 Mbps，低于片源 13.3 Mbps"),
            playbackContinuityLine(PlaybackState(diagnostics = slowLink), false, startIndex = 0),
        )
        assertEquals(
            PlaybackStatusLine("正在准备画面"),
            playbackContinuityLine(PlaybackState(diagnostics = fastLink), false, startIndex = 0),
        )
    }

    @Test
    fun the_chip_counts_the_longer_of_the_two_buffers_in_whole_seconds() {
        val buffering = fastLink.copy(bufferedDurationMs = 4_900L, sourceBufferedMs = 7_300L)
        assertEquals(
            PlaybackStatusLine("正在重新缓冲", "正在重新缓冲 · 已缓冲 7 秒"),
            playbackStatusChipLine(buffering, networkRecoveryPending = false),
        )
        assertEquals(
            PlaybackStatusLine("网络速度不足", "网络速度不足 · 已缓冲 2 秒"),
            playbackStatusChipLine(slowLink.copy(bufferedDurationMs = 2_500L), networkRecoveryPending = false),
        )
        assertEquals(
            PlaybackStatusLine("网络已恢复，正在续播"),
            playbackStatusChipLine(slowLink, networkRecoveryPending = true),
        )
    }

    @Test
    fun a_transition_lands_in_the_fitted_picture_only_when_its_size_is_known() {
        val sized = PlaybackState(videoHeight = 1_080, diagnostics = PlaybackDiagnostics(videoWidth = 1_920))
        assertEquals(1_920f / 1_080f, transitionAspectRatio(VideoScaleMode.Fit, sized))
        assertNull(transitionAspectRatio(VideoScaleMode.Fill, sized))
        assertNull(transitionAspectRatio(VideoScaleMode.Fit, PlaybackState()))
    }
}
