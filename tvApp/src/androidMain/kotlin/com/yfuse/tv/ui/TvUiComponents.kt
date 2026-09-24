package com.yfuse.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.tv.focus.FocusAnchor
import com.yfuse.tv.focus.FocusCandidate
import com.yfuse.tv.focus.FocusContext
import com.yfuse.tv.focus.FocusRepository
import com.yfuse.tv.focus.FocusRestorePolicy
import com.yfuse.tv.focus.FocusRestoreRequest
import com.yfuse.tv.focus.FocusTargetId
import com.yfuse.tv.focus.InMemoryFocusRepository
import com.yfuse.tv.focus.RemoteIntent
import com.yfuse.tv.focus.TvFocusRequesterRegistry
import com.yfuse.tv.focus.tvFocusTarget
import com.yfuse.tv.focus.tvRemoteKeyHandler

internal val TvSafeHorizontal = 48.dp
internal val TvSafeVertical = 27.dp
internal val TvRailWidth = 184.dp

/**
 * The room a scrolling row leaves at its ends. A lazy row clips at its edges, and without this a
 * focused first or last item lost its lift and part of its white edge there.
 */
internal val TvFocusInset = 8.dp

/**
 * The page a focus scope belongs to: the scope's first segment, except that every 详情 is a route
 * of its own (`detail:<itemId>`). They all share one screen, and detail A → related B → back used
 * to find B's last focus waiting where A's should have been. A detail dialog's scope (`detail:more`)
 * has no section after the id, and stays with the plain `detail` route.
 */
internal fun tvFocusRoute(scope: String): String {
    val route = scope.substringBefore(':')
    if (route != DETAIL_ROUTE) return route
    val rest = scope.substringAfter(':', missingDelimiterValue = "")
    return if (':' in rest) tvDetailRoute(rest.substringBefore(':')) else route
}

/** The focus route of one detail page — see [tvFocusRoute]. */
internal fun tvDetailRoute(itemId: String): String = "$DETAIL_ROUTE:$itemId"

private const val DETAIL_ROUTE = "detail"

/**
 * TV focus is restored by semantic identity, never by a Lazy list index. An item can move after
 * a refresh and still receive focus when the user backs out of detail.
 */
@Stable
internal class TvUiFocusMemory {
    private val anchors = mutableStateMapOf<String, String>()
    private val routeContexts = mutableMapOf<String, FocusContext>()
    val repository: FocusRepository = InMemoryFocusRepository()
    val requesterRegistry = TvFocusRequesterRegistry()
    private val rowStates = mutableMapOf<String, LazyListState>()
    private val gridStates = mutableMapOf<String, LazyGridState>()

    /** Routes just entered whose saved focus has not been put back yet — see [TvRestoreRouteFocusEffect]. */
    private val pendingRestores = mutableSetOf<String>()

    fun remember(
        scope: String,
        stableId: String,
        serverId: String? = null,
        profileId: String? = null,
    ) {
        anchors[scope] = stableId
        val route = tvFocusRoute(scope)
        routeContexts[route] = FocusContext(route, serverId, profileId)
        // Focus is in the page now — put there by the restore, its fallback or the viewer — so
        // this entry has nothing left to put back.
        settleRestore(route)
    }

    fun anchor(scope: String): String? = anchors[scope]

    fun targetId(
        scope: String,
        stableId: String,
    ): FocusTargetId = FocusTargetId(scope, stableId)

    fun context(scope: String): FocusContext {
        val route = tvFocusRoute(scope)
        return routeContexts[route] ?: FocusContext(route = route)
    }

    fun activateContext(context: FocusContext): FocusContext {
        routeContexts[context.route] = context
        return context
    }

    fun contextForRoute(route: String): FocusContext = routeContexts[route] ?: FocusContext(route = route)

    fun lastForRoute(
        route: String,
        context: FocusContext? = null,
    ): FocusAnchor? = repository.last(context ?: contextForRoute(route))

    /** The last focus inside one [section] of a route, whatever the route has focused since. */
    fun lastInSection(
        route: String,
        section: String,
        context: FocusContext? = null,
    ): FocusAnchor? = repository.lastInSection(context ?: contextForRoute(route), section)

