package com.yfuse.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yfuse.tv.focus.FocusRestoreRequest
import com.yfuse.tv.focus.FocusTargetId
import com.yfuse.tv.focus.InMemoryFocusRepository
import com.yfuse.tv.focus.RemoteIntent
import com.yfuse.tv.focus.RestoreTvFocusEffect
import com.yfuse.tv.focus.TvFocusRequesterRegistry
import com.yfuse.tv.focus.tvFocusTarget
import com.yfuse.tv.focus.tvRemoteKeyHandler

internal val TvSafeHorizontal = 48.dp
internal val TvSafeVertical = 27.dp
internal val TvRailWidth = 184.dp

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

    fun remember(
        scope: String,
        stableId: String,
        serverId: String? = null,
        profileId: String? = null,
    ) {
        anchors[scope] = stableId
        val route = scope.substringBefore(':')
        routeContexts[route] = FocusContext(route, serverId, profileId)
    }

    fun anchor(scope: String): String? = anchors[scope]

    fun targetId(
        scope: String,
        stableId: String,
    ): FocusTargetId = FocusTargetId(scope, stableId)

    fun context(scope: String): FocusContext =
        routeContexts[scope.substringBefore(':')]
            ?: FocusContext(route = scope.substringBefore(':'))

    fun activateContext(context: FocusContext): FocusContext {
        routeContexts[context.route] = context
        return context
    }

    fun contextForRoute(route: String): FocusContext = routeContexts[route] ?: FocusContext(route = route)

    fun lastForRoute(
        route: String,
        context: FocusContext? = null,
    ): FocusAnchor? = repository.last(context ?: contextForRoute(route))

    fun requestLastForRoute(
        route: String,
        context: FocusContext? = null,
    ): Boolean {
        val anchor = lastForRoute(route, context) ?: return false
        return requesterRegistry.requestFocus(FocusTargetId(anchor.sectionId, anchor.itemStableId))
    }

    fun rowState(section: String): LazyListState = rowStates.getOrPut(section) { LazyListState() }

    fun gridState(route: String): LazyGridState = gridStates.getOrPut(route) { LazyGridState() }
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
                        route = focusScope.substringBefore(':'),
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
        modifier = modifier.height(52.dp),
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
 * Restores the last stable target for a route. The saved context includes server/profile, so a
 * card from another household profile cannot receive focus after an account switch.
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
) {
    val restoreContext = context?.let(focusMemory::activateContext) ?: focusMemory.contextForRoute(route)
    val saved = focusMemory.lastForRoute(route, restoreContext)
    if (saved != null) {
        val target = FocusTargetId(saved.sectionId, saved.itemStableId)
        val restoreCandidates =
            candidates.ifEmpty {
                listOf(
                    FocusCandidate(
                        targetId = target,
                        sectionId = saved.sectionId,
                        itemStableId = saved.itemStableId,
                        index = saved.fallbackIndex,
                    ),
                )
            }
        RestoreTvFocusEffect(
            request =
                FocusRestoreRequest(
                    context = saved.context,
                    candidates = restoreCandidates,
                    preferredTargetId = target,
                ),
            repository = focusMemory.repository,
            requesterRegistry = focusMemory.requesterRegistry,
            scrollToAnchor = scrollToAnchor,
        )
        LaunchedEffect(route, saved, restoreCandidates, fallback, contentGeneration) {
            repeat(4) { withFrameNanos { } }
            if (restoreCandidates.none { focusMemory.requesterRegistry.contains(it.targetId) }) {
                fallback?.let { runCatching { it.requestFocus() } }
            }
        }
    } else if (fallback != null) {
        LaunchedEffect(route, fallback, contentGeneration) {
            withFrameNanos { }
            runCatching { fallback.requestFocus() }
        }
    }
}

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
    val route = sectionKey.substringBefore(':')
    val saved = focusMemory.lastForRoute(route)
    if (saved?.sectionId == sectionKey) {
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
        TvRestoreRouteFocusEffect(
            route = route,
            focusMemory = focusMemory,
            contentGeneration = items.map(TvMediaCardModel::stableId),
            context = saved.context,
            candidates = candidates,
            scrollToAnchor = { anchor ->
                if (anchor.sectionId == sectionKey) {
                    rowState.scrollToItem(
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
