package com.yfuse.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.AppIcons

/**
 * The keys over the middle of the picture: 继续播放 while paused, and the ending's keys once an item
 * has stopped at its end. They share the one spot, which is what lets the paused key hand its disc
 * straight to the ending instead of one fading out while the other fades in — see [EndedKeys].
 */
@Composable
internal fun BoxScope.PlayerCenterKeys(
    showPausedKey: Boolean,
    /** The ending is up, or 重播 is still flowing its keys back together ([onHold]). */
    showEndedKeys: Boolean,
    watchLocked: Boolean,
    hasNext: Boolean,
    onResume: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    onHold: (Boolean) -> Unit,
) {
    // The ending forms out of the paused key's disc when that is what is on screen: the
    // key hands over without fading out, and the ending splits from it without fading in.
    // Decided as the ending appears and kept while it stays, so its entrance never changes midway.
    val pausedKeyWasUp = remember { booleanArrayOf(false) }
    val endingFromPausedKey = remember(showEndedKeys) { showEndedKeys && pausedKeyWasUp[0] }
    SideEffect { pausedKeyWasUp[0] = showPausedKey }
    ChromeVisibility(
        visible = showPausedKey,
        modifier = Modifier.align(Alignment.Center),
        instantExit = showEndedKeys,
    ) {
        CircleControl(
            // 播放, never 暂停. This is an affordance, not a readout — it says what the
            // tap does, the way every transport key in the app does.
            icon = AppIcons.Play,
            description = if (watchLocked) "已暂停，等待房主继续" else "继续播放",
            size = CenterKeySize,
            iconSize = CenterKeyIconSize,
            enabled = !watchLocked,
            // The one control that has to be found at a glance in a dark room, so it
            // takes the filled treatment the transport keys leave to it.
            filled = true,
            onClick = onResume,
        )
    }

    // The ending's keys, where the paused key was. They form out of one white drop: see [EndedKeys].
    ChromeVisibility(
        visible = showEndedKeys,
        modifier = Modifier.align(Alignment.Center),
        instantEnter = endingFromPausedKey,
    ) {
        EndedKeys(
            hasNext = hasNext,
            watchLocked = watchLocked,
            fromPausedKey = endingFromPausedKey,
            onNext = onNext,
            onReplay = onReplay,
            onBack = onBack,
            onHold = onHold,
        )
    }
}
