package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

data class WatchRoomPlaylistEntry(
    val id: String,
    val mediaKey: String,
    val title: String,
)

data class WatchRoomPlaylistState(
    val supported: Boolean = false,
    val entries: List<WatchRoomPlaylistEntry> = emptyList(),
    val revision: Long = 0L,
    /** One mutation at a time keeps every write based on the last authoritative revision. */
    val mutationPending: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the client side of the room-playlist protocol.
 *
 * The relay is authoritative. Mutations are never applied optimistically: the UI changes only
 * after the server broadcasts the next playlist revision. This also means a stale edit can recover
 * from the snapshot included in the server error instead of overwriting another member's change.
 */
class WatchRoomPlaylistController internal constructor(
    private val sender: (WatchWireMessage) -> Boolean,
) {
    private val _state = MutableStateFlow(WatchRoomPlaylistState())
    val state: StateFlow<WatchRoomPlaylistState> = _state.asStateFlow()

    fun add(
        mediaKey: String,
        title: String,
        index: Int? = null,
    ): Boolean {
        val normalizedTitle = title.trim()
        if (!WatchProtocol.isValidMediaKey(mediaKey) ||
            !WatchProtocol.isValidPlaylistTitle(normalizedTitle)
        ) {
            failLocally("无法添加：媒体或标题无效")
            return false
        }
        val entry =
            WatchWirePlaylistEntry(
                id = newEntryId(),
                mediaKey = mediaKey,
                title = normalizedTitle,
            )
        return mutate(
            WatchWireMessage(
                type = "playlistAdd",
                playlistRevision = _state.value.revision,
                playlistEntry = entry,
                playlistIndex = index,
            ),
        )
    }

    fun remove(entryId: String): Boolean {
        if (!WatchProtocol.isValidPlaylistEntryId(entryId)) {
            failLocally("无法删除：播放列表项目无效")
            return false
        }
        return mutate(
            WatchWireMessage(
                type = "playlistRemove",
                playlistRevision = _state.value.revision,
                playlistEntryId = entryId,
            ),
        )
    }

    fun move(
        entryId: String,
        destinationIndex: Int,
    ): Boolean {
        val current = _state.value
        if (!WatchProtocol.isValidPlaylistEntryId(entryId) || destinationIndex !in current.entries.indices) {
            failLocally("无法排序：目标位置无效")
            return false
        }
        return mutate(
            WatchWireMessage(
                type = "playlistReorder",
                playlistRevision = current.revision,
                playlistEntryId = entryId,
                playlistIndex = destinationIndex,
            ),
        )
    }

    fun clearError() {
        _state.update { if (it.error == null) it else it.copy(error = null) }
    }

    internal fun reset() {
        _state.value = WatchRoomPlaylistState()
    }

    /** Applies welcome/roomUpdate snapshots only when the complete pair is valid. */
    internal fun applySnapshot(message: WatchWireMessage) {
        val supported =
            if (message.type == "welcome") {
                WatchProtocol.CAPABILITY_ROOM_PLAYLIST in message.capabilities.orEmpty()
            } else {
                _state.value.supported
            }
        val playlist = message.playlist
        val revision = message.playlistRevision
        if (playlist == null && revision == null) {
            if (message.type == "welcome") {
                _state.update { it.copy(supported = supported, mutationPending = false) }
            }
            return
        }
        if (!WatchProtocol.isValidPlaylist(playlist) || !WatchProtocol.isValidPlaylistRevision(revision)) {
            _state.update {
                it.copy(
                    supported = supported,
                    mutationPending = false,
                    error = "服务器返回的房间播放列表无效",
                )
            }
            return
        }
        _state.value =
            WatchRoomPlaylistState(
                supported = supported,
                entries = playlist!!.map(WatchWirePlaylistEntry::toDomain),
                revision = revision!!,
                mutationPending = false,
                error = null,
            )
    }

    /** Playlist errors carry the latest snapshot, allowing stale revisions to self-heal. */
    internal fun applyServerError(message: WatchWireMessage) {
        val playlist = message.playlist
        val revision = message.playlistRevision
        if (WatchProtocol.isValidPlaylist(playlist) && WatchProtocol.isValidPlaylistRevision(revision)) {
            _state.update {
                it.copy(
                    entries = playlist!!.map(WatchWirePlaylistEntry::toDomain),
                    revision = revision!!,
                    mutationPending = false,
                    error = message.message ?: "播放列表更新失败",
                )
            }
        } else {
            _state.update {
                it.copy(
                    mutationPending = false,
                    error = message.message ?: "播放列表更新失败",
                )
            }
        }
    }

    private fun mutate(message: WatchWireMessage): Boolean {
        val current = _state.value
        if (!current.supported) {
            failLocally("当前一起看服务不支持房间播放列表")
            return false
        }
        if (current.mutationPending) return false
        if (!sender(message)) {
            failLocally("播放列表请求发送失败，请重试")
            return false
        }
        _state.update { it.copy(mutationPending = true, error = null) }
        return true
    }

    private fun failLocally(message: String) {
        _state.update { it.copy(mutationPending = false, error = message) }
    }

    private fun newEntryId(): String = buildString(18) {
        append("p-")
        repeat(16) { append(ENTRY_ID_ALPHABET[Random.nextInt(ENTRY_ID_ALPHABET.length)]) }
    }
}

private fun WatchWirePlaylistEntry.toDomain(): WatchRoomPlaylistEntry =
    WatchRoomPlaylistEntry(
        id = id,
        mediaKey = mediaKey,
        title = title,
    )

private const val ENTRY_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
