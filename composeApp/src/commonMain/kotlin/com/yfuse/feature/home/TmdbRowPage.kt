package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.SelectionAction
import com.yfuse.core.designsystem.SelectionActionBar
import com.yfuse.core.designsystem.SelectionMark
import com.yfuse.core.designsystem.TabBarInset
import com.yfuse.core.designsystem.coversAll
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.selectingAll
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.toggling
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Smallest poster column this grid will draw.
 *
 * Chosen so a 360dp phone still lays out three across, while a tablet or an unfolded
 * device spends its extra width on more posters instead of three stretched ones.
 */
private val PosterMinWidth = 94.dp

/**
 * 全部 — one home shelf, opened out into a grid.
 *
 * The chip beside every shelf used to navigate to the 库 tab. For 继续观看 that is at least
 * arguable — those are the user's own files. For 热门 / 正在上映 / 即将上映 it was simply
 * wrong: those come from TMDB and most of them are not in the library at all, so the chip
 * promised "more of this" and delivered a different screen showing none of it.
 *
 * A layer over the home page rather than a pushed route, for the reason 查看全部 on the
 * detail page is one: the shelf's items are already loaded and already in the home store,
 * and a route would mean threading the same list through the navigation stack to show
 * something the page above it is already holding. The home page remains composed below this
 * layer, so dismissing it returns to the already composed home page. The home page shows it in
 * an [com.yfuse.core.designsystem.OverlayPage], which gives it a route's push and pop.
 */
@Composable
internal fun TmdbRowPage(
    title: String,
    items: List<TmdbItem>,
    showReleaseDate: Boolean,
    onOpen: (TmdbItem) -> Unit,
    onDismiss: () -> Unit,
    /** The same 浮起菜单 the shelf's posters have; this page is that shelf, all of it. */
    liftMenu: ((TmdbItem) -> LiftMenu)? = null,
) {
    HomeRowPageFrame(title = title, caption = "TMDB · ${items.size} 部", onDismiss = onDismiss) {
        motionItems(items, key = { "${it.mediaType}:${it.id}" }) { item ->
            CaptionedPoster(
                url = TmdbImages.poster(item.posterPath),
                fallbackUrls =
                    listOfNotNull(
                        TmdbImages.media(item.posterPath),
                        TmdbImages.poster(item.posterPath, "original"),
                        TmdbImages.media(item.posterPath, "original"),
                        TmdbImages.backdrop(item.backdropPath, "w780"),
                        TmdbImages.media(item.backdropPath, "w780"),
                    ),
                title = item.title,
                rating = item.rating,
                year =
                    "TMDB · " +
                        if (showReleaseDate) {
                            item.releaseDate?.let { "上映 $it" } ?: "上映日期待定"
                        } else {
                            item.year ?: "年份未知"
                        },
                // The same title can sit in two shelves at once, and this page is
                // opened from one of them; a shared element would compete with the
                // shelf poster still mounted underneath.
                onClick = { onOpen(item) },
                liftMenu = liftMenu?.let { build -> { build(item) } },
                modifier = Modifier.fillMaxWidth(),
                posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            )
        }
    }
}

/**
 * 全部 for a shelf read from the media servers — 继续观看, 下一集, 我的收藏.
 *
 * Their 全部 used to switch to the 库 tab, which reads the default server alone where these
 * shelves gather every server's, so the page it opened held fewer titles than the shelf. This is
 * the shelf itself, every entry the home page holds, and a long press offers what it does there.
 *
 * With [selectionActions] the header has 编辑, which turns the grid into 多选 (I-21): a tap ticks a
 * card rather than opening it, and the bar under the grid acts on every ticked card at once. Taking
 * cards off a shelf used to be one long press per card, found only by holding one. [editing] is the
 * caller's, so the shelf's own 编辑 on 首页 can open this page already selecting.
 */
