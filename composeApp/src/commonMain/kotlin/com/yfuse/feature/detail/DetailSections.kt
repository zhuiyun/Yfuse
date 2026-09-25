package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.SectionHeader
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.Person
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreSection(
    genres: List<String>,
    modifier: Modifier = Modifier,
    /** A chip is a query: tapping 科幻 searches the libraries for it. */
    onGenreClick: ((String) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("分类")
        // Wraps: six Chinese genre names do not fit one 360dp row, and a Row clipped the rest.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            genres.take(6).forEach { genre ->
                Text(
                    genreDisplayLabel(genre),
                    style = AppTypography.body.medium,
                    color = palette.body,
                    modifier =
                        Modifier
                            .let { base ->
                                if (onGenreClick != null) base.pressable { onGenreClick(genre) } else base
                            }.shadow(GlassLift.control, AppShapes.chip)
                            .liquidGlass(
                                shape = AppShapes.chip,
                                fill =
                                    if (palette.isDark) {
                                        Color.White.copy(alpha = 0.075f)
                                    } else {
                                        Color.White.copy(alpha = 0.42f)
                                    },
                                border = palette.border.copy(alpha = if (palette.isDark) 0.72f else 0.62f),
                                sheen = 0.7f,
                            ).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

internal fun genreDisplayLabel(genre: String): String =
    when (genre.trim().lowercase()) {
        "sci-fi & fantasy", "science fiction & fantasy", "sci-fi and fantasy" -> "科幻奇幻"
        else -> genre
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
            motionItemsIndexed(tags, key = { _, tag -> tag }) { index, tag ->
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
                            .clip(AppShapes.card),
                )
            }
        }
    }
}

/** 外部链接 — where this title lives outside the library. */
@Composable
internal fun ExternalLinksSection(
    /** Label and address pairs from [externalLinks]. */
    links: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    if (links.isEmpty()) return
    Column(modifier) {
        SectionHeader("外部链接")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (label, url) ->
                Row(
                    Modifier
                        .pressable { runCatching { uriHandler.openUri(url) } }
                        .shadow(GlassLift.control, AppShapes.chip)
                        .liquidGlass(
                            shape = AppShapes.chip,
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
 *
 * TMDB and TheTVDB number films, series and episodes separately, so the page depends on [type]:
 * every link used to be a film's, and a series opened whichever film shared its number, or a 404.
 * An episode's own TMDB id numbers the episode, which no TMDB page is addressed by, so its TMDB
 * link needs the series' id and is left out without one.
 */
internal fun externalLinks(
    providerIds: Map<String, String>,
    type: String? = null,
    seriesTmdbId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): List<Pair<String, String>> {
    fun id(name: String) =
        providerIds.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    val episode = type.equals("Episode", ignoreCase = true)
    val tmdb =
        when {
            episode ->
                seriesTmdbId?.takeIf { it.isNotBlank() }?.let { show ->
                    if (seasonNumber != null && episodeNumber != null) {
                        "https://www.themoviedb.org/tv/$show/season/$seasonNumber/episode/$episodeNumber"
                    } else {
                        "https://www.themoviedb.org/tv/$show"
                    }
                }
            type.equals("Series", ignoreCase = true) -> id("Tmdb")?.let { "https://www.themoviedb.org/tv/$it" }
            else -> id("Tmdb")?.let { "https://www.themoviedb.org/movie/$it" }
        }
    val tvdbKind =
        when {
            episode -> "episode"
            type.equals("Movie", ignoreCase = true) -> "movie"
            else -> "series"
        }
    return buildList {
        tmdb?.let { add("TMDB" to it) }
        id("Imdb")?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
        id("Tvdb")?.let { add("TheTVDB" to "https://thetvdb.com/dereferrer/$tvdbKind/$it") }
    }
}

/** 简介 — capped at three lines so the episode list stays reachable. */
@Composable
internal fun OverviewSection(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Read while drawing the 展开 link, so the artwork's colour blends in without a recomposition. */
    accent: () -> Color,
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
            style = AppTypography.body.reading,
            color = palette.body,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (overflowed || expanded) {
            Spacer(Modifier.height(6.dp))
            val background = palette.background
            BasicText(
                if (expanded) "收起" else "展开",
                style = AppTypography.body.strong,
                color = { readableStateAccent(accent(), background, minimumRatio = 4.5f) },
            )
        }
    }
}

@Composable
internal fun CastRow(
    baseUrl: String,
    accessToken: String,
    people: List<Person>,
    modifier: Modifier = Modifier,
    /** Tapping a face searches for the person; the libraries are the only filmography we have. */
    onPersonClick: ((Person) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("主演", Modifier.padding(horizontal = Dimens.pageHorizontal))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            motionItemsIndexed(
                people.take(20),
                key = { index, person -> "person-${person.id}-$index" },
            ) { _, person ->
                Column(
                    Modifier
                        .width(66.dp)
                        .let { base ->
                            if (onPersonClick != null) base.pressable { onPersonClick(person) } else base
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
