package com.yfuse.core.network

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.ThemeText

actual fun localNetworkConnectionsRestricted(): Boolean = Build.VERSION.SDK_INT >= 37

@Composable
actual fun LocalNetworkAccessNotice(
    hasServers: Boolean,
    onGranted: () -> Unit,
) {
    if (!localNetworkConnectionsRestricted()) return
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("local_network_access", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(preferences.getBoolean("explained", false)) }

    fun dismiss() {
        dismissed = true
        preferences.edit().putBoolean("explained", true).apply()
    }
    val request =
        rememberLocalNetworkPermissionRequest(
            onGranted = {
                dismissed = true
                onGranted()
            },
            onDenied = { dismiss() },
        )
    if (!hasServers ||
        dismissed ||
        preferences.getBoolean("explained", false) ||
        localNetworkPermissionGranted()
    ) {
        return
    }
    GlassDialog(onDismiss = { dismiss() }) {
        OverlayHeader(title = "允许访问家庭服务器", onClose = { dismiss() })
        ThemeText(
            "连接家中的媒体服务器和投屏设备，需要允许“附近的设备”访问。暂不允许仍可使用互联网服务器，之后也能在权限检查中开启。",
            color = LocalPalette.current.body,
        )
        OverlayButton(label = "允许访问", tone = OverlayButtonTone.Primary, onClick = request)
        OverlayButton(label = "暂不允许", onClick = { dismiss() })
    }
}