@Composable
internal fun LibraryRowPage(
    title: String,
    caption: String,
    entries: List<HomeResumeEntry>,
    onOpen: (HomeResumeEntry) -> Unit,
    liftMenu: (HomeResumeEntry) -> LiftMenu,
    onDismiss: () -> Unit,
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    /** 编辑's bar for the ticked cards; null where 编辑 has nothing to offer. */
    selectionActions: ((List<HomeResumeEntry>) -> List<SelectionAction>)? = null,
) {
    val selecting = editing && selectionActions != null
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    // Every 编辑 starts from nothing ticked.
    LaunchedEffect(selecting) { if (!selecting) ticked = emptySet() }
    // 返回 leaves 编辑 before it leaves the page.
    PlatformBackHandler(enabled = selecting) { onEditingChange(false) }
    val keys = remember(entries) { entries.map { it.key } }
    // A card that has left the shelf meanwhile leaves the selection with it.
    val selection = entries.filter { it.key in ticked }
    HomeRowPageFrame(
        title = title,
        caption = if (selecting) "已选择 ${selection.size} 项" else caption,
        onDismiss = onDismiss,
        headerActions =
            if (selectionActions == null) {
                null
            } else {
                {
                    if (selecting) {
                        val all = ticked.coversAll(keys)
                        HeaderTextAction(if (all) "取消全选" else "全选", if (all) "取消选中" else "选中全部") {
                            ticked = ticked.selectingAll(keys)
                        }
                    }
                    HeaderTextAction(if (selecting) "完成" else "编辑", if (selecting) "退出多选" else "进入多选") {
                        onEditingChange(!selecting)
                    }
                }
            },
        bottomBar = {
            SelectionActionBar(visible = selecting, actions = selectionActions?.invoke(selection).orEmpty())
        },
    ) {
        motionItems(entries, key = { "${it.server.id}:${it.item.id}" }) { entry ->
            val item = entry.item
            val selected = entry.key in ticked
            // While selecting, the whole tile is one checkbox, and neither opens nor lifts.
            Box(
                if (selecting) {
                    Modifier
                        .pressable(role = Role.Checkbox, onClickLabel = if (selected) "取消选择" else "选择") {
                            ticked = ticked.toggling(entry.key)
                        }.semantics {
                            this.selected = selected
                            stateDescription = if (selected) "已选择" else "未选择"
                        }
                } else {
                    Modifier
                },
            ) {
                CaptionedPoster(
                    url =
                        EmbyImages.poster(
                            entry.server.baseUrl,
                            item,
                            accessToken = entry.server.accessToken,
                        ),
                    title = item.title,
                    rating = item.communityRating,
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    // An episode's line names the episode; a film's or a show's gives its year.
                    year =
                        listOfNotNull(
                            item.subtitle,
                            entry.server.serverName.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                    // No shared element, for the reason the TMDB page has none: the shelf's own
                    // card is still mounted underneath.
                    onClick = { onOpen(entry) }.takeUnless { selecting },
                    liftMenu = { liftMenu(entry) }.takeUnless { selecting },
                    modifier = Modifier.fillMaxWidth(),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
                if (selecting) {
                    SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(7.dp), onArtwork = true)
                }
            }
        }
    }
}

/** 编辑, 全选 and 完成 at the header's end: words in the accent, as 下载's 多选 is. */
@Composable
private fun HeaderTextAction(
    label: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = AppTypography.body.strong,
        color = LocalAccentColors.current.accent,
        modifier =
            Modifier
                .pressable(onClickLabel = onClickLabel, onClick = onClick)
                .touchTarget()
                .padding(horizontal = 8.dp),
    )
}

/** What both 全部 pages share: the back key, the title and its count over a poster grid. */
@Composable
private fun HomeRowPageFrame(
    title: String,
    caption: String,
    onDismiss: () -> Unit,
    /** At the header's end, after the title; none leaves the title the whole row. */
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    /** Under the grid, which gives up its foot to whatever this draws. */
    bottomBar: @Composable ColumnScope.() -> Unit = {},
    content: LazyGridScope.() -> Unit,
) {
    val palette = LocalPalette.current

    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = Dimens.contentTop, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "关闭全部内容", onClick = onDismiss)
                            .touchTarget()
                            .size(36.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = AppTypography.section.strong, color = palette.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        caption,
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                headerActions?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
            }

            LazyVerticalGrid(
                modifier = Modifier.weight(1f),
                // Three across on a phone, more on anything wider — see [PosterMinWidth].
                columns = GridCells.Adaptive(PosterMinWidth),
                contentPadding =
                    PaddingValues(
                        start = Dimens.pageHorizontal,
                        end = Dimens.pageHorizontal,
                        bottom = TabBarInset,
                    ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            bottomBar()
        }
    }
}
