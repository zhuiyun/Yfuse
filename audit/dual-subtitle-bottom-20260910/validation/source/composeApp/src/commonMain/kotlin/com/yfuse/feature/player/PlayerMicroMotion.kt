package com.yfuse.feature.player

/**
 * A short buffering pulse is normally a seek, surface resize or decoder hand-off rather than
 * something the viewer needs to be told about. Waiting before showing the spinner prevents the
 * transport key flashing on every one of those normal transitions.
 */
internal const val BUFFERING_INDICATOR_DELAY_MS = 250L

internal enum class TransportVisualState {
    Play,
    Pause,
    Buffering,
}

internal fun transportVisualState(
    playing: Boolean,
    buffering: Boolean,
    bufferingIndicatorVisible: Boolean,
): TransportVisualState =
    when {
        buffering && bufferingIndicatorVisible -> TransportVisualState.Buffering
        playing -> TransportVisualState.Pause
        else -> TransportVisualState.Play
    }

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
