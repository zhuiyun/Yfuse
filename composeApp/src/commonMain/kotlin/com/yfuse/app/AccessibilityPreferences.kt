package com.yfuse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.platformAnimationsDisabled

/**
 * The person's display preferences plus the device's 「移除动画」, as the one
 * [AccessibilityOptions] every window runs under.
 *
 * The phone shell, both player windows and the television used to build this each on their
 * own, and they drifted: the player passed only the motion flag, so 减少透明效果 and 大号文字
 * stopped at its door and the system switch took its drag-to-dismiss away; the television never
 * heard the system switch at all. They all come here now.
 */
@Composable
fun rememberAppAccessibilityOptions(preferences: ThemePreferences?): AccessibilityOptions {
    val reduceTransparency = preferences?.reduceTransparency?.collectAsState()?.value ?: false
    val largeText = preferences?.largeText?.collectAsState()?.value ?: false
    val reduceMotion = preferences?.reduceMotion?.collectAsState()?.value ?: false
    val systemMotionOff = platformAnimationsDisabled()
    return remember(reduceTransparency, largeText, reduceMotion, systemMotionOff) {
        AccessibilityOptions(
            reduceTransparency = reduceTransparency,
            largeText = largeText,
            reduceMotion = reduceMotion || systemMotionOff,
            reduceMotionByUser = reduceMotion,
        )
    }
}

/**
 * 减弱透明度 is an accessibility contract: it exists to make every surface opaque and legible,
 * so a decorative material choice must not be able to reinstate the effect it turns off.
 */
fun effectiveGlassStyle(
    style: GlassStyle,
    reduceTransparency: Boolean,
): GlassStyle = if (reduceTransparency) GlassStyle.Frosted else style
