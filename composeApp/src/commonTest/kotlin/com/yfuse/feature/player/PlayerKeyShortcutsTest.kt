package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerKeyShortcutsTest {
    private val paused =
        PlayerKeyContext(
            watchGuest = false,
            playing = false,
            positionMs = 60_000L,
            durationMs = 1_200_000L,
            stepMs = 10_000L,
            previousFrameMs = 50_000L,
            nextFrameMs = 70_000L,
        )

    @Test
    fun j_and_l_follow_the_double_tap_step() {
        assertEquals(PlayerKeyAction.Seek(50_000L, "快退 10 秒"), resolvePlayerKey(PlayerKey.StepBack, paused))
        val thirty = paused.copy(stepMs = 30_000L)
        assertEquals(PlayerKeyAction.Seek(90_000L, "快进 30 秒"), resolvePlayerKey(PlayerKey.StepForward, thirty))
    }

    @Test
    fun arrows_nudge_five_seconds_inside_the_file() {
        assertEquals(PlayerKeyAction.Seek(55_000L, "快退 5 秒"), resolvePlayerKey(PlayerKey.NudgeBack, paused))
        val nearStart = paused.copy(positionMs = 2_000L)
        assertEquals(PlayerKeyAction.Seek(0L, "快退 5 秒"), resolvePlayerKey(PlayerKey.NudgeBack, nearStart))
        val nearEnd = paused.copy(positionMs = 1_198_000L)
        assertEquals(PlayerKeyAction.Seek(1_200_000L, "快进 5 秒"), resolvePlayerKey(PlayerKey.NudgeForward, nearEnd))
    }

    @Test
    fun nothing_to_seek_along_passes_the_key_on() {
        val opening = paused.copy(durationMs = 0L)
        assertEquals(PlayerKeyAction.Pass, resolvePlayerKey(PlayerKey.StepForward, opening))
        assertEquals(PlayerKeyAction.Pass, resolvePlayerKey(PlayerKey.FrameForward, opening))
        assertEquals(PlayerKeyAction.TogglePlay, resolvePlayerKey(PlayerKey.PlayPause, opening))
    }

    @Test
    fun a_guest_keeps_only_the_keys_that_are_their_own() {
        val guest = paused.copy(watchGuest = true)
        listOf(
            PlayerKey.PlayPause,
            PlayerKey.StepBack,
            PlayerKey.StepForward,
            PlayerKey.NudgeBack,
            PlayerKey.NudgeForward,
            PlayerKey.FrameBack,
            PlayerKey.FrameForward,
        ).forEach { key ->
            assertEquals(PlayerKeyAction.Say("房主控制播放"), resolvePlayerKey(key, guest), key.name)
        }
        assertEquals(PlayerKeyAction.ToggleFill, resolvePlayerKey(PlayerKey.Fill, guest))
        assertEquals(PlayerKeyAction.ToggleMute, resolvePlayerKey(PlayerKey.Mute, guest))
    }

    @Test
    fun frames_step_through_the_storyboard_only_while_paused() {
        assertEquals(PlayerKeyAction.Seek(50_000L, "上一格"), resolvePlayerKey(PlayerKey.FrameBack, paused))
        assertEquals(PlayerKeyAction.Seek(70_000L, "下一格"), resolvePlayerKey(PlayerKey.FrameForward, paused))
        val playing = paused.copy(playing = true)
        assertEquals(PlayerKeyAction.Say("暂停后可以逐格查看"), resolvePlayerKey(PlayerKey.FrameForward, playing))
        val noStoryboard = paused.copy(previousFrameMs = null, nextFrameMs = null)
        assertEquals(
            PlayerKeyAction.Say("这个视频没有缩略图，无法逐格"),
            resolvePlayerKey(PlayerKey.FrameBack, noStoryboard),
        )
    }

    @Test
    fun mute_takes_the_volume_and_gives_it_back() {
        val muted = muteToggle(volume = 0.6f, restoreTo = null)
        assertEquals(MuteToggle(volume = 0f, restoreTo = 0.6f, message = "静音"), muted)
        assertEquals(MuteToggle(volume = 0.6f, restoreTo = null, message = "音量 60%"), muteToggle(0f, muted.restoreTo))
        // Already silent with nothing to give back: half, not full.
        assertEquals(MuteToggle(volume = 0.5f, restoreTo = null, message = "音量 50%"), muteToggle(0f, null))
    }
}
