package com.yfuse.core.offline

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberDownloadNotificationAccess(): DownloadNotificationAccess {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }

    fun status(): String =
        when {
            !manager.areNotificationsEnabled() -> "通知未开启"
            manager.getNotificationChannel(OFFLINE_NOTIFICATION_CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE -> "下载通知已关闭"
            Build.VERSION.SDK_INT < 36 -> "使用普通下载通知"
            else -> {
                // This public API arrived after base API 36. Reflection keeps early Android 16
                // builds compatible without relying on a manufacturer's minor-version number.
                val allowed =
                    runCatching {
                        NotificationManager::class.java
                            .getMethod(
                                "canPostPromotedNotifications",
                            ).invoke(manager) as Boolean
                    }.getOrNull()
                when (allowed) {
                    true -> "实况通知已允许"
                    false -> "实况通知未允许"
                    null -> "由系统决定展示方式"
                }
            }
        }
    var label by remember(context) { mutableStateOf(status()) }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) label = status() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return DownloadNotificationAccess(label) {
        val appSettings =
            Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val channelBlocked =
            manager.getNotificationChannel(OFFLINE_NOTIFICATION_CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        val preferred =
            if (Build.VERSION.SDK_INT >= 36 && manager.areNotificationsEnabled() && !channelBlocked) {
                Intent(
                    Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS,
                ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                appSettings
            }
        val fallbacks =
            listOf(
                preferred,
                appSettings,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        for (intent in fallbacks) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
        }
    }
}
