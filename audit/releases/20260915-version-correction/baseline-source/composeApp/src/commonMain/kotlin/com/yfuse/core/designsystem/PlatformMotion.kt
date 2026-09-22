package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Whether the platform itself has been told to remove animations.
 *
 * 减弱动态效果 is our own preference; this is the same request made one level down — Android's
 * 开发者选项 → 动画时长缩放 = 关闭, which the accessibility shortcut 「移除动画」 writes too. A user
 * who has turned the whole system off should not have to find our switch as well, so callers OR
 * the two together when they build [AccessibilityOptions].
 */
@Composable
expect fun platformAnimationsDisabled(): Boolean
