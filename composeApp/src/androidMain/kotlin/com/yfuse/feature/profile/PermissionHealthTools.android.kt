package com.yfuse.feature.profile

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass

@Composable
actual fun PermissionHealthTools() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalPalette.current
    var snapshot by remember { mutableStateOf(context.permissionHealthSnapshot()) }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            snapshot = context.permissionHealthSnapshot()
        }

    fun openSettings(action: String) {
        val target = Intent(action, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(target) }
            .onFailure {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    snapshot = context.permissionHealthSnapshot()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
    ) {
        PermissionHealthRow(
            title = "通知",
            healthy = snapshot.notifications,
            healthyLabel = "已允许",
            unavailableLabel = "仅在开启提醒或后台播放时需要 ›",
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                }
            },
        )
        SettingsDivider()
        PermissionHealthRow(
            title = "精确追剧提醒",
            healthy = snapshot.exactAlarms,
            healthyLabel = "可精确提醒",
            unavailableLabel = "当前使用约时提醒 ›",
            onClick = { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
        )
        SettingsDivider()
        PermissionHealthRow(
            title = "局域网发现",
            healthy = snapshot.localNetwork,
            healthyLabel = if (Build.VERSION.SDK_INT < 36) "当前系统无需授权" else "已允许",
            unavailableLabel = "仅自动发现服务器时需要 ›",
            onClick = {
                if (Build.VERSION.SDK_INT >= 36) {
                    requestPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            },
        )
        SettingsDivider()
        PermissionHealthRow(
            title = "扫码导入",
            healthy = snapshot.camera,
            healthyLabel = "已允许",
            unavailableLabel = "也可从相册导入 ›",
            onClick = { requestPermission.launch(Manifest.permission.CAMERA) },
        )
        SettingsDivider()
        PermissionHealthRow(
            title = "应用内更新安装",
            healthy = snapshot.packageInstall,
            healthyLabel = "已允许",
            unavailableLabel = "安装更新时再授权 ›",
            onClick = { openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) },
        )
    }
}

@Composable
private fun PermissionHealthRow(
    title: String,
    healthy: Boolean,
    healthyLabel: String,
    unavailableLabel: String,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        value = if (healthy) healthyLabel else unavailableLabel,
        embedded = true,
        onClick = if (healthy) null else onClick,
    )
}

private data class PermissionHealthSnapshot(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val localNetwork: Boolean,
    val camera: Boolean,
    val packageInstall: Boolean,
)

private fun Context.permissionHealthSnapshot(): PermissionHealthSnapshot {
    val alarmManager = getSystemService(AlarmManager::class.java)
    return PermissionHealthSnapshot(
        notifications =
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        exactAlarms = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms(),
        localNetwork =
            Build.VERSION.SDK_INT < 36 ||
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED,
        camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        packageInstall = packageManager.canRequestPackageInstalls(),
    )
}