    fun requestLastForRoute(
        route: String,
        context: FocusContext? = null,
    ): Boolean {
        val anchor = lastForRoute(route, context) ?: return false
        return requesterRegistry.requestFocus(FocusTargetId(anchor.sectionId, anchor.itemStableId))
    }

    /** Focuses [stableId] in [scope] when it is on screen; false when it is not. */
    fun requestFocus(
        scope: String,
        stableId: String,
    ): Boolean = requesterRegistry.requestFocus(targetId(scope, stableId))

    /** A new entry into [route]: its saved focus is put back once, not on every recomposition. */
    fun beginRestore(route: String) {
        pendingRestores.add(route)
    }

    fun restorePending(route: String): Boolean = route in pendingRestores

    /**
     * Ends [route]'s entry restore. Focus landing on anything [remember] does not see — a text
     * field, a phone control embedded in the page — has to say so here, or a restore still
     * waiting for content would later take focus away from it.
     */
    fun settleRestore(route: String) {
        pendingRestores.remove(route)
    }

    fun rowState(section: String): LazyListState = rowStates.getOrPut(section) { LazyListState() }

    fun gridState(route: String): LazyGridState = gridStates.getOrPut(route) { LazyGridState() }
}

/** Scrolls [index] into view for a restore, and leaves a row that already shows it where it is. */
internal suspend fun LazyListState.revealForRestore(
    index: Int,
    scrollOffset: Int = 0,
) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index, scrollOffset)
}

/** [LazyListState.revealForRestore] for a grid. */
internal suspend fun LazyGridState.revealForRestore(
    index: Int,
    scrollOffset: Int = 0,
) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index, scrollOffset)
}

internal enum class TvArtworkShape(
    val ratio: Float,
) {
    Poster(2f / 3f),
    Landscape(16f / 9f),
}

internal data class TvMediaCardModel(
    val stableId: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val serverId: String? = null,
    val profileId: String? = null,
    val progress: Float? = null,
    val badge: String? = null,
    val artworkShape: TvArtworkShape = TvArtworkShape.Poster,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun TvFocusableSurface(
    stableId: String,
    focusScope: String,
    focusMemory: TvUiFocusMemory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
    returnToNavigationOnLeft: Boolean = false,
    selected: Boolean = false,
    scaleWhenFocused: Float = 1.055f,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    onFocused: (() -> Unit)? = null,
    onContextMenu: (() -> Unit)? = null,
    fallbackIndex: Int = 0,
    scrollOffset: Int = 0,
    serverId: String? = null,
    profileId: String? = null,
    /** What a screen reader says for this surface; the stable id is an internal key, not a name. */
    contentDescription: String? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // One clock, read only in the layer and draw phases: scale, edge and plate move together
    // and a focus change recomposes nothing. See [TvFocusMotion].
    val focusAmount =
        animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = TvFocusMotion.spec(reduceMotion),
            label = "tv-focus",
        )
    val focusScale = TvFocusMotion.scale(scaleWhenFocused, reduceMotion)
    val restEdge = if (selected) TvAccent.copy(alpha = 0.88f) else TvHairline
    val requesterModifier =
        if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    val targetId = remember(focusScope, stableId) { focusMemory.targetId(focusScope, stableId) }

    Box(
        modifier
            .graphicsLayer {
                val scale = 1f + (focusScale - 1f) * focusAmount.value
                scaleX = scale
                scaleY = scale
            }.then(requesterModifier)
            .tvFocusTarget(
                targetId = targetId,
                anchor =
                    FocusAnchor(
                        route = tvFocusRoute(focusScope),
                        serverId = serverId,
                        profileId = profileId,
                        sectionId = focusScope,
                        itemStableId = stableId,
                        fallbackIndex = fallbackIndex,
                        scrollOffset = scrollOffset.coerceAtLeast(0),
                    ),
                repository = focusMemory.repository,
                requesterRegistry = focusMemory.requesterRegistry,
                makeFocusable = false,
            ).onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) {
                    focusMemory.remember(focusScope, stableId, serverId, profileId)
                    onFocused?.invoke()
                }
            }.onPreviewKeyEvent { event ->
                if (
                    returnToNavigationOnLeft &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft
                ) {
                    navigationRequester?.requestFocus()
                    true
                } else {
                    false
                }
            }.tvRemoteKeyHandler { intent ->
                if (intent is RemoteIntent.OpenContextMenu && onContextMenu != null) {
                    onContextMenu()
                    true
                } else {
                    false
                }
            }.clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                onDrawWithContent {
                    val amount = focusAmount.value.coerceIn(0f, 1f)
                    drawOutline(outline, lerp(TvSurface, TvSurfaceFocused, amount))
                    drawContent()
                    // The clip removes the outer half of a centred stroke, so twice the width
                    // leaves exactly the token inside the shape — what `border` used to draw.
                    val edge = lerp(TvFocusMotion.restBorder, TvFocusMotion.focusBorder, amount).toPx()
                    drawOutline(outline, lerp(restEdge, Color.White, amount), style = Stroke(edge * 2f))
                }
            }.clickable(onClick = onClick)
            .testTag(stableId)
            .semantics {
                role = Role.Button
                this.selected = selected
                contentDescription?.let { this.contentDescription = it }
            },
    ) {
        content(focused)
    }
}

