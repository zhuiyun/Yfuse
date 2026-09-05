package com.yfuse.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalOverlayVisibility
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalTabIdentity
import com.yfuse.core.designsystem.LocalTabReselected
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.MiniPlayerTokens
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.OverlayVisibility
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.SkeletonPulseProvider
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.drawLensIsland
import com.yfuse.core.designsystem.liquidNavigationGlass
import com.yfuse.core.designsystem.navigationGlass
import com.yfuse.core.designsystem.overlayGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.useNavigationRail
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.home.HomeTabScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchScreen
import com.yfuse.feature.servers.ServersTabScreen
import com.yfuse.feature.watch.InviteResolution
import com.yfuse.feature.watch.WatchInviteSheet
import com.yfuse.feature.watch.WatchRoomInfoDialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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

/**
 * Legacy fixed clearance for screens not yet migrated to [floatingNavigationContentInset].
 * Root library/profile pages use the dynamic helper so the system navigation inset is exact.
 */
val TabBarInset = Dimens.contentBottom

@Composable
fun App(root: RootComponent) {
    val mode by root.themePreferences.mode.collectAsState()
    val accent by root.themePreferences.accent.collectAsState()
    val reduceTransparency by root.themePreferences.reduceTransparency.collectAsState()
    val largeText by root.themePreferences.largeText.collectAsState()
    val reduceMotion by root.themePreferences.reduceMotion.collectAsState()
    val glassStyle by root.themePreferences.glassStyle.collectAsState()
    val backgroundImage by root.themePreferences.backgroundImage.collectAsState()
    val backgroundDim by root.themePreferences.backgroundDim.collectAsState()
    val dark = mode.resolveDark(isSystemInDarkTheme())

    YfuseTheme(
        dark = dark,
        accent = accent,
        accessibility =
            AccessibilityOptions(
                reduceTransparency = reduceTransparency,
                largeText = largeText,
                reduceMotion = reduceMotion,
            ),
        // 减弱透明度 is an accessibility contract: it exists to make every surface opaque and
        // legible, so a decorative material choice must not be able to reinstate the effect
        // it turns off.
        glassStyle = if (reduceTransparency) GlassStyle.Frosted else glassStyle,
    ) {
        val active by root.activeTab.subscribeAsState()
        val homeStack by root.home.stack.subscribeAsState()
        val browseStack by root.browse.stack.subscribeAsState()
        val searchStack by root.search.stack.subscribeAsState()
        val profileStack by root.profile.stack.subscribeAsState()
        val miniPlayback by ActivePlayback.state.collectAsState()
        val reportingCoordinator = root.dependencies.playbackReportingCoordinator
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

        // What the bottom stack says about the room — the mini player's second line while a
        // player is up, and the whole of [WatchRoomBar] when one isn't.
        //
        // A warning outranks the participant count: entering can fail (a room that has not
        // started playing, one playing something no attached server holds) and without this
        // the tap that fails has nothing to show for it. It is a long string in a one-line
        // bar and will ellipsize; the first few characters are the part that matters, and
        // 「我的」→ 一起看 has it in full.
        val watchRoomNote =
            when {
                !watchState.connected -> null
                watchState.syncWarning != null -> watchState.syncWarning
                watchState.reconnecting -> "一起看 · 重连中"
                watchState.isHost -> "一起看 · 房主 · ${watchState.participantCount} 人"
                else -> "一起看 · ${watchState.participantCount} 人"
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
        // furniture, so the bar has to be told to get out of its way — see [OverlayVisibility].
        val overlays = remember { OverlayVisibility() }

        var roomInfoOpen by remember { mutableStateOf(false) }
        // Dismissing the bar is per-room, not permanent: a different room — or the same code
        // rejoined — is news again, and hiding one notice must not silence the next.
        var hiddenRoomCode by remember { mutableStateOf<String?>(null) }
        val roomBarHidden = hiddenRoomCode != null && hiddenRoomCode == watchState.roomCode

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
        CompositionLocalProvider(
            LocalOverlayVisibility provides overlays,
            LocalTabReselected provides root.tabReselected,
        ) {
            SkeletonPulseProvider {
                AppBackdrop(
                    // A wallpaper is decoration, and 减弱透明度 is the switch for people who
                    // need the page to be a flat readable surface. It wins.
                    imageUri = backgroundImage.takeUnless { reduceTransparency },
                    dim = backgroundDim,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val expandedNavigation = useNavigationRail(maxWidth, maxHeight)
                        // A rail reserves horizontal space only while the rail itself belongs to
                        // the current route. Detail/grid/player pages hide root navigation and must
                        // immediately reclaim the full width; otherwise the stale inset is visible
                        // as a plain strip beside artwork-backed pages.
                        val navigationRailActive = expandedNavigation && showBottomBar
                        val onSelectTab: (Tab) -> Unit = { tab ->
                            if (tab == active) {
                                root.reselectTab(tab, atRoot)
                            } else {
                                root.selectTab(tab)
                            }
                        }
                        // Reading gets the screen; navigating gets it back. Not saveable on
                        // purpose: a collapsed bar is a transient consequence of where the finger
                        // just went, and restoring one after process death would leave the user
                        // looking at an app with no visible navigation and no idea why.
                        var navCollapsed by remember { mutableStateOf(false) }
                        val navCollapseGuard = remember { NavigationCollapseGuard() }
                        // Arriving anywhere new is a fresh page, and a fresh page shows its bar.
                        LaunchedEffect(active) {
                            navCollapsed = false
                            navCollapseGuard.reset()
                        }
                        val navScroll =
                            rememberNavCollapseConnection(
                                collapsed = navCollapsed,
                                onCollapsedChange = { navCollapsed = it },
                                guard = navCollapseGuard,
                            )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(start = if (navigationRailActive) 104.dp else 0.dp)
                                // Only root pages with the bottom dock need scroll-to-collapse.
                                // Secondary pages own the whole screen and have no root navigation
                                // to collapse or expand.
                                .then(
                                    if (expandedNavigation || !showBottomBar) {
                                        Modifier
                                    } else {
                                        Modifier.nestedScroll(navScroll)
                                    },
                                ).backdropSource(backdrop),
                        ) {
                            val previousRootTab = remember { arrayOf(active) }
                            val rootMotion = rootTabMotion(previousRootTab[0], active)
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
                                            Tab.Home -> HomeTabScreen(root.home)
                                            Tab.Browse -> LibraryScreen(root.browse)
                                            Tab.Servers -> ServersTabScreen(root.servers)
                                            Tab.Search -> SearchScreen(root.search)
                                            Tab.Profile -> ProfileTabScreen(root.profile)
                                        }
                                    }
                                }
                            }
                        }

                        if (showBottomBar && !overlays.any) {
                            if (expandedNavigation) {
                                GlassNavigationRail(
                                    active = active,
                                    onSelect = onSelectTab,
                                    backdrop = backdrop,
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            } else {
                                BottomNavigationDock(
                                    active = active,
                                    collapsed = navCollapsed,
                                    onSelect = onSelectTab,
                                    onExpand = {
                                        // A tap during fling is explicit navigation intent. Keep
                                        // the expanded dock pinned until that fling finishes or
                                        // the user starts a new direct scroll gesture.
                                        navCollapseGuard.onManualExpand()
                                        navCollapsed = false
                                    },
                                    onSearch = { onSelectTab(Tab.Search) },
                                    backdrop = backdrop,
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding(),
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
                                    .padding(start = if (expandedNavigation) 104.dp else 0.dp)
                                    .widthIn(max = 520.dp)
                                    .padding(horizontal = Dimens.tabBarInset)
                                    .padding(
                                        bottom =
                                            if (expandedNavigation) {
                                                Dimens.tabBarInset
                                            } else {
                                                Dimens.tabBarHeight + 22.dp
                                            },
                                    )
                            // Video backgrounding is represented by Android PiP. The old long,
                            // music-like mini controller duplicated transport controls and only
                            // appeared at tab roots, so it is intentionally not rendered here.
                            if (!miniPlayback.active && watchRoomNote != null && !roomBarHidden) {
                                WatchRoomBar(
                                    note = watchRoomNote,
                                    attention =
                                        watchState.reconnecting ||
                                            watchState.syncWarning != null,
                                    onEnter = root::enterWatchRoom,
                                    onView = { roomInfoOpen = true },
                                    onClose = { hiddenRoomCode = watchState.roomCode },
                                    backdrop = backdrop,
                                    modifier = bottomStackSlot,
                                )
                            }
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

/**
 * 一起看 — a live room with no player in front of it.
 *
 * A room outlives playback, and until this bar the only thing that said so was a line on
 * the mini player, which `PlayerActivity.onDestroy` takes away with it: backing out of a
 * film left the user in a room with nothing on screen to show for it. This is the room's
 * own place in the bottom stack, in the mini player's slot and its material — tap the body
 * to go back into whatever the room is watching.
 *
 * [onClose] hides the bar; it does not leave the room, because a bar is not the room and a
 * stray tap must not end everyone else's evening. Leaving is still 「我的」→ 一起看 →
 * 退出房间, which is the only thing that releases this device's hold on it.
 */
@Composable
private fun WatchRoomBar(
    note: String,
    /**
     * In the room, but not following it this instant — reconnecting, or holding a warning.
     * The dot is the only part of the bar that can say so at a glance, since [note] is
     * carrying the reason.
     */
    attention: Boolean,
    onEnter: () -> Unit,
    onView: () -> Unit,
    onClose: () -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .shadow(Shadows.tabBar, GlassShapes.card)
            // Shares the tab bar's material, so it shares the blur under it — §3.
            .backdropBlur(backdrop, GlassShapes.card)
            .overlayGlass(
                GlassShapes.card,
                MiniPlayerTokens.fill,
                MiniPlayerTokens.border,
            ).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The body carries 进入; only the two trailing buttons are cut out of it, so the
        // large easy target is still the one that does the common thing.
        Row(
            Modifier.weight(1f).fillMaxHeight().pressable(onClick = onEnter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (attention) Brand.Offline else Brand.Online),
            )
            Text(
                note,
                style = AppTypography.caption.strong,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "查看",
            style = AppTypography.caption.strong,
            color = accent.accent,
            maxLines = 1,
            modifier =
                Modifier
                    .pressable(onClick = onView)
                    .touchTarget()
                    .clip(GlassShapes.chip)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "隐藏一起看提示",
            tint = Color.White.copy(alpha = 0.72f),
            modifier =
                Modifier
                    .pressable(onClick = onClose)
                    // 12dp glyph in 6dp of padding came to a 24dp target sitting right beside
                    // 查看 — the two smallest controls in the app, adjacent, in a 44dp bar.
                    .touchTarget()
                    .clip(CircleShape)
                    .padding(6.dp)
                    .size(12.dp),
        )
    }
}

/** How far a drag has to travel in one direction before the bar answers it. */
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
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    guard: NavigationCollapseGuard,
): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { NavCollapseThreshold.toPx() }
    val state = rememberUpdatedState(collapsed to onCollapsedChange)
    return remember(threshold) {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!guard.acceptsScroll(source == NestedScrollSource.UserInput)) {
                    travel = 0f
                    return Offset.Zero
                }
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                if (delta > 0f != travel > 0f) travel = 0f
                travel += delta
                val (isCollapsed, setCollapsed) = state.value
                when {
                    // Dragging up moves content down: the user is reading forward.
                    travel <= -threshold && !isCollapsed -> {
                        travel = 0f
                        setCollapsed(true)
                    }
                    travel >= threshold && isCollapsed -> {
                        travel = 0f
                        setCollapsed(false)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Unconsumed downward scroll means the list is already at its top.
                if (available.y > 0f && state.value.first) {
                    travel = 0f
                    state.value.second(false)
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
    }
}

/**
 * The bottom furniture: the four destinations, and 搜索 as its own control beside them.
 *
 * Two shapes for one row. Expanded, the tabs fill a capsule and search is a circle at its
 * end. Collapsed, the capsule contracts to a single button carrying the icon of wherever the
 * user is — enough to say "navigation lives here" without spending a bar's worth of screen on
 * four destinations nobody is looking at while reading — and tapping it brings the row back.
 * Search does not collapse: it is one tap from anywhere, and that is the point of moving it
 * out of the row.
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
) {
    Row(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = Dimens.tabBarInset)
            .padding(bottom = Dimens.tabBarInset)
            .height(Dimens.tabBarHeight),
        // The gap between the capsule and 搜索 is the same token as the margin to the screen
        // edge, so the three spaces across the row read as one rhythm.
        horizontalArrangement = Arrangement.spacedBy(Dimens.tabBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsed) {
            CollapsedNavButton(active = active, backdrop = backdrop, onClick = onExpand)
            Spacer(Modifier.weight(1f))
        } else {
            GlassTabBar(
                active = active,
                onSelect = onSelect,
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
            )
        }
        SearchButton(
            selected = active == Tab.Search,
            backdrop = backdrop,
            onClick = onSearch,
        )
    }
}

/** The bar contracted to one key — the current tab's glyph, and a way back to the rest. */
@Composable
private fun CollapsedNavButton(
    active: Tab,
    backdrop: BackdropState,
    onClick: () -> Unit,
) {
    val accent = LocalAccentColors.current
    val item = tabs.firstOrNull { it.tab == active } ?: tabs.first()
    Box(
        Modifier
            .size(Dimens.tabBarHeight)
            .pressable(
                pressedScale = 0.96f,
                haptic = HapticSignal.Select,
                onClickLabel = "展开导航栏",
                onClick = onClick,
            )
            // Round, like the search key beside it and like the capsule it collapsed out of.
            // A rounded square made the pair read as two unrelated controls.
            .navigationGlass(backdrop, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassTabIcon(item = item, tint = accent.accent, compact = true)
    }
}

/**
 * 搜索 — a circle of the same material at the end of the row.
 *
 * Round where the tabs are a capsule, because it is not one of them: it does not hold a
 * position in the app, it takes you out of wherever you are and hands you back somewhere
 * else. It stays put when the bar collapses.
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
            .size(Dimens.tabBarHeight)
            .pressable(
                pressedScale = 0.96f,
                haptic = HapticSignal.Select,
                role = Role.Tab,
                onClickLabel = "搜索",
                onClick = onClick,
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
 * `.tabbar` — 浮层: left/right 14, bottom 14, height 62, a full capsule, items spaced
 * `space-around`. The material is the navigation lens — see `Modifier.navigationGlass`.
 *
 * §3 fixes the bottom stack as 内容 → 迷你播放器 → tab bar, with the mini player sharing
 * the horizontal inset so the two read as one continuous overlay.
 */
@Composable
private fun GlassTabBar(
    active: Tab,
    onSelect: (Tab) -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
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
    // Two independently sprung edges make the selected glass pull slightly in the direction
    // of travel. The draw phase caps that stretch, so a jump across the bar never turns the
    // indicator into a stripe spanning unrelated icons.
    val initialIndex = selectedIndex.coerceAtLeast(0).toFloat()
    val indicatorLeft = remember { Animatable(tabPillTargetLeft(initialIndex)) }
    val indicatorRight = remember { Animatable(tabPillTargetRight(initialIndex)) }
    LaunchedEffect(selectedIndex, reduceMotion) {
        if (selectedIndex < 0) return@LaunchedEffect
        val targetIndex = selectedIndex.toFloat()
        val targetLeft = tabPillTargetLeft(targetIndex)
        val targetRight = tabPillTargetRight(targetIndex)
        if (reduceMotion) {
            indicatorLeft.snapTo(targetLeft)
            indicatorRight.snapTo(targetRight)
        } else {
            val currentCenter = (indicatorLeft.value + indicatorRight.value) / 2f
            val movingRight = targetIndex + 0.5f >= currentCenter
            coroutineScope {
                launch {
                    indicatorLeft.animateTo(
                        targetValue = targetLeft,
                        animationSpec =
                            if (movingRight) {
                                Motion.tabIndicatorTrailing()
                            } else {
                                Motion.tabIndicatorLeading()
                            },
                    )
                }
                launch {
                    indicatorRight.animateTo(
                        targetValue = targetRight,
                        animationSpec =
                            if (movingRight) {
                                Motion.tabIndicatorLeading()
                            } else {
                                Motion.tabIndicatorTrailing()
                            },
                    )
                }
            }
        }
    }
    val indicatorAlpha by animateFloatAsState(
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
            .height(Dimens.tabBarHeight)
            // A true capsule rather than a rounded rectangle, so the shell stays soft at the
            // taller bar height.
            .navigationGlass(backdrop, CircleShape)
            // After the material and before the buttons: the island belongs to the glass, not
            // over the icons.
            .drawBehind {
                if (indicatorAlpha <= 0f) return@drawBehind
                val cell = size.width / tabs.size
                // The selected region nearly fills its cell — a broad island, not a small
                // Material indicator — so it stays legible over artwork-heavy roots and the
                // quiet ones alike.
                val bounds =
                    tabIndicatorBounds(
                        rawLeft = indicatorLeft.value,
                        rawRight = indicatorRight.value,
                        tabCount = tabs.size,
                    )
                val pillWidth = cell * bounds.width
                val pillHeight = size.height * 0.86f
                val left = cell * bounds.left
                val top = (size.height - pillHeight) / 2f
                val alpha = indicatorAlpha.coerceIn(0f, 1f)
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
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            TabButton(item = item, selected = active == item.tab, onClick = { onSelect(item.tab) })
        }
    }
}

/** Pure-icon tab. Each button takes a full fifth of the bar, not just the glyph. */
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
                // The icon already carries [item.label] as its description; merging the cell
                // means the tab is read once, as one control, with its state attached rather
                // than as an icon and a caption that happen to sit together.
                .semantics(mergeDescendants = true) { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidGlassTabIcon(item = item, tint = tint)
    }
}

/** Expanded-width navigation keeps targets compact instead of stretching four across 840dp. */
@Composable
private fun GlassNavigationRail(
    active: Tab,
    onSelect: (Tab) -> Unit,
    backdrop: BackdropState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(start = Dimens.tabBarInset, top = 72.dp, bottom = 72.dp)
            .width(76.dp)
            .fillMaxHeight()
            .selectableGroup()
            .navigationGlass(backdrop, GlassShapes.tabBar)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { item ->
            RailTabButton(
                item = item,
                selected = active == item.tab,
                onClick = { onSelect(item.tab) },
            )
        }
    }
}

@Composable
private fun RailTabButton(
    item: TabItem,
    selected: Boolean,
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
        label = "railTabTint",
    )
    val islandAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "railTabIsland",
    )
    Column(
        Modifier
            .width(64.dp)
            .heightIn(min = 58.dp)
            .clip(GlassShapes.card)
            .drawBehind {
                if (islandAlpha <= 0f) return@drawBehind
                val rect = Rect(Offset.Zero, size)
                if (liquid) {
                    drawLensIsland(rect, dark = palette.isDark, accent = accent.accent, alpha = islandAlpha)
                } else {
                    val selection = navigationGlass.selection
                    drawRect(color = selection.copy(alpha = selection.alpha * islandAlpha))
                }
            }
            .pressable(
                pressedScale = 0.96f,
                haptic = HapticSignal.Select,
                role = Role.Tab,
                onClick = onClick,
            ).semantics(mergeDescendants = true) { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidGlassTabIcon(item = item, tint = tint, compact = true)
    }
}

/**
 * A tab glyph in its optical box.
 *
 * The glyphs are silhouettes, so there is nothing for the material to do here beyond the
 * tint: the holes cut through them show the lens underneath. Unselected tabs used to shrink
 * to 94%, which made the four read as a row of things not quite in focus; every glyph now
 * sits at full size and only the ink changes.
 */
@Composable
private fun LiquidGlassTabIcon(
    item: TabItem,
    tint: Color,
    compact: Boolean = false,
) {
    val boxSize = if (compact) 34.dp else 38.dp
    val iconSize = if (compact) 25.dp else 28.dp
    Box(
        Modifier.size(boxSize),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(iconSize),
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
): TabIndicatorBounds {
    require(tabCount > 0)
    val center = (rawLeft + rawRight) / 2f
    val width =
        kotlin.math
            .abs(rawRight - rawLeft)
            .coerceIn(
                TAB_PILL_WIDTH_FRACTION * TAB_PILL_MIN_SCALE,
                TAB_PILL_WIDTH_FRACTION * TAB_PILL_MAX_SCALE,
            )
    val left = (center - width / 2f).coerceIn(0f, tabCount.toFloat() - width)
    return TabIndicatorBounds(left = left, width = width)
}

private fun tabPillTargetLeft(index: Float): Float = index + (1f - TAB_PILL_WIDTH_FRACTION) / 2f

private fun tabPillTargetRight(index: Float): Float = index + (1f + TAB_PILL_WIDTH_FRACTION) / 2f

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
