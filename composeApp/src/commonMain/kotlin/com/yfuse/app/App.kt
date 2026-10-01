package com.yfuse.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.CALM_DURATION_SCALE
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LiftMenuHost
import com.yfuse.core.designsystem.LiftMenuState
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalLiftMenu
import com.yfuse.core.designsystem.LocalOverlayVisibility
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalPulseSweepEnabled
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.LocalTabIdentity
import com.yfuse.core.designsystem.LocalTabReselected
import com.yfuse.core.designsystem.LocalTips
import com.yfuse.core.designsystem.LocalToastBottomInset
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.SearchDockOrigin
import com.yfuse.core.designsystem.SkeletonPulseProvider
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.attentionSweep
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.drawLensIsland
import com.yfuse.core.designsystem.drawMotionSweep
import com.yfuse.core.designsystem.drawPhaseLight
import com.yfuse.core.designsystem.liquidMotionEnabled
import com.yfuse.core.designsystem.liquidNavigationGlass
import com.yfuse.core.designsystem.liquidOutline
import com.yfuse.core.designsystem.navigationGlass
import com.yfuse.core.designsystem.pathBackdropBlurOrigin
import com.yfuse.core.designsystem.playerHandoffStage
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.rememberLiquidNavigationGlass
import com.yfuse.core.designsystem.rememberPhaseLightCount
import com.yfuse.core.designsystem.rememberRouteVisibility
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
import com.yfuse.feature.servers.ServersTabComponent
import com.yfuse.feature.servers.ServersTabScreen
import com.yfuse.feature.watch.InviteResolution
import com.yfuse.feature.watch.WatchInviteSheet
import com.yfuse.feature.watch.WatchRoomInfoDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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

/** 搜索 as the collapsed key carries it, while the search page is where the user is. */
private val searchKeyItem = TabItem(Tab.Search, "搜索", AppIcons.SearchTab)

