package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.util.shiftIsoDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The broadcast schedule, kept for the day it was fetched.
 *
 * Only the schedule half is cached, never the library status. They age at completely
 * different rates: a broadcast date is settled days in advance and will not change while
 * the app is open, whereas "do you have this episode" changes the moment a download lands —
 * and that transition, 未入库 → 可播放, is the one the user is watching for. Caching it
 * would mean showing them yesterday's answer to the only question they came to ask.
 *
 * The schedule is expensive: two discover queries plus two requests per show. Status is
 * one request when the library holds none of the shows, which is the common case. So the
 * expensive, stable half is stored and the cheap, volatile half is always fresh.
 */
class AiringScheduleCache(
    private val settings: Settings,
) {
    private companion object {
        const val KEY_EPISODES = "calendar.schedule.episodes"
        const val KEY_FETCHED_ON = "calendar.schedule.fetchedOn"
        const val KEY_WINDOW = "calendar.schedule.window"
        const val SERIES_FALLBACK_RETENTION_DAYS = 7
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val serializer = ListSerializer(AiringEpisode.serializer())
    private var lastPrunedOn: String? = null

    /**
     * The stored schedule, or null when there is none, it was fetched on another day, or it
     * covers a different window. Same-day is the whole validity rule — a schedule does not
     * become wrong during a day, and by the next one there are new episodes to place.
     */
    fun read(
        today: String,
        window: String,
    ): List<AiringEpisode>? {
        pruneExpiredSeries(today)
        if (settings.getStringOrNull(KEY_FETCHED_ON) != today) return null
        if (settings.getStringOrNull(KEY_WINDOW) != window) return null
        return readStored()
    }

    /**
     * The most recently completed schedule, regardless of the day/window it was fetched for.
     *
     * This is only a paint cache: callers must filter it to their current window and still
     * refresh in the background. Keeping it available prevents a new day (or a transient TMDB
     * outage) from turning a calendar that was useful yesterday into a full-screen spinner.
     */
    fun readLastSuccessful(): List<AiringEpisode>? = readStored()

    private fun readStored(): List<AiringEpisode>? {
        val raw = settings.getStringOrNull(KEY_EPISODES) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure {
                clear()
                AppLog.warning(
                    category = "feature.calendar",
                    event = "schedule_cache_unreadable",
                    message = "Cached broadcast schedule could not be read and was discarded",
                    throwable = it,
                )
            }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun write(
        today: String,
        window: String,
        episodes: List<AiringEpisode>,
    ) {
        // An empty remote success is not evidence that yesterday's verified schedule became
        // invalid. Keep the last successful paint cache and let the caller surface the empty
        // refresh separately.
        if (episodes.isEmpty()) return
        runCatching {
            settings.putString(KEY_EPISODES, json.encodeToString(serializer, episodes))
            settings.putString(KEY_FETCHED_ON, today)
            settings.putString(KEY_WINDOW, window)
        }.onFailure {
            AppLog.warning(
                category = "feature.calendar",
                event = "schedule_cache_write_failed",
                message = "Broadcast schedule could not be cached",
                throwable = it,
            )
        }
    }

    fun readSeries(
        tmdbId: Int,
        today: String,
    ): List<AiringEpisode>? {
        pruneExpiredSeries(today)
        if (settings.getStringOrNull(seriesDateKey(tmdbId)) != today) return null
        return readSeriesStored(tmdbId)
    }

    /**
     * Last non-empty schedule for one series, even when it was fetched on an earlier day.
     *
     * Used only as a degraded fallback after the current refresh fails. The caller still
     * filters it to the requested date window, so stale rows cannot escape that window.
     */
    fun readSeriesLastSuccessful(tmdbId: Int): List<AiringEpisode>? = readSeriesStored(tmdbId)

    private fun readSeriesStored(tmdbId: Int): List<AiringEpisode>? {
        val raw = settings.getStringOrNull(seriesDataKey(tmdbId)) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { error ->
                clearSeries(tmdbId)
                AppLog.warning(
                    category = "feature.calendar",
                    event = "series_schedule_cache_unreadable",
                    message = "A cached series schedule was discarded",
                    throwable = error,
                    attributes = mapOf("tmdbId" to tmdbId.toString()),
                )
            }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun writeSeries(
        tmdbId: Int,
        today: String,
        episodes: List<AiringEpisode>,
    ) {
        if (tmdbId <= 0 || episodes.isEmpty()) return
        runCatching {
            settings.putString(seriesDataKey(tmdbId), json.encodeToString(serializer, episodes))
            settings.putString(seriesDateKey(tmdbId), today)
        }.onFailure { error ->
            AppLog.warning(
                category = "feature.calendar",
                event = "series_schedule_cache_write_failed",
                message = "A series schedule could not be cached",
                throwable = error,
                attributes = mapOf("tmdbId" to tmdbId.toString()),
            )
        }
    }

    private fun pruneExpiredSeries(today: String) {
        if (lastPrunedOn == today) return
        lastPrunedOn = today
        val oldestRetainedDate = shiftIsoDate(today, -SERIES_FALLBACK_RETENTION_DAYS)
        settings.keys
            .filter { it.startsWith("calendar.series.") && it.endsWith(".fetchedOn") }
            .forEach { dateKey ->
                val fetchedOn = settings.getStringOrNull(dateKey) ?: return@forEach
                if (fetchedOn >= oldestRetainedDate) return@forEach
                val tmdbId =
                    dateKey
                        .removePrefix("calendar.series.")
                        .removeSuffix(".fetchedOn")
                        .toIntOrNull()
                        ?: return@forEach
                clearSeries(tmdbId)
            }
    }

    fun clearSeries(tmdbId: Int) {
        settings.remove(seriesDataKey(tmdbId))
        settings.remove(seriesDateKey(tmdbId))
    }

    private fun seriesDataKey(tmdbId: Int) = "calendar.series.$tmdbId.episodes"

    private fun seriesDateKey(tmdbId: Int) = "calendar.series.$tmdbId.fetchedOn"

    fun clear() {
        settings.remove(KEY_EPISODES)
        settings.remove(KEY_FETCHED_ON)
        settings.remove(KEY_WINDOW)
    }

    fun diagnostics(): AiringScheduleCacheDiagnostics =
        AiringScheduleCacheDiagnostics(
            fetchedOn = settings.getStringOrNull(KEY_FETCHED_ON),
            window = settings.getStringOrNull(KEY_WINDOW),
            entryCount = readStored()?.size ?: 0,
        )
}

data class AiringScheduleCacheDiagnostics(
    val fetchedOn: String?,
    val window: String?,
    val entryCount: Int,
)
