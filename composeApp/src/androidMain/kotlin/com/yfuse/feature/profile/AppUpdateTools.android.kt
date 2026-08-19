package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.BuildConfig
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.update.LocalAppUpdateManager
import com.yfuse.update.UpdateState
import com.yfuse.core.designsystem.flatGlass as glass

@Composable
actual fun AppUpdateTools() {
    val manager = LocalAppUpdateManager.current
    val palette = LocalPalette.current

    Section(title = "应用更新") {
        if (manager == null) {
            SettingRow(
                title = "检测升级",
                value = "暂不可用",
                icon = AppIcons.Refresh,
                iconTint = SettingTint.sync,
            )
            return@Section
        }

        val state by manager.state.collectAsState()
        val value =
            when (val current = state) {
                UpdateState.Idle -> "检测升级 ›"
                UpdateState.Checking -> "正在检查…"
                UpdateState.Current -> "已是最新版本 ›"
                is UpdateState.Available -> "发现 ${current.manifest.versionName} ›"
                is UpdateState.Downloading ->
                    "后台下载 ${(current.progress * 100).toInt()}% ›"
                is UpdateState.Paused -> "已暂停 ${(current.progress * 100).toInt()}%，继续 ›"
                is UpdateState.Ready -> "立即安装 ›"
                is UpdateState.Error -> "检查失败，点击重试 ›"
            }
        // A download in flight belongs to the dialog, which shows its progress and can pause
        // it; only an idle row starts a fresh check.
        val onClick: (() -> Unit) =
            when (val current = state) {
                is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Paused ->
                    manager::showPrompt
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
                icon = AppIcons.Refresh,
                iconTint = SettingTint.sync,
            )
        }
    }
}

@Composable
actual fun AppVersionFooter() {
    val palette = LocalPalette.current
    Column(
        modifier =
            Modifier
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
