package com.yfuse.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.logging.DiagnosticLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun PlaybackDiagnosticExportButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    try {
                        result =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openOutputStream(uri)?.use(DiagnosticLogStore::export)
                                        ?: error("无法写入文件")
                                }.fold({ "诊断包已导出" }, { "导出失败，请重试" })
                            }
                    } finally {
                        busy = false
                    }
                }
            }
        }
    OverlayOptionRow(
        if (busy) "正在导出…" else "导出诊断包",
        false,
        { if (!busy) launcher.launch("Yfuse-playback-diagnostics.zip") },
        description = result ?: "包含本次播放和近期故障记录",
    )
}
