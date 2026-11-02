package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.data.TheOtherDbRepository
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.matchOnlineSkipSegments
import com.yfuse.core.network.TheOtherDbQuery
import com.yfuse.core.network.TheOtherDbVersion

/** Episode-owned TMDB ids are never mistaken for the parent show's TMDB id. */
internal fun PlayerMediaItem.onlineSkipQuery(): TheOtherDbQuery? {
    val tv =
        seriesId != null ||
            seriesKey != null ||
            episodeNumber != null ||
            mediaType.equals("Episode", true) ||
            mediaType.equals("tv", true)
    if (tv) {
        val season = seasonNumber?.takeIf { it >= 0 } ?: return null
        val episode = episodeNumber?.takeIf { it > 0 } ?: return null
        val seriesTmdb =
            seriesKey
                ?.removePrefix("provider:")
                ?.let { Regex("tmdb:(?:tv:)?([0-9]+)").matchEntire(it)?.groupValues?.get(1) }
        val coordinate = Regex("tmdb:([0-9]+)/s${season}e$episode")
        val tmdb =
            seriesTmdb ?: (matchKeys + watchKey).firstNotNullOfOrNull {
                coordinate.matchEntire(it)?.groupValues?.get(1)
            }
        return tmdb?.toLongOrNull()?.takeIf { it > 0L }?.let { TheOtherDbQuery(it, season, episode) }
    }
    // Files without catalog identity remain manual-only; do not guess by a filename/title.
    val tmdb =
        providerIds.entries.firstOrNull { it.key.equals("tmdb", true) }?.value
            ?: (matchKeys + watchKey).firstNotNullOfOrNull {
                Regex("tmdb:(?:movie:)?([0-9]+)").matchEntire(it)?.groupValues?.get(1)
            }
    return tmdb?.toLongOrNull()?.takeIf { it > 0L }?.let { TheOtherDbQuery(it) }
}

internal data class OnlineSkipMarkers(
    val segments: List<PlaybackSegment> = emptyList(),
    val status: String = "等待在线标记",
)

/** Re-keying clears results synchronously and cancels the previous source's in-flight request. */
@Composable
internal fun rememberOnlineSkipMarkers(
    item: PlayerMediaItem?,
    durationMs: Long,
    enabled: Boolean,
    repository: TheOtherDbRepository,
): OnlineSkipMarkers {
    val query = item?.onlineSkipQuery()
    var versions by remember(item?.id, item?.serverId, item?.versionId, item?.url, query, enabled) {
        mutableStateOf<List<TheOtherDbVersion>?>(null)
    }
    var finished by remember(item?.id, item?.serverId, item?.versionId, item?.url, query, enabled) {
        mutableStateOf(false)
    }
    LaunchedEffect(item?.id, item?.serverId, item?.versionId, item?.url, query, enabled, repository) {
        if (enabled && query != null) {
            versions = repository.versions(query)
            finished = true
        }
    }
    val segments =
        remember(versions, query, durationMs, enabled) {
            if (enabled &&
                query != null
            ) {
                matchOnlineSkipSegments(versions.orEmpty(), query, durationMs)
            } else {
                emptyList()
            }
        }
    val status =
        when {
            !enabled -> "在线获取已关闭"
            query == null -> "缺少 TMDB 或季集信息，可手动设置"
            !finished -> "正在获取 TheOtherDB 标记"
            versions == null -> "在线获取失败，仍可手动设置"
            durationMs <= 0L -> "等待视频时长以匹配版本"
            segments.isEmpty() -> "暂无匹配此版本的在线标记"
            else -> "已匹配 TheOtherDB 标记"
        }
    return OnlineSkipMarkers(segments, status)
}
