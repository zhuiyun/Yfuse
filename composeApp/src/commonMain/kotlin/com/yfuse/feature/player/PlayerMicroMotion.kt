package com.yfuse.feature.player

/**
 * A short buffering pulse is normally a seek, surface resize or decoder hand-off rather than
 * something the viewer needs to be told about. Waiting before showing the spinner prevents the
 * transport key flashing on every one of those normal transitions.
 */
internal const val BUFFERING_INDICATOR_DELAY_MS = 250L

/**
 * A glyph swapping on a key (播放 ↔ 暂停) grows in from this and shrinks away to [ICON_SWAP_SCALE_OUT]:
 * far enough to read as the key changing its answer, near enough not to read as a new key.
 */
internal const val ICON_SWAP_SCALE_IN = 0.82f
internal const val ICON_SWAP_SCALE_OUT = 0.88f

/** The gesture HUD is a whole pill of text, so it travels less than a glyph does. */
internal const val HUD_SCALE_IN = 0.88f
internal const val HUD_SCALE_OUT = 0.92f

/**
 * Whether the transport key offers 暂停 rather than 播放.
 *
 * A stall reports `playing = false` while the viewer's request to play still stands, and the
 * key is not taken away for it any more — a spinner used to replace it, so nobody could pause a
 * film that would not start. Through buffering it keeps its [settledPlaying] answer, which the
 * key itself flips when pressed; before there is one it offers 暂停, since a player that is
 * buffering has almost always just been asked to play.
 */
internal fun transportShowsPause(
    playing: Boolean,
    buffering: Boolean,
    settledPlaying: Boolean?,
): Boolean = if (buffering) settledPlaying ?: true else playing

/**
 * Keeps rapidly changing numeric HUD text in one animated surface. Only a change of gesture kind
 * (seek, volume, brightness, transport) should replay the entrance animation.
 */
internal fun gestureHudMotionKey(value: String?): String =
    when {
        value == null -> "hidden"
        value.startsWith("音量") -> "volume"
        value.startsWith("亮度") -> "brightness"
        value.contains(" / ") || value.startsWith("跳转") -> "seek"
        else -> value
    }
