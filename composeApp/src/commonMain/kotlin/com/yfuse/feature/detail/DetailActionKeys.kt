package com.yfuse.feature.detail

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.capabilities
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia

/** The ids of the page's own keys, for a key from elsewhere to be placed beside one of them. */
internal object DetailActionKeyIds {
    const val FAVORITE = "favorite"
    const val WATCH_LATER = "watchLater"
    const val PLAYED = "played"
    const val DOWNLOAD = "download"

    /** 预告片, last in the row: see DetailTrailers. */
    const val TRAILER = "trailer"
}

/**
 * What decides which keys stand under 播放 and how each reads. A key with nothing to do is left out
 * rather than shown dead: with no server to write to (an offline copy, an external item) there is no
 * 收藏, 稍后看 or 已看; a server that keeps no favourites (Plex) has no 收藏; and a 播放 target with no
 * file of its own — a season's page — has no 下载.
 */
internal data class DetailActionFacts(
    /** This title's lists and watched state can be written to a server. */
    val serverActions: Boolean,
    /** That server keeps favourites at all. */
    val favoriteAvailable: Boolean,
    val favorite: Boolean,
    val watchLater: Boolean,
    /** 稍后看 is being written; its key waits instead of taking a second tap. */
    val watchLaterMutating: Boolean,
    val played: Boolean,
    /** Null while 播放's target resolves; then whether that target is a file that can be downloaded. */
    val downloadable: Boolean?,
    /** That file's copy on this device, finished or on its way. */
    val download: DownloadStatus? = null,
)

/**
 * The keys under 播放 in reading order: 收藏, 稍后看, 已看, 下载.
 *
 * The same for a film, an episode and a show, with two differences the callers carry: 已看 on a show
 * is every episode's history in one tap, so [onTogglePlayed] asks first there, as 更多 does; and 下载
 * downloads what 播放 opens — for a show, its next episode — in a sheet that offers the season whole.
 */
internal fun detailActionKeys(
    facts: DetailActionFacts,
    onToggleFavorite: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
): List<DetailActionKey> =
    buildList {
        if (facts.serverActions && facts.favoriteAvailable) {
            add(
                DetailActionKey(
                    id = DetailActionKeyIds.FAVORITE,
                    icon = AppIcons.Heart,
                    checkedIcon = AppIcons.HeartFilled,
                    label = "收藏",
                    // 更多 also holds 个人收藏, so the key says whose list it is.
                    description = "服务器收藏",
                    checked = facts.favorite,
                    stateDescription = if (facts.favorite) "已收藏" else "未收藏",
                    onClick = onToggleFavorite,
                ),
            )
        }
        if (facts.serverActions) {
            add(
                DetailActionKey(
                    id = DetailActionKeyIds.WATCH_LATER,
                    icon = AppIcons.Bookmark,
                    checkedIcon = AppIcons.BookmarkFilled,
                    label = "稍后看",
                    description = "稍后观看",
                    checked = facts.watchLater,
                    stateDescription =
                        when {
                            facts.watchLaterMutating -> "正在同步"
                            facts.watchLater -> "已加入"
                            else -> "未加入"
                        },
                    busy = facts.watchLaterMutating,
                    onClick = onToggleWatchLater,
                ),
            )
            add(
                DetailActionKey(
                    id = DetailActionKeyIds.PLAYED,
                    icon = AppIcons.Check,
                    label = "已看",
                    checked = facts.played,
                    stateDescription = if (facts.played) "已看过" else "未看过",
                    onClick = onTogglePlayed,
                ),
            )
        }
        if (facts.downloadable != false) {
            add(
                DetailActionKey(
                    id = DetailActionKeyIds.DOWNLOAD,
                    icon = AppIcons.Download,
                    label = downloadKeyLabel(facts.download),
                    description = "下载",
                    stateDescription = episodeDownloadLabel(facts.download),
                    // Seen while 播放's file resolves, so the row does not reflow when it arrives.
                    enabled = facts.downloadable == true,
                    onClick = onDownload,
                ),
            )
        }
    }

/** The 下载 key's own words: the episode cards' 下载已暂停 is too wide for a fifth of the row. */
private fun downloadKeyLabel(status: DownloadStatus?): String =
    when (status) {
        null -> "下载"
        DownloadStatus.Completed -> "已下载"
        DownloadStatus.Failed -> "下载失败"
        DownloadStatus.Paused -> "已暂停"
        DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.Downloading -> "下载中"
    }

/**
 * [detailActionKeys] for the page as [state] has it. [downloads] are this device's copies from the
 * server 播放 is on, by item id; 播放's target is a file once it lists a version of one.
 */
internal fun detailPageActionKeys(
    state: DetailState,
    downloads: Map<String, OfflineMedia>,
    accept: (DetailIntent) -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
): List<DetailActionKey> {
    val detail = state.detail ?: return emptyList()
    val target = state.playTarget
    return detailActionKeys(
        DetailActionFacts(
            serverActions = state.server != null,
            favoriteAvailable =
                state.playServer
                    ?.kind
                    ?.capabilities()
                    ?.favorites != false,
            favorite = detail.isFavorite,
            watchLater = state.watchLater,
            watchLaterMutating = state.watchLaterMutating,
            played = detail.played,
            // Unknown, so seen but not usable, until 播放's target and its server have resolved — as
            // on the television's detail page.
            downloadable =
                target
                    ?.takeIf { state.playServer != null && !state.selectionLoading }
                    ?.versions
                    ?.isNotEmpty(),
            download = target?.let { downloads[it.id]?.status },
        ),
        onToggleFavorite = { accept(DetailIntent.ToggleFavorite) },
        onToggleWatchLater = { accept(DetailIntent.ToggleWatchLater) },
        onTogglePlayed = onTogglePlayed,
        onDownload = onDownload,
    )
}
