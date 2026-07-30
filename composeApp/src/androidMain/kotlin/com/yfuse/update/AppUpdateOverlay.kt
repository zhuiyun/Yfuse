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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc

@Composable
fun AppUpdateOverlay(manager: AppUpdateManager) {
    val state by manager.state.collectAsState()
    var dismissedVersion by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state) {
        if (state is UpdateState.Checking) dismissedVersion = -1
    }
    val manifest = when (val value = state) {
        is UpdateState.Available -> value.manifest
        is UpdateState.Downloading -> value.manifest
        is UpdateState.Ready -> value.manifest
        is UpdateState.Error -> value.manifest
        else -> null
    } ?: return
    if (dismissedVersion == manifest.versionCode && state is UpdateState.Available) return

    val palette = LocalPalette.current
    val downloading = state as? UpdateState.Downloading
    GlassDialog(
        onDismiss = {
            if (downloading == null) dismissedVersion = manifest.versionCode
        },
    ) {
        OverlayHeader(
            title = "发现新版本 ${manifest.versionName}",
            subtitle = "当前版本可直接在应用内升级",
            onClose = if (downloading == null) {
                { dismissedVersion = manifest.versionCode }
            } else {
                null
            },
        )
        if (manifest.notes.isNotBlank()) {
            Text(
                manifest.notes,
                style = sc(12f, 400, lineHeight = 19f),
                color = palette.body,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        if (downloading != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { downloading.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Brand.Primary,
                    trackColor = palette.card2,
                )
                Text(
                    "正在下载 ${(downloading.progress * 100).toInt()}%",
                    style = mr(10.5f, 500),
                    color = palette.sub2,
                )
            }
        } else {
            if (state is UpdateState.Error) {
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
                    label = "稍后",
                    onClick = { dismissedVersion = manifest.versionCode },
                    modifier = Modifier.weight(1f),
                )
                OverlayButton(
                    label = if (state is UpdateState.Ready) "安装" else "下载并安装",
                    onClick = {
                        when (val value = state) {
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
}
