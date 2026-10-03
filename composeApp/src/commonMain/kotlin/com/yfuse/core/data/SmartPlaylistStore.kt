package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.LibrarySort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Saved queries, not cached result lists: opening a rule always queries the current library. */
@Serializable
data class SmartPlaylist(
    val name: String,
    val query: String = "",
    val serverId: String? = null,
    val libraryId: String? = null,
    val type: String = "All",
    val year: Int? = null,
    val genre: String? = null,
    val watchStatus: String = "All",
    val sort: String = "RecentlyAdded",
    val pinned: Boolean = true,
)

/** The library listing a pinned 智能片单 stands for, shown on 首页 as a poster rail. */
data class SmartShelfQuery(
    val serverId: String,
    val libraryId: String,
    val genre: String?,
    val sort: LibrarySort,
    val unplayedOnly: Boolean,
)

/**
 * The listing this rule is, when it is no more than one library under a genre, an order and
 * perhaps 未看: then 首页 can show it as posters rather than as a name to tap. A rule with words to
 * search for, a year, a type or a watch status the listing cannot ask for stays a chip.
 */
fun SmartPlaylist.libraryShelf(): SmartShelfQuery? {
    val server = serverId?.takeIf(String::isNotBlank) ?: return null
    val library = libraryId?.takeIf(String::isNotBlank) ?: return null
    if (query.isNotBlank() || year != null || type != "All") return null
    val unplayedOnly =
        when (watchStatus) {
            "All" -> false
            "Unplayed" -> true
            else -> return null
        }
    val order =
        when (sort) {
            "YearNewest" -> LibrarySort.Year
            "Rating" -> LibrarySort.Rating
            "Name" -> LibrarySort.Name
            else -> LibrarySort.RecentlyAdded
        }
    return SmartShelfQuery(server, library, genre?.takeIf(String::isNotBlank), order, unplayedOnly)
}

class SmartPlaylistStore(
    private val settings: Settings,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SmartPlaylist.serializer())
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private var unreadable = false
    private val mutableItems = MutableStateFlow(load())
    val items: StateFlow<List<SmartPlaylist>> = mutableItems

    private fun load(): List<SmartPlaylist> =
        try {
            val raw = settings.getStringOrNull(KEY)
            if (raw == null) {
                emptyList()
            } else {
                require(raw.length <= 100_000)
                json.decodeFromString(serializer, raw).also { rules ->
                    require(rules.size <= LIMIT && rules.map { it.name }.distinct().size == rules.size)
                    require(rules.all { it.name.isNotBlank() && it.name.length <= 40 && it.query.length <= 200 })
                }
            }
        } catch (_: Exception) {
            unreadable = true
            mutableError.value = "智能片单读取失败，原始数据已保留"
            emptyList()
        }

    fun save(rule: SmartPlaylist): Boolean {
        val name = rule.name.trim()
        if (name.isEmpty() || name.length > 40 || rule.query.length > 200) {
            mutableError.value = "片单名称需为 1–40 字，关键词不超过 200 字"
            return false
        }
        val existing = mutableItems.value
        if (existing.size >= LIMIT && existing.none { it.name == name }) {
            mutableError.value = "最多保存 $LIMIT 个智能片单"
            return false
        }
        return persist(existing.filterNot { it.name == name } + rule.copy(name = name, query = rule.query.trim()))
    }

    fun remove(name: String): Boolean = persist(mutableItems.value.filterNot { it.name == name })

    private fun persist(items: List<SmartPlaylist>): Boolean =
        try {
            // Refuse to overwrite unreadable data just because an empty UI was shown.
            check(!unreadable)
            settings.putString(KEY, json.encodeToString(serializer, items))
            mutableItems.value = items
            mutableError.value = null
            true
        } catch (_: Exception) {
            mutableError.value = "智能片单保存失败，请检查存储后重试"
            false
        }

    private companion object {
        const val KEY = "media.smartPlaylists.v1"
        const val LIMIT = 40
    }
}
