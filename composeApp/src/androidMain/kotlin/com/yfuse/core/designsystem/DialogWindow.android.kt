package com.yfuse.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal actual fun DialogTouchPassThrough(enabled: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window, enabled) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
        onDispose { }
    }
}

@Composable
internal actual fun rememberDialogSystemBarStyle(fallbackDarkIcons: Boolean): DialogSystemBarStyle {
    val view = LocalView.current
    return remember(view, fallbackDarkIcons) {
        val window = view.findDialogWindow() ?: view.context.findDialogActivity()?.window
        if (window == null) {
            DialogSystemBarStyle(fallbackDarkIcons, fallbackDarkIcons)
        } else {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            DialogSystemBarStyle(
                statusBarDarkIcons = controller.isAppearanceLightStatusBars,
                navigationBarDarkIcons = controller.isAppearanceLightNavigationBars,
            )
        }
    }
}

@Composable
internal actual fun DialogSystemBars(style: DialogSystemBarStyle) {
    val view = LocalView.current
    DisposableEffect(view, style) {
        val apply = Runnable { view.findDialogWindow()?.applyModalSystemBars(style) }
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) {
                    apply.run()
                    attached.post(apply)
                }

                override fun onViewDetachedFromWindow(detached: View) = Unit
            }
        view.addOnAttachStateChangeListener(listener)
        apply.run()
        // Compose DialogLayout initially requests WRAP_CONTENT in updateProperties; reapply after show.
        view.post(apply)
        onDispose {
            view.removeCallbacks(apply)
            view.removeOnAttachStateChangeListener(listener)
        }
    }
}

private fun View.findDialogWindow(): Window? {
    var ancestor: Any? = this
    while (ancestor != null) {
        if (ancestor is DialogWindowProvider) return ancestor.window
        ancestor = (ancestor as? View)?.parent
    }
    return null
}

private fun Context.findDialogActivity(): Activity? {
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

@Suppress("DEPRECATION")
private fun Window.applyModalSystemBars(style: DialogSystemBarStyle) {
    // FloatingDialogWindowTheme enables native dimming. GlassDialog already draws its own animated scrim.
    clearFlags(
        WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
    )
    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    applyDialogDisplayCutoutMode()
    WindowCompat.setDecorFitsSystemWindows(this, false)
    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        navigationBarDividerColor = Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = style.statusBarDarkIcons
        isAppearanceLightNavigationBars = style.navigationBarDarkIcons
    }
}

internal fun Window.applyDialogDisplayCutoutMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    // Compose 1.12 sets ALWAYS from API 28, but 28/29 only support SHORT_EDGES for edge-to-edge.
    val mode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    val params = attributes
    if (params.layoutInDisplayCutoutMode != mode) {
        params.layoutInDisplayCutoutMode = mode
        attributes = params
    }
}
