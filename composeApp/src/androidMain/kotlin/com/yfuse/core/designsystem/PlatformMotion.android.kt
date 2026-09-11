package com.yfuse.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun platformAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    // Read once and kept: the switch lives in 开发者选项 / 无障碍, so changing it mid-session means
    // leaving the app, and a ContentObserver would cost every screen a listener to catch a value
    // that is already settled by the time the shell composes.
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
