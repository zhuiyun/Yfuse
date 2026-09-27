package com.yfuse.feature.library

import androidx.compose.runtime.mutableStateMapOf
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.UserStateWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 标记已看 and 收藏 from a 浮起菜单 on a screen whose items are a snapshot — search results,
 * 相关推荐 — with nothing to reload afterwards.
 *
 * A change shows at once and is remembered here, keyed by server and item, so the menu does not
 * offer 收藏 again for a title just favourited. It is never taken back: the write goes through
 * the sync manager, which keeps a write the server turns down queued, so the new value is still
 * the one that will reach it and the toast says so.
 */
class LiftFlagWriter(
    private val scope: CoroutineScope,
    private val writer: UserStateWriter,
    private val serverById: (String) -> SavedServer?,
) {
    private val overrides = mutableStateMapOf<String, MediaItem>()

    private val _message = MutableStateFlow<String?>(null)

    /** What the page's toast says once a write has been handed over. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** [item] with any flag changed here since the snapshot was taken. */
    fun current(
        serverId: String,
        item: MediaItem,
    ): MediaItem = overrides[key(serverId, item.id)] ?: item

    fun setFavorite(
        serverId: String,
        item: MediaItem,
        favorite: Boolean,
    ) = write(serverId, item, favorite = favorite)

    fun setPlayed(
        serverId: String,
        item: MediaItem,
        played: Boolean,
    ) = write(serverId, item, played = played)

    fun dismissMessage() {
        _message.value = null
    }

    private fun write(
        serverId: String,
        item: MediaItem,
        favorite: Boolean? = null,
        played: Boolean? = null,
    ) {
        val server = serverById(serverId) ?: return
        val before = current(serverId, item)
        overrides[key(serverId, item.id)] =
            before.copy(
                isFavorite = favorite ?: before.isFavorite,
                played = played ?: before.played,
            )
        scope.launch {
            val result =
                if (favorite != null) {
                    writer.setFavorite(server, item.id, item.title, favorite)
                } else {
                    writer.setPlayed(server, item.id, item.title, played ?: return@launch)
                }
            _message.value = flagChangeMessage(favorite = favorite, played = played, queued = result.isFailure)
        }
    }

    private fun key(
        serverId: String,
        itemId: String,
    ) = "$serverId:$itemId"
}
