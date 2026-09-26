package com.yfuse.feature.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** 键盘快捷键 — the keys the player answers on a phone, tablet or Chromebook with a keyboard. */
internal enum class PlayerKey {
    /** Space and K. */
    PlayPause,

    /** J and L: the double-tap step. */
    StepBack,
    StepForward,

    /** ← and →: five seconds. */
    NudgeBack,
    NudgeForward,

    /** F: 适应 ↔ 裁剪填满. */
    Fill,

    /** M. */
    Mute,

    /** , and . while paused. */
    FrameBack,
    FrameForward,
}

/** ← / → move this far, as the web players they are borrowed from do. */
internal const val KEYBOARD_NUDGE_MS = 5_000L

/** What the player knows at the moment of the key press. */
internal data class PlayerKeyContext(
    /** A connected guest: the timeline is the host's, so only per-viewer keys answer. */
    val watchGuest: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 双击步长, which J and L share. */
    val stepMs: Long,
    /** Where , and . go from here, or null when there is no storyboard to step through. */
    val previousFrameMs: Long?,
    val nextFrameMs: Long?,
)

/** What a key press does. */
internal sealed interface PlayerKeyAction {
    data object TogglePlay : PlayerKeyAction

    data class Seek(
        val targetMs: Long,
        val message: String,
    ) : PlayerKeyAction

    data object ToggleFill : PlayerKeyAction

    data object ToggleMute : PlayerKeyAction

    /** Answered with words only: refused, or not possible here. The key is still spent. */
    data class Say(
        val message: String,
    ) : PlayerKeyAction

    /** Not the player's to answer: the key goes on to whatever else wants it. */
    data object Pass : PlayerKeyAction
}

/**
 * 键盘快捷键, decided. Timeline keys are the host's in a room, as the gestures are; 画面 and 静音 stay
 * each viewer's own. , and . walk the trickplay storyboard a frame at a time — no engine here
 * steps single video frames — and only while paused, where a frame can be looked at.
 */
internal fun resolvePlayerKey(
    key: PlayerKey,
    context: PlayerKeyContext,
): PlayerKeyAction {
    val timeline = key != PlayerKey.Fill && key != PlayerKey.Mute
    if (timeline && context.watchGuest) return PlayerKeyAction.Say("房主控制播放")
    return when (key) {
        PlayerKey.PlayPause -> PlayerKeyAction.TogglePlay
        PlayerKey.StepBack -> context.seekBy(-context.stepMs)
        PlayerKey.StepForward -> context.seekBy(context.stepMs)
        PlayerKey.NudgeBack -> context.seekBy(-KEYBOARD_NUDGE_MS)
        PlayerKey.NudgeForward -> context.seekBy(KEYBOARD_NUDGE_MS)
        PlayerKey.Fill -> PlayerKeyAction.ToggleFill
        PlayerKey.Mute -> PlayerKeyAction.ToggleMute
        PlayerKey.FrameBack -> context.frameStep(context.previousFrameMs, "上一格")
        PlayerKey.FrameForward -> context.frameStep(context.nextFrameMs, "下一格")
    }
}

private fun PlayerKeyContext.seekBy(deltaMs: Long): PlayerKeyAction {
    // Nothing to move along yet — a live stream, or a file still opening.
    if (durationMs <= 0L) return PlayerKeyAction.Pass
    val target = (positionMs + deltaMs).coerceIn(0L, durationMs)
    val verb = if (deltaMs < 0L) "快退" else "快进"
    return PlayerKeyAction.Seek(target, "$verb ${abs(deltaMs) / 1_000L} 秒")
}

private fun PlayerKeyContext.frameStep(
    targetMs: Long?,
    label: String,
): PlayerKeyAction =
    when {
        durationMs <= 0L -> PlayerKeyAction.Pass
        playing -> PlayerKeyAction.Say("暂停后可以逐格查看")
        targetMs == null -> PlayerKeyAction.Say("这个视频没有缩略图，无法逐格")
        else -> PlayerKeyAction.Seek(targetMs.coerceIn(0L, durationMs), label)
    }

/** M: the volume to set, the level to come back to on the next press, and what the HUD says. */
internal data class MuteToggle(
    val volume: Float,
    val restoreTo: Float?,
    val message: String,
)

/** Where M goes back to when nothing was muted by it — a sound that is there, not a blast. */
private const val UNMUTE_FALLBACK_VOLUME = 0.5f

/** Anything this quiet is already silent for M's purposes, so the press un-mutes it. */
private const val SILENT_VOLUME = 0.005f

/**
 * M — mutes by taking the media volume the player already drives to nothing, and gives back the
 * level it took on the next press. A volume that was already at zero comes back to half.
 */
internal fun muteToggle(
    volume: Float,
    restoreTo: Float?,
): MuteToggle {
    if (volume > SILENT_VOLUME) return MuteToggle(volume = 0f, restoreTo = volume, message = "静音")
    val back = (restoreTo ?: UNMUTE_FALLBACK_VOLUME).coerceIn(0.05f, 1f)
    return MuteToggle(volume = back, restoreTo = null, message = "音量 ${(back * 100).roundToInt()}%")
}
