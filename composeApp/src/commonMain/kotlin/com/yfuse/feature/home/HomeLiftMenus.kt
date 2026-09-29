package com.yfuse.feature.home

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.SelectionAction
import com.yfuse.core.designsystem.mediaRatingLabel
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.PosterShareCard
import com.yfuse.feature.library.detailsLiftAction
import com.yfuse.feature.library.favoriteLiftAction
import com.yfuse.feature.library.liftRemainingLabel
import com.yfuse.feature.library.mediaItemLiftMenu
import com.yfuse.feature.library.playedLiftAction
import com.yfuse.feature.library.posterShareCard
import com.yfuse.feature.library.shareLiftAction

/**
 * 浮起菜单 on a 继续观看, 下一集 or 我的收藏 card. It replaces a sheet that offered one row, 查看详情
 * — the same thing the tap then did — because 首页's store could open a library title and nothing
 * else. It can play one from where it was left, or from the start, and write the two flags people
 * change without opening a title.
 *
 * [inResume] for a card on 继续观看 itself, which can also be taken off that shelf. [playsOnTap]
 * for a card whose tap already resumes it (继续观看, 下一集): 查看详情 leads its menu in place of
 * 播放, since the menu is now the way to the title's page. [undoWatched] where the page has a toast
 * to offer 撤销 on: 标记为已看 then waits for it before anything is written (see
 * [HomeIntent.SetEntryPlayed]); the television has none, and marks at once.
 */
internal fun HomeResumeEntry.homeLiftMenu(
    onIntent: (HomeIntent) -> Unit,
    inResume: Boolean = false,
    playsOnTap: Boolean = false,
    undoWatched: Boolean = false,
    onShare: (() -> Unit)? = null,
): LiftMenu {
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
                    if (playsOnTap) {
                        detailsLiftAction { onIntent(HomeIntent.OpenResume(entry)) }
                    } else {
                        ItemAction(
                            label = if (resumable) "继续播放" else "播放",
                            icon = AppIcons.Play,
                            detail = if (resumable) item.liftRemainingLabel() else null,
                            leavesPage = true,
                            onSelect = { onIntent(HomeIntent.PlayEntry(entry)) },
                        )
                    },
                    if (resumable) {
                        ItemAction(
                            label = "从头播放",
                            icon = AppIcons.Refresh,
                            leavesPage = true,
                            onSelect = { onIntent(HomeIntent.PlayEntry(entry, fromStart = true)) },
                        )
                    } else {
                        null
                    },
                ),
                listOfNotNull(
                    playedLiftAction(item.played) {
                        onIntent(HomeIntent.SetEntryPlayed(entry, it, undoable = undoWatched))
                    },
                    if (inResume) {
                        ItemAction(
                            label = "从继续观看移除",
                            icon = AppIcons.Close,
                            destructive = true,
                            undoable = true,
                            onSelect = { onIntent(HomeIntent.RemoveFromResume(entry)) },
                        )
                    } else {
                        null
                    },
                ),
                listOfNotNull(
                    favoriteLiftAction(item.isFavorite) { onIntent(HomeIntent.SetEntryFavorite(entry, it)) },
                    ItemAction(
                        label = "稍后看",
                        icon = AppIcons.Bookmark,
                        onSelect = { onIntent(HomeIntent.AddEntryToWatchLater(entry)) },
                    ),
                    onShare?.let(::shareLiftAction),
                ),
            ),
    )
}

/**
 * 编辑's bar on 继续观看's 全部 page (I-21): the lift's 标记为已看 and 从继续观看移除, for every card in
 * [selection] at once. Both are held back for the toast's one 撤销, as the lift holds one card's.
 * 标记已看 passes over a card that is watched already, and dims when that is every card.
 */
internal fun resumeSelectionActions(
    selection: List<HomeResumeEntry>,
    onIntent: (HomeIntent) -> Unit,
): List<SelectionAction> {
    val unwatched = selection.filterNot { it.item.played }
    return listOf(
        SelectionAction("标记已看", enabled = unwatched.isNotEmpty(), onClickLabel = "将所选标记为已看") {
            onIntent(HomeIntent.MarkEntriesWatched(unwatched))
        },
        SelectionAction(
            "移除",
            enabled = selection.isNotEmpty(),
            destructive = true,
            onClickLabel = "从继续观看移除所选",
        ) { onIntent(HomeIntent.RemoveEntriesFromResume(selection)) },
    )
}

/**
 * 浮起菜单 on a TMDB pick — 首页's shelves and their 查看全部 page. Both rows go through the same
 * library match the hero uses: 播放 opens the title's info instead when the library lacks it,
 * and 收藏 says so.
 */
internal fun TmdbItem.homeLiftMenu(
    onShare: (() -> Unit)? = null,
    onIntent: (HomeIntent) -> Unit,
): LiftMenu {
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
                    ItemAction(
                        label = "播放",
                        icon = AppIcons.Play,
                        leavesPage = true,
                        onSelect = { onIntent(HomeIntent.Play(item)) },
                    ),
                    ItemAction(
                        label = "加入收藏",
                        icon = AppIcons.Heart,
                        onSelect = { onIntent(HomeIntent.Favorite(item)) },
                    ),
                ),
                listOfNotNull(onShare?.let(::shareLiftAction)),
            ),
    )
}

/** A library card as a share card; the poster is read on this device only, to paint it. */
internal fun HomeResumeEntry.shareCard(): PosterShareCard =
    item.posterShareCard(EmbyImages.poster(server.baseUrl, item, accessToken = server.accessToken))

/** A TMDB pick as a share card: its own id is the public one. */
internal fun TmdbItem.shareCard(): PosterShareCard =
    PosterShareCard(
        title = title,
        year = year?.take(4)?.toIntOrNull(),
        rating = rating,
        posterUrl = TmdbImages.poster(posterPath),
        tmdbId = id.toString(),
        mediaType = mediaType,
    )
