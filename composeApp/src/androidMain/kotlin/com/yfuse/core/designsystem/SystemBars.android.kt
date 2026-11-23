package com.yfuse.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
@Suppress("DEPRECATION")
actual fun StatusBarIconStyle(darkIcons: Boolean) {
    val view = LocalView.current
    val routeVisible = LocalRouteVisible.current
    val configuration = LocalConfiguration.current
    val palette = LocalPalette.current
    val navigationDarkIcons = !palette.isDark
    val navigationBackground =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) palette.background.toArgb() else Color.TRANSPARENT

    // Edge-to-edge reapplies the system theme on configuration changes, even when this
    // activity and its visible route are retained. Reapply the route's foreground as well.
    DisposableEffect(view, darkIcons, navigationDarkIcons, navigationBackground, routeVisible, configuration) {
        val window = view.context.findActivity()?.window
        if (!routeVisible || window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = darkIcons
            // The page can use white status icons over artwork in a light app.
            // Navigation follows its palette, retaining contrast protection where
            // bright artwork or cards can scroll behind the system buttons.
            controller.isAppearanceLightNavigationBars = navigationDarkIcons
            window.navigationBarColor = navigationBackground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = true
            }
            // Shared-element routes keep the outgoing screen composed briefly.
            // Restoring its old value on disposal races the already-visible detail
            // screen and can leave dark icons over bright artwork.
            onDispose {}
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
