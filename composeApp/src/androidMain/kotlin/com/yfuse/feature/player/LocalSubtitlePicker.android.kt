package com.yfuse.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core2.android.AndroidExternalSubtitleLoader
import com.yfuse.core2.api.YExternalSubtitleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** SAF is needed only while importing; validated text is copied into private persistent storage. */
internal data class LocalSubtitlePickerActions(
    val open: () -> Unit,
    val cancel: () -> Unit,
)

@Composable
internal fun rememberLocalSubtitlePicker(
    target: SubtitleItemKey?,
    enabled: Boolean,
    store: ImportedSubtitleStore,
    onImported: (SubtitleItemKey, List<PlayerExternalSubtitle>) -> Unit,
    onMessage: (String) -> Unit,
): LocalSubtitlePickerActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestTarget by rememberUpdatedState(target)
    val latestEnabled by rememberUpdatedState(enabled)
    val importCallback by rememberUpdatedState(onImported)
    val messageCallback by rememberUpdatedState(onMessage)
    var pendingTarget by remember { mutableStateOf<SubtitleItemKey?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(target, enabled) {
        onDispose { importJob?.cancel() }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val owner = pendingTarget
            pendingTarget = null
            if (uri != null && owner != null && owner == latestTarget && latestEnabled) {
                importJob?.cancel()
                importJob =
                    scope.launch {
                        try {
                            messageCallback("正在校验字幕文件…")
                            val subtitle =
                                withContext(Dispatchers.IO) {
                                    // Reuse the bounded, cancellable loader and real parser. A renamed video or
                                    // empty text file must not appear as a successfully imported subtitle.
                                    var saved: PlayerExternalSubtitle? = null
                                    val importContext = coroutineContext
                                    AndroidExternalSubtitleLoader(context).load(
                                        YExternalSubtitleSource(uri.toString()),
                                        emptyMap(),
                                        onValidated = { text, format ->
                                            saved =
                                                store.save(owner, text, format) { importContext.ensureActive() }
                                        },
                                    )
                                    coroutineContext.ensureActive()
                                    requireNotNull(saved)
                                    store.load(owner)
                                }
                            coroutineContext.ensureActive()
                            if (owner == latestTarget && latestEnabled) importCallback(owner, subtitle)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (owner == latestTarget) {
                                val message =
                                    error.message?.takeIf {
                                        it.startsWith(
                                            "本片已保存",
                                        ) ||
                                            it.startsWith("本地字幕已达到")
                                    }
                                messageCallback(message ?: UNREADABLE_SUBTITLE_MESSAGE)
                            }
                        }
                    }
            } else if (uri != null && owner != null) {
                messageCallback("播放内容已变更，请为当前影片重新选择字幕。")
            }
        }
    val open = {
        if (latestEnabled && latestTarget != null) {
            pendingTarget = latestTarget
            try {
                picker.launch(arrayOf("*/*"))
            } catch (_: Exception) {
                pendingTarget = null
                messageCallback("此设备没有文件选择器，请先在服务器中添加字幕。")
            }
        }
    }
    return LocalSubtitlePickerActions(open = open, cancel = {
        pendingTarget = null
        importJob?.cancel()
    })
}

private const val UNREADABLE_SUBTITLE_MESSAGE = "无法读取字幕，请选择有效的 SRT、ASS、SSA、VTT、SMI、TTML 或 MicroDVD 字幕文件。"
