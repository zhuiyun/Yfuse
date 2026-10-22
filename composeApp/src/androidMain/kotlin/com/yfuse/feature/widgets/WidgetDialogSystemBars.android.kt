package com.yfuse.feature.widgets

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yfuse.core.designsystem.applyDialogDisplayCutoutMode

@Composable
internal actual fun WidgetDialogSystemBars(darkIcons: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkIcons) {
        val apply =
            Runnable {
                // Resolve after attachment too: the Compose owner need not be the provider's direct child.
                view.widgetDialogWindow()?.applyWidgetSystemBars(darkIcons)
            }
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) {
                    attached.post(apply)
                }

                override fun onViewDetachedFromWindow(detached: View) = Unit
            }
        view.addOnAttachStateChangeListener(listener)
        apply.run()
        // Dialog.show/updateProperties can follow initial composition and restore WRAP_CONTENT.
        view.post(apply)
        onDispose {
            view.removeCallbacks(apply)
            view.removeOnAttachStateChangeListener(listener)
        }
    }
}

private fun View.widgetDialogWindow(): Window? {
    var ancestor: Any? = this
    while (ancestor != null) {
        if (ancestor is DialogWindowProvider) return ancestor.window
        ancestor = (ancestor as? View)?.parent
    }
    return null
}

@Suppress("DEPRECATION")
private fun Window.applyWidgetSystemBars(darkIcons: Boolean) {
    // This dialog is a full page. A floating window's dim layer must not show above its content.
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
        isAppearanceLightStatusBars = darkIcons
        isAppearanceLightNavigationBars = darkIcons
    }
}
