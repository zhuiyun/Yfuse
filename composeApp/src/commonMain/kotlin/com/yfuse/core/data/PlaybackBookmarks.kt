package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable media identifiers only; never persist a playback URL or authorization header. */
@Serializable
data class PlaybackBookmarkKey(
    val serverId: String?,
    val itemId: String,
    val versionId: String? = null,
)

@Serializable
data class PlaybackBookmark(
    val id: Long,
    val media: PlaybackBookmarkKey,
    val positionMs: Long,
    val title: String,
    val note: String = "",
)

class PlaybackBookmarks(
    private val settings: Settings,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded =
        runCatching {
            val encoded = settings.getStringOrNull(KEY)
            encoded?.let { json.decodeFromString<List<PlaybackBookmark>>(it) }.orEmpty().also { records ->
                require(records.size <= MAX_BOOKMARKS)
                require(records.map { it.id }.distinct().size == records.size)
                require(
                    records.all {
                        it.id > 0 &&
                            it.id < Long.MAX_VALUE &&
                            it.positionMs >= 0 &&
                            it.media.itemId.isNotBlank()
                    },
                )
            }
        }
    private val state = MutableStateFlow(loaded.getOrDefault(emptyList()))
    val items = state.asStateFlow()
    val loadError: String? = loaded.exceptionOrNull()?.let { "书签记录暂时无法读取，请保留应用数据后重试" }

    fun save(
        media: PlaybackBookmarkKey,
        positionMs: Long,
        title: String,
        note: String = "",
    ) = synchronized(lock) {
        check(loaded.isSuccess) { loadError.orEmpty() }
        require(media.itemId.isNotBlank()) { "当前视频尚未就绪" }
        require(positionMs >= 0) { "书签时间不能为负数" }
        val previous = state.value
        val same = previous.firstOrNull { it.media == media && it.positionMs == positionMs }
        require(same != null || previous.size < MAX_BOOKMARKS) { "最多保存 $MAX_BOOKMARKS 个书签，请先删除不需要的书签" }
        val nextId = same?.id ?: ((previous.maxOfOrNull { it.id } ?: 0L) + 1L)
        require(nextId < Long.MAX_VALUE) { "书签编号已达到上限" }
        val bookmark =
            PlaybackBookmark(
                id = nextId,
                media = media,
                positionMs = positionMs,
                title = title.trim().take(80).ifBlank { "时间书签" },
                note = note.trim().take(240),
            )
        persist(previous.filterNot { it.id == bookmark.id } + bookmark)
    }

    fun remove(
        media: PlaybackBookmarkKey,
        id: Long,
    ) = synchronized(lock) {
        check(loaded.isSuccess) { loadError.orEmpty() }
        persist(state.value.filterNot { it.media == media && it.id == id })
    }

    private fun persist(next: List<PlaybackBookmark>) {
        settings.putString(KEY, json.encodeToString(next))
        state.value = next
    }

    companion object {
        private const val KEY = "playback.bookmarks.v1"
        const val MAX_BOOKMARKS = 500
    }
}
