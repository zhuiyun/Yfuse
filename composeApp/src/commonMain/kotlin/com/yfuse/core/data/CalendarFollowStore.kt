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

class CalendarFollowStore(
    private val settings: Settings,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val serializer = ListSerializer(FollowedSeries.serializer())
    private val _followed = MutableStateFlow(read())
    val followed: StateFlow<List<FollowedSeries>> = _followed.asStateFlow()

    fun savedPlatformFilter(): String? = settings.getStringOrNull(KEY_PLATFORM_FILTER)?.takeIf(String::isNotBlank)

    fun savedContentFilter(): String? = settings.getStringOrNull(KEY_CONTENT_FILTER)?.takeIf(String::isNotBlank)

    fun savePlatformFilter(value: String?) {
        if (value.isNullOrBlank()) {
            settings.remove(KEY_PLATFORM_FILTER)
        } else {
            settings.putString(KEY_PLATFORM_FILTER, value)
        }
    }

    fun saveContentFilter(value: String) {
        settings.putString(KEY_CONTENT_FILTER, value)
    }

    fun isFollowing(tmdbId: Int): Boolean = _followed.value.any { it.tmdbId == tmdbId }

    fun follow(series: FollowedSeries) {
        require(series.tmdbId > 0)
        update((_followed.value.filterNot { it.tmdbId == series.tmdbId } + series).sortedBy { it.title })
    }

    fun unfollow(tmdbId: Int) {
        update(_followed.value.filterNot { it.tmdbId == tmdbId })
        clearReminderState(tmdbId)
    }

    fun unfollowAll() {
        val ids = _followed.value.map(FollowedSeries::tmdbId)
        ids.forEach(::clearReminderState)
        update(emptyList())
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
            val normalized =
                series
                    .distinctBy(FollowedSeries::tmdbId)
                    .sortedBy(FollowedSeries::title)
            val restoredIds = normalized.map(FollowedSeries::tmdbId).toSet()
            val removedIds =
                _followed.value
                    .map(FollowedSeries::tmdbId)
                    .filterNot(restoredIds::contains)
            removedIds.forEach(::clearReminderState)
            update(normalized)
        }

    fun setReminder(
        tmdbId: Int,
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ) {
        val previous = _followed.value.firstOrNull { it.tmdbId == tmdbId } ?: return
        if (
            previous.reminderMode == CalendarReminderMode.WhenAvailable ||
            mode == CalendarReminderMode.WhenAvailable
        ) {
            // Clear before publishing the new flow value: the Android observer can enqueue an
            // immediate worker as soon as update() emits. Broadcast dedup keys remain intact,
            // so changing reminder modes cannot re-notify an episode that just aired.
            clearAvailabilityState(tmdbId)
        }
        update(
            _followed.value.map {
                if (it.tmdbId == tmdbId) {
                    it.copy(
                        reminderMode = mode,
                        remindBeforeMinutes = beforeMinutes.coerceIn(0, 24 * 60),
                    )
                } else {
                    it
                }
            },
        )
    }

    fun setReminderForAll(
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ) {
        val normalizedBefore = beforeMinutes.coerceIn(0, 24 * 60)
        _followed.value.forEach { previous ->
            if (
                previous.reminderMode == CalendarReminderMode.WhenAvailable ||
                mode == CalendarReminderMode.WhenAvailable
            ) {
                clearAvailabilityState(previous.tmdbId)
            }
        }
        update(
            _followed.value.map {
                it.copy(reminderMode = mode, remindBeforeMinutes = normalizedBefore)
            },
        )
    }

    private fun clearAvailabilityState(tmdbId: Int) {
        settings.keys
            .filter { key ->
                key == "calendar.reminder.available.baseline.$tmdbId" ||
                    key.startsWith("calendar.reminder.available.seen.$tmdbId.")
            }.forEach(settings::remove)
    }

    private fun clearReminderState(tmdbId: Int) {
        clearAvailabilityState(tmdbId)
        val marker = ".$tmdbId."
        settings.keys
            .filter { key ->
                key.startsWith("calendar.reminder.sent.") && marker in key
            }.forEach(settings::remove)
    }

    private fun update(value: List<FollowedSeries>) {
        _followed.value = value
        if (value.isEmpty()) settings.remove(KEY) else settings.putString(KEY, json.encodeToString(serializer, value))
    }

    private fun read(): List<FollowedSeries> =
        settings
            .getStringOrNull(KEY)
            ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
            ?.filter { it.tmdbId > 0 }
            ?.distinctBy(FollowedSeries::tmdbId)
            .orEmpty()

    private companion object {
        const val KEY = "calendar.followed.series.v1"
        const val MAX_FOLLOWED_SERIES = 500
        const val KEY_PLATFORM_FILTER = "calendar.filter.platform.v1"
        const val KEY_CONTENT_FILTER = "calendar.filter.content.v1"
    }
}
