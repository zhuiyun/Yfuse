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
internal fun DetailActionDock(
    accent: Color,
    label: String,
    /**
     * `S1 E4 · 20:01` — which entry the key opens and where it picks up.
     *
     * The button used to say 继续观看 and nothing else, which on a show is the one word that
     * leaves the actual question unanswered: continue *what*. Null for a film that has
     * never been started, where there is nothing to add.
     */
    detailLine: String?,
    resolving: Boolean,
    favorite: Boolean,
    /** Shown only when there is progress to discard. */
    canPlayFromStart: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onFavorite: () -> Unit,
    onWatchLater: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One confident primary key, with a translucent icon well inside the branded body.
        // The two material layers and the lifted shadow keep it dimensional in both themes,
        // while the established brand gradient preserves the rest of the page's palette.
        Row(
            Modifier
                .fillMaxWidth()
                .height(DetailPlayButtonHeight)
                .shadow(GlassLift.key, GlassShapes.card)
                .clip(GlassShapes.card)
                .background(actionKeyBrush(accent))
                .border(
                    Dimens.hairline,
                    Color.White.copy(alpha = 0.34f),
                    GlassShapes.card,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(DetailPlayButtonHeight)
                    .pressable(enabled = !resolving, onClick = onPlay)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(
                            Dimens.hairline,
                            Color.White.copy(alpha = 0.22f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (resolving) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = AppTypography.body.strong,
                        color = Color.White,
                        maxLines = 1,
                    )
                    detailLine?.let {
                        Text(
                            it,
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.76f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (canPlayFromStart) {
                Box(
                    Modifier
                        .width(Dimens.hairline)
                        .height(30.dp)
                        .background(Color.White.copy(alpha = 0.26f)),
                )
                Column(
                    Modifier
                        .width(74.dp)
                        .height(DetailPlayButtonHeight)
                        .pressable(
                            enabled = !resolving,
                            onClickLabel = "从头播放",
                            onClick = onPlayFromStart,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        AppIcons.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "从头",
                        style = AppTypography.caption.strong,
                        color = Color.White,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassActionButton(
                icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = if (favorite) "已收藏" else "收藏",
                active = favorite,
                accent = accent,
                onClick = onFavorite,
                modifier = Modifier.weight(1f),
            )
            GlassActionButton(
                icon = AppIcons.Bookmark,
                label = "稍后观看",
                active = false,
                accent = accent,
                onClick = onWatchLater,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A layered secondary key: glass body, inset icon well and a visible selected state. */
@Composable
private fun GlassActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val fill =
        when {
            active -> accent.copy(alpha = if (palette.isDark) 0.20f else 0.12f)
            palette.isDark -> Color.White.copy(alpha = 0.075f)
            else -> Color.White.copy(alpha = 0.72f)
        }
    val edge =
        when {
            active -> accent.copy(alpha = 0.38f)
            palette.isDark -> Color.White.copy(alpha = 0.19f)
            else -> Color(0xFFE0E7F1)
        }
    Row(
        modifier
            .height(46.dp)
            // 收藏 / 稍后观看 change state in place and navigate nowhere, so the tap needs
            // to be felt as well as seen.
            .pressable(haptic = HapticSignal.Confirm, onClick = onClick)
            .shadow(GlassLift.control, GlassShapes.card)
            .liquidGlass(
                shape = GlassShapes.card,
                fill = fill,
                border = edge,
                sheen = 0.72f,
            ).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        accent.copy(alpha = 0.14f)
                    } else {
                        palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.045f)
                    },
                ).border(
                    Dimens.hairline,
                    if (active) accent.copy(alpha = 0.20f) else palette.border,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BurstIcon(
                icon = icon,
                active = active,
                contentDescription = label,
                tint = if (active) accent else palette.body,
                burstColor = accent,
            )
        }
        Text(
            label,
            style = if (active) AppTypography.body.strong else AppTypography.body.medium,
            color = if (active) accent else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
        }
    }
}

// ---------------------------------------------------------------- sections

/** 分类 — the genres as chips, which is the only place they are listed in full. */
