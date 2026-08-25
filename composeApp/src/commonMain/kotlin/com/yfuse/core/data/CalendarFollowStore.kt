package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class CalendarReminderMode { Off, AtBroadcast, BeforeAndAtBroadcast, WhenAvailable }

@Serializable
data class FollowedSeries(
    val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val serverId: String? = null,
    val seriesItemId: String? = null,
    val reminderMode: CalendarReminderMode = CalendarReminderMode.Off,
    val remindBeforeMinutes: Int = 30,
)

class CalendarFollowStore(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(FollowedSeries.serializer())
    private val _followed = MutableStateFlow(read())
    val followed: StateFlow<List<FollowedSeries>> = _followed.asStateFlow()

    fun isFollowing(tmdbId: Int): Boolean = _followed.value.any { it.tmdbId == tmdbId }

    fun follow(series: FollowedSeries) {
        require(series.tmdbId > 0)
        update((_followed.value.filterNot { it.tmdbId == series.tmdbId } + series).sortedBy { it.title })
    }

    fun unfollow(tmdbId: Int) {
        update(_followed.value.filterNot { it.tmdbId == tmdbId })
        val marker = ".$tmdbId."
        settings.keys
            .filter { key ->
                key == "calendar.reminder.available.baseline.$tmdbId" ||
                    key.startsWith("calendar.reminder.available.seen.$tmdbId.") ||
                    (key.startsWith("calendar.reminder.sent.") && marker in key)
            }.forEach(settings::remove)
    }

    fun replaceFromSync(series: List<FollowedSeries>): Result<Unit> =
        runCatching {
            require(series.size <= MAX_FOLLOWED_SERIES) { "追剧同步数据过多" }
            require(
                series.all {
                    it.tmdbId > 0 &&
                        it.title.isNotBlank() &&
                        it.title.length <= 120 &&
                        it.remindBeforeMinutes in 0..24 * 60
                },
            ) { "追剧同步数据无效" }
            update(
                series
                    .distinctBy(FollowedSeries::tmdbId)
                    .sortedBy(FollowedSeries::title),
            )
        }

    fun setReminder(
        tmdbId: Int,
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ) = update(
        _followed.value.map {
            if (it.tmdbId == tmdbId) {
                it.copy(reminderMode = mode, remindBeforeMinutes = beforeMinutes.coerceIn(0, 24 * 60))
            } else {
                it
            }
        },
    )

    private fun update(value: List<FollowedSeries>) {
        _followed.value = value
        if (value.isEmpty()) settings.remove(KEY) else settings.putString(KEY, json.encodeToString(serializer, value))
    }

    private fun read(): List<FollowedSeries> =
        settings.getStringOrNull(KEY)
            ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
            ?.filter { it.tmdbId > 0 }
            ?.distinctBy(FollowedSeries::tmdbId)
            .orEmpty()

    private companion object {
        const val KEY = "calendar.followed.series.v1"
        const val MAX_FOLLOWED_SERIES = 500
    }
}
