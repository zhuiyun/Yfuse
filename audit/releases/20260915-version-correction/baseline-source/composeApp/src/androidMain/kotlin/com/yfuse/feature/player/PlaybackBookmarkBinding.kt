package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yfuse.core.data.PlaybackBookmarkKey
import com.yfuse.core.data.PlaybackBookmarks
import com.yfuse.core.data.PlaybackPreferences
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
    val scope = rememberCoroutineScope()
    val entries =
        store
            ?.items
            ?.collectAsState()
            ?.value
            .orEmpty()
            .filter { it.media == key }

    fun update(block: PlaybackBookmarks.(PlaybackBookmarkKey) -> Unit) {
        val target = key ?: return
        val currentStore = store ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { currentStore.block(target) }
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "书签保存失败，请重试"
            }
        }
    }
    return PlaybackBookmarkPanelState(
        mediaIdentity = key.toString(),
        items = entries,
        available = key != null && store != null && store?.loadError == null,
        error = error ?: store?.loadError,
    ) to
        PlaybackBookmarkActions(
            onSave = { title, note ->
                val capturedPosition = positionMs().coerceAtLeast(0)
                update { save(it, capturedPosition, title, note) }
            },
            onDelete = { id -> update { remove(it, id) } },
        )
}
