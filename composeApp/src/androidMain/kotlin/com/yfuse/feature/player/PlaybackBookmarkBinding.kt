package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.data.PlaybackBookmark
import com.yfuse.core.data.PlaybackBookmarkKey
import com.yfuse.core.data.PlaybackBookmarks
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.ExclusiveUiOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberPlaybackBookmarkBinding(
    preferences: PlaybackPreferences,
    item: PlayerMediaItem?,
    positionMs: () -> Long,
): Pair<PlaybackBookmarkPanelState, PlaybackBookmarkActions> {
    var store by remember(preferences) { mutableStateOf<PlaybackBookmarks?>(null) }
    LaunchedEffect(preferences) { store = withContext(Dispatchers.IO) { preferences.bookmarks } }
    val key = item?.takeIf { it.id.isNotBlank() }?.let { PlaybackBookmarkKey(it.serverId, it.id, it.versionId) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    var highlightedPosition by remember(key) { mutableStateOf<Long?>(null) }
    var deleted by remember(key) { mutableStateOf<PlaybackBookmark?>(null) }
    val work = remember { ExclusiveUiOperation() }
    val latestKey by rememberUpdatedState(key)
    val scope = rememberCoroutineScope()
    val entries =
        store
            ?.items
            ?.collectAsState()
            ?.value
            .orEmpty()
            .filter { it.media == key }

    fun update(
        success: String,
        position: Long? = null,
        removed: PlaybackBookmark? = null,
        block: PlaybackBookmarks.(PlaybackBookmarkKey) -> Unit,
    ) {
        val target = key ?: return
        val currentStore = store ?: return
        work.launch(scope, "正在保存书签…") {
            try {
                withContext(Dispatchers.IO) { currentStore.block(target) }
                if (latestKey == target) {
                    error = null
                    notice = success
                    highlightedPosition = position
                    deleted = removed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalArgumentException) {
                // The store's own checks say what to change, in words meant for the viewer.
                if (latestKey == target) error = refused.message ?: BOOKMARK_SAVE_FAILED
            } catch (_: Exception) {
                // Anything else is a storage or encoding failure whose text was never meant to be shown.
                if (latestKey == target) error = BOOKMARK_SAVE_FAILED
            }
        }
    }
    return PlaybackBookmarkPanelState(
        mediaIdentity = key.toString(),
        items = entries,
        available = key != null && store != null && store?.loadError == null,
        error = error ?: store?.loadError,
        busy = work.busy,
        notice = notice,
        highlightedPosition = highlightedPosition,
        undoTitle = deleted?.title,
    ) to
        PlaybackBookmarkActions(
            onSave = { title, note ->
                val capturedPosition = positionMs().coerceAtLeast(0)
                update(
                    "已保存 ${formatTime(capturedPosition)} 的书签",
                    capturedPosition,
                ) { save(it, capturedPosition, title, note) }
            },
            onDelete = { id ->
                val removed = entries.firstOrNull { it.id == id }
                if (removed != null) update("书签已删除，可撤销", removed = removed) { remove(it, id) }
            },
            onUndo = {
                deleted?.let { removed ->
                    update("书签已恢复", removed.positionMs) { restore(removed) }
                }
            },
        )
}

private const val BOOKMARK_SAVE_FAILED = "书签保存失败，请重试"