/**
 * A remote-sized button. It takes the width its label needs unless the caller fixes one, and a
 * label that still does not fit ends in an ellipsis: a Row of fixed widths is how 更多 was
 * measured to 0dp and left unreachable, and a clipped 服务器已收藏 read as 服务器.
 */
@Composable
internal fun TvActionButton(
    label: String,
    stableId: String,
    focusScope: String,
    focusMemory: TvUiFocusMemory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
    returnToNavigationOnLeft: Boolean = false,
    serverId: String? = null,
    profileId: String? = null,
) {
    TvFocusableSurface(
        stableId = stableId,
        focusScope = focusScope,
        focusMemory = focusMemory,
        onClick = onClick,
        contentDescription = label,
        modifier = modifier.height(52.dp).width(IntrinsicSize.Max),
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
        returnToNavigationOnLeft = returnToNavigationOnLeft,
        selected = selected,
        serverId = serverId,
        profileId = profileId,
        shape = RoundedCornerShape(12.dp),
        scaleWhenFocused = 1.035f,
    ) { focused ->
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            focused -> Color.White
                            primary -> TvAccent.copy(alpha = 0.9f)
                            else -> Color.Transparent
                        },
                    ).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint =
                        if (focused) {
                            Color.Black
                        } else if (primary) {
                            Color.Black
                        } else {
                            TvOnSurface
                        },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = label,
                color =
                    if (focused) {
                        Color.Black
                    } else if (primary) {
                        Color.Black
                    } else {
                        TvOnSurface
                    },
                fontSize = TvType.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TvMediaCard(
    model: TvMediaCardModel,
    focusScope: String,
    focusMemory: TvUiFocusMemory,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
    returnToNavigationOnLeft: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onContextMenu: (() -> Unit)? = null,
    fallbackIndex: Int = 0,
) {
    val width = if (model.artworkShape == TvArtworkShape.Poster) 142.dp else 232.dp
    TvFocusableSurface(
        stableId = model.stableId,
        contentDescription = listOfNotNull(model.title, model.subtitle).joinToString("，"),
        focusScope = focusScope,
        focusMemory = focusMemory,
        onClick = model.onClick,
        modifier = modifier.width(width),
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
        returnToNavigationOnLeft = returnToNavigationOnLeft,
        selected = model.selected,
        onFocused = onFocused,
        onContextMenu = onContextMenu,
        fallbackIndex = fallbackIndex,
        serverId = model.serverId,
        profileId = model.profileId,
    ) { focused ->
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(model.artworkShape.ratio)
                    .background(TvPlaceholder),
            ) {
                AsyncImage(
                    model = model.imageUrl,
                    contentDescription = model.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0.56f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.76f),
                                ),
                            )
                        },
                )
                model.badge?.let { badge ->
                    Text(
                        text = badge,
                        color = Color.White,
                        fontSize = TvType.caption,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.Black.copy(alpha = 0.66f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                model.progress?.coerceIn(0f, 1f)?.let { progress ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(5.dp)
                                .background(if (focused) Color.Black else TvAccent),
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(
                    text = model.title,
                    color = TvOnSurface,
                    fontSize = TvType.caption,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                model.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = TvOnSurfaceMuted,
                        fontSize = TvType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Puts focus back where the viewer left this page — once, when the page is entered.
 *
 * It used to rebuild its request from the last focus on every recomposition, and every new
 * request scrolled and refocused again: a row that recomposed for an unrelated reason — the next
 * grid page arriving, the home reel turning, a download ticking — jumped to put the focused card
 * first, and a page whose saved card had gone pulled focus off the navigation rail on each turn
 * of the reel. An entry now lasts only until something in the route has focus: this restore, its
 * [fallback] or the viewer. [contentGeneration] lets an entry that is still waiting try again
 * when the content it needs arrives, and does nothing after that.
 *
 * The saved context includes server/profile, so a card from another household profile cannot
 * receive focus after an account switch. [section] restores that section's own last focus rather
 * than the route's: 设置's root shares its route with every sub-page it opens.
 */
@Composable
internal fun TvRestoreRouteFocusEffect(
    route: String,
    focusMemory: TvUiFocusMemory,
    fallback: FocusRequester? = null,
    contentGeneration: Any? = Unit,
    context: FocusContext? = null,
    candidates: List<FocusCandidate> = emptyList(),
    scrollToAnchor: suspend (FocusAnchor) -> Unit = {},
    section: String? = null,
) {
    DisposableEffect(focusMemory, route) {
        focusMemory.beginRestore(route)
        onDispose { focusMemory.settleRestore(route) }
    }
    val restoreContext = context?.let(focusMemory::activateContext) ?: focusMemory.contextForRoute(route)
    val saved =
        if (section == null) {
            focusMemory.lastForRoute(route, restoreContext)
        } else {
            focusMemory.lastInSection(route, section, restoreContext)
        }
    TvPendingFocusRestore(
        route = route,
        focusMemory = focusMemory,
        saved = saved,
        candidates = candidates,
        fallback = fallback,
        contentGeneration = contentGeneration,
        scrollToAnchor = scrollToAnchor,
    )
}

/**
 * A scrolling row's part in its page's restore: while the page's entry is still waiting and the
 * saved card is in this row, bring the card into view — only when it is out of view — and focus
 * it, or its nearest neighbour when it has gone. It begins no entry of its own.
 */
@Composable
internal fun TvRestoreSectionFocusEffect(
    route: String,
    focusMemory: TvUiFocusMemory,
    saved: FocusAnchor,
    candidates: List<FocusCandidate>,
    contentGeneration: Any?,
    scrollToAnchor: suspend (FocusAnchor) -> Unit,
) {
    TvPendingFocusRestore(
        route = route,
        focusMemory = focusMemory,
        saved = saved,
        candidates = candidates,
        fallback = null,
        contentGeneration = contentGeneration,
        scrollToAnchor = scrollToAnchor,
    )
}

@Composable
private fun TvPendingFocusRestore(
    route: String,
    focusMemory: TvUiFocusMemory,
    saved: FocusAnchor?,
    candidates: List<FocusCandidate>,
    fallback: FocusRequester?,
    contentGeneration: Any?,
    scrollToAnchor: suspend (FocusAnchor) -> Unit,
) {
    val latestSaved by rememberUpdatedState(saved)
    val latestCandidates by rememberUpdatedState(candidates)
    val latestFallback by rememberUpdatedState(fallback)
    val latestScrollToAnchor by rememberUpdatedState(scrollToAnchor)
    val policy = remember { FocusRestorePolicy() }
    LaunchedEffect(focusMemory, route, contentGeneration) {
        if (!focusMemory.restorePending(route)) return@LaunchedEffect
        val anchor = latestSaved
        val decision =
            anchor?.let {
                val target = FocusTargetId(it.sectionId, it.itemStableId)
                policy.resolve(
                    FocusRestoreRequest(
                        context = it.context,
                        candidates =
                            latestCandidates.ifEmpty {
                                listOf(FocusCandidate(target, it.sectionId, it.itemStableId, it.fallbackIndex))
                            },
                        preferredTargetId = target,
                    ),
                    it,
                )
            }
        val candidate = decision?.candidate
        val resolved = decision?.anchor
        if (candidate != null && resolved != null) latestScrollToAnchor(resolved)
        // The saved card gets these frames to attach; the fallback only comes after them, which
        // also lets the shell's own first focus on the rail land before a page takes it.
        repeat(RESTORE_ATTEMPT_FRAMES) {
            withFrameNanos { }
            if (!focusMemory.restorePending(route)) return@LaunchedEffect
            if (candidate != null && focusMemory.requesterRegistry.requestFocus(candidate.targetId)) {
                return@LaunchedEffect
            }
        }
        val fallbackRequester = latestFallback ?: return@LaunchedEffect
        runCatching { fallbackRequester.requestFocus() }
    }
}

private const val RESTORE_ATTEMPT_FRAMES = 4

@Composable
internal fun TvMediaRow(
    title: String,
    sectionKey: String,
    items: List<TvMediaCardModel>,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    modifier: Modifier = Modifier,
    firstFocusRequester: FocusRequester? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val rowState = focusMemory.rowState(sectionKey)
    val route = tvFocusRoute(sectionKey)
    val saved = focusMemory.lastForRoute(route)
    if (saved != null && saved.sectionId == sectionKey) {
        val candidates =
            items.mapIndexed { index, item ->
                FocusCandidate(
                    targetId = focusMemory.targetId(sectionKey, item.stableId),
                    sectionId = sectionKey,
                    itemStableId = item.stableId,
                    index = index,
                )
            } +
                listOfNotNull(
                    onSeeAll?.let {
                        val stableId = "$sectionKey:see-all"
                        FocusCandidate(
                            targetId = focusMemory.targetId(sectionKey, stableId),
                            sectionId = sectionKey,
                            itemStableId = stableId,
                            index = items.size,
                        )
                    },
                )
        TvRestoreSectionFocusEffect(
            route = route,
            focusMemory = focusMemory,
            saved = saved,
            candidates = candidates,
            contentGeneration = items.map(TvMediaCardModel::stableId),
            scrollToAnchor = { anchor ->
                if (anchor.sectionId == sectionKey) {
                    rowState.revealForRestore(
                        anchor.fallbackIndex.coerceIn(0, candidates.lastIndex),
                        anchor.scrollOffset,
                    )
                }
            },
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = TvOnSurface,
                fontSize = TvType.section,
                fontWeight = FontWeight.Bold,
            )
            if (onSeeAll != null) {
                Text(
                    text = "查看全部",
                    color = TvOnSurfaceMuted,
                    fontSize = TvType.caption,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, item -> "$sectionKey:${item.stableId}" }) { index, item ->
                TvMediaCard(
                    model = item,
                    focusScope = sectionKey,
                    focusMemory = focusMemory,
                    focusRequester = if (index == 0) firstFocusRequester else null,
                    navigationRequester = navigationRequester,
                    returnToNavigationOnLeft = index == 0,
                    fallbackIndex = index,
                )
            }
            if (onSeeAll != null) {
                item(key = "$sectionKey:see-all") {
                    TvFocusableSurface(
                        stableId = "$sectionKey:see-all",
                        focusScope = sectionKey,
                        focusMemory = focusMemory,
                        onClick = onSeeAll,
                        modifier = Modifier.width(116.dp).height(180.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("›", color = TvOnSurface, fontSize = TvType.display)
                            Text("查看全部", color = TvOnSurfaceMuted, fontSize = TvType.caption)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvEmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusScope: String,
    focusMemory: TvUiFocusMemory,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(TvSafeHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(description, color = TvOnSurfaceMuted, fontSize = TvType.body)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            TvActionButton(
                label = actionLabel,
                stableId = "$focusScope:empty-action",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onClick = onAction,
                modifier = Modifier.width(180.dp),
                primary = true,
                focusRequester = focusRequester,
                navigationRequester = navigationRequester,
                returnToNavigationOnLeft = true,
            )
        }
    }
}

@Composable
internal fun TvLoadingState(label: String = "正在加载") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(TvAccent),
            )
            Spacer(Modifier.height(12.dp))
            Text(label, color = TvOnSurfaceMuted, fontSize = TvType.body)
        }
    }
}
