package com.yfuse.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.DiagnosticLogStats
import com.yfuse.core.logging.DiagnosticLogStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

@Composable
actual fun DiagnosticLogTools() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val diagnosticPreferences = remember {
        GlobalContext.get().get<DiagnosticPreferences>()
    }
    val logcatEnabled by diagnosticPreferences.logcatEnabled.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<DiagnosticLogStats?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun exportTo(uri: Uri) {
        scope.launch {
            exporting = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use(DiagnosticLogStore::export)
                        ?: error("无法写入诊断包")
                }
            }
            result
                .onSuccess {
                    status = "诊断日志已导出"
                    revision++
                }
                .onFailure { error ->
                    status = error.message ?: "导出失败"
                    AppLog.error(
                        category = "diagnostics",
                        event = "export_failed",
                        message = "Failed to export diagnostic package",
                        throwable = error,
                    )
                }
            exporting = false
        }
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(::exportTo) }

    LaunchedEffect(revision) {
        stats = withContext(Dispatchers.IO) { DiagnosticLogStore.stats() }
    }
    LaunchedEffect(logcatEnabled) {
        if (logcatEnabled) {
            while (true) {
                val remaining = diagnosticPreferences.logcatOutputRemainingMs()
                if (remaining <= 0L) break
                // Recompute after every wait so a manual wall-clock change cannot leave the
                // switch visually on after the persisted privacy window has expired.
                delay(remaining)
            }
            diagnosticPreferences.isLogcatEnabledNow()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text("本地诊断日志", style = sc(13f, 550), color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(
                stats?.let {
                    "${it.entryCount} 条记录 · ${formatDiagnosticBytes(it.totalBytes)} · ${it.fileCount} 个文件"
                } ?: "正在统计…",
                style = mr(10.5f, 400),
                color = palette.sub2,
            )
        }
        DiagnosticDivider()
        DiagnosticToggleRow(
            title = "输出到 Logcat",
            subtitle = "默认关闭，开启 1 小时后自动关闭；输出仍会先脱敏",
            checked = logcatEnabled,
            onChange = { enabled ->
                diagnosticPreferences.setLogcatEnabled(enabled)
                status = if (enabled) {
                    "Logcat 实时输出已开启，将在 1 小时后自动关闭"
                } else {
                    "Logcat 实时输出已关闭"
                }
                AppLog.info(
                    category = "diagnostics",
                    event = "logcat_output_changed",
                    message = "Live Logcat diagnostic output preference changed",
                    attributes = mapOf("enabled" to enabled.toString()),
                )
            },
        )
        DiagnosticDivider()
        DiagnosticActionRow(
            title = if (exporting) "正在导出…" else "导出诊断包",
            subtitle = "ZIP 包含脱敏日志、应用版本和设备环境",
            enabled = !exporting,
            onClick = {
                status = null
                exportFile.launch(
                    "Yfuse-diagnostics-${
                        LocalDateTime.now().format(ExportFileTime)
                    }.zip",
                )
            },
        )
        DiagnosticDivider()
        DiagnosticActionRow(
            title = "清除本地日志",
            subtitle = "日志最多保留 7 天，总占用不超过 5 MB",
            enabled = !exporting,
            destructive = true,
            onClick = { confirmClear = true },
        )
    }

    status?.let {
        Text(
            it,
            style = mr(10.5f, 600),
            color = Brand.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Brand.Primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清除诊断日志",
            message = "本机现有的诊断记录将被永久删除，已导出的诊断包不受影响。",
            confirmLabel = "清除",
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                scope.launch {
                    stats = withContext(Dispatchers.IO) {
                        DiagnosticLogStore.clear()
                        DiagnosticLogStore.stats()
                    }
                    status = "本地诊断日志已清除"
                }
            },
        )
    }
}

@Composable
private fun DiagnosticToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sc(12.5f, 550), color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = mr(10f, 400), color = palette.sub2)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun DiagnosticActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = sc(12.5f, 550),
                color = when {
                    !enabled -> palette.sub2
                    destructive -> Brand.Danger
                    else -> palette.text
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = mr(10f, 400), color = palette.sub2)
        }
        Text("›", style = mr(16f, 500), color = palette.sub2)
    }
}

@Composable
private fun DiagnosticDivider() {
    val palette = LocalPalette.current
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.border.copy(alpha = if (palette.isDark) 0.24f else 0.48f)),
    )
}

private fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val ExportFileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
