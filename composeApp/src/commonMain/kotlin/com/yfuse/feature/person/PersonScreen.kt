package com.yfuse.feature.person

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.SectionHeader
import com.yfuse.core.designsystem.SkeletonPosterTile
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 演员页 on the phone: portrait, 生卒 and 出生地, a 简介 that opens out, then two grids — what this
 * library holds, each marked 可播放 and opening its detail page, and the TMDB titles it does not,
 * which open their TMDB page and play nothing.
 */
@Composable
fun PersonScreen(component: PersonComponent) {
    val state by component.state.collectAsState()
    val palette = LocalPalette.current
    StatusBarIconStyle(darkIcons = !palette.isDark)
    var biographyExpanded by rememberSaveable(component.request.personId) { mutableStateOf(false) }
    // The age beside the birth date is a label, not a clock: once per page is enough.
    val today = remember { currentIsoDate() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PersonTopBar(title = state.displayName, onBack = component.onBack)
        if (state.serverMissing) {
            PageHint("这台服务器已经不在列表里了", modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PosterMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Dimens.pageHorizontal,
                    end = Dimens.pageHorizontal,
                    bottom = Dimens.contentBottom,
                ),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            motionItem(key = "person-header", span = FullLine) {
                PersonHeader(state = state, personId = component.request.personId, today = today)
            }
            state.overview?.let { text ->
                motionItem(key = "person-biography", span = FullLine) {
                    PersonBiography(
                        text = text,
                        fromTmdb = state.overviewFromTmdb,
                        expanded = biographyExpanded,
                        onToggle = { biographyExpanded = !biographyExpanded },
                    )
                }
            }
            libraryWorks(state, component)
            tmdbWorks(state, component)
        }
    }
}

@Composable
private fun PersonTopBar(
    title: String,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .pressable(onClickLabel = "返回上一页", onClick = onBack)
                .touchTarget()
                .size(34.dp)
                .glass(AppShapes.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.ChevronLeft, contentDescription = "返回", tint = palette.text, modifier = Modifier.size(15.dp))
        }
        Text(
            title,
            style = AppTypography.display.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Composable
private fun PersonHeader(
    state: PersonPageState,
    personId: String,
    today: String,
) {
    val palette = LocalPalette.current
    val server = state.server
    // The server's portrait is the face the cast row showed; TMDB's stands in where it has none.
    val portraits =
        listOfNotNull(
            server?.let { from ->
                state.serverImageTag?.let {
                    EmbyImages.primary(from.baseUrl, personId, it, maxHeight = 360, accessToken = from.accessToken)
                }
            },
            TmdbImages.poster(state.tmdbProfilePath, PORTRAIT_SIZE),
            TmdbImages.media(state.tmdbProfilePath, PORTRAIT_SIZE),
        )
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(
            url = portraits.firstOrNull(),
            fallbackUrls = portraits.drop(1),
            shape = CircleShape,
            contentDescription = state.displayName,
            modifier = Modifier.size(84.dp).border(1.dp, palette.border, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val facts =
                listOfNotNull(
                    personLifeLine(state.birthDate, state.deathDate, today),
                    personBirthPlaceLine(state.birthPlace),
                )
            if (facts.isEmpty() && (state.profileLoading || state.otherWorksLoading)) {
                Text("正在读取资料", style = AppTypography.caption.regular, color = palette.hint)
            }
            facts.forEach { line ->
                Text(line, style = AppTypography.body.regular, color = palette.body)
            }
            state.tmdb?.knownForDepartment?.let(::departmentLabel)?.let { department ->
                Text(department, style = AppTypography.caption.medium, color = palette.sub2)
            }
        }
    }
}

@Composable
private fun PersonBiography(
    text: String,
    fromTmdb: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var overflowed by remember(text) { mutableStateOf(false) }
    val canToggle = overflowed || expanded
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.settle(reduceMotion))
            .then(
                if (canToggle) {
                    Modifier
                        .pressable(onClickLabel = if (expanded) "收起简介" else "展开简介", onClick = onToggle)
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                } else {
                    Modifier
                },
            ),
    ) {
        SectionHeader("简介")
        Text(
            text,
            style = AppTypography.body.reading,
            color = palette.body,
            maxLines = if (expanded) Int.MAX_VALUE else BIOGRAPHY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (canToggle) {
            Spacer(Modifier.height(6.dp))
            Text(if (expanded) "收起" else "展开", style = AppTypography.body.strong, color = accent.accent)
        }
        if (fromTmdb) {
            Spacer(Modifier.height(6.dp))
            Text("简介来自 TMDB", style = AppTypography.caption.regular, color = palette.hint)
        }
    }
}

