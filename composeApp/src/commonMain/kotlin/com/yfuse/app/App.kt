package com.yfuse.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MiniPlayerTokens
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.overlayGlass
import com.yfuse.core.designsystem.shadow
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.home.HomeTabScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchScreen
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.feature.watch.InviteResolution
import com.yfuse.feature.watch.WatchInviteResolver
import com.yfuse.feature.watch.WatchInviteSheet
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

private data class TabItem(val tab: Tab, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.Home, "首页", AppIcons.Home),
    TabItem(Tab.Browse, "库", AppIcons.Grid),
    TabItem(Tab.Search, "搜索", AppIcons.SearchTab),
    TabItem(Tab.Profile, "我的", AppIcons.User),
)

/** `.tab` inactive tint. */
private val TabInactive = Color(0xFF95A0B3)

/** Space scrollable content leaves for the floating bar — `padding-bottom:100px`. */
val TabBarInset = Dimens.contentBottom

/**
 * The prototype hides the bar while the user scrolls down and brings it back on the
 * way up: `transform:translateY(90px);opacity:0` over `.3s ease`.
 */
@Stable
class BottomBarState {
    var hidden by mutableStateOf(false)
        internal set

    internal fun onScroll(deltaY: Float, offsetY: Float) {
        when {
            offsetY < 24f -> hidden = false
            deltaY < -4f -> hidden = true
            deltaY > 4f -> hidden = false
        }
    }
}

val LocalBottomBar = staticCompositionLocalOf { BottomBarState() }

