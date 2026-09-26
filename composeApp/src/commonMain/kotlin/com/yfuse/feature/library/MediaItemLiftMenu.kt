package com.yfuse.feature.library

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.heroDurationLabel
import com.yfuse.core.designsystem.mediaRatingLabel
import com.yfuse.core.model.MediaItem

/** Emby counts time in 100ns ticks. */
private const val TICKS_PER_SECOND = 10_000_000L

/**
 * The 浮起菜单 for one library title, wherever its poster is: 首页's shelves, a library grid,
 * 统一媒体库. The card says what the title is and how far in it is; [onOpen] is what releasing on
 * it does. [actions] arrive in the groups the menu separates.
 */
internal fun mediaItemLiftMenu(
    item: MediaItem,
    backdropUrl: String?,
    onOpen: () -> Unit,
    actions: List<List<ItemAction>>,
): LiftMenu =
    LiftMenu(
        title = item.title,
        meta = item.liftMeta(),
        backdropUrls = listOfNotNull(backdropUrl),
        progress = item.liftProgress(),
        progressLabel = item.liftProgressLabel(),
        onOpen = onOpen,
        sections = actions,
    )

/** "第 2 季 · 第 3 集 · 45分钟 · ★ 8.4", or the year where there is no episode line. */
internal fun MediaItem.liftMeta(): String? =
    listOfNotNull(
        subtitle?.takeIf { it.isNotBlank() } ?: year?.toString(),
        heroDurationLabel(runtimeMinutes),
        mediaRatingLabel(communityRating)?.let { "★ $it" },
    ).joinToString(" · ").ifBlank { null }

internal fun MediaItem.liftProgress(): Float? =
    if (played) {
        1f
    } else {
        playedPercentage?.takeIf { it > 0.0 }?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
    }

/** "剩余 42分钟" for a title stopped part-way; null when unstarted, finished, or of unknown length. */
internal fun MediaItem.liftRemainingLabel(): String? {
    if (played) return null
    val seconds = resumePositionTicks?.takeIf { it > 0L }?.div(TICKS_PER_SECOND) ?: return null
    val left = runtimeMinutes?.let { it - (seconds / 60L).toInt() }?.takeIf { it > 0 } ?: return null
    return heroDurationLabel(left)?.let { "剩余 $it" }
}

/** "看到 1:06:10 · 剩余 42分钟", "已看完", or null for a title not yet started. */
internal fun MediaItem.liftProgressLabel(): String? {
    if (played) return "已看完"
    val seconds = resumePositionTicks?.takeIf { it > 0L }?.div(TICKS_PER_SECOND) ?: return null
    return listOfNotNull("看到 ${liftClock(seconds)}", liftRemainingLabel()).joinToString(" · ")
}

/** 1:06:10, or 42:05 under an hour. */
internal fun liftClock(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val tail = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    return if (hours > 0L) "$hours:$tail" else "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** 收藏 / 取消收藏, named for what it will do rather than what the title is now. */
internal fun favoriteLiftAction(
    favorite: Boolean,
    onSet: (Boolean) -> Unit,
): ItemAction =
    ItemAction(
        label = if (favorite) "取消收藏" else "收藏",
        icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
        onSelect = { onSet(!favorite) },
    )

/** 标记为已看 / 未看. */
internal fun playedLiftAction(
    played: Boolean,
    onSet: (Boolean) -> Unit,
): ItemAction =
    ItemAction(
        label = if (played) "标记为未看" else "标记为已看",
        icon = if (played) AppIcons.Eye else AppIcons.Check,
        onSelect = { onSet(!played) },
    )

/**
 * 浮起菜单 on the 媒体库 tab's own rails, 播放记录 and each library's shelf. Its store plays a
 * title (through 详情, which picks the file) and writes the favourite; it owns no watched flag.
 */
internal fun libraryHomeLiftMenu(
    item: MediaItem,
    backdropUrl: String?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: (Boolean) -> Unit,
): LiftMenu {
    val resumable = (item.resumePositionTicks ?: 0L) > 0L && !item.played
    return mediaItemLiftMenu(
        item = item,
        backdropUrl = backdropUrl,
        onOpen = onOpen,
        actions =
            listOf(
                listOf(
                    ItemAction(
                        label = if (resumable) "继续播放" else "播放",
                        icon = AppIcons.Play,
                        detail = item.liftRemainingLabel(),
                        leavesPage = true,
                        onSelect = onPlay,
                    ),
                ),
                listOf(favoriteLiftAction(item.isFavorite, onFavorite)),
            ),
    )
}
