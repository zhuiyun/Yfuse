package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.yfuse.BuildConfig
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.update.LocalAppUpdateManager
import com.yfuse.update.UpdateState

@Composable
actual fun AppUpdateTools() {
    val manager = LocalAppUpdateManager.current
    val palette = LocalPalette.current

    Section(title = "应用更新") {
        if (manager == null) {
            SettingRow(
                title = "检测升级",
                value = "暂不可用",
            )
            return@Section
        }

        val state by manager.state.collectAsState()
        val value = when (val current = state) {
            UpdateState.Checking -> "正在检查…"
            UpdateState.Current -> "已是最新版本 ›"
            is UpdateState.Available -> "发现 ${current.manifest.versionName} ›"
            is UpdateState.Downloading ->
                "正在下载 ${(current.progress.coerceIn(0f, 1f) * 100).toInt()}%"
            is UpdateState.Ready -> "立即安装 ›"
            is UpdateState.Error -> "检查失败，点击重试 ›"
        }
        val onClick: (() -> Unit)? = when (val current = state) {
            is UpdateState.Downloading -> null
            is UpdateState.Ready -> {
                { manager.install(current.apk) }
            }
            else -> manager::check
        }

        Column(
            Modifier
                .fillMaxWidth()
                .glass(GlassShapes.card, palette.card2, palette.border),
        ) {
            SettingRow(
                title = "检测升级",
                value = value,
                embedded = true,
                onClick = onClick,
            )
        }
    }
}

@Composable
actual fun AppVersionFooter() {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Yfuse",
            style = sc(11f, 600),
            color = palette.sub2,
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",
            style = mr(10f, 400),
            color = palette.sub2.copy(alpha = 0.82f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