/** Attach to a scrollable so it drives the floating bar's show / hide. */
@Composable
fun Modifier.hideBottomBarOnScroll(): Modifier {
    val bar = LocalBottomBar.current
    val connection = remember(bar) {
        object : NestedScrollConnection {
            private var offset = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                offset = (offset - available.y).coerceAtLeast(0f)
                bar.onScroll(available.y, offset)
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

@Composable
fun App(root: RootComponent) {
    val mode by root.themePreferences.mode.collectAsState()
    val accent by root.themePreferences.accent.collectAsState()
    val reduceTransparency by root.themePreferences.reduceTransparency.collectAsState()
    val largeText by root.themePreferences.largeText.collectAsState()
    val reduceMotion by root.themePreferences.reduceMotion.collectAsState()
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    YfuseTheme(
        dark = dark,
        accent = accent,
        accessibility = AccessibilityOptions(
            reduceTransparency = reduceTransparency,
            largeText = largeText,
            reduceMotion = reduceMotion,
        ),
    ) {
        val active by root.activeTab.subscribeAsState()
        val homeStack by root.home.stack.subscribeAsState()
        val browseStack by root.browse.stack.subscribeAsState()
        val searchStack by root.search.stack.subscribeAsState()
        val profileStack by root.profile.stack.subscribeAsState()
        val miniPlayback by ActivePlayback.state.collectAsState()
        val scope = rememberCoroutineScope()

        // Watch-together lives above the tabs: an invite can arrive from a chat app at any
        // moment, and an active room has to stay visible after the player is dismissed —
        // the client is a singleton, so without this the user could be in a room with no
        // indication anywhere in the app.
        val watchTogether = remember { GlobalContext.get().get<WatchTogetherClient>() }
        val inviteResolver = remember { GlobalContext.get().get<WatchInviteResolver>() }
        val watchPreferences = remember { GlobalContext.get().get<WatchTogetherPreferences>() }
        val watchState by watchTogether.state.collectAsState()
        val watchEndpoint by watchPreferences.endpoint.collectAsState()
        val pendingInvite by root.pendingInvite.collectAsState()

        var inviteResolution by remember {
            mutableStateOf<InviteResolution>(InviteResolution.Resolving)
        }
        LaunchedEffect(pendingInvite) {
            val invite = pendingInvite ?: return@LaunchedEffect
            inviteResolution = InviteResolution.Resolving
            inviteResolution = inviteResolver.resolve(invite)
        }

        val watchRoomNote = when {
            !watchState.connected -> null
            watchState.reconnecting -> "一起看 · 重连中"
            watchState.isHost -> "一起看 · 房主 · ${watchState.participantCount} 人"
            else -> "一起看 · ${watchState.participantCount} 人"
        }

        // The bar belongs to the four roots; any pushed page (detail, grid, add
        // server, player) owns the whole screen.
        val atRoot = when (active) {
            Tab.Home -> homeStack.active.instance is HomeTabComponent.Child.Home
            Tab.Browse -> browseStack.active.instance is LibraryComponent.Child.Home
            Tab.Search -> searchStack.active.instance is SearchComponent.Child.Home
            Tab.Profile -> profileStack.active.instance is ProfileTabComponent.Child.Home
        }
        val childCanGoBack = !atRoot
        val showBottomBar = atRoot ||
            (active == Tab.Browse && browseStack.active.instance is LibraryComponent.Child.Grid)
        PlatformBackHandler(enabled = childCanGoBack || active != Tab.Home) {
            if (childCanGoBack) {
                when (active) {
                    Tab.Home -> root.home.navigateBack()
                    Tab.Browse -> root.browse.navigateBack()
                    Tab.Search -> root.search.navigateBack()
                    Tab.Profile -> root.profile.navigateBack()
                }
            } else {
                root.selectTab(Tab.Home)
            }
        }

        val bottomBar = remember { BottomBarState() }

        CompositionLocalProvider(LocalBottomBar provides bottomBar) {
            AppBackdrop {
                when (active) {
                    Tab.Home -> HomeTabScreen(root.home)
                    Tab.Browse -> LibraryScreen(root.browse)
                    Tab.Search -> SearchScreen(root.search)
                    Tab.Profile -> ProfileTabScreen(root.profile)
                }

                if (showBottomBar) {
                    // `transform:translateY(90px);opacity:0` — 90px, .3s ease.
                    val shift by animateFloatAsState(
                        targetValue = if (bottomBar.hidden) 90f else 0f,
                        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 300),
                        label = "tabBarShift",
                    )
                    val fade by animateFloatAsState(
                        targetValue = if (bottomBar.hidden) 0f else 1f,
                        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 300),
                        label = "tabBarFade",
                    )
                    GlassTabBar(
                        active = active,
                        onSelect = root::selectTab,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .graphicsLayer {
                                translationY = shift.dp.toPx()
                                alpha = fade
                            },
                    )
                    if (miniPlayback.active) {
                        MiniPlayer(
                            title = miniPlayback.title,
                            playing = miniPlayback.playing,
                            roomNote = watchRoomNote,
                            progress = if (miniPlayback.durationMs > 0L) {
                                miniPlayback.positionMs.toFloat() / miniPlayback.durationMs
                            } else {
                                0f
                            },
                            onOpen = ActivePlayback::open,
                            onToggle = ActivePlayback::toggle,
                            onClose = ActivePlayback::close,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = Dimens.tabBarInset)
                                .padding(bottom = Dimens.tabBarHeight + 22.dp),
                        )
                    }
                }

                pendingInvite?.let { invite ->
                    WatchInviteSheet(
                        roomCode = invite.roomCode,
                        resolution = inviteResolution,
                        unfamiliarEndpoint = invite.endpoint
                            ?.takeIf { it.trimEnd('/') != watchEndpoint.trimEnd('/') },
                        onJoin = {
                            scope.launch {
                                val target = inviteResolver.resolveTarget(invite) ?: return@launch
                                // Join before navigating: the client is a singleton, so the
                                // player picks the room up from its state on start and the
                                // guest reconcile loop syncs from the timeline — no need to
                                // thread a room code through the launch path.
                                watchTogether.joinRoom(
                                    endpoint = invite.endpoint ?: watchEndpoint,
                                    roomCode = invite.roomCode,
                                    mediaKey = invite.mediaKey.orEmpty(),
                                )
                                root.openWatchTarget(target.server.id, target.item.id)
                            }
                        },
                        onSearchByName = root::openSearchForInvite,
                        onDismiss = root::dismissInvite,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    playing: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null while a watch-together room is live — this is the only place an active
     *  room is visible once the player has been dismissed. */
    roomNote: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(Shadows.tabBar, GlassShapes.card)
            .overlayGlass(
                GlassShapes.card,
                MiniPlayerTokens.fill,
                MiniPlayerTokens.border,
            )
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(GlassShapes.thumb)
                .background(MiniPlayerTokens.artwork),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { "正在播放" }, style = mr(11.5f, 650), color = Color.White, maxLines = 1)
            if (roomNote != null) {
                Spacer(Modifier.height(2.dp))
                Text(roomNote, style = mr(9.5f, 500), color = Brand.Primary, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.18f))) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Brand.Primary),
                )
            }
        }
        Icon(
            if (playing) AppIcons.Pause else AppIcons.Play,
            contentDescription = if (playing) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier
                .size(34.dp)
                .clickable(onClick = onToggle)
                .padding(8.dp),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "关闭播放器",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onClose)
                .padding(8.dp),
        )
    }
}

/**
 * `.tabbar` — 浮层: left/right 14, bottom 14, height 62, radius 26 (大档), glass fill,
 * 1px hairline, `0 12px 30px rgba(60,90,150,.18)`, items spaced `space-around`.
 *
 * §3 fixes the bottom stack as 内容 → 迷你播放器 → tab bar, with the mini player sharing
 * this material, radius and horizontal inset so the two read as one continuous overlay.
 */
@Composable
private fun GlassTabBar(active: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.tabBarInset)
            .padding(bottom = Dimens.tabBarInset)
            .height(Dimens.tabBarHeight)
            .shadow(Shadows.tabBar, GlassShapes.tabBar)
            .overlayGlass(GlassShapes.tabBar, palette.glassStrong, palette.tabbarBorder),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            TabButton(item = item, selected = active == item.tab, onClick = { onSelect(item.tab) })
        }
    }
}

/**
 * `.tab` — column, `gap:3px`, `font:500 9.5px Manrope`, 22px icon. Each button
 * takes a full quarter of the bar so the whole cell is tappable, not just the glyph.
 */
@Composable
private fun RowScope.TabButton(item: TabItem, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Brand.Primary else TabInactive
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(GlassShapes.tabBar)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(item.label, style = mr(9.5f, 500), color = tint)
    }
}
