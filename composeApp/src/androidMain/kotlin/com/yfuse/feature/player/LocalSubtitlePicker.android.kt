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
import com.yfuse.core2.subtitle.externalTextSubtitleFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** SAF grants are used only for this playback session; the subtitle is never uploaded. */
@Composable
internal fun rememberLocalSubtitlePicker(
    target: SubtitleItemKey?,
    enabled: Boolean,
    onImported: (SubtitleItemKey, PlayerExternalSubtitle) -> Unit,
    onMessage: (String) -> Unit,
): () -> Unit {
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
                                    val loaded =
                                        AndroidExternalSubtitleLoader(context).load(
                                            YExternalSubtitleSource(uri.toString()),
                                            emptyMap(),
                                        )
                                    coroutineContext.ensureActive()
                                    val format =
                                        requireNotNull(externalTextSubtitleFormat(uri.toString(), loaded.track.codec))
                                    PlayerExternalSubtitle(uri = uri.toString(), codec = format.name.lowercase())
                                }
                            coroutineContext.ensureActive()
                            if (owner == latestTarget && latestEnabled) importCallback(owner, subtitle)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (owner == latestTarget) messageCallback("无法读取字幕，请选择有效的 SRT、ASS、SSA 或 VTT 文件。")
                        }
                    }
            } else if (uri != null && owner != null) {
                messageCallback("播放内容已变更，请为当前影片重新选择字幕。")
            }
        }
    return {
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
}
