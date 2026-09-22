package com.yfuse.tv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.AppIcons

/**
 * Permission health for a television.
 *
 * The phone checks five permissions. Camera, exact alarms and package installs are all absent by
 * design on this build: there is no QR scanner, no calendar reminder and no in-app updater in the
 * television package, so listing them would report failures for features that do not exist.
 */
@Composable
internal fun TvPermissionHealthPage(
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:permissions"
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    // Permissions are granted in a system screen, so the only reliable moment to re-read them is
    // when this screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    revision++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationsGranted =
        remember(revision) {
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    val localNetworkGranted =
        remember(revision) {
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        }

    val requestNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val requestLocalNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { status = "这台设备没有可打开的应用设置页面。" }
    }

    TvSettingsPageScaffold(page = TvSettingsPage.PermissionHealth, status = status) {
        item(key = "permissions-notifications") {
            TvSettingRow(
                title = "通知",
                value = if (notificationsGranted) "已授权" else "未授权",
                stableId = "permissions:notifications",
                focusMemory = focusMemory,
                onClick = {
                    if (notificationsGranted) {
                        openAppSettings()
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                icon = AppIcons.Bell,
                focusScope = focusScope,
                subtitle = "缺少通知权限时，后台播放的控制条不会出现",
                selected = notificationsGranted,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "permissions-local-network") {
            TvSettingRow(
                title = "局域网设备",
                value = if (localNetworkGranted) "已授权" else "未授权",
                stableId = "permissions:local-network",
                focusMemory = focusMemory,
                onClick = {
                    if (localNetworkGranted) {
                        openAppSettings()
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        requestLocalNetwork.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                },
                icon = AppIcons.Search,
                focusScope = focusScope,
                subtitle = "缺少此权限时，添加服务器里的「搜索局域网」找不到任何设备",
                selected = localNetworkGranted,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "permissions-open-settings") {
            TvSettingRow(
                title = "打开系统应用设置",
                value = "",
                stableId = "permissions:open-settings",
                focusMemory = focusMemory,
                onClick = ::openAppSettings,
                icon = AppIcons.More,
                focusScope = focusScope,
                subtitle = "在系统页面里逐项查看和撤销权限",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "permissions-note") {
            TvSettingsNote(
                "电视版没有二维码扫描、追剧提醒和应用内更新，因此不申请摄像头、精确闹钟和安装应用权限。",
            )
        }
    }
}
