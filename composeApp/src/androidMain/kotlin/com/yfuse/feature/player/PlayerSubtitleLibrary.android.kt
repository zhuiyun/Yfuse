package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Subtitle operations belong to a media version and are canceled on source/language changes. */
@Composable
internal fun rememberPlayerSubtitleLibrary(
    item: PlayerMediaItem?,
    server: SavedServer?,
    casting: Boolean,
    repository: EmbyRepository,
    onImportsChanged: (SubtitleItemKey, List<PlayerExternalSubtitle>) -> Boolean,
): Pair<RemoteSubtitlePanelState, RemoteSubtitleActions> {
    val key = item?.subtitleItemKey()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember(context) { ImportedSubtitleStore(context.applicationContext.filesDir) }
    var panel by remember(key, casting) { mutableStateOf(RemoteSubtitlePanelState()) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var importRevision by remember(key) { mutableStateOf(0L) }
    val latestKey by rememberUpdatedState(key)
    val imported by rememberUpdatedState(onImportsChanged)
    val unavailable = if (casting) "请先返回本机播放，再搜索或导入字幕。" else subtitleSearchUnavailableReason(server?.kind)
    DisposableEffect(key, casting) {
        onDispose { operation?.cancel() }
    }
    LaunchedEffect(key, casting) {
        if (key != null && !casting) {
            val revision = importRevision
            val saved = withContext(Dispatchers.IO) { store.load(key) }
            if (revision == importRevision && saved.isNotEmpty() && key == latestKey && imported(key, saved)) {
                panel = panel.copy(importedCount = saved.size)
            }
        }
    }
    val picker =
        rememberLocalSubtitlePicker(
            target = key,
            enabled = item != null && !casting,
            store = store,
            onImported = { owner, saved ->
                importRevision++
                if (owner == latestKey && imported(owner, saved)) {
                    panel = panel.copy(importedCount = saved.size, message = "已保存到本机，下次播放当前版本会自动加载。")
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
                                                onFailure = { it.toUserMessage("字幕下载失败，请重试。") },
                                            ),
                                    )
                            }
                        }
                }
            },
            onImport = {
                importRevision++
                picker.open()
            },
            onClearImports = {
                if (key != null && !casting) {
                    picker.cancel()
                    val revision = ++importRevision
                    operation?.cancel()
                    panel = panel.copy(loading = false, downloadingId = null)
                    operation =
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.clear(key) }
                                if (revision == importRevision && key == latestKey && imported(key, emptyList())) {
                                    panel = panel.copy(importedCount = 0, message = "已清除本片的本地字幕副本。")
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (key == latestKey) panel = panel.copy(message = "字幕未能清除，请检查存储后重试。")
                            }
                        }
                }
            },
        )
    return panel.copy(
        searchUnavailableReason = unavailable,
        importUnavailableReason = "请先返回本机播放，再导入本地字幕。".takeIf { casting },
    ) to actions
}
