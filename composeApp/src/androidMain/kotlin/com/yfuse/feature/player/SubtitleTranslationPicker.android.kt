package com.yfuse.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.subtitletranslation.OpenAiSubtitleTranslator
import com.yfuse.core.subtitletranslation.SubtitleTranslationSettings
import com.yfuse.core.subtitletranslation.createSubtitleTranslationSettingsStore
import com.yfuse.core2.android.AndroidExternalSubtitleLoader
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitleTimeline
import com.yfuse.core2.subtitle.YTextSubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
internal fun rememberSubtitleTranslationPicker(
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
    val imported by rememberUpdatedState(onImported)
    val message by rememberUpdatedState(onMessage)
    var pendingOwner by remember { mutableStateOf<SubtitleItemKey?>(null) }
    var chosen by remember { mutableStateOf<Pair<SubtitleItemKey, String>?>(null) }
    var configuration by remember { mutableStateOf<SubtitleTranslationSettings?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(target, enabled) {
        onDispose {
            job?.cancel()
            chosen = null
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val owner = pendingOwner
            pendingOwner = null
            if (uri != null && owner != null && owner == latestTarget && latestEnabled) {
                try {
                    val settings = createSubtitleTranslationSettingsStore().load()
                    if (settings.endpoint.isBlank() || settings.model.isBlank()) {
                        message("请先在高级播放设置中配置字幕翻译地址和模型。")
                    } else {
                        configuration = settings
                        chosen = owner to uri.toString()
                    }
                } catch (_: Exception) {
                    message("无法读取翻译配置，请在设置中重新保存。")
                }
            }
        }
    chosen?.let { selection ->
        val settings = configuration
        if (settings != null) {
            AlertDialog(
                onDismissRequest = { chosen = null },
                title = { Text("翻译所选字幕") },
                text = {
                    Text(
                        "字幕文字将发送至已配置的接口，使用 ${settings.model} 翻译为${settings.targetLanguage}，" +
                            "可能产生服务费用。完成后作为新字幕保存到当前影片，保留原字幕和时间轴。",
                    )
                },
                dismissButton = { TextButton(onClick = { chosen = null }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        chosen = null
                        job?.cancel()
                        job =
                            scope.launch {
                                try {
                                    message("正在读取字幕…")
                                    val timeline =
                                        withContext(Dispatchers.IO) {
                                            var value: YSubtitleTimeline? = null
                                            val activeContext = coroutineContext
                                            AndroidExternalSubtitleLoader(
                                                context,
                                            ).load(
                                                YExternalSubtitleSource(
                                                    selection.second,
                                                ),
                                                emptyMap(),
                                                onValidated = {
                                                        text,
                                                        format,
                                                    ->
                                                    value =
                                                        YTextSubtitleParser.parse(
                                                            text,
                                                            format,
                                                        ) { activeContext.ensureActive() }
                                                },
                                            )
                                            requireNotNull(value)
                                        }
                                    val output =
                                        OpenAiSubtitleTranslator().translate(
                                            timeline,
                                            settings,
                                        ) { done, total -> message("正在翻译字幕：$done / $total") }
                                    coroutineContext.ensureActive()
                                    if (selection.first != latestTarget || !latestEnabled) return@launch
                                    val saved =
                                        withContext(Dispatchers.IO) {
                                            val activeContext = coroutineContext
                                            store.save(
                                                selection.first,
                                                output,
                                                YSubtitleFormat.Srt,
                                                translated = true,
                                            ) { activeContext.ensureActive() }
                                            store.load(selection.first)
                                        }
                                    if (selection.first == latestTarget && latestEnabled) {
                                        imported(selection.first, saved)
                                        message("翻译字幕已保存，可在字幕轨道中选择；原字幕已保留。")
                                    }
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message("翻译未完成，原字幕未改动。请核对接口、模型、余额及字幕格式后重试。")
                                }
                            }
                    }) { Text("翻译并发送字幕") }
                },
            )
        }
    }
    return LocalSubtitlePickerActions(
        open = {
            if (latestEnabled && latestTarget != null) {
                pendingOwner = latestTarget
                try {
                    picker.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    message("此设备没有可用的文件选择器。")
                }
            }
        },
        cancel = {
            pendingOwner = null
            chosen = null
            job?.cancel()
            message("已取消字幕翻译。")
        },
    )
}