/** 库内作品 — every title of theirs on this server; each one plays. */
private fun LazyGridScope.libraryWorks(
    state: PersonPageState,
    component: PersonComponent,
) {
    val server = state.server
    motionItem(key = "person-library-header", span = FullLine) {
        SectionHeader(
            "库内作品",
            Modifier.padding(top = Dimens.space.sm),
            actionLabel =
                state.works.size
                    .takeIf { it > 0 }
                    ?.let { count -> listOfNotNull("$count 部", server?.serverName).joinToString(" · ") },
        )
    }
    when {
        state.worksLoading ->
            items(count = SKELETON_TILES, key = { "person-library-skeleton-$it" }) {
                SkeletonPosterTile(Modifier.fillMaxWidth(), posterHeight = SkeletonPosterHeight)
            }
        state.worksError != null ->
            motionItem(key = "person-library-error", span = FullLine) {
                ErrorState(message = state.worksError, onRetry = component::retry)
            }
        state.works.isEmpty() || server == null ->
            motionItem(key = "person-library-empty", span = FullLine) {
                PageHint("这台服务器上还没有 TA 的作品", icon = null)
            }
        else ->
            motionItems(state.works, key = { "person-work:${it.id}" }) { item ->
                LibraryWorkTile(item = item, server = server, onOpen = { component.onOpenItem(server.id, item.id) })
            }
    }
}

/**
 * TMDB 其他作品 — only once TMDB is known to be this person, and only what the library lacks.
 * Nothing is drawn while it is being looked up: a heading that could vanish again would move the
 * whole grid twice for nothing.
 */
private fun LazyGridScope.tmdbWorks(
    state: PersonPageState,
    component: PersonComponent,
) {
    if (state.otherWorks.isEmpty()) return
    motionItem(key = "person-tmdb-header", span = FullLine) {
        val palette = LocalPalette.current
        Column(Modifier.padding(top = Dimens.space.sm)) {
            SectionHeader("TMDB 其他作品")
            Text("不在你的媒体库中，点开看作品资料", style = AppTypography.caption.regular, color = palette.sub2)
        }
    }
    motionItems(state.otherWorks, key = { "person-tmdb:${it.mediaType}:${it.id}" }) { item ->
        TmdbWorkTile(item = item, onOpen = { component.onOpenTmdbItem(item) })
    }
}

@Composable
private fun LibraryWorkTile(
    item: MediaItem,
    server: SavedServer,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.pressable(onClickLabel = "打开${item.title}", onClick = onOpen)) {
        Poster(
            url = EmbyImages.poster(server.baseUrl, item, accessToken = server.accessToken),
            blurHash = item.posterBlurHash,
            rating = item.communityRating,
            progress = item.playedPercentage?.div(100.0)?.toFloat(),
            contentDescription = "${item.title}，可播放",
            modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO),
            overlay = { PlayableBadge(Modifier.align(Alignment.BottomStart).padding(6.dp)) },
        )
        Spacer(Modifier.height(7.dp))
        Text(
            item.title,
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            item.year?.toString().orEmpty(),
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
        )
    }
}

/** The mark that sets a library title apart from a TMDB one: this one plays. */
@Composable
private fun PlayableBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(BadgeScrim, AppShapes.pill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(8.dp))
        Text("可播放", style = AppTypography.caption.medium, color = Color.White)
    }
}

@Composable
private fun TmdbWorkTile(
    item: TmdbItem,
    onOpen: () -> Unit,
) {
    CaptionedPoster(
        url = TmdbImages.poster(item.posterPath, POSTER_SIZE),
        fallbackUrls =
            listOfNotNull(
                TmdbImages.media(item.posterPath, POSTER_SIZE),
                TmdbImages.backdrop(item.backdropPath, "w780"),
            ),
        title = item.title,
        year = item.year,
        rating = item.rating,
        onClick = onOpen,
    )
}

/** TMDB's department names, as the page says them; an unknown one says nothing. */
internal fun departmentLabel(department: String): String? =
    when (department.trim().lowercase()) {
        "acting" -> "演员"
        "directing" -> "导演"
        "writing" -> "编剧"
        "production" -> "制片"
        "sound" -> "音乐"
        "camera" -> "摄影"
        "editing" -> "剪辑"
        else -> null
    }

private val FullLine: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** As the library's 查看更多 grid: three columns on a 360dp phone, more on anything wider. */
private val PosterMinWidth = 96.dp
private val GridSpacing = 10.dp
private val SkeletonPosterHeight = 150.dp
private val BadgeScrim = Color.Black.copy(alpha = 0.58f)
private const val POSTER_RATIO = 2f / 3f
private const val POSTER_SIZE = "w342"
private const val PORTRAIT_SIZE = "h632"
private const val BIOGRAPHY_LINES = 4
private const val SKELETON_TILES = 6
