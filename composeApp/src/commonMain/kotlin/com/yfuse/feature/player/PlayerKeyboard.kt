package com.yfuse.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The shortcut a key event is, or null for every other key and for any chord: Ctrl, Alt, Meta and
 * Shift combinations belong to the system and the text fields.
 *
 * ← and → are the timeline's only while [anchorFocused] — nothing else in the player has focus.
 * With a control focused they move between controls, and the seek bar and the volume slider take
 * their own steps, as they always have.
 */
internal fun KeyEvent.playerKey(anchorFocused: Boolean): PlayerKey? {
    if (isCtrlPressed || isAltPressed || isMetaPressed || isShiftPressed) return null
    return when (key) {
        Key.Spacebar, Key.K -> PlayerKey.PlayPause
        Key.J -> PlayerKey.StepBack
        Key.L -> PlayerKey.StepForward
        Key.DirectionLeft -> PlayerKey.NudgeBack.takeIf { anchorFocused }
        Key.DirectionRight -> PlayerKey.NudgeForward.takeIf { anchorFocused }
        Key.F -> PlayerKey.Fill
        Key.M -> PlayerKey.Mute
        Key.Comma -> PlayerKey.FrameBack
        Key.Period -> PlayerKey.FrameForward
        else -> null
    }
}

/** Keys that flip something: held down, auto-repeat would flip it ten times a second. */
private val PlayerKey.toggles: Boolean
    get() = this == PlayerKey.PlayPause || this == PlayerKey.Fill || this == PlayerKey.Mute

/**
 * 键盘快捷键 across key events: the flip keys that are down, so auto-repeat answers once, and the
 * level M took away, so the next M gives it back.
 */
internal class PlayerKeyboardShortcuts {
    private val held = mutableSetOf<PlayerKey>()

    /** What M muted from; null when M has nothing to give back. */
    var mutedFrom: Float? = null

    /** True when [event] was a shortcut and has been answered through [perform]. */
    fun handle(
        event: KeyEvent,
        anchorFocused: Boolean,
        context: () -> PlayerKeyContext,
        perform: (PlayerKeyAction) -> Unit,
    ): Boolean {
        val key = event.playerKey(anchorFocused) ?: return false
        if (event.type == KeyEventType.KeyUp) return held.remove(key)
        if (event.type != KeyEventType.KeyDown) return false
        if (key.toggles && !held.add(key)) return true
        val action = resolvePlayerKey(key, context())
        if (action == PlayerKeyAction.Pass) {
            held.remove(key)
            return false
        }
        perform(action)
        return true
    }
}

/**
 * Where key presses land while nothing in the player has focus.
 *
 * Compose hands a key event to the focused node and its ancestors, and to nothing at all while no
 * node has focus — which is the player's normal state, and every shortcut would fall through to the
 * window. This takes focus only when nothing else in the player holds it, draws nothing, and has no
 * semantics, so a screen reader never lands on it. Only composed on a phone, tablet or Chromebook
 * with a keyboard: TV keeps its remote controller and its own focus.
 */
@Composable
internal fun PlayerKeyboardAnchor(
    requester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .focusRequester(requester)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .focusTarget(),
    )
}
