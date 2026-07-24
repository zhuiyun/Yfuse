package com.yfuse.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun StatusBarIconStyle(darkIcons: Boolean) {
    val view = LocalView.current

    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previous = controller.isAppearanceLightStatusBars
            controller.isAppearanceLightStatusBars = darkIcons
            onDispose {
                controller.isAppearanceLightStatusBars = previous
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    repeat(12) {
        when (val context = current) {
            is Activity -> return context
            is ContextWrapper -> {
                val base = context.baseContext
                if (base === context) return null
                current = base
            }
            else -> return null
        }
    }
    return null
}
