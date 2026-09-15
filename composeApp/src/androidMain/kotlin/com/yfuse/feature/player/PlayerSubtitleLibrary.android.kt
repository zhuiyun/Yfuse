package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Subtitle operations belong to a media version and are canceled on source/language changes. */
@Composable
internal fun rememberPlayerSubtitleLibrary(
    item: PlayerMediaItem?,
    server: SavedServer?,
    casting: Boolean,
    repository: EmbyRepository,
    onImported: (SubtitleItemKey, PlayerExternalSubtitle) -> Boolean,
): Pair<RemoteSubtitlePanelState, RemoteSubtitleActions> {
    val key = item?.subtitleItemKey()
    val scope = rememberCoroutineScope()
    var panel by remember(key, casting) { mutableStateOf(RemoteSubtitlePanelState()) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val latestKey by rememberUpdatedState(key)
    val imported by rememberUpdatedState(onImported)
    val unavailable = if (casting) "请先返回本机播放，再搜索或导入字幕。" else subtitleSearchUnavailableReason(server?.kind)
    DisposableEffect(key, casting) {
        onDispose { operation?.cancel() }
    }
    val picker =
        rememberLocalSubtitlePicker(
            target = key,
            enabled = item != null && !casting,
            onImported = { owner, subtitle ->
                if (owner == latestKey && imported(owner, subtitle)) {
                    panel = panel.copy(message = "已导入，可在字幕轨道中选择；本次播放结束后不保留。")
                }
            },
            onMessage = { panel = panel.copy(message = it) },
        )
    val actions =
        RemoteSubtitleActions(
            onLanguage = { language ->
                if (language != panel.language) {
                    operation?.cancel()
                    panel =
                        panel.copy(
                            language = language,
                            loading = false,
                            downloadingId = null,
                            results = emptyList(),
                            message = null,
                        )
                }
            },
            onSearch = {
                if (unavailable == null &&
                    item != null &&
                    server != null &&
                    !panel.loading &&
                    panel.downloadingId == null
                ) {
                    operation?.cancel()
                    val language = panel.language
                    panel = panel.copy(loading = true, results = emptyList(), message = null)
                    operation =
                        scope.launch {
                            val result = repository.searchRemoteSubtitles(server, item.id, language.code)
                            coroutineContext.ensureActive()
                            if (key == latestKey) {
                                panel = panel.withSearchResult(result, language)
                            }
                        }
                }
            },
            onDownload = { subtitleId ->
                if (unavailable == null &&
                    item != null &&
                    server != null &&
                    panel.downloadingId == null &&
                    !panel.loading
                ) {
                    operation?.cancel()
                    panel = panel.copy(downloadingId = subtitleId, message = null)
                    operation =
                        scope.launch {
                            val result = repository.downloadRemoteSubtitle(server, item.id, subtitleId)
                            coroutineContext.ensureActive()
                            if (key == latestKey) {
                                panel =
                                    panel.copy(
                                        downloadingId = null,
                                        message =
                                            result.fold(
                                                onSuccess = { "字幕已添加到服务器，重新打开影片后可选择新轨道。" },
                                                onFailure = { it.message ?: "字幕下载失败，请重试。" },
                                            ),
                                    )
                            }
                        }
                }
            },
            onImport = picker,
        )
    return panel.copy(
        searchUnavailableReason = unavailable,
        importUnavailableReason = "请先返回本机播放，再导入本地字幕。".takeIf { casting },
    ) to actions
}