@Composable
fun App(root: RootComponent) {
    BindBackgroundServices(root)
    val pulseSweep by root.themePreferences.pulseSweep.collectAsState()
    val navCollapseOnScroll by root.themePreferences.navCollapseOnScroll.collectAsState()
    val backgroundImage by root.themePreferences.backgroundImage.collectAsState()
    val backgroundDim by root.themePreferences.backgroundDim.collectAsState()

    AppTheme(root.themePreferences) {
        val accessibility = LocalAccessibilityOptions.current
        val motionOff = accessibility.reduceMotion
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
        // `ActivePlayback.state` ticks with every playback position update while a mini
        // player/PiP is open; every read here only ever needs `active`, so collecting the
        // whole object recomposed this entire shell on every tick. A remembered derived
        // flow keeps that narrow, the same way [ActivityStatusCapsule] derives its own
        // cast/room summaries from their source flows.
        val miniPlaybackActiveFlow = remember { ActivePlayback.state.map { it.active }.distinctUntilChanged() }
        val miniPlaybackActive by miniPlaybackActiveFlow.collectAsState(ActivePlayback.state.value.active)
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
            if (watchState.isHost || miniPlaybackActive) return@LaunchedEffect
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
        // library's grid. Under a scroll it no longer slides away either — with 滚动时收起导航栏
        // on it collapses to the one key below, so "is the bar there?" never depends on where the
        // user has scrolled to; with it off, it simply stays up.
        val showBottomBar = atRoot

        // Reading gets the screen; navigating gets it back. Not saveable on purpose: a collapsed
        // bar is a transient consequence of where the finger just went, and restoring one after
        // process death would leave the user looking at an app with no visible navigation and no
        // idea why.
        // Read by the dock alone, so a collapse recomposes the dock and not the shell around it.
        val navCollapsed = remember { mutableStateOf(false) }
        val navCollapseGuard = remember { NavigationCollapseGuard() }
        // Arriving anywhere new is a fresh page, and a fresh page shows its bar; so does turning
        // the collapse off while the bar is collapsed.
        LaunchedEffect(active, navCollapseOnScroll) {
            navCollapsed.value = false
            navCollapseGuard.reset()
        }
        val navScroll = rememberNavCollapseConnection(navCollapsed, navCollapseGuard)

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
        // Whether anything is sampling [backdrop]: the dock, composed exactly while it is visible or
        // still animating out, and a lifted poster's blur. A pushed page, which owns the whole screen
        // and may capture a backdrop of its own, is otherwise not also recorded here.
        // Written from the dock's effect and read only inside the capture's draw.
        val dockOnScreen = remember { mutableStateOf(false) }
        // How tall the activity capsule stands over the dock, and zero while it is away: a toast on
        // a root page rests above it. Written from the capsule's layout, read by [ToastFloor] alone.
        val activityCapsuleHeight = remember { mutableStateOf(0.dp) }
        // 浮起菜单: every content poster lifts into this one host, drawn over the dock below.
        val liftMenu = remember { LiftMenuState() }
        val tips = rememberAppTips()
        CompositionLocalProvider(
            LocalPulseSweepEnabled provides pulseSweep,
            LocalTabReselected provides root.tabReselected,
            LocalLiftMenu provides liftMenu,
            LocalTips provides tips,
        ) {
            SkeletonPulseProvider {
                AppBackdrop(
                    // A wallpaper is decoration, and 减弱透明度 is the switch for people who
                    // need the page to be a flat readable surface. It wins.
                    imageUri = backgroundImage.takeUnless { accessibility.reduceTransparency },
                    dim = backgroundDim,
                ) {
                    // The page's half of the player transitions draws over, and transforms, this whole
                    // shell — dock and capsules included — while a launch is under way; idle it adds nothing.
                    Box(Modifier.fillMaxSize().playerHandoffStage()) {
                        val onSelectTab: (Tab) -> Unit = { tab ->
                            if (tab == active) {
                                root.reselectTab(tab, atRoot)
                            } else {
                                root.selectTab(tab)
                            }
                        }
                        // On a root page, unless the tab has opened a page over its root — 我的's
                        // settings pages, 首页's 查看全部 — which the dock steps aside for.
                        val dockShown = showBottomBar && overlays?.coversShell != true
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Only a dock that is up collapses under a scroll, and only while the
                                // setting asks for it. Pushed pages own the whole screen and have no
                                // root navigation to collapse or expand; nor does a page opened over a
                                // root, and scrolling one used to collapse the dock it had hidden.
                                .then(
                                    if (dockShown && navCollapseOnScroll) {
                                        Modifier.nestedScroll(navScroll)
                                    } else {
                                        Modifier
                                    },
                                ).backdropSource(backdrop, record = { dockOnScreen.value || liftMenu.isOpen }),
                        ) {
                            val previousRootTab = remember { arrayOf(active) }
                            val rootMotion = remember(active) { rootTabMotion(previousRootTab[0], active) }
                            SideEffect { previousRootTab[0] = active }
                            // Top-level tabs are a real Navigation 3 back stack, while each tab's
                            // nested host continues to own its child routes. This host opts into
                            // equal-level root motion; nested stacks own their push/pop gestures.
                            ToastFloor(dockShown, activityCapsuleHeight) {
                                OfficialNavDisplay(
                                    backStack = topLevelBackStack(active, root.startTab),
                                    onBack = { root.selectTab(root.startTab) },
                                    contentKey = { "tab:${it.name}" },
                                    modifier = Modifier.fillMaxSize(),
                                    motion = rootMotion,
                                    popMotion = rootPopMotion(active, root.startTab, rootMotion),
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
                                ServerNoticeToast(root.servers)
                            }
                        }

                        // The dock leaves with the page rather than in one frame. Pushing a detail
                        // route animates the content over Motion.PUSH while the bar was simply
                        // dropped out of composition, so the one piece of furniture that stays
                        // still across the whole app was also the only thing that ever blinked.
                        // Whether the dock is *wanted* still follows [showBottomBar] alone, so
                        // nothing that reasons about the bar's presence is waiting on an animation.
                        //
                        // The dock leaves when a route is pushed and comes back when one is popped,
                        // so those are its durations — they used to be the other way round, which
                        // made the bar linger after the page it belonged to had already gone.
                        // Under 静息 those pages fade over a few dp, so the dock, and the status
                        // capsule riding these same transitions, does too, at 静息's shorter length.
                        val calmDock = !motionOff && calmMotion()
                        val dockEnter =
                            when {
                                motionOff -> 0
                                calmDock -> (Motion.POP * CALM_DURATION_SCALE).roundToInt()
                                else -> Motion.POP
                            }
                        val dockExit =
                            when {
                                motionOff -> 0
                                calmDock -> (Motion.PUSH * CALM_DURATION_SCALE).roundToInt()
                                else -> Motion.PUSH
                            }
                        val calmDockTravelPx = with(LocalDensity.current) { CalmDockTravel.roundToPx() }
                        // Half its own height, not all of it: the bar is furniture settling back
                        // into place, and a full-height slide reads as a separate object flying in
                        // from off-screen.
                        val dockTravel: (Int) -> Int = { height ->
                            if (calmDock) minOf(height / 2, calmDockTravelPx) else height / 2
                        }
                        val dockEnterTransition =
                            fadeIn(tween(dockEnter, easing = Motion.Curve)) +
                                slideInVertically(
                                    animationSpec = tween(dockEnter, easing = Motion.Curve),
                                    initialOffsetY = dockTravel,
                                )
                        val dockExitTransition =
                            fadeOut(tween(dockExit, easing = Motion.Curve)) +
                                slideOutVertically(
                                    animationSpec = tween(dockExit, easing = Motion.Curve),
                                    targetOffsetY = dockTravel,
                                )
                        AnimatedVisibility(
                            visible = dockShown,
                            // The one piece of furniture that stays still across the whole app
                            // does not ride the cold-start library wave either.
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
                            // A dock that comes back — from a pushed page, or from a page opened over the
                            // root — arrives open, with 搜索 budding off it, however collapsed it left.
                            // Reset once it has gone, so the next one is composed open and plays its
                            // entrance; and when it is called back halfway out, which opens it in place.
                            // Never while it stays up: collapsing then is the scroll's to decide.
                            DisposableEffect(navCollapsed, navCollapseGuard) {
                                onDispose {
                                    navCollapsed.value = false
                                    navCollapseGuard.reset()
                                }
                            }
                            LaunchedEffect(dockShown) {
                                if (dockShown) {
                                    navCollapsed.value = false
                                    navCollapseGuard.reset()
                                }
                            }
                            // Still composed while it slides away, but no longer the bar: a tap on the
                            // current tab in those 280ms popped the page that had just been pushed.
                            val dockWanted by rememberUpdatedState(dockShown)
                            BottomNavigationDock(
                                active = active,
                                collapsed = navCollapsed.value,
                                onSelect = { if (dockWanted) onSelectTab(it) },
                                onExpand = {
                                    if (dockWanted) {
                                        // A tap during a fling is explicit navigation intent. Keep the
                                        // expanded dock pinned until that fling finishes or the user
                                        // starts a new direct scroll gesture.
                                        navCollapseGuard.onManualExpand()
                                        navCollapsed.value = false
                                    }
                                },
                                onSearch = { if (dockWanted) onSelectTab(Tab.Search) },
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
                                .padding(bottom = activityCapsuleOffset(dockHeight()))
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
                            visible = dockShown && !miniPlaybackActive,
                            enter = dockEnterTransition,
                            exit = dockExitTransition,
                            onHeightChanged = { activityCapsuleHeight.value = it },
                        )

                        // Above the page, the dock and the capsule, so a lifted poster dims all of
                        // them; below the dialog windows, which a menu row may open.
                        LiftMenuHost(liftMenu, backdrop = backdrop)

                        // A room survives the process: the client keeps the capabilities the
                        // server granted, so a restart can offer to go back instead of making
                        // the guest hunt for the invite again. Declining forgets the room.
                        // The shell's own dialogs are windows above everything, the launch splash
                        // included; they wait for the app to be on screen before asking anything.
                        val launchSettled = LocalRouteVisible.current
                        val resumableRoom by watchTogether.resumableRoom.collectAsState()
                        val rejoinOffer =
                            resumableRoom?.takeIf {
                                launchSettled &&
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

                        pendingInvite?.takeIf { launchSettled }?.let { invite ->
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

/**
 * The theme a window of the app draws in, built from the person's 外观 and 辅助 settings.
 *
 * [App] draws in it, and so does whatever is composed beside the app in its window — the update
 * prompt at launch, which outside it came up as a light panel in dark mode and took no notice of
 * 减少动画 or 大号文字. One per window: inside one, another is only its content, since a second
 * would scale 大号文字 twice and keep an overlay count that the window's dialog backdrop never hears.
 */
@Composable
internal fun AppTheme(
    preferences: ThemePreferences,
    content: @Composable () -> Unit,
) {
    if (LocalInAppTheme.current) {
        content()
        return
    }
    val mode by preferences.mode.collectAsState()
    // 「移除动画」 on the device is the same request as our own 减弱动态效果, so the two are one
    // effective value from here down; the user's own switch still travels separately, for the
    // few places where motion is a gesture rather than a duration — see
    // [AccessibilityOptions.reduceMotionByUser]. Built in one place for every window.
    val accessibility = rememberAppAccessibilityOptions(preferences)
    val particleLight by preferences.particleLight.collectAsState()
    val dialogAnimation by preferences.dialogAnimation.collectAsState()
    val glassStyle by preferences.glassStyle.collectAsState()
    val loadingAnimation by preferences.loadingAnimation.collectAsState()
    val glassMaterials by preferences.glassMaterials.collectAsState()
    val motionTheme by preferences.motionTheme.collectAsState()
    val dark = mode.resolveDark(isSystemInDarkTheme())

    YfuseTheme(
        dark = dark,
        accessibility = accessibility,
        glassStyle = effectiveGlassStyle(glassStyle, accessibility.reduceTransparency),
        dialogAnimation = dialogAnimation,
        loadingAnimation = loadingAnimation,
        glassMaterials = glassMaterials,
        particleLight = particleLight,
        motionTheme = motionTheme,
    ) {
        CompositionLocalProvider(LocalInAppTheme provides true, content = content)
    }
}

/** Whether an [AppTheme] already holds this part of the window. */
private val LocalInAppTheme = staticCompositionLocalOf { false }

/**
 * The tab the session started on, then the one showing when that is another: back from any other
 * tab returns to the start, and on the start itself back is the system's and leaves the app.
 */
internal fun topLevelBackStack(
    active: Tab,
    start: Tab,
): List<Tab> = if (active == start) listOf(start) else listOf(start, active)

/**
 * 服务器's notices — a save, an edit the registry refused — shown by the shell rather than by
 * that tab, so they stay up whichever tab the app is on by the time they arrive. Its own scope,
 * so a notice recomposes the toast and not the navigation host beside it.
 */
@Composable
private fun BoxScope.ServerNoticeToast(servers: ServersTabComponent) {
    val notice by servers.notice.collectAsState()
    ActionToast(message = notice, onDismiss = servers::dismissNotice)
}

/**
 * Where toasts rest while the dock is up — see [floatingNavigationToastInset]; everywhere else a
 * toast only needs to clear the system bar. The capsule's height is read here, so its coming, going
 * or growing recomposes the toasts and not the navigation host they are provided to.
 */
@Composable
private fun ToastFloor(
    dockShown: Boolean,
    capsuleHeight: State<Dp>,
    content: @Composable () -> Unit,
) {
    val floor = if (dockShown) floatingNavigationToastInset(capsule = capsuleHeight.value) else null
    CompositionLocalProvider(LocalToastBottomInset provides floor, content = content)
}

/** The glyph box inside a tab cell, and the glyph inside it — see [LiquidGlassTabIcon]. */
private val DockIconBox = 34.dp
private val DockIconGlyph = 25.dp

/** What is left of [Dimens.tabBarHeight] around the glyph box and one caption line at 1× type. */
private val DockVerticalPadding = 13.dp

/** 静息's dock travel: the few dp its pages move, enough to say where the bar went. */
private val CalmDockTravel = 8.dp

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

/** How far the page has to scroll in one direction before the bar answers it. */
private val NavCollapseThreshold = 42.dp

/** Prevents leftover fling deltas from undoing an explicit tap on the collapsed dock. */
internal class NavigationCollapseGuard {
    private var suppressAnimatedCollapse = false

    fun onManualExpand() {
        suppressAnimatedCollapse = true
    }

    fun acceptsScroll(userInput: Boolean): Boolean {
        if (userInput) {
            suppressAnimatedCollapse = false
            return true
        }
        return !suppressAnimatedCollapse
    }

    fun onFlingFinished() {
        suppressAnimatedCollapse = false
    }

    fun reset() {
        suppressAnimatedCollapse = false
    }
}

/**
 * Collapses the bar while the user is reading down a page and brings it back on the way up.
 *
 * Only the scroll the page actually made counts. The finger's own travel used to, so a swipe up a
 * page too short to scroll — 服务器 with a server or two, an empty 库, 搜索 before anything is typed
 * — collapsed the bar over a page that had not moved, and only a tap or a pull brought it back.
 *
 * Accumulated rather than per-event: a single fling delivers dozens of small deltas, and
 * reacting to each one would flip the bar back and forth inside one gesture. The accumulator
 * resets on every direction change, so the threshold is "42dp of travel *this way*", not
 * 42dp of net movement since the page loaded.
 *
 * Reaching the top always restores the bar regardless of travel: at rest at the top of a page
 * there is no reading in progress to protect, and it is the one position where a user who has
 * lost the bar will reliably look for it.
 */
@Composable
private fun rememberNavCollapseConnection(
    collapsed: MutableState<Boolean>,
    guard: NavigationCollapseGuard,
): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { NavCollapseThreshold.toPx() }
    return remember(threshold, collapsed, guard) { NavigationCollapseConnection(threshold, collapsed, guard) }
}

/** See [rememberNavCollapseConnection]; [threshold] is in pixels. */
internal class NavigationCollapseConnection(
    private val threshold: Float,
    private val collapsed: MutableState<Boolean>,
    private val guard: NavigationCollapseGuard,
) : NestedScrollConnection {
    private var travel = 0f

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val accepted = guard.acceptsScroll(source == NestedScrollSource.UserInput)
        // Unconsumed downward scroll means the list is already at its top.
        if (available.y > 0f && collapsed.value) {
            travel = 0f
            collapsed.value = false
            return Offset.Zero
        }
        if (!accepted) {
            travel = 0f
            return Offset.Zero
        }
        val delta = consumed.y
        if (delta == 0f) return Offset.Zero
        if (delta > 0f != travel > 0f) travel = 0f
        travel += delta
        val isCollapsed = collapsed.value
        when {
            // The page moved on towards its end: the user is reading forward.
            travel <= -threshold && !isCollapsed -> {
                travel = 0f
                collapsed.value = true
            }
            travel >= threshold && isCollapsed -> {
                travel = 0f
                collapsed.value = false
            }
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        travel = 0f
        guard.onFlingFinished()
        return Velocity.Zero
    }
}

/**
 * The bottom furniture: the four destinations in a capsule, and 搜索 as its own round key at
 * the end of the row — one tap away wherever the row is open.
 *
 * Two shapes for one row. Expanded, the tabs fill a capsule and search is a circle at its end.
 * Collapsed, the whole row is one key carrying the icon of wherever the user is — enough to say
 * "navigation lives here" without spending a bar's worth of screen on destinations nobody is
 * looking at while reading — and tapping it brings the row back. 搜索 collapses with the tabs:
 * it is one body with them, and comes apart from it only as the row opens out.
 *
 * With the liquid on (see [DockLiquid]) every change of shape is liquid: rising onto a root page
 * or expanding, the row is one capsule that 搜索 grows out of; collapsing, 搜索 flows back into
 * the capsule before it contracts. For those frames the two panes hand their glass to the liquid
 * drawn behind the row, and take it back once it rests. Leaving is what it always was.
 */
@Composable
private fun BottomNavigationDock(
    active: Tab,
    collapsed: Boolean,
    onSelect: (Tab) -> Unit,
    onExpand: () -> Unit,
    onSearch: () -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
    /** Changes to a non-null value when something arrives for the user — a 一起看 invite. */
    cueKey: Any? = null,
) {
    val height = dockHeight()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val liquidMotion = liquidMotionEnabled()
    // Composed afresh each time the dock enters, so every entrance starts as one capsule.
    val liquid =
        remember {
            DockLiquid(
                armed = if (liquidMotion && !collapsed) DockLiquidMove.Enter else null,
                collapsed = collapsed,
            )
        }
    val routeVisible = rememberRouteVisibility()
    LaunchedEffect(liquid) {
        if (liquid.clock.move != DockLiquidMove.Enter) return@LaunchedEffect
        // At launch the dock is composed behind the splash: 搜索 buds once the app is on screen —
        // unless a collapse has taken the dock over meanwhile.
        snapshotFlow { routeVisible.value }.first { it }
        if (liquid.clock.move != DockLiquidMove.Enter || liquid.clock.elapsed > 0f) return@LaunchedEffect
        liquid.clock.play(DockLiquidMove.Enter, DockLiquidMove.Enter.durationMs)
    }
    val latestLiquidMotion by rememberUpdatedState(liquidMotion)
    val wasCollapsed = remember { booleanArrayOf(collapsed) }
    LaunchedEffect(collapsed) {
        if (wasCollapsed[0] == collapsed) return@LaunchedEffect
        wasCollapsed[0] = collapsed
        val clock = liquid.clock
        val move = clock.move
        when {
            // Without the liquid the panes change shape themselves: the capsule's spring, 搜索's fade.
            !latestLiquidMotion -> {
                clock.rest()
                liquid.keyShown = collapsed
            }
            !collapsed -> {
                liquid.keyShown = false
                when (move) {
                    null -> {
                        clock.arm(DockLiquidMove.Expand)
                        clock.runTo(DockLiquidMove.Expand.durationMs.toFloat())
                    }
                    // A collapse overtaken halfway goes back the way it came: 搜索 flows out again.
                    DockLiquidMove.Collapse -> clock.runTo(0f)
                    else -> clock.runTo(move.durationMs.toFloat())
                }
            }
            move == DockLiquidMove.Expand -> {
                // An expand overtaken halfway goes back the way it came: 搜索 flows in, then the key.
                if (clock.elapsed > DOCK_GATHER_MS) clock.runTo(DOCK_GATHER_MS.toFloat(), holdLastFrame = true)
                liquid.keyShown = true
                clock.runTo(0f)
            }
            move == DockLiquidMove.Enter -> {
                // Still rising: it arrives first, then collapses from rest.
                clock.runTo(DockLiquidMove.Enter.durationMs.toFloat(), holdLastFrame = true)
                liquid.collapse()
            }
            else -> liquid.collapse(fromMs = if (move == DockLiquidMove.Collapse) clock.elapsed else 0f)
        }
    }
    val liquidDrawing = liquid.drawing
    val glass = rememberLiquidNavigationGlass(backdrop)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = Dimens.tabBarInset)
            .padding(bottom = Dimens.tabBarInset)
            .height(height)
            .onSizeChanged {
                liquid.metrics =
                    DockLiquidMetrics(
                        width = it.width / density.density,
                        height = it.height / density.density,
                        gap = Dimens.tabBarInset.value,
                    )
            }.pathBackdropBlurOrigin(glass.blur)
            // Behind both panes while the dock shows: the capsule and 搜索 as one body.
            .drawBehind {
                val frame = liquid.frame() ?: return@drawBehind
                val metrics = liquid.metrics ?: return@drawBehind
                val segments = liquidOutline(frame.bodies(metrics), 0f, metrics.width, metrics.radius * 1.5f)
                // Laid out from the right under RTL, where the capsule is on the right.
                liquid.paths.update(
                    segments,
                    scale = if (rtl) -density.density else density.density,
                    axisY = size.height / 2f,
                    originX = if (rtl) size.width else 0f,
                )
                with(glass) { drawGlass(liquid.paths) }
            }
            // One accent sweep across the dock as the invite lands, before its sheet opens:
            // the bar is where the user is looking, and it is the surface the invite belongs to.
            .attentionSweep(cueKey),
        // The gap between the capsule and 搜索 is the same token as the margin to the screen
        // edge, so the three spaces across the row read as one rhythm.
        horizontalArrangement = Arrangement.spacedBy(Dimens.tabBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val expandedWidth = maxWidth
            val paneWidth =
                animateDpAsState(
                    targetValue = if (collapsed) height else maxWidth,
                    // The liquid owns every change of shape, so the pane is at its new width at once.
                    animationSpec =
                        if (reduceMotion || liquidMotion) {
                            snap()
                        } else {
                            spring(dampingRatio = 0.92f, stiffness = 360f)
                        },
                    label = "navigationDockWidth",
                )
            // Under the liquid the pane spans the row and is clipped to the liquid's capsule; it
            // takes its own width back together with its glass, in the same frame.
            val dockWidth =
                remember(liquid, paneWidth, expandedWidth) {
                    derivedStateOf { if (liquid.drawing) expandedWidth else paneWidth.value }
                }
            // A single continuous lens changes width; content fades inside its clipped bounds.
            // The fixed outer slot keeps search still, including when a fling is interrupted.
            AnimatedContent(
                // Collapsing, the tabs stay up until 搜索 has flowed into the capsule — see [DockLiquid.keyShown].
                targetState = liquid.keyShown,
                modifier =
                    Modifier
                        .then(
                            if (liquidDrawing) {
                                Modifier.clipToLiquidCapsule(liquid, rtl)
                            } else {
                                Modifier.clip(CircleShape).navigationGlass(backdrop, CircleShape)
                            },
                        ).navigationDockViewport(dockWidth, expandedWidth),
                contentAlignment = Alignment.CenterStart,
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else Motion.EMPHASIZED
                    val quick = if (reduceMotion) 0 else Motion.QUICK
                    val arriving =
                        fadeIn(Motion.tween(duration)) + scaleIn(Motion.tween(duration), initialScale = 0.96f)
                    val leaving =
                        fadeOut(Motion.tween(quick)) + scaleOut(Motion.tween(duration), targetScale = 0.98f)
                    (arriving togetherWith leaving).using(null)
                },
                label = "navigationDockContent",
            ) { isCollapsed ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (isCollapsed) {
                        CollapsedNavButton(active = active, diameter = height, onClick = onExpand)
                    } else {
                        GlassTabBar(
                            active = active,
                            onSelect = onSelect,
                            backdrop = backdrop,
                            modifier =
                                Modifier
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .requiredWidth(expandedWidth),
                            height = height,
                            drawShell = false,
                            dockLiquid = liquid,
                        )
                    }
                }
            }
        }
        // 搜索 is inside the collapsed key. The liquid takes it in and lets it out; without the
        // liquid it fades with the capsule, quickly going and at the tabs' pace coming back.
        val searchShown =
            animateFloatAsState(
                targetValue = if (collapsed) 0f else 1f,
                animationSpec =
                    when {
                        reduceMotion || liquidMotion -> snap()
                        collapsed -> Motion.tween(Motion.QUICK)
                        else -> Motion.tween(Motion.EMPHASIZED)
                    },
                label = "dockSearchShown",
            )
        SearchButton(
            selected = active == Tab.Search,
            backdrop = backdrop,
            diameter = height,
            onClick = onSearch,
            dockLiquid = liquid,
            inKey = collapsed,
            shown = searchShown,
        )
    }
}

/** Content inside the capsule stays inside it, wherever the liquid has the capsule's right edge this frame. */
private fun Modifier.clipToLiquidCapsule(
    liquid: DockLiquid,
    rtl: Boolean,
): Modifier =
    drawWithContent {
        val right = liquid.frame()?.capsuleRight?.times(density)
        if (right == null) {
            drawContent()
        } else if (rtl) {
            clipRect(left = size.width - right) { this@drawWithContent.drawContent() }
        } else {
            clipRect(right = right) { this@drawWithContent.drawContent() }
        }
    }

/** Measure tab content once at its resting width; animation only changes the surrounding clipped viewport. */
internal fun Modifier.navigationDockViewport(
    animatedWidth: State<Dp>,
    expandedWidth: Dp,
): Modifier =
    layout { measurable, constraints ->
        val fullWidth = expandedWidth.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val content = measurable.measure(constraints.copy(minWidth = fullWidth, maxWidth = fullWidth))
        // The width clock is deliberately read during layout, never while composing the tab buttons.
        val visibleWidth = animatedWidth.value.roundToPx().coerceIn(constraints.minWidth, fullWidth)
        layout(visibleWidth, content.height) { content.placeRelative(0, 0) }
    }

/**
 * The bar contracted to one key — the glyph of wherever the user is, 搜索 included now that it
 * collapses with the tabs, and a way back to the rest.
 */
@Composable
private fun CollapsedNavButton(
    active: Tab,
    diameter: Dp,
    onClick: () -> Unit,
) {
    val accent = LocalAccentColors.current
    val item = tabs.firstOrNull { it.tab == active } ?: searchKeyItem.takeIf { active == Tab.Search } ?: tabs.first()
    Box(
        Modifier
            .size(diameter)
            .pressable(
                pressedScale = 0.96f,
                haptic = HapticSignal.Select,
                onClickLabel = "展开导航栏",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassTabIcon(item = item, tint = accent.accent, description = item.label)
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
 *
 * While the dock moves as liquid its pane is the drop growing out of the capsule or flowing
 * back into it: the key stays where it is to be tapped, and the island and magnifier ride the
 * drop, the magnifier coming into focus as the drop lands and going first as it leaves.
 *
 * [inKey]: the dock has collapsed into one key and 搜索 is inside it — out of sight, out of
 * reach and out of TalkBack's order until the row opens out again. [shown] is how much of it
 * there is while the liquid is not drawing it.
 */
@Composable
private fun SearchButton(
    selected: Boolean,
    backdrop: BackdropState,
    diameter: Dp,
    onClick: () -> Unit,
    dockLiquid: DockLiquid? = null,
    inKey: Boolean = false,
    shown: State<Float>? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val liquid = liquidNavigationGlass()
    val navigationGlass = navigationGlassVisuals(palette, accent)
    val drawShell = dockLiquid?.drawing != true
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
            .graphicsLayer { alpha = if (dockLiquid?.drawing == true) 1f else (shown?.value ?: 1f) }
            .then(if (inKey) Modifier.clearAndSetSemantics {} else Modifier)
            .searchDockSource()
            // Inside the key it has no press node at all, not a disabled one: that still took the
            // finger, and the dock is over the page, so a tap or a drag that began where 搜索 had
            // been never reached the page. It stays laid out, since the row and the liquid are
            // measured against it.
            .then(
                if (inKey) {
                    Modifier
                } else {
                    Modifier.pressable(
                        pressedScale = 0.96f,
                        haptic = HapticSignal.Select,
                        role = Role.Tab,
                        onClickLabel = "搜索",
                        onClick = {
                            if (!selected) SearchDockOrigin.begin()
                            onClick()
                        },
                    )
                },
            ).semantics(mergeDescendants = true) { this.selected = selected }
            .then(if (drawShell) Modifier.navigationGlass(backdrop, CircleShape) else Modifier)
            .graphicsLayer {
                val frame = dockLiquid?.frame() ?: return@graphicsLayer
                translationX = frame.dropShift * density * if (rtl) -1f else 1f
                scaleX = frame.dropScale
                scaleY = frame.dropScale
            }.drawBehind {
                val shown = islandAlpha * (dockLiquid?.frame()?.glyph ?: 1f)
                if (shown <= 0f) return@drawBehind
                // The island sits just inside the rim, as the tab pill sits inside the bar.
                val inset = SEARCH_ISLAND_INSET.toPx()
                val rect = Rect(inset, inset, size.width - inset, size.height - inset)
                if (liquid) {
                    drawLensIsland(rect, dark = palette.isDark, accent = accent.accent, alpha = shown)
                } else {
                    val selection = navigationGlass.selection
                    drawRoundRect(
                        color = selection.copy(alpha = selection.alpha * shown),
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
            modifier =
                Modifier.size(26.dp).graphicsLayer {
                    // Shape first, content after: the magnifier comes into focus as the drop lands.
                    // Going back in it stays sharp, riding the drop, and only fades as it is taken.
                    val frame = dockLiquid?.frame() ?: return@graphicsLayer
                    alpha = frame.glyph
                    scaleX = SEARCH_GLYPH_FOCUS_FROM + (1f - SEARCH_GLYPH_FOCUS_FROM) * frame.glyphFocus
                    scaleY = scaleX
                    val blur = SEARCH_GLYPH_BLUR.toPx() * (1f - frame.glyphFocus)
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                },
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
    /** False inside the dock, whose viewport or liquid draws the pane instead. */
    drawShell: Boolean = true,
    /** While the dock shows as liquid, the four cells follow the capsule's right edge. */
    dockLiquid: DockLiquid? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    // 静息: the pill takes its new place without travelling or stretching.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
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
            .then(if (drawShell) Modifier.navigationGlass(backdrop, CircleShape) else Modifier)
            // After the material and before the buttons: the island belongs to the glass, not
            // over the icons.
            .drawBehind {
                if (indicatorAlpha.value <= 0f) return@drawBehind
                // While the dock shows as liquid, the cells follow the capsule's right edge.
                val span = dockLiquid?.frame()?.tabSpan?.times(density) ?: size.width
                val cell = span / tabs.size
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
                val left = if (rtl) size.width - cell * (bounds.left + bounds.width) else cell * bounds.left
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
        tabs.forEachIndexed { index, item ->
            TabButton(
                item = item,
                selected = active == item.tab,
                onClick = { onSelect(item.tab) },
                // Where the liquid capsule has this tab's cell this frame: from a quarter of the
                // whole row, closing up behind the capsule's retreating edge.
                modifier =
                    if (dockLiquid == null) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            val span = dockLiquid.frame()?.tabSpan ?: return@graphicsLayer
                            val shift = (index + 0.5f) * (span * density / tabs.size - size.width)
                            translationX = if (rtl) -shift else shift
                        }
                    },
            )
        }
    }
}

/** Each labeled tab owns its complete touch target and one accessibility node. */
@Composable
private fun RowScope.TabButton(
    item: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
                .then(modifier)
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
    /** Only where no caption names the glyph — the collapsed key. */
    description: String? = null,
) {
    // No lift-and-settle on the selected glyph under 静息 either.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
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
            contentDescription = description,
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

/**
 * How going back looks from [active], which [arrival] brought up.
 *
 * A back gesture previews its pop before anything has changed, so while a tab other than the start
 * is up, going back is that tab giving way to the start, whatever brought it up — 媒体库 reached
 * from 搜索 used to go back with 搜索 closing. Once back has landed on the start, the pop being
 * drawn is the move that just made it.
 */
internal fun rootPopMotion(
    active: Tab,
    start: Tab,
    arrival: OfficialNavMotion,
): OfficialNavMotion = if (active != start) rootTabMotion(active, start) else arrival

private const val TAB_PILL_WIDTH_FRACTION = 0.82f
private const val TAB_PILL_MIN_SCALE = 0.94f
private const val TAB_PILL_MAX_SCALE = 1.12f

/** How far 搜索's island sits inside its rim, as the tab island sits inside the bar. */
private val SEARCH_ISLAND_INSET = 3.dp

/** 搜索's magnifier comes into focus from this scale and this blur as its drop lands. */
private const val SEARCH_GLYPH_FOCUS_FROM = 0.75f
private val SEARCH_GLYPH_BLUR = 5.dp
