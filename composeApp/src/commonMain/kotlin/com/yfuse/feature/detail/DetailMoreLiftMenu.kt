package com.yfuse.feature.detail

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.feature.library.favoriteLiftAction
import com.yfuse.feature.library.playedLiftAction

/**
 * What a finger held on 详情's 更多 can slide to: the actions people reach for without reading
 * the sheet, and 全部操作 for the rest. A tap still opens the whole 更多操作 sheet, and so does
 * letting go back on the button.
 *
 * @param onWatchTogether null when a room cannot be started from here.
 */
internal fun detailMoreLiftMenu(
    title: String,
    played: Boolean,
    favoriteAvailable: Boolean,
    favorite: Boolean,
    watchLater: Boolean,
    onTogglePlayed: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onDownload: () -> Unit,
    onWatchTogether: (() -> Unit)?,
    onAllActions: () -> Unit,
): LiftMenu =
    LiftMenu(
        title = title,
        onOpen = onAllActions,
        anchored = true,
        sections =
            listOf(
                listOf(playedLiftAction(played) { onTogglePlayed() }),
                listOfNotNull(
                    if (favoriteAvailable) favoriteLiftAction(favorite) { onToggleFavorite() } else null,
                    ItemAction(
                        label = if (watchLater) "移出稍后看" else "稍后看",
                        icon = if (watchLater) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                        onSelect = onToggleWatchLater,
                    ),
                ),
                listOfNotNull(
                    ItemAction(label = "下载…", icon = AppIcons.Download, onSelect = onDownload),
                    onWatchTogether?.let {
                        ItemAction(label = "一起看…", icon = AppIcons.Chat, onSelect = it)
                    },
                ),
                listOf(ItemAction(label = "全部操作…", icon = AppIcons.More, onSelect = onAllActions)),
            ),
    )
