package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.security.RelayMigrationDescriptor
import com.yfuse.core.security.RelayMigrationPackage

/** Advanced data tools are isolated from the profile navigation/state coordinator. */
@Composable
internal fun DataAndDiagnosticsScreen(
    onBack: () -> Unit,
    serverCount: Int,
    customUserAgent: String,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
    onExportRelay: (Long) -> Result<RelayMigrationPackage>,
    onInspectRelay: (String) -> RelayMigrationDescriptor,
    onIsRelay: (String) -> Boolean,
    onImportRelay: (String, ByteArray, Long) -> Result<Int>,
    imageCacheUsageBytes: Long?,
    videoCacheUsageBytes: Long?,
    videoCacheSize: VideoCacheSize,
    onUserAgent: () -> Unit,
    onClearCache: () -> Unit,
    onClearVideoCache: () -> Unit,
) {
    SettingsPage(
        title = "高级设置",
        subtitle = "网络兼容、迁移与问题排查",
        onBack = onBack,
    ) {
        item {
            Section(title = "网络与兼容") {
                SettingsCard {
                    SettingRow(
                        "自定义 User-Agent",
                        if (customUserAgent.isBlank()) "应用默认 ›" else "已启用 ›",
                        true,
                        onUserAgent,
                    )
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                ServerBackupTools(
                    serverCount = serverCount,
                    onExport = onExport,
                    onImport = onImport,
                    onExportRelay = onExportRelay,
                    onInspectRelay = onInspectRelay,
                    onIsRelay = onIsRelay,
                    onImportRelay = onImportRelay,
                )
            }
        }
        item {
            Section(title = "缓存") {
                SettingsCard {
                    SettingRow(
                        "清除图片缓存",
                        imageCacheUsageBytes?.let { "已用 ${formatDownloadBytes(it)} · 不影响离线下载 ›" }
                            ?: "正在计算 · 不影响离线下载 ›",
                        true,
                        onClearCache,
                    )
                    SettingsDivider()
                    SettingRow(
                        "清除视频缓存",
                        videoCacheUsageSummary(videoCacheUsageBytes, videoCacheSize),
                        true,
                        onClearVideoCache,
                    )
                }
            }
        }
        item {
            Section(title = "权限与后台能力") { PermissionHealthTools() }
        }
        item {
            Section(title = "问题诊断") { DiagnosticLogTools() }
        }
    }
}

internal fun videoCacheUsageSummary(
    usedBytes: Long?,
    cacheSize: VideoCacheSize,
): String =
    when {
        usedBytes == null -> "正在计算 · 上限 ${cacheSize.label} ›"
        cacheSize.bytes <= 0L && usedBytes <= 0L -> "已关闭 · 无缓存 ›"
        cacheSize.bytes <= 0L -> "已关闭 · 已用 ${formatDownloadBytes(usedBytes)} ›"
        else -> "已用 ${formatDownloadBytes(usedBytes)} / ${cacheSize.label} ›"
    }
