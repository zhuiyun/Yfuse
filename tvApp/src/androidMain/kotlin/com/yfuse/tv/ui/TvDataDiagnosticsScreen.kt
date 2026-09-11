package com.yfuse.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.DiagnosticLogStats
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.feature.profile.ProfileComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log capture, diagnostic export and cache reclamation.
 *
 * The phone exports through a Storage Access Framework picker. Many television devices ship no
 * document provider at all, so the export writes into the app's own external files directory and
 * reports the path, which `adb pull` and a USB file manager can both reach.
 */
@Composable
internal fun TvDataDiagnosticsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:diagnostics"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnosticPreferences = remember { GlobalContext.get().get<DiagnosticPreferences>() }
    val logcatEnabled by diagnosticPreferences.logcatEnabled.collectAsState()

    var revision by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<DiagnosticLogStats?>(null) }
    var imageCacheBytes by remember { mutableStateOf<Long?>(null) }
    var videoCacheBytes by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        stats = withContext(Dispatchers.IO) { DiagnosticLogStore.stats() }
        imageCacheBytes = component.imageCacheUsageBytes()
        videoCacheBytes = component.videoCacheUsageBytes()
    }

    TvSettingsPageScaffold(page = TvSettingsPage.DataAndDiagnostics, status = status) {
        item(key = "diag-section-logs") { TvSettingsSectionTitle("日志") }
        item(key = "diag-logcat") {
            TvToggleRow(
                title = "写入系统日志",
                checked = logcatEnabled,
                stableId = "diag:logcat",
                focusMemory = focusMemory,
                onToggle = diagnosticPreferences::setLogcatEnabled,
                icon = AppIcons.Info,
                focusScope = focusScope,
                subtitle = "同时把诊断事件写到 logcat，便于用 adb 实时查看",
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "diag-stats") {
            TvSettingsNote(
                stats?.let {
                    "已记录 ${it.entryCount} 条，占用 ${formatBytes(it.totalBytes)}，" +
                        "共 ${it.fileCount} 个文件，丢弃 ${it.droppedEntryCount} 条"
                } ?: "正在统计…",
            )
        }
        item(key = "diag-export") {
            TvSettingRow(
                title = "导出诊断包",
                value = "",
                stableId = "diag:export",
                focusMemory = focusMemory,
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val directory =
                                        File(context.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }
                                    val stamp =
                                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                    val target = File(directory, "yfuse-diagnostics-$stamp.zip")
                                    target.outputStream().use(DiagnosticLogStore::export)
                                    target.absolutePath
                                }
                            }
                        busy = false
                        status =
                            result.fold(
                                onSuccess = { path ->
                                    revision++
                                    "已导出到 $path"
                                },
                                onFailure = { error ->
                                    AppLog.error(
                                        category = "diagnostics",
                                        event = "tv_export_failed",
                                        message = "Failed to export diagnostic package on TV",
                                        throwable = error,
                                    )
                                    "导出失败：${error.message ?: "请稍后重试"}"
                                },
                            )
                    }
                },
                icon = AppIcons.Download,
                focusScope = focusScope,
                subtitle = "写入本机应用目录，可用 adb pull 或文件管理器取出",
                enabled = !busy,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "diag-clear-logs") {
            TvSettingRow(
                title = "清除日志",
                value = "",
                stableId = "diag:clear-logs",
                focusMemory = focusMemory,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { DiagnosticLogStore.clear() }
                        status = "诊断日志已清除"
                        revision++
                    }
                },
                icon = AppIcons.Close,
                focusScope = focusScope,
                subtitle = "已导出的诊断包不受影响",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "diag-section-cache") { TvSettingsSectionTitle("缓存") }
        item(key = "diag-image-cache") {
            TvSettingRow(
                title = "清除图片缓存",
                value = imageCacheBytes?.let(::formatBytes) ?: "统计中",
                stableId = "diag:image-cache",
                focusMemory = focusMemory,
                onClick = {
                    scope.launch {
                        component.onClearCache()
                        status = "图片缓存已清除"
                        revision++
                    }
                },
                icon = AppIcons.Grid,
                focusScope = focusScope,
                subtitle = "海报与剧照会在下次浏览时重新下载",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "diag-video-cache") {
            TvSettingRow(
                title = "清除视频缓存",
                value = videoCacheBytes?.let(::formatBytes) ?: "统计中",
                stableId = "diag:video-cache",
                focusMemory = focusMemory,
                onClick = {
                    scope.launch {
                        val freed = component.onClearVideoCache()
                        status = "已释放 ${formatBytes(freed)}"
                        revision++
                    }
                },
                icon = AppIcons.Movie,
                focusScope = focusScope,
                subtitle = "离线下载的文件不受影响",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "diag-note") {
            TvSettingsNote("诊断包里不含服务器令牌、账号密码和完整的媒体地址。")
        }
    }
}

internal fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
