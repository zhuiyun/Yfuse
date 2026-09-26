package com.yfuse.feature.home

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LiftMenuAction
import com.yfuse.core.designsystem.mediaRatingLabel
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.library.favoriteLiftAction
import com.yfuse.feature.library.liftRemainingLabel
import com.yfuse.feature.library.mediaItemLiftMenu
import com.yfuse.feature.library.playedLiftAction

/**
 * 浮起菜单 on a 继续观看 or 我的收藏 card. It replaces a sheet that offered one row, 查看详情 —
 * the same thing the tap already did — because 首页's store could open a library title and
 * nothing else. It can now play one from where it was left, or from the start, and write the
 * two flags people change without opening a title.
 */
internal fun HomeResumeEntry.homeLiftMenu(onIntent: (HomeIntent) -> Unit): LiftMenu {
    val entry = this
    val item = entry.item
    // A series resolves its own next episode; only a single title has a place to go back to.
    val resumable = item.type != "Series" && (item.resumePositionTicks ?: 0L) > 0L
    return mediaItemLiftMenu(
        item = item,
        backdropUrl = EmbyImages.backdrop(entry.server.baseUrl, item, accessToken = entry.server.accessToken),
        onOpen = { onIntent(HomeIntent.OpenResume(entry)) },
        actions =
            listOf(
                listOfNotNull(
                    LiftMenuAction(
                        label = if (resumable) "继续播放" else "播放",
                        icon = AppIcons.Play,
                        detail = if (resumable) item.liftRemainingLabel() else null,
                        leavesPage = true,
                        onSelect = { onIntent(HomeIntent.PlayEntry(entry)) },
                    ),
                    if (resumable) {
                        LiftMenuAction(
                            label = "从头播放",
                            icon = AppIcons.Refresh,
                            leavesPage = true,
                            onSelect = { onIntent(HomeIntent.PlayEntry(entry, fromStart = true)) },
                        )
                    } else {
                        null
                    },
                ),
                listOf(
                    playedLiftAction(item.played) { onIntent(HomeIntent.SetEntryPlayed(entry, it)) },
                    favoriteLiftAction(item.isFavorite) { onIntent(HomeIntent.SetEntryFavorite(entry, it)) },
                ),
            ),
    )
}

/**
 * 浮起菜单 on a TMDB pick — 首页's shelves and their 查看全部 page. Both rows go through the same
 * library match the hero uses: 播放 opens the title's info instead when the library lacks it,
 * and 收藏 says so.
 */
internal fun TmdbItem.homeLiftMenu(onIntent: (HomeIntent) -> Unit): LiftMenu {
    val item = this
    return LiftMenu(
        title = item.title,
        meta =
            listOfNotNull(
                "TMDB",
                item.year,
                mediaRatingLabel(item.rating)?.let { "★ $it" },
            ).joinToString(" · "),
        backdropUrls =
            listOfNotNull(
                TmdbImages.backdrop(item.backdropPath, "w780"),
                TmdbImages.media(item.backdropPath, "w780"),
            ),
        onOpen = { onIntent(HomeIntent.Open(item)) },
        sections =
            listOf(
                listOf(
                    LiftMenuAction(
                        label = "播放",
                        icon = AppIcons.Play,
                        leavesPage = true,
                        onSelect = { onIntent(HomeIntent.Play(item)) },
                    ),
                    LiftMenuAction(
                        label = "加入收藏",
                        icon = AppIcons.Heart,
                        onSelect = { onIntent(HomeIntent.Favorite(item)) },
                    ),
                ),
            ),
    )
}
