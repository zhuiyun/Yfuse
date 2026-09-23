package com.yfuse.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalOverlayVisibility
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalPulseSweepEnabled
import com.yfuse.core.designsystem.LocalTabIdentity
import com.yfuse.core.designsystem.LocalTabReselected
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.SearchDockOrigin
import com.yfuse.core.designsystem.SkeletonPulseProvider
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.attentionSweep
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.drawLensIsland
import com.yfuse.core.designsystem.drawMotionSweep
import com.yfuse.core.designsystem.drawPhaseLight
import com.yfuse.core.designsystem.liquidNavigationGlass
import com.yfuse.core.designsystem.navigationGlass
import com.yfuse.core.designsystem.platformAnimationsDisabled
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.rememberPhaseLightCount
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.core.designsystem.searchDockSource
import com.yfuse.core.network.LocalNetworkAccessNotice
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.home.HomeTabScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.feature.player.PlaybackReportingWarning
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchScreen
import com.yfuse.feature.servers.ServersTabScreen
import com.yfuse.feature.watch.InviteResolution
import com.yfuse.feature.watch.WatchInviteSheet
import com.yfuse.feature.watch.WatchRoomInfoDialog
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private data class TabItem(
    val tab: Tab,
    val label: String,
    val icon: ImageVector,
)

internal data class NavigationGlassVisuals(
    val shell: Color,
    val selection: Color,
)

/**
 * The navigation furniture's plate and pill for the two fallback materials — 毛玻璃 and
 * 减弱透明度. The liquid style draws a lens instead: see `Modifier.navigationGlass` and
 * `drawLensIsland`.
 */
internal fun navigationGlassVisuals(
    palette: com.yfuse.core.designsystem.Palette,
    accent: com.yfuse.core.designsystem.AccentColors,
): NavigationGlassVisuals =
    NavigationGlassVisuals(
        shell = palette.glassStrong,
        selection = accent.container.copy(alpha = if (palette.isDark) 0.44f else 0.58f),
    )

/**
 * The four destinations in the bar.
 *
 * 搜索 left the row and became [SearchButton], a control of its own beside it. It was the odd
 * one out: the other four are places the app can be in — each keeps a back stack, each is
 * where you end up and stay — while search is a thing you do to get somewhere and leave. As a
 * fifth equal cell it also cost the other four a fifth of the bar, and made the row a set of
 * five narrow targets rather than four comfortable ones.
 */
private val tabs =
    listOf(
        TabItem(Tab.Home, "首页", AppIcons.TabHome),
        TabItem(Tab.Browse, "库", AppIcons.TabLibrary),
        TabItem(Tab.Servers, "服务器", AppIcons.TabServers),
        TabItem(Tab.Profile, "我的", AppIcons.TabProfile),
    )

