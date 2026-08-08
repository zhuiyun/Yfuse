package com.yfuse.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc

/**
 * 进入首页自动检测更新.
 *
 * This composes only once the launch animation has handed over, so the check runs as the main
 * UI appears and again whenever 首页 is entered. [AppUpdateManager.checkIfDue] throttles the
 * request, and the dialog below it opens automatically at most once a day per version.
 */
@Composable
fun AppUpdateOverlay(manager: AppUpdateManager, root: RootComponent) {
    val activeTab by root.activeTab.subscribeAsState()
    LaunchedEffect(Unit) { manager.checkIfDue() }
    LaunchedEffect(activeTab) {
        if (activeTab == RootComponent.Tab.Home) manager.checkIfDue()
    }

    val visible by manager.promptVisible.collectAsState()
    if (!visible) return
    val state by manager.state.collectAsState()
    val manifest = when (val value = state) {
        is UpdateState.Available -> value.manifest
        is UpdateState.Downloading -> value.manifest
        is UpdateState.Paused -> value.manifest
        is UpdateState.Ready -> value.manifest
        is UpdateState.Error -> value.manifest
        else -> null
    } ?: return

    val palette = LocalPalette.current
    val downloading = state as? UpdateState.Downloading
    val paused = state as? UpdateState.Paused
    GlassDialog(onDismiss = manager::dismissPrompt) {
        OverlayHeader(
            title = "发现新版本 ${manifest.versionName}",
            subtitle = if (downloading != null) {
                "可以关闭本窗口，下载会在后台继续"
            } else {
                "当前版本可直接在应用内升级"
            },
            onClose = manager::dismissPrompt,
        )
        if (manifest.notes.isNotBlank()) {
            Text(
                manifest.notes,
                style = sc(12f, 400, lineHeight = 19f),
                color = palette.body,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        if (downloading != null || paused != null) {
            val progress = downloading?.progress ?: paused?.progress ?: 0f
            val downloaded = downloading?.downloadedBytes ?: paused?.downloadedBytes ?: 0L
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Brand.Primary,
                    trackColor = palette.card2,
                )
                Text(
                    buildString {
                        append(if (downloading != null) "正在下载" else "已暂停")
                        append(' ')
                        append((progress * 100).toInt())
                        append("% · ")
                        append(formatUpdateBytes(downloaded))
                        append(" / ")
                        append(formatUpdateBytes(manifest.size))
                    },
                    style = mr(10.5f, 500),
                    color = palette.sub2,
                )
                paused?.message?.let {
                    Text(it, style = sc(11.5f, 500), color = Brand.Danger)
                }
            }
        } else if (state is UpdateState.Error) {
            Text(
                (state as UpdateState.Error).message,
                style = sc(11.5f, 500),
                color = Brand.Danger,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton(
                label = if (downloading != null) "后台下载" else "稍后",
                onClick = manager::dismissPrompt,
                modifier = Modifier.weight(1f),
            )
            OverlayButton(
                label = when {
                    downloading != null -> "暂停"
                    paused != null -> "继续下载"
                    state is UpdateState.Ready -> "立即安装"
                    else -> "下载并安装"
                },
                onClick = {
                    when (val value = state) {
                        is UpdateState.Downloading -> manager.pauseDownload()
                        is UpdateState.Ready -> manager.install(value.apk)
                        else -> manager.download(manifest)
                    }
                },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
            )
        }
    }
}

internal fun formatUpdateBytes(bytes: Long): String {
    val megabytes = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (megabytes >= 100.0) {
        "${megabytes.toInt()} MB"
    } else {
        "${(megabytes * 10).toInt() / 10.0} MB"
    }
}
