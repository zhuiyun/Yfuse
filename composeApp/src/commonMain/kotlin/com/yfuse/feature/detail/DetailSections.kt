package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkAccent
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyBadge
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.isSharedMediaArtworkActive
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.player.PlaybackSelection
import com.yfuse.feature.player.PlaybackSelectionState
import com.yfuse.feature.watch.WatchInviteShareSheet
@Composable
internal fun GenreSection(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("分类")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genres.take(6).forEach { genre ->
                Text(
                    genre,
                    style = AppTypography.body.strong,
                    color = palette.body,
                    modifier =
                        Modifier
                            .shadow(GlassLift.control, GlassShapes.chip)
                            .liquidGlass(
                                shape = GlassShapes.chip,
                                fill =
                                    if (palette.isDark) {
                                        Color.White.copy(alpha = 0.075f)
                                    } else {
                                        Color.White.copy(alpha = 0.72f)
                                    },
                                border = palette.border,
                                sheen = 0.7f,
                            ).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 艺术图 — the item's other backdrops.
 *
 * Only the first is ever used as the hero, so the rest are artwork the library holds and
 * nothing in the app has shown until now.
 */
@Composable
internal fun ArtworkSection(
    baseUrl: String,
    accessToken: String,
    itemId: String,
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("艺术图", Modifier.padding(horizontal = Dimens.pageHorizontal))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(tags, key = { _, tag -> tag }) { index, tag ->
                FallbackImage(
                    urls =
                        listOf(
                            EmbyImages.backdropAt(
                                baseUrl,
                                itemId,
                                index,
                                tag,
                                maxWidth = 720,
                                accessToken = accessToken,
                            ),
                        ),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .width(232.dp)
                            .height(130.dp)
                            .clip(GlassShapes.card),
                )
            }
        }
    }
}

/** 外部链接 — where this title lives outside the library. */
@Composable
internal fun ExternalLinksSection(
    providerIds: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val links = remember(providerIds) { externalLinks(providerIds) }
    if (links.isEmpty()) return
    Column(modifier) {
        SectionHeader("外部链接")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (label, url) ->
                Row(
                    Modifier
                        .pressable { runCatching { uriHandler.openUri(url) } }
                        .shadow(GlassLift.control, GlassShapes.chip)
                        .liquidGlass(
                            shape = GlassShapes.chip,
                            fill =
                                if (palette.isDark) {
                                    Color.White.copy(alpha = 0.075f)
                                } else {
                                    Color.White.copy(alpha = 0.72f)
                                },
                            border = palette.border,
                            sheen = 0.7f,
                        ).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Cloud,
                        contentDescription = null,
                        tint = palette.sub,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(label, style = AppTypography.body.strong, color = palette.body)
                }
            }
        }
    }
}

/**
 * The provider ids Emby carries that have a public page worth opening. Anything else it
 * returns (a scraper's internal key, say) has nowhere to link to and is left out.
 */
internal fun externalLinks(providerIds: Map<String, String>): List<Pair<String, String>> {
    fun id(name: String) =
        providerIds.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    return buildList {
        id("Tmdb")?.let { add("TMDB" to "https://www.themoviedb.org/movie/$it") }
        id("Imdb")?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
        id("Tvdb")?.let { add("TheTVDB" to "https://thetvdb.com/dereferrer/series/$it") }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppTypography.section.strong, color = LocalPalette.current.text)
        trailing()
    }
}

/** 简介 — capped at three lines so the episode list stays reachable. */
@Composable
internal fun OverviewSection(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var overflowed by remember(text) { mutableStateOf(false) }
    val canToggle = overflowed || expanded
    Column(
        modifier
            .animateContentSize(
                animationSpec = if (reduceMotion) snap() else Motion.settle(),
            ).then(
                if (canToggle) {
                    Modifier
                        .pressable(
                            onClickLabel = if (expanded) "收起剧情简介" else "展开剧情简介",
                            onClick = onToggle,
                        ).semantics {
                            stateDescription = if (expanded) "已展开" else "已收起"
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        SectionHeader("剧情简介")
        Text(
            text,
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.body,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (overflowed || expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) "收起" else "展开",
                style = AppTypography.body.strong,
                color = accent,
            )
        }
    }
}

/**
 * Season header with the `切换季数 ▾` chip. The season list expands inline rather than
 * as an overlay: a popup drawn from inside a lazy item is painted under the rows that
 * follow it, and the old one hard-coded a white plate that broke under the dark theme.
 */

@Composable
internal fun CastRow(
    baseUrl: String,
    accessToken: String,
    people: List<Person>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("主演", Modifier.padding(horizontal = Dimens.pageHorizontal))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(
                people.take(20),
                key = { index, person -> "person-${person.id}-$index" },
            ) { _, person ->
                Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Poster(
                        url = EmbyImages.avatar(baseUrl, person, accessToken = accessToken),
                        shape = CircleShape,
                        modifier =
                            Modifier
                                .size(52.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.88f), CircleShape),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.name,
                        style = AppTypography.caption.medium,
                        color = palette.body,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!person.role.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            person.role,
                            style = AppTypography.caption.regular,
                            color = palette.hint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Loading placeholder shaped like the page it becomes.
 */
