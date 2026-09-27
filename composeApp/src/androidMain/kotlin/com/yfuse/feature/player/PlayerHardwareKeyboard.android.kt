package com.yfuse.feature.player

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Whether 键盘快捷键 have a keyboard to come from: one attached and exposed — a Chromebook's, a
 * tablet cover's, a Bluetooth one. The on-screen keyboard is not one, and a folded-away keyboard
 * reports itself hidden. Read from the configuration, so plugging one in recomposes.
 */
@Composable
internal fun hardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}