@Composable
fun App(root: RootComponent) {
    BindBackgroundServices(root)
    val mode by root.themePreferences.mode.collectAsState()
    val reduceTransparency by root.themePreferences.reduceTransparency.collectAsState()
    val largeText by root.themePreferences.largeText.collectAsState()
    val reduceMotion by root.themePreferences.reduceMotion.collectAsState()
    // 「移除动画」 on the device is the same request as our own 减弱动态效果, so the two are one
    // effective value from here down. Without this the app kept animating for a user who had
    // switched animation off system-wide — the only surface that honoured it was the player,
    // which reads the setting itself for its window transitions. The user's own switch still
    // travels separately, for the few places where motion is a gesture rather than a duration:
    // see [AccessibilityOptions.reduceMotionByUser].
    val systemMotionOff = platformAnimationsDisabled()
    val motionOff = reduceMotion || systemMotionOff
    val pulseSweep by root.themePreferences.pulseSweep.collectAsState()
    val particleLight by root.themePreferences.particleLight.collectAsState()
    val dialogAnimation by root.themePreferences.dialogAnimation.collectAsState()
    val glassStyle by root.themePreferences.glassStyle.collectAsState()
    val loadingAnimation by root.themePreferences.loadingAnimation.collectAsState()
    val glassMaterials by root.themePreferences.glassMaterials.collectAsState()
    val backgroundImage by root.themePreferences.backgroundImage.collectAsState()
    val backgroundDim by root.themePreferences.backgroundDim.collectAsState()
    val dark = mode.resolveDark(isSystemInDarkTheme())

    YfuseTheme(
        dark = dark,
        accessibility =
            AccessibilityOptions(
                reduceTransparency = reduceTransparency,
                largeText = largeText,
                reduceMotion = motionOff,
                reduceMotionByUser = reduceMotion,
            ),
        // 减弱透明度 is an accessibility contract: it exists to make every surface opaque and
        // legible, so a decorative material choice must not be able to reinstate the effect
        // it turns off.
        glassStyle = if (reduceTransparency) GlassStyle.Frosted else glassStyle,
        dialogAnimation = dialogAnimation,
        loadingAnimation = loadingAnimation,
        glassMaterials = glassMaterials,
        particleLight = particleLight,
    ) {
        BindProductServices(root)
        val savedServers by root.dependencies.serverRegistry.data
            .collectAsState()
        val permissionScope = rememberCoroutineScope()
        LocalNetworkAccessNotice(hasServers = savedServers.servers.isNotEmpty()) {
            permissionScope.launch { root.dependencies.serverHealthMonitor.refreshAll() }
        }
        val active by root.activeTab.subscribeAsState()
        val homeStack by root.home.stack.subscribeAsState()
        val browseStack by root.browse.stack.subscribeAsState()
        val searchStack by root.search.stack.subscribeAsState()
        val profileStack by root.profile.stack.subscribeAsState()
        val miniPlayback by ActivePlayback.state.collectAsState()
        val reportingCoordinator = root.dependencies.playbackReportingCoordinator
        PlaybackReportingWarning(reportingCoordinator)
        LaunchedEffect(reportingCoordinator) {
            reportingCoordinator.flushPending()
        }
        // Watch-together lives above the tabs: an invite can arrive from a chat app at any
        // moment, and an active room has to stay visible after the player is dismissed —
        // the client is a singleton, so without this the user could be in a room with no
        // indication anywhere in the app.
        val watchTogether = root.dependencies.watchTogether
        val inviteResolver = root.dependencies.inviteResolver
        val watchState by watchTogether.state.collectAsState()
        val pendingInvite by root.pendingInvite.collectAsState()
        val accountState by root.dependencies.account.state
            .collectAsState()
        val watchAvailable = accountState.canUseWatchTogether()

        var inviteResolution by remember {
            mutableStateOf<InviteResolution>(InviteResolution.Resolving)
        }
        LaunchedEffect(pendingInvite, watchAvailable) {
            val invite = pendingInvite ?: return@LaunchedEffect
            if (!watchAvailable) return@LaunchedEffect
            inviteResolution = InviteResolution.Resolving
            if (invite.unsupportedEndpoint == null) {
                inviteResolution = inviteResolver.resolve(invite)
            }
        }

        // Following a room that was joined by code alone (「我的」→ 加入一起看).
        //
        // That entry has no media context, so it enters the room with an empty mediaKey and
        // there is nothing to open — joining looked completely inert from the guest's side
        // while the host's player correctly showed two people in the room. An invite link
        // avoids this only because its sheet resolves the title *before* joining; a typed
        // code has to take the room's own timeline as the answer instead, which is what this
        // does the moment the server hands one over.
        // Keyed by room *and* media, not room alone: the host changing what the room is
        // watching has to be followed too, and a lookup that came back empty must be free
        // to succeed on the next title rather than writing the room off for good.
        var followed by remember { mutableStateOf<Pair<String, String>?>(null) }
        LaunchedEffect(watchState.roomCode, watchState.mediaKey, watchState.isHost) {
            val roomCode = watchState.roomCode
            if (roomCode == null) {
                // Left the room; a later re-join of the same code has to be followed again.
                followed = null
                return@LaunchedEffect
            }
            // A host already knows what it is playing, and a player that is already up
            // reconciles from the timeline on its own.
            if (watchState.isHost || miniPlayback.active) return@LaunchedEffect
            val mediaKey = watchState.mediaKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            if (followed == roomCode to mediaKey) return@LaunchedEffect
            // Resolving and opening is the same work as entering a room by hand, and lives
            // with it — this effect owns only the decision to do it unasked, which is what
            // the guard above is. 「我的」→ 一起看 → 进入房间 is the way back once this has
            // fired and the user has since left the player.
            if (root.followWatchRoom()) followed = roomCode to mediaKey
        }

        // The bar belongs to the four roots; any pushed page (detail, grid, add
        // server, player) owns the whole screen.
        val atRoot =
            when (active) {
                Tab.Home -> homeStack.active.instance is HomeTabComponent.Child.Home
                Tab.Browse -> browseStack.active.instance is LibraryComponent.Child.Home
                // 服务器 is a single screen: it has no stack that could be anywhere but its root.
                Tab.Servers -> true
                Tab.Search -> searchStack.active.instance is SearchComponent.Child.Home
                Tab.Profile -> profileStack.active.instance is ProfileTabComponent.Child.Home
            }
        // The bar belongs to the roots and nothing else: it used to also ride along on the
        // library's grid, and to slide away under scroll, which left "is the bar there?"
        // depending on where the user happened to have scrolled to.
        val showBottomBar = atRoot

        // An overlay owned by one of the tab screens composes below this shell's floating
        // furniture, so the bar has to be told to get out of its way. The visibility is the
        // theme's own: the dialog backdrop capture listens to the same object, and a second one
        // provided here would leave that capture waiting on a counter no dialog ever reaches.
        val overlays = LocalOverlayVisibility.current

        var roomInfoOpen by remember { mutableStateOf(false) }

        // Each tab keeps its own saved state — above all, where it was scrolled to.
        //
        // Only the active tab is composed, so switching away used to discard the outgoing
        // tab's state outright: `rememberLazyListState` is saveable, but nothing was holding
        // its saved value once the branch left the tree, and every switch landed the user
        // back at the top of the page they had already scrolled through.
        val tabStates = rememberSaveableStateHolder()
        // What the floating bottom furniture blurs. The page is captured here and the bar
        // is a sibling drawn after it, which is the arrangement that keeps the bar out of
        // its own backdrop — see [backdropSource].
        val backdrop = rememberBackdropState()
        // Whether anything is sampling [backdrop]. The dock is its only consumer, and it is
        // composed exactly while it is visible or still animating out — so a pushed page, which
        // owns the whole screen and may capture a backdrop of its own, is not also recorded here.
        // Written from the dock's effect and read only inside the capture's draw.
        val dockOnScreen = remember { mutableStateOf(false) }
        CompositionLocalProvider(
            LocalPulseSweepEnabled provides pulseSweep,
            LocalTabReselected provides root.tabReselected,
        ) {
            SkeletonPulseProvider {
                AppBackdrop(
                    // A wallpaper is decoration, and 减弱透明度 is the switch for people who
                    // need the page to be a flat readable surface. It wins.
                    imageUri = backgroundImage.takeUnless { reduceTransparency },
                    dim = backgroundDim,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        val onSelectTab: (Tab) -> Unit = { tab ->
                            if (tab == active) {
                                root.reselectTab(tab, atRoot)
                            } else {
                                root.selectTab(tab)
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .backdropSource(backdrop, record = { dockOnScreen.value }),
                        ) {
                            val previousRootTab = remember { arrayOf(active) }
                            val rootMotion = remember(active) { rootTabMotion(previousRootTab[0], active) }
                            SideEffect { previousRootTab[0] = active }
                            // Top-level tabs are a real Navigation 3 back stack, while each tab's
                            // nested host continues to own its child routes. This host opts into
                            // equal-level root motion; nested stacks own their push/pop gestures.
                            OfficialNavDisplay(
                                backStack = topLevelBackStack(active),
                                onBack = { root.selectTab(Tab.Home) },
                                contentKey = { "tab:${it.name}" },
                                modifier = Modifier.fillMaxSize(),
                                motion = rootMotion,
                            ) { tab ->
                                CompositionLocalProvider(LocalTabIdentity provides tab.name) {
                                    tabStates.SaveableStateProvider(tab.name) {
                                        when (tab) {
                                            Tab.Home ->
                                                com.yfuse.feature.personal.PersonalDiscoveryGuard(
                                                    onOpenLibrary = { root.selectTab(Tab.Browse) },
                                                ) { HomeTabScreen(root.home) }
                                            Tab.Browse -> LibraryScreen(root.browse)
                                            Tab.Servers -> ServersTabScreen(root.servers)
                                            Tab.Search -> SearchScreen(root.search)
                                            Tab.Profile -> ProfileTabScreen(root.profile)
                                        }
                                    }
                                }
                            }
                        }

                        // The dock leaves with the page rather than in one frame. Pushing a detail
                        // route animates the content over Motion.PUSH while the bar was simply
                        // dropped out of composition, so the one piece of furniture that stays
                        // still across the whole app was also the only thing that ever blinked.
                        // Whether the dock is *wanted* still follows [showBottomBar] alone, so
                        // nothing that reasons about the bar's presence is waiting on an animation.
                        val dockShown = showBottomBar && overlays?.any != true
                        // The dock leaves when a route is pushed and comes back when one is popped,
                        // so those are its durations — they used to be the other way round, which
                        // made the bar linger after the page it belonged to had already gone.
                        val dockEnter = if (motionOff) 0 else Motion.POP
                        val dockExit = if (motionOff) 0 else Motion.PUSH
                        val dockEnterTransition =
                            fadeIn(tween(dockEnter, easing = Motion.Curve)) +
                                slideInVertically(
                                    animationSpec = tween(dockEnter, easing = Motion.Curve),
                                    // Half its own height, not all of it: the bar is furniture
                                    // settling back into place, and a full-height slide reads as
                                    // a separate object flying in from off-screen.
                                    initialOffsetY = { it / 2 },
                                )
                        val dockExitTransition =
                            fadeOut(tween(dockExit, easing = Motion.Curve)) +
                                slideOutVertically(
                                    animationSpec = tween(dockExit, easing = Motion.Curve),
                                    targetOffsetY = { it / 2 },
                                )
                        AnimatedVisibility(
                            visible = dockShown,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding(),
                            enter = dockEnterTransition,
                            exit = dockExitTransition,
                            label = "bottomNavigationDock",
                        ) {
                            DisposableEffect(dockOnScreen) {
                                dockOnScreen.value = true
                                onDispose { dockOnScreen.value = false }
                            }
                            BottomNavigationDock(
                                active = active,
                                onSelect = onSelectTab,
                                onSearch = { onSelectTab(Tab.Search) },
                                backdrop = backdrop,
                                cueKey = pendingInvite?.roomCode,
                            )
                        }
                        // One slot above the tab bar, and the two things that can occupy it
                        // never coexist: while a player is alive the mini player carries the
                        // room note itself, and the room bar is for exactly the case where it
                        // isn't — the player closed, the room still up.
                        val bottomStackSlot =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .widthIn(max = 520.dp)
                                .padding(horizontal = Dimens.tabBarInset)
                                // The dock's own height — it grows with the font scale — plus
                                // its margin below and a step of air above it.
                                .padding(bottom = dockHeight() + Dimens.tabBarInset + Dimens.space.sm)
                        // Video backgrounding is represented by Android PiP. The old long,
                        // music-like mini controller duplicated transport controls and only
                        // appeared at tab roots, so it is intentionally not rendered here.
                        //
                        // The bar rides the dock's own enter and exit. It sits on top of the dock
                        // and used to be dropped straight out of composition while the dock below
                        // it slid away, so the pair came apart every time a route was pushed.
                        ActivityStatusCapsule(
                            root = root,
                            onRoomInfo = { roomInfoOpen = true },
                            modifier = bottomStackSlot,
                            visible = dockShown && !miniPlayback.active,
                            enter = dockEnterTransition,
                            exit = dockExitTransition,
                        )

                        // A room survives the process: the client keeps the capabilities the
                        // server granted, so a restart can offer to go back instead of making
                        // the guest hunt for the invite again. Declining forgets the room.
                        val resumableRoom by watchTogether.resumableRoom.collectAsState()
                        val rejoinOffer =
                            resumableRoom?.takeIf {
                                watchAvailable &&
                                    watchState.roomCode == null &&
                                    !watchState.connecting &&
                                    !watchState.reconnecting
                            }
                        var rejoinDeclinedFor by remember { mutableStateOf<String?>(null) }
                        if (rejoinOffer != null && rejoinDeclinedFor != rejoinOffer.roomCode) {
                            ConfirmDialog(
                                title = "回到上次的一起看房间？",
                                message = "房间 ${rejoinOffer.roomCode} 还在，你上次离开时没有退出。",
                                confirmLabel = "回到房间",
                                dismissLabel = "不用了",
                                onConfirm = {
                                    watchTogether.rejoinPersistedRoom(WatchTogetherPreferences.DEFAULT_ENDPOINT)
                                },
                                onDismiss = {
                                    rejoinDeclinedFor = rejoinOffer.roomCode
                                    watchTogether.discardPersistedRoom()
                                },
                            )
                        }

                        if (roomInfoOpen) {
                            WatchRoomInfoDialog(
                                state = watchState,
                                resolver = inviteResolver,
                                onEnter = root::enterWatchRoom,
                                onDismiss = { roomInfoOpen = false },
                            )
                        }

                        pendingInvite?.let { invite ->
                            if (invite.unsupportedEndpoint != null || watchAvailable) {
                                WatchInviteSheet(
                                    roomCode = invite.roomCode,
                                    resolution = inviteResolution,
                                    unsupportedEndpoint = invite.unsupportedEndpoint,
                                    onJoin = {
                                        // Join, and let the room say what it is playing.
                                        //
                                        // This used to resolve `invite.mediaKey` and navigate to that.
                                        // A link is written when the room is created, which for a show
                                        // is before the host has started an episode — so its key names
                                        // the *show*, and resolving it landed the guest on the series,
                                        // which auto-plays whatever episode *they* were up to. Two
                                        // people, two different episodes, every time.
                                        //
                                        // The room's own timeline names the episode, and the shell
                                        // already follows it (see the effect above), so joining is the
                                        // whole of the work. The invite's key keeps its other job:
                                        // naming the title in the sheet before any of this happens.
                                        watchTogether.joinRoomFromInvite(
                                            endpoint = WatchTogetherPreferences.DEFAULT_ENDPOINT,
                                            roomCode = invite.roomCode,
                                            mediaKey = invite.mediaKey.orEmpty(),
                                        )
                                        root.dismissInvite()
                                    },
                                    onSearchByName = root::openSearchForInvite,
                                    onDismiss = root::dismissInvite,
                                )
                            } else if (accountState !is AccountState.Restoring) {
                                ConfirmDialog(
                                    title = "登录后使用一起看",
                                    message = "一起看房间会绑定你的 Yfuse 账号。请先到“我的”登录，再重新打开邀请。",
                                    confirmLabel = "去登录",
                                    onConfirm = {
                                        root.dismissInvite()
                                        root.selectTab(Tab.Profile)
                                    },
                                    onDismiss = root::dismissInvite,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun topLevelBackStack(active: Tab): List<Tab> =
    if (active == Tab.Home) listOf(Tab.Home) else listOf(Tab.Home, active)

/** The glyph box inside a tab cell, and the glyph inside it — see [LiquidGlassTabIcon]. */
private val DockIconBox = 34.dp
private val DockIconGlyph = 25.dp

/** What is left of [Dimens.tabBarHeight] around the glyph box and one caption line at 1× type. */
private val DockVerticalPadding = 13.dp

/**
 * The dock's height: [Dimens.tabBarHeight], or as tall as the glyph and its caption need.
 *
 * 62dp holds a 34dp glyph box and one caption line at the default font scale. Under 大号文字
 * on top of a large system font the caption line alone grows past what is left, and a fixed
 * height clipped the label. The density read here is the theme's, so both scales are in it.
 */
@Composable
internal fun dockHeight(): Dp {
    val captionLine =
        with(LocalDensity.current) {
            AppTypography.caption.regular.lineHeight
                .toDp()
        }
    return dockHeight(captionLine)
}

internal fun dockHeight(captionLine: Dp): Dp =
    maxOf(Dimens.tabBarHeight, DockIconBox + captionLine + DockVerticalPadding)

/**
 * The bottom furniture: the four destinations in a capsule, and 搜索 as its own round key at
 * the end of the row — one tap from anywhere, which is the point of moving it out of the row.
 */
@Composable
private fun BottomNavigationDock(
    active: Tab,
    onSelect: (Tab) -> Unit,
    onSearch: () -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
    /** Changes to a non-null value when something arrives for the user — a 一起看 invite. */
    cueKey: Any? = null,
) {
    val height = dockHeight()
    Row(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = Dimens.tabBarInset)
            .padding(bottom = Dimens.tabBarInset)
            .height(height)
            // One accent sweep across the dock as the invite lands, before its sheet opens:
            // the bar is where the user is looking, and it is the surface the invite belongs to.
            .attentionSweep(cueKey),
        // The gap between the capsule and 搜索 is the same token as the margin to the screen
        // edge, so the three spaces across the row read as one rhythm.
        horizontalArrangement = Arrangement.spacedBy(Dimens.tabBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassTabBar(
            active = active,
            onSelect = onSelect,
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
            height = height,
        )
        SearchButton(
            selected = active == Tab.Search,
            backdrop = backdrop,
            diameter = height,
            onClick = onSearch,
        )
    }
}

/**
 * 搜索 — a circle of the same material at the end of the row.
 *
 * Round where the tabs are a capsule, because it is not one of them: it does not hold a
 * position in the app, it takes you out of wherever you are and hands you back somewhere
 * else. It is as tall as the capsule beside it — see [dockHeight].
 *
 * Its resting state is the same ink as the tabs, at full size. It used to be grey and
 * shrunk like an unselected tab, which is the language of "not where you are" — but
 * search is not a place, it is an action that is always available, and it should look it.
 * Only while the search page is open does it take the accent and an island of its own.
 */
@Composable
private fun SearchButton(
    selected: Boolean,
    backdrop: BackdropState,
    diameter: Dp,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val liquid = liquidNavigationGlass()
    val navigationGlass = navigationGlassVisuals(palette, accent)
    val tint by animateColorAsState(
        targetValue = if (selected) accent.accent else palette.text,
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "searchTint",
    )
    val islandAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "searchIsland",
    )
    Box(
        Modifier
            .size(diameter)
            .searchDockSource()
            .pressable(
                pressedScale = 0.96f,
                haptic = HapticSignal.Select,
                role = Role.Tab,
                onClickLabel = "搜索",
                onClick = {
                    if (!selected) SearchDockOrigin.begin()
                    onClick()
                },
            ).semantics(mergeDescendants = true) { this.selected = selected }
            .navigationGlass(backdrop, CircleShape)
            .drawBehind {
                if (islandAlpha <= 0f) return@drawBehind
                // The island sits just inside the rim, as the tab pill sits inside the bar.
                val inset = SEARCH_ISLAND_INSET.toPx()
                val rect = Rect(inset, inset, size.width - inset, size.height - inset)
                if (liquid) {
                    drawLensIsland(rect, dark = palette.isDark, accent = accent.accent, alpha = islandAlpha)
                } else {
                    val selection = navigationGlass.selection
                    drawRoundRect(
                        color = selection.copy(alpha = selection.alpha * islandAlpha),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = CornerRadius(rect.height / 2f),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.SearchTab,
            contentDescription = "搜索",
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * `.tabbar` — 浮层: left/right 14, bottom 14, [dockHeight] tall, a full capsule, items spaced
 * `space-around`. The material is the navigation lens — see `Modifier.navigationGlass`.
 *
 * §3 fixes the bottom stack as 内容 → 迷你播放器 → tab bar, with the mini player sharing
 * the horizontal inset so the two read as one continuous overlay.
 */
@Composable
internal fun GlassTabBar(
    active: Tab,
    onSelect: (Tab) -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
    height: Dp = dockHeight(),
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val liquid = liquidNavigationGlass()
    val navigationGlass = navigationGlassVisuals(palette, accent)
    // -1 while 搜索 is open: it is not one of the cells any more, so the pill has nowhere to
    // be and is not drawn rather than parking under 首页 and claiming the user is there.
    val selectedIndex = tabs.indexOfFirst { it.tab == active }
    val hasSelection = selectedIndex >= 0
    val enhanced = LocalPulseSweepEnabled.current
    val highlights = !LocalAccessibilityOptions.current.reduceTransparency && !reduceMotion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val liquidMotion =
        if (enhanced) {
            rememberLiquidTabMotion(selectedIndex, tabs.size, reduceMotion) { onSelect(tabs[it].tab) }
        } else {
            null
        }
    // Two independently sprung edges make the selected glass pull slightly in the direction
    // of travel. The draw phase caps that stretch, so a jump across the bar never turns the
    // indicator into a stripe spanning unrelated icons.
    val defaultMotion = if (enhanced) null else rememberDefaultTabMotion(selectedIndex, reduceMotion)
    val phaseMoving by remember(liquidMotion?.sweep, liquidMotion?.dragging) {
        derivedStateOf { liquidMotion?.dragging == true || (liquidMotion?.sweep?.value ?: 1f) < 1f }
    }
    val phaseLights = rememberPhaseLightCount(phaseMoving)
    val indicatorAlpha =
        animateFloatAsState(
            targetValue = if (hasSelection) 1f else 0f,
            animationSpec =
                if (reduceMotion) {
                    snap()
                } else {
                    tween(Motion.QUICK, easing = Motion.Curve)
                },
            label = "tabIndicatorAlpha",
        )
    Row(
        modifier
            .fillMaxWidth()
            // One group of four, so a screen reader announces "第 2 项，共 4 项" rather than
            // reading four unrelated controls.
            .selectableGroup()
            .then(liquidMotion?.gestures ?: Modifier)
            .height(height)
            // A true capsule rather than a rounded rectangle, so the shell stays soft at the
            // taller bar height.
            .navigationGlass(backdrop, CircleShape)
            // After the material and before the buttons: the island belongs to the glass, not
            // over the icons.
            .drawBehind {
                if (indicatorAlpha.value <= 0f) return@drawBehind
                val cell = size.width / tabs.size
                // The selected region nearly fills its cell — a broad island, not a small
                // Material indicator — so it stays legible over artwork-heavy roots and the
                // quiet ones alike.
                val bounds =
                    tabIndicatorBounds(
                        rawLeft = liquidMotion?.left?.value ?: checkNotNull(defaultMotion).left.value,
                        rawRight = liquidMotion?.right?.value ?: checkNotNull(defaultMotion).right.value,
                        tabCount = tabs.size,
                        maxScale = if (enhanced) 1.8f else TAB_PILL_MAX_SCALE,
                    )
                val pillWidth = cell * bounds.width
                val stretch = (bounds.width / TAB_PILL_WIDTH_FRACTION - 1f).coerceIn(0f, 0.8f)
                val pillHeight = size.height * 0.86f * (1f - if (enhanced) stretch * 0.12f else 0f)
                val left = cell * if (rtl) tabs.size - bounds.left - bounds.width else bounds.left
                val top = (size.height - pillHeight) / 2f
                val alpha = indicatorAlpha.value.coerceIn(0f, 1f)
                if (liquid) {
                    drawLensIsland(
                        rect = Rect(left, top, left + pillWidth, top + pillHeight),
                        dark = palette.isDark,
                        accent = accent.accent,
                        alpha = alpha,
                    )
                } else {
                    val selection = navigationGlass.selection
                    drawRoundRect(
                        color = selection.copy(alpha = selection.alpha * alpha),
                        topLeft = Offset(left, top),
                        size = Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2f),
                    )
                }
                if (liquidMotion != null && highlights) {
                    drawMotionSweep(
                        Rect(left, top, left + pillWidth, top + pillHeight),
                        accent.accent,
                        liquidMotion.sweep.value,
                        alpha,
                    )
                    drawPhaseLight(
                        Rect(left, top, left + pillWidth, top + pillHeight),
                        if (liquidMotion.dragging) 0.5f else liquidMotion.sweep.value,
                        phaseLights,
                        palette.text,
                        trail = true,
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            TabButton(item = item, selected = active == item.tab, onClick = { onSelect(item.tab) })
        }
    }
}

/** Each labeled tab owns its complete touch target and one accessibility node. */
@Composable
private fun RowScope.TabButton(
    item: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Unselected tabs are the page's ink, not a grey: the glyphs are silhouettes now and
    // have to hold their own over artwork. Crossfading the tint puts it on the same spring
    // as the island sliding underneath, so the two halves of one transition stay together.
    val tint by animateColorAsState(
        targetValue = if (selected) accent.accent else palette.text,
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "tabTint",
    )

    Column(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .heightIn(min = MinTouchTarget)
                .clip(CircleShape)
                // This was `clickable(indication = null)` with nothing put back, so the one
                // control every session touches most had no press feedback at all.
                .pressable(
                    pressedScale = 0.96f,
                    haptic = HapticSignal.Select,
                    // Without this every tab was announced as an unlabelled clickable region.
                    role = Role.Tab,
                    onClick = onClick,
                )
                // The visible caption labels the merged tab; its selected state is announced once.
                .semantics(mergeDescendants = true) { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidGlassTabIcon(item = item, tint = tint, selected = selected)
        Text(item.label, style = AppTypography.caption.regular, color = tint, maxLines = 1)
    }
}

/**
 * A tab glyph in its optical box.
 *
 * The glyphs are silhouettes, so there is nothing for the material to do here beyond the
 * tint: the holes cut through them show the lens underneath. Inactive glyphs keep their
 * full optical size; selection adds a restrained spring scale and one-dp lift.
 */
@Composable
private fun LiquidGlassTabIcon(
    item: TabItem,
    tint: Color,
    selected: Boolean = false,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.tabIcon(reduceMotion),
        label = "tabSelectionScale",
    )
    Box(
        Modifier.size(DockIconBox).graphicsLayer {
            scaleX = 1f + 0.10f * emphasis
            scaleY = scaleX
            translationY = if (reduceMotion) 0f else -1.dp.toPx() * emphasis
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            item.icon,
            // The visible caption labels the merged tab; the glyph would only repeat it.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(DockIconGlyph),
        )
    }
}

internal data class TabIndicatorBounds(
    val left: Float,
    val width: Float,
)

internal fun tabIndicatorBounds(
    rawLeft: Float,
    rawRight: Float,
    tabCount: Int,
    maxScale: Float = TAB_PILL_MAX_SCALE,
): TabIndicatorBounds {
    require(tabCount > 0)
    val center = (rawLeft + rawRight) / 2f
    val width =
        kotlin.math
            .abs(rawRight - rawLeft)
            .coerceIn(
                TAB_PILL_WIDTH_FRACTION * TAB_PILL_MIN_SCALE,
                (TAB_PILL_WIDTH_FRACTION * maxScale).coerceAtMost(tabCount.toFloat()),
            )
    val left = (center - width / 2f).coerceIn(0f, tabCount.toFloat() - width)
    return TabIndicatorBounds(left = left, width = width)
}

internal fun tabPillTargetLeft(index: Float): Float = index + (1f - TAB_PILL_WIDTH_FRACTION) / 2f

internal fun tabPillTargetRight(index: Float): Float = index + (1f + TAB_PILL_WIDTH_FRACTION) / 2f

internal fun rootTabMotion(
    previous: Tab,
    current: Tab,
): OfficialNavMotion =
    when {
        previous != Tab.Search && current == Tab.Search -> OfficialNavMotion.SearchEnter
        previous == Tab.Search && current != Tab.Search -> OfficialNavMotion.SearchExit
        else -> OfficialNavMotion.RootTab
    }

private const val TAB_PILL_WIDTH_FRACTION = 0.82f
private const val TAB_PILL_MIN_SCALE = 0.94f
private const val TAB_PILL_MAX_SCALE = 1.12f

/** How far 搜索's island sits inside its rim, as the tab island sits inside the bar. */
private val SEARCH_ISLAND_INSET = 3.dp
