package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
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
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val serializer = ListSerializer(AiringEpisode.serializer())

    /**
     * The stored schedule, or null when there is none, it was fetched on another day, or it
     * covers a different window. Same-day is the whole validity rule — a schedule does not
     * become wrong during a day, and by the next one there are new episodes to place.
     */
    fun read(
        today: String,
        window: String,
    ): List<AiringEpisode>? {
        if (settings.getStringOrNull(KEY_FETCHED_ON) != today) return null
        if (settings.getStringOrNull(KEY_WINDOW) != window) return null
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
        if (episodes.isEmpty()) {
            clear()
            return
        }
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

    fun clear() {
        settings.remove(KEY_EPISODES)
        settings.remove(KEY_FETCHED_ON)
        settings.remove(KEY_WINDOW)
    }
}
