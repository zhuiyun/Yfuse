package com.yfuse.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.migration.MigrationRelayApi
import com.yfuse.feature.profile.ProfileComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Server backup, export and migration for a television.
 *
 * The phone hands the backup over by QR code or a document picker. A television has no camera and
 * usually no document provider, so both directions go through files in the app's own external
 * directory: `adb pull`, `adb push` or a USB file manager can reach them. Both payload formats the
 * phone can produce are accepted, so a migration started on a phone finishes here.
 */
@Composable
internal fun TvServerBackupPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:backup"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by component.store.states.collectAsState(component.store.state)
    val relayApi = remember { MigrationRelayApi() }

    DisposableEffect(relayApi) {
        onDispose(relayApi::close)
    }

    val backupDirectory =
        remember { File(context.getExternalFilesDir(null) ?: context.filesDir, "backups") }
    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<TvBackupFile>>(emptyList()) }
    var passphrase by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedBackup by remember { mutableStateOf<TvSelectedBackup?>(null) }
    var loadingSelection by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        try {
            files = listTvBackupFiles(backupDirectory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status = "无法读取备份目录：${error.message ?: "请稍后重试"}"
        }
    }

    LaunchedEffect(selectedFile, revision) {
        selectedBackup = null
        val file = selectedFile ?: return@LaunchedEffect
        loadingSelection = true
        try {
            selectedBackup = loadTvBackupFile(file, component::isRelayServers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status = "无法读取备份：${error.message ?: "请重新选择文件"}"
        } finally {
            loadingSelection = false
        }
    }
    val selectedIsRelay = selectedBackup?.isRelay == true

    TvSettingsPageScaffold(page = TvSettingsPage.ServerBackup, status = status) {
        item(key = "backup-summary") {
            TvSettingsNote("本机当前保存了 ${state.serverCount} 台服务器。备份内容经过口令加密，服务器令牌不会明文落盘。")
        }

        item(key = "backup-section-export") { TvSettingsSectionTitle("导出") }
        item(key = "backup-passphrase") {
            TvSettingsTextField(
                value = passphrase,
                label = if (selectedIsRelay) "6 位迁移码" else "保护口令（至少 12 位）",
                stableId = "backup:passphrase",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                secret = true,
            )
        }
        item(key = "backup-export") {
            TvSettingRow(
                title = "导出到文件",
                value = "",
                stableId = "backup:export",
                focusMemory = focusMemory,
                onClick = {
                    if (passphrase.length < 12) {
                        status = "导出口令至少需要 12 位"
                        return@TvSettingRow
                    }
                    busy = true
                    val secret = passphrase.toCharArray()
                    scope.launch {
                        val result =
                            try {
                                withContext(Dispatchers.IO) {
                                    check(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "无法创建备份目录" }
                                    val payload =
                                        component
                                            .exportServers(
                                                secret,
                                                System.currentTimeMillis() / 1_000L,
                                            ).getOrThrow()
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                    val target = File(backupDirectory, "yfuse-servers-$stamp.json")
                                    target.writeText(payload)
                                    Result.success(target.absolutePath)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure(error)
                            } finally {
                                secret.fill('\u0000')
                                busy = false
                            }
                        revision++
                        status =
                            result.fold(
                                onSuccess = { path -> "已导出到 $path" },
                                onFailure = { "导出失败：${it.message ?: "请稍后重试"}" },
                            )
                    }
                },
                icon = AppIcons.Download,
                focusScope = focusScope,
                subtitle = "写入应用目录，可用 adb pull 或 U 盘取出",
                enabled = !busy && state.serverCount > 0,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "backup-section-import") { TvSettingsSectionTitle("导入与迁移") }
        if (files.isEmpty()) {
            item(key = "backup-no-files") {
                TvSettingsNote("${backupDirectory.absolutePath} 里还没有备份文件。把手机导出的文件放进这个目录即可导入。")
            }
        }
        files.forEach { entry ->
            val file = entry.file
            item(key = "backup-file:${file.name}") {
                TvSettingRow(
                    title = file.name,
                    value = if (selectedFile?.name == file.name) "已选择" else "选择",
                    stableId = "backup:file:${file.name}",
                    focusMemory = focusMemory,
                    onClick = {
                        if (selectedFile == file) revision++ else selectedFile = file
                        selectedBackup = null
                        status = null
                    },
                    icon = AppIcons.PlaybackSource,
                    focusScope = focusScope,
                    subtitle = "${formatBytes(entry.sizeBytes)} · ${formatEpoch(entry.modifiedAt)}",
                    selected = selectedFile?.name == file.name,
                    enabled = !busy,
                    navigationRequester = navigationRequester,
                )
            }
        }
        selectedFile?.let { file ->
            item(key = "backup-selected-hint") {
                TvSettingsNote(
                    if (loadingSelection || selectedBackup == null) {
                        if (loadingSelection) "正在读取备份…" else "请重新选择可读取的备份文件。"
                    } else if (selectedIsRelay) {
                        "这是一次性迁移包，请在上方输入源设备显示的 6 位迁移码。"
                    } else {
                        "这是口令保护的备份，请在上方输入导出时设置的口令。"
                    },
                    tone = TvAccent,
                )
            }
            item(key = "backup-import") {
                TvSettingRow(
                    title = "导入这份备份",
                    value = "",
                    stableId = "backup:import",
                    focusMemory = focusMemory,
                    onClick = {
                        val backup = selectedBackup?.takeIf { it.file == file } ?: return@TvSettingRow
                        val enteredPassphrase = passphrase
                        busy = true
                        status = null
                        scope.launch {
                            val result =
                                try {
                                    Result.success(importTvBackup(backup, enteredPassphrase, component, relayApi))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Result.failure(error)
                                } finally {
                                    busy = false
                                }
                            status =
                                result.fold(
                                    onSuccess = { count ->
                                        passphrase = ""
                                        selectedFile = null
                                        "已导入 $count 台服务器"
                                    },
                                    onFailure = { "导入失败：${it.message ?: "请检查口令或迁移码"}" },
                                )
                        }
                    },
                    icon = AppIcons.Check,
                    focusScope = focusScope,
                    subtitle = "同名服务器会被覆盖，其余保持不变",
                    enabled = !busy && !loadingSelection && selectedBackup?.file == file && passphrase.isNotBlank(),
                    navigationRequester = navigationRequester,
                )
            }
        }
        item(key = "backup-note") {
            TvSettingsNote(
                "手机端还能用二维码直接交接。电视没有摄像头，所以迁移包需要以文件形式送达这台设备。",
            )
        }
    }
}
