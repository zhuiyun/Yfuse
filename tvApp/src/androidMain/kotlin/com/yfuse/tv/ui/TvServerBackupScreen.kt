package com.yfuse.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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

    val backupDirectory =
        remember { File(context.getExternalFilesDir(null), "backups").apply { mkdirs() } }
    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var passphrase by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        files =
            withContext(Dispatchers.IO) {
                backupDirectory
                    .listFiles { file -> file.isFile && file.length() > 0L }
                    ?.sortedByDescending(File::lastModified)
                    ?: emptyList()
            }
    }

    val selectedIsRelay =
        remember(selectedFile, revision) {
            selectedFile?.let { file ->
                runCatching { component.isRelayServers(file.readText()) }.getOrDefault(false)
            } ?: false
        }

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
                            withContext(Dispatchers.IO) {
                                component
                                    .exportServers(secret, System.currentTimeMillis() / 1_000L)
                                    .mapCatching { payload ->
                                        val stamp =
                                            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                        val target = File(backupDirectory, "yfuse-servers-$stamp.json")
                                        target.writeText(payload)
                                        target.absolutePath
                                    }
                            }
                        secret.fill(' ')
                        busy = false
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
        files.forEach { file ->
            item(key = "backup-file:${file.name}") {
                TvSettingRow(
                    title = file.name,
                    value = if (selectedFile?.name == file.name) "已选择" else "选择",
                    stableId = "backup:file:${file.name}",
                    focusMemory = focusMemory,
                    onClick = {
                        selectedFile = file
                        status = null
                    },
                    icon = AppIcons.PlaybackSource,
                    focusScope = focusScope,
                    subtitle = "${formatBytes(file.length())} · ${formatEpoch(file.lastModified())}",
                    selected = selectedFile?.name == file.name,
                    navigationRequester = navigationRequester,
                )
            }
        }
        selectedFile?.let { file ->
            item(key = "backup-selected-hint") {
                TvSettingsNote(
                    if (selectedIsRelay) {
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
                        busy = true
                        status = null
                        scope.launch {
                            val now = System.currentTimeMillis() / 1_000L
                            val result =
                                runCatching {
                                    val payload = withContext(Dispatchers.IO) { file.readText() }
                                    if (component.isRelayServers(payload)) {
                                        require(passphrase.length == 6 && passphrase.all { it in '0'..'9' }) {
                                            "请输入 6 位数字迁移码"
                                        }
                                        val descriptor = component.inspectRelayServers(payload)
                                        val secret =
                                            relayApi.redeem(
                                                descriptor.relayId,
                                                passphrase,
                                                descriptor.payloadSha256,
                                            )
                                        try {
                                            withContext(Dispatchers.Default) {
                                                component.importRelayServers(payload, secret, now).getOrThrow()
                                            }
                                        } finally {
                                            secret.fill(0)
                                        }
                                    } else {
                                        val secret = passphrase.toCharArray()
                                        try {
                                            withContext(Dispatchers.Default) {
                                                component.importServers(payload, secret, now).getOrThrow()
                                            }
                                        } finally {
                                            secret.fill(' ')
                                        }
                                    }
                                }
                            busy = false
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
                    enabled = !busy && passphrase.isNotBlank(),
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
