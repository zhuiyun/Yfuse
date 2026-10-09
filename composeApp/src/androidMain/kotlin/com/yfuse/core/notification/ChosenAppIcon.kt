package com.yfuse.core.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yfuse.feature.profile.AppIconVariant
import com.yfuse.feature.profile.currentAppIconVariant
import com.yfuse.feature.profile.launcherIcon
import com.yfuse.feature.profile.notificationIcon

/**
 * Gives a notification that speaks for the app itself, 追剧更新 among them, the icon chosen in
 * Logo 与开屏动画 → APP 图标: the chosen mark as its small icon, and the chosen icon beside its text.
 *
 * Not the app icon ColorOS and Android 16's restyled shade draw at the top left: the system reads
 * that from the installed package's application icon, which switching the launcher alias does not
 * change. The small icon still leads the status bar, and the header wherever the system draws the
 * small icon there; the large icon is what is left for showing the choice in the notification
 * itself, so it is left out for the default icon, which the system already shows.
 */
internal fun NotificationCompat.Builder.setChosenAppIcon(context: Context): NotificationCompat.Builder {
    val variant = currentAppIconVariant()
    setSmallIcon(variant.notificationIcon())
    if (variant != AppIconVariant.Default) {
        launcherIconBitmap(context, variant.launcherIcon())?.let { setLargeIcon(it) }
    }
    return this
}

/** The launcher icon at the size a notification shows it, in the device's own icon shape. */
private fun launcherIconBitmap(
    context: Context,
    icon: Int,
): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, icon) ?: return null
    val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
    val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
    }
}
