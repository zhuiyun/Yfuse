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
internal fun RelatedSection(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    items: List<MediaItem>,
    accent: Color,
    onOpen: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = Dimens.sectionGap)) {
        SectionHeader(
            title = "相关推荐",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(
                items,
                key = { index, item -> "related-${item.id}-$index" },
            ) { _, item ->
                val sharedKey = MediaSharedElementKey(serverId, item.id)
                Column(
                    Modifier
                        .width(96.dp)
                        .pressable(
                            onClick = sharedMediaOnClick(sharedKey) { onOpen(item.id) },
                        ),
                ) {
                    Poster(
                        url =
                            EmbyImages.primary(
                                baseUrl = baseUrl,
                                itemId = item.posterItemId,
                                tag = item.posterTag,
                                maxHeight = 480,
                                accessToken = accessToken,
                            ),
                        shape = GlassShapes.poster,
                        sharedTransitionKey = sharedKey,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.communityRating?.let { ((it * 10).toInt() / 10.0).toString() }
                            ?: item.year?.toString().orEmpty(),
                        style = AppTypography.caption.strong,
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- scroll plumbing

/** Section blocks share one horizontal inset and one vertical rhythm (§8.4 大区块间距). */
internal fun Modifier.sectionPadding(): Modifier =
    this.padding(horizontal = Dimens.pageHorizontal).padding(top = Dimens.sectionGap)

/** Pixels of the hero that have scrolled past the top edge. */
