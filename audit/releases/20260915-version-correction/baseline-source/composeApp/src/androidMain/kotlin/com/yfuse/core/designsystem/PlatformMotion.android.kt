package com.yfuse.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
actual fun platformAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // The same Activity can survive a visit to accessibility/developer settings. Refresh when
    // focus changes so returning to it cannot replay particles under an obsolete motion policy.
    // This adds no observer or idle polling to the window.
    return remember(context, windowFocused) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
