package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics

/** 旋转锁: the orientation the Activity holds, and the key that pins or frees it. */
@Immutable
internal class PlayerRotationLock(
    val locked: Boolean,
    val onToggle: () -> Unit,
)

/** 旋转锁's key: pins the way the screen faces now, or lets it follow the phone again. */
@Composable
internal fun RotationLockKey(
    lock: PlayerRotationLock,
    locked: Boolean,
    onActivity: () -> Unit,
) {
    val haptics = LocalHaptics.current
    CircleControl(
        icon = if (locked) AppIcons.RotationLocked else AppIcons.RotationUnlocked,
        description = if (locked) "屏幕方向已锁定，轻点解锁" else "锁定屏幕方向",
        size = 28.dp,
        iconSize = 12.dp,
        active = locked,
        crossfadeIcon = true,
        onClick = {
            haptics.play(if (lock.locked) HapticSignal.ToggleOff else HapticSignal.ToggleOn)
            lock.onToggle()
            onActivity()
        },
    )
}
