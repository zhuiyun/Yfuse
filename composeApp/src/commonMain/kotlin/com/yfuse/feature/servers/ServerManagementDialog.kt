@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.yfuse.feature.servers

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.model.SavedServer
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ServerManagementDialog(
    server: SavedServer,
    state: ServerManagementUiState,
    onReload: () -> Unit,
    onRefreshLibrary: (String) -> Unit,
    onRunTask: (String) -> Unit,
    onSwitchHomeUser: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var plexHomePin by remember(server.id, (state as? ServerManagementUiState.Ready)?.sessionId) { mutableStateOf("") }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "服务器管理",
            subtitle = server.serverName,
            onClose = onDismiss,
        )
        when (state) {
            ServerManagementUiState.Idle,
            is ServerManagementUiState.Loading,
            -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbProgress(size = 16.dp)
                    Text("正在读取媒体库与服务器任务…", style = AppTypography.body.medium, color = palette.body)
                }
            }
            is ServerManagementUiState.Error -> {
                Text(state.message, style = AppTypography.body.medium, color = palette.error)
                Spacer(Modifier.height(12.dp))
                OverlayButton(
                    label = "重试",
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    tone = OverlayButtonTone.Primary,
                )
            }
            is ServerManagementUiState.Ready -> {
                if (state.snapshot.supportsPlexHomeSwitch && state.snapshot.plexHomeUsers.isNotEmpty()) {
                    Text("Plex Home 用户", style = AppTypography.caption.strong, color = palette.sub2)
                    Spacer(Modifier.height(8.dp))
                    if (state.snapshot.plexHomeUsers.any { it.pinProtected }) {
                        YfFormField(
                            value = plexHomePin,
                            onValueChange = { plexHomePin = it.filter(Char::isDigit).take(4) },
                            label = "受保护用户 PIN",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                        state.snapshot.plexHomeUsers.forEach { user ->
                            OverlayActionRow(
                                label = user.name,
                                description =
                                    buildString {
                                        append(if (user.admin) "管理员" else "家庭用户")
                                        if (user.pinProtected) append(" · 需要 PIN")
                                    },
                                onClick = {
                                    onSwitchHomeUser(
                                        user.id,
                                        plexHomePin.takeIf { user.pinProtected }.orEmpty(),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text("媒体库扫描", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                    state.snapshot.libraries.forEach { library ->
                        OverlayActionRow(
                            label = library.name,
                            description =
                                if (state.busyId == "library:${library.id}") {
                                    "正在提交扫描…"
                                } else {
                                    "扫描 ${library.collectionType?.ifBlank { "媒体库" } ?: "媒体库"}"
                                },
                            onClick = { onRefreshLibrary(library.id) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("服务器任务", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(8.dp))
                if (!state.snapshot.supportsScheduledTasks) {
                    Text(
                        "Plex 没有稳定的通用计划任务接口；媒体库扫描、单项元数据刷新和分析仍可用。",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                } else if (state.snapshot.scheduledTasksError != null) {
                    Text(
                        state.snapshot.scheduledTasksError,
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                } else if (state.snapshot.tasks.isEmpty()) {
                    Text("服务器未返回计划任务。", style = AppTypography.caption.regular, color = palette.sub2)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                        state.snapshot.tasks.forEach { task ->
                            val progress =
                                task.progressPercent
                                    ?.toInt()
                                    ?.let { " · $it%" }
                                    .orEmpty()
                            OverlayActionRow(
                                label = task.name,
                                description = "${task.state}$progress${task.lastResult?.let { " · 上次 $it" }.orEmpty()}",
                                onClick = { onRunTask(task.id) },
                            )
                        }
                    }
                }
                state.message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = AppTypography.caption.medium, color = palette.success)
                }
                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = AppTypography.caption.medium, color = palette.error)
                }
                Spacer(Modifier.height(12.dp))
                OverlayButton(
                    label = "刷新状态",
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    tone = OverlayButtonTone.Plain,
                )
            }
        }
    }
}
