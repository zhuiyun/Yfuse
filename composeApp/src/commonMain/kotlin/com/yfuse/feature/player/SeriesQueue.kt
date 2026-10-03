package com.yfuse.feature.player

import com.yfuse.core.model.Episode
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.isShortRuntime

/** How close to an episode's end the player refreshes its series queue from the server. */
private const val QUEUE_REFRESH_NEAR_END_MS = 5 * 60_000L

/** A short episode refreshes the queue only within this many episodes of the queue's end. */
private const val SHORT_EPISODE_REFRESH_TAIL = 3

/**
 * Whether the series queue is refreshed at this sample: within five minutes of the episode's end.
 * Every 短剧 episode lies inside that window, so a binge re-fetched the whole season with its media
 * sources every five minutes. A short episode refreshes only near the end of the queue, which is
 * where an episode published since the player opened would otherwise be missed.
 */
internal fun queueRefreshDue(
    remainingMs: Long,
    durationMs: Long,
    itemsAfterCurrent: Int,
): Boolean =
    remainingMs in 1L..QUEUE_REFRESH_NEAR_END_MS &&
        (isShortRuntime(durationMs) != true || itemsAfterCurrent <= SHORT_EPISODE_REFRESH_TAIL)

/**
 * The episodes a series queue plays through. Not those the server only knows of — missing or
 * unaired, with nothing to play — and not specials, whose season 0 sorts before season 1 and came
 * up as 上一集 of the first episode, unless a special is what is playing. [currentId] always stays.
 */
internal fun List<Episode>.queueEpisodes(currentId: String?): List<Episode> {
    val playingSpecial = firstOrNull { it.id == currentId }?.seasonNumber == 0
    return filter { episode ->
        episode.id == currentId || (!episode.missing && (playingSpecial || episode.seasonNumber != 0))
    }
}

/** Where an item's fetched media segments are kept: by server and item, as ids repeat across servers. */
internal fun mediaSegmentKey(
    serverId: String?,
    itemId: String,
): String = "${serverId.orEmpty()}/$itemId"

/** [this] with the server's media segments from [cache], where it has none of its own. */
internal fun PlayerMediaItem.withMediaSegments(cache: Map<String, List<PlaybackSegment>>): PlayerMediaItem {
    if (playbackSegments.isNotEmpty()) return this
    val fetched = cache[mediaSegmentKey(serverId, id)]?.takeIf { it.isNotEmpty() } ?: return this
    return copy(playbackSegments = fetched)
}
