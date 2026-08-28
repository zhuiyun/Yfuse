package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
enum class CalendarReminderMode { Off, AtBroadcast, BeforeAndAtBroadcast, WhenAvailable }

@Serializable
enum class CalendarTrackingOrigin { Manual, LibraryAuto }

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
    val trackingOrigin: CalendarTrackingOrigin = CalendarTrackingOrigin.Manual,
)

internal data class CalendarAutoFollowReconcileResult(
    val added: Int,
    val removed: Int,
    val updated: Int,
)

class CalendarFollowStore(
    private val settings: Settings,
) {
    private val stateLock = Any()
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

    fun isFollowing(tmdbId: Int): Boolean = synchronized(stateLock) {
        _followed.value.any { it.tmdbId == tmdbId }
    }

    internal fun automaticFollowRefreshDue(
        nowEpochMs: Long,
        maxAgeMs: Long,
    ): Boolean = synchronized(stateLock) {
        val previous = settings.getLongOrNull(KEY_AUTO_REFRESH_EPOCH_MS) ?: return@synchronized true
        nowEpochMs - previous !in 0 until maxAgeMs
    }

    internal fun markAutomaticFollowRefresh(epochMs: Long) {
        synchronized(stateLock) {
            settings.putLong(KEY_AUTO_REFRESH_EPOCH_MS, epochMs)
        }
    }

    fun follow(series: FollowedSeries) {
        require(series.tmdbId > 0)
        synchronized(stateLock) {
            writeAutoDismissalsLocked(readAutoDismissalsLocked() - series.tmdbId)
            updateLocked(
                (_followed.value.filterNot { it.tmdbId == series.tmdbId } +
                    series.copy(trackingOrigin = CalendarTrackingOrigin.Manual))
                    .sortedBy(FollowedSeries::title),
            )
        }
    }

    /** Adds exact library identities as durable automatic follows without overriding user choices. */
    fun autoFollowLibrarySeries(series: List<FollowedSeries>): Int =
        reconcileAutoFollowLibrarySeries(series, authoritativeServerIds = emptySet()).added

    /**
     * Reconciles successful library scans without deleting entries from a server that failed.
     * Manual follows always win; automatic rows retain the user's reminder choice while their
     * title/poster/server binding is refreshed from the latest exact identity.
     */
    internal fun reconcileAutoFollowLibrarySeries(
        series: List<FollowedSeries>,
        authoritativeServerIds: Set<String>,
    ): CalendarAutoFollowReconcileResult = synchronized(stateLock) {
        val candidates =
            series
                .asSequence()
                .filter { it.tmdbId > 0 && it.title.isNotBlank() }
                .distinctBy(FollowedSeries::tmdbId)
                .associateBy(FollowedSeries::tmdbId)
        val dismissed = readAutoDismissalsLocked()
        var updated = 0
        val retained =
            _followed.value.mapNotNull { existing ->
                val candidate = candidates[existing.tmdbId]
                when {
                    existing.trackingOrigin == CalendarTrackingOrigin.Manual -> existing
                    candidate != null -> {
                        val refreshed =
                            existing.copy(
                                title = candidate.title,
                                year = candidate.year ?: existing.year,
                                posterPath = candidate.posterPath ?: existing.posterPath,
                                serverId = candidate.serverId ?: existing.serverId,
                                seriesItemId = candidate.seriesItemId ?: existing.seriesItemId,
                            )
                        if (refreshed != existing) updated += 1
                        refreshed
                    }
                    existing.serverId != null && existing.serverId in authoritativeServerIds -> null
                    else -> existing
                }
            }
        val retainedIds = retained.map(FollowedSeries::tmdbId).toSet()
        val additions =
            candidates.values
                .asSequence()
                .filterNot { it.tmdbId in dismissed || it.tmdbId in retainedIds }
                .take((MAX_FOLLOWED_SERIES - retainedIds.size).coerceAtLeast(0))
                .map {
                    it.copy(
                        reminderMode = CalendarReminderMode.WhenAvailable,
                        trackingOrigin = CalendarTrackingOrigin.LibraryAuto,
                    )
                }.toList()
        val removedIds =
            _followed.value
                .asSequence()
                .filter { it.trackingOrigin == CalendarTrackingOrigin.LibraryAuto }
                .map(FollowedSeries::tmdbId)
                .filterNot(retainedIds::contains)
                .toList()
        removedIds.forEach(::clearReminderStateLocked)
        val next = (retained + additions).sortedBy(FollowedSeries::title)
        if (next != _followed.value) updateLocked(next)
        CalendarAutoFollowReconcileResult(
            added = additions.size,
            removed = removedIds.size,
            updated = updated,
        )
    }

    fun unfollow(tmdbId: Int) {
        synchronized(stateLock) {
            if (_followed.value.any { it.tmdbId == tmdbId }) {
                writeAutoDismissalsLocked(readAutoDismissalsLocked() + tmdbId)
            }
            updateLocked(_followed.value.filterNot { it.tmdbId == tmdbId })
            clearReminderStateLocked(tmdbId)
        }
    }

    fun unfollowAll() {
        synchronized(stateLock) {
            val ids = _followed.value.map(FollowedSeries::tmdbId)
            writeAutoDismissalsLocked(readAutoDismissalsLocked() + ids)
            ids.forEach(::clearReminderStateLocked)
            updateLocked(emptyList())
        }
    }

    fun replaceFromSync(series: List<FollowedSeries>): Result<Unit> =
        runCatching {
            synchronized(stateLock) {
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
                writeAutoDismissalsLocked(
                    readAutoDismissalsLocked() - normalized.map(FollowedSeries::tmdbId).toSet(),
                )
                val restoredIds = normalized.map(FollowedSeries::tmdbId).toSet()
                val removedIds =
                    _followed.value
                        .map(FollowedSeries::tmdbId)
                        .filterNot(restoredIds::contains)
                removedIds.forEach(::clearReminderStateLocked)
                updateLocked(normalized)
            }
        }

    fun setReminder(
        tmdbId: Int,
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ) {
        synchronized(stateLock) {
            val previous = _followed.value.firstOrNull { it.tmdbId == tmdbId } ?: return
            if (
                previous.reminderMode == CalendarReminderMode.WhenAvailable ||
                mode == CalendarReminderMode.WhenAvailable
            ) {
                // Clear before publishing the new flow value: the Android observer can enqueue an
                // immediate worker as soon as update() emits. Broadcast dedup keys remain intact,
                // so changing reminder modes cannot re-notify an episode that just aired.
                clearAvailabilityStateLocked(tmdbId)
            }
            updateLocked(
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
    }

    fun setReminderForAll(
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ) {
        synchronized(stateLock) {
            val normalizedBefore = beforeMinutes.coerceIn(0, 24 * 60)
            _followed.value.forEach { previous ->
                if (
                    previous.reminderMode == CalendarReminderMode.WhenAvailable ||
                    mode == CalendarReminderMode.WhenAvailable
                ) {
                    clearAvailabilityStateLocked(previous.tmdbId)
                }
            }
            updateLocked(
                _followed.value.map {
                    it.copy(reminderMode = mode, remindBeforeMinutes = normalizedBefore)
                },
            )
        }
    }

    private fun clearAvailabilityStateLocked(tmdbId: Int) {
        settings.keys
            .filter { key ->
                key == "calendar.reminder.available.baseline.$tmdbId" ||
                    key.startsWith("calendar.reminder.available.seen.$tmdbId.")
            }.forEach(settings::remove)
    }

    private fun clearReminderStateLocked(tmdbId: Int) {
        clearAvailabilityStateLocked(tmdbId)
        val marker = ".$tmdbId."
        settings.keys
            .filter { key ->
                key.startsWith("calendar.reminder.sent.") && marker in key
            }.forEach(settings::remove)
    }

    private fun readAutoDismissalsLocked(): Set<Int> =
        settings
            .getStringOrNull(KEY_AUTO_DISMISSALS)
            ?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(Int.serializer()), raw).toSet() }.getOrNull()
            }.orEmpty()

    private fun writeAutoDismissalsLocked(value: Set<Int>) {
        val normalized = value.filter { it > 0 }.distinct().take(MAX_AUTO_DISMISSALS)
        if (normalized.isEmpty()) {
            settings.remove(KEY_AUTO_DISMISSALS)
        } else {
            settings.putString(
                KEY_AUTO_DISMISSALS,
                json.encodeToString(ListSerializer(Int.serializer()), normalized),
            )
        }
    }

    private fun updateLocked(value: List<FollowedSeries>) {
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
        const val KEY_AUTO_DISMISSALS = "calendar.followed.auto.dismissed.v1"
        const val MAX_AUTO_DISMISSALS = 1_000
        const val KEY_AUTO_REFRESH_EPOCH_MS = "calendar.followed.auto.refreshed_at.v1"
    }
}
