package com.yfuse.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
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
import com.yfuse.core.designsystem.OverlayVisibility
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.SkeletonPulseProvider
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.overlayGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.home.HomeTabScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchScreen
import com.yfuse.feature.watch.InviteResolution
import com.yfuse.feature.watch.WatchInviteSheet
import com.yfuse.feature.watch.WatchRoomInfoDialog

private data class TabItem(val tab: Tab, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.Home, "首页", AppIcons.Home),
    TabItem(Tab.Browse, "库", AppIcons.Grid),
    TabItem(Tab.Search, "搜索", AppIcons.SearchTab),
    TabItem(Tab.Profile, "我的", AppIcons.User),
)

/** Space scrollable content leaves for the floating bar — `padding-bottom:100px`. */
val TabBarInset = Dimens.contentBottom

@Composable
fun App(root: RootComponent) {
    val mode by root.themePreferences.mode.collectAsState()
    val accent by root.themePreferences.accent.collectAsState()
    val reduceTransparency by root.themePreferences.reduceTransparency.collectAsState()
    val largeText by root.themePreferences.largeText.collectAsState()
    val reduceMotion by root.themePreferences.reduceMotion.collectAsState()
    val dark = mode.resolveDark(isSystemInDarkTheme())

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

        // Watch-together lives above the tabs: an invite can arrive from a chat app at any
        // moment, and an active room has to stay visible after the player is dismissed —
        // the client is a singleton, so without this the user could be in a room with no
        // indication anywhere in the app.
        val watchTogether = root.dependencies.watchTogether
        val inviteResolver = root.dependencies.inviteResolver
        val watchPreferences = root.dependencies.watchTogetherPreferences
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
        val watchRoomNote = when {
            !watchState.connected -> null
            watchState.syncWarning != null -> watchState.syncWarning
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
                AppBackdrop {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .backdropSource(backdrop),
                    ) {
                        // Top-level tabs are a real Navigation 3 back stack: every non-Home root
                        // previews Home during the system gesture, while each tab's nested host
                        // continues to own its child routes. The shared host supplies the same
                        // edge-reveal back transition used by nested destinations.
                        OfficialNavDisplay(
                            backStack = topLevelBackStack(active),
                            onBack = { root.selectTab(Tab.Home) },
                            contentKey = { "tab:${it.name}" },
                            modifier = Modifier.fillMaxSize(),
                        ) { tab ->
                            CompositionLocalProvider(LocalTabIdentity provides tab.name) {
                                tabStates.SaveableStateProvider(tab.name) {
                                    when (tab) {
                                        Tab.Home -> HomeTabScreen(root.home)
                                        Tab.Browse -> LibraryScreen(root.browse)
                                        Tab.Search -> SearchScreen(root.search)
                                        Tab.Profile -> ProfileTabScreen(root.profile)
                                    }
                                }
                            }
                        }
                    }

                    if (showBottomBar && !overlays.any) {
                        GlassTabBar(
                            active = active,
                            onSelect = { tab ->
                                // Tapping the tab you are already on is not a no-op — see
                                // [RootComponent.reselectTab].
                                if (tab == active) {
                                    root.reselectTab(tab, atRoot)
                                } else {
                                    root.selectTab(tab)
                                }
                            },
                            backdrop = backdrop,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding(),
                        )
                        // One slot above the tab bar, and the two things that can occupy it
                        // never coexist: while a player is alive the mini player carries the
                        // room note itself, and the room bar is for exactly the case where it
                        // isn't — the player closed, the room still up.
                        val bottomStackSlot = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = Dimens.tabBarInset)
                            .padding(bottom = Dimens.tabBarHeight + 22.dp)
                        // Video backgrounding is represented by Android PiP. The old long,
                        // music-like mini controller duplicated transport controls and only
                        // appeared at tab roots, so it is intentionally not rendered here.
                        if (!miniPlayback.active && watchRoomNote != null && !roomBarHidden) {
                            WatchRoomBar(
                                note = watchRoomNote,
                                attention = watchState.reconnecting ||
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
                        WatchInviteSheet(
                            roomCode = invite.roomCode,
                            resolution = inviteResolution,
                            unfamiliarEndpoint = invite.endpoint
                                ?.takeIf { it.trimEnd('/') != watchEndpoint.trimEnd('/') },
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
                                    endpoint = invite.endpoint ?: watchEndpoint,
                                    roomCode = invite.roomCode,
                                    mediaKey = invite.mediaKey.orEmpty(),
                                )
                                root.dismissInvite()
                            },
                            onSearchByName = root::openSearchForInvite,
                            onDismiss = root::dismissInvite,
                        )
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
            )
            .padding(start = 14.dp, end = 8.dp),
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
            modifier = Modifier
                .pressable(onClick = onView)
                .touchTarget()
                .clip(GlassShapes.chip)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "隐藏一起看提示",
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier
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

/**
 * `.tabbar` — 浮层: left/right 14, bottom 14, height 62, radius 26 (大档), glass fill,
 * 1px hairline, `0 12px 30px rgba(60,90,150,.18)`, items spaced `space-around`.
 *
 * §3 fixes the bottom stack as 内容 → 迷你播放器 → tab bar, with the mini player sharing
 * this material, radius and horizontal inset so the two read as one continuous overlay.
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
    val selectedIndex = tabs.indexOfFirst { it.tab == active }.coerceAtLeast(0)
    // Reference-style shell: almost-opaque light glass with a quiet neutral selected island.
    // The accent stays on the icon/label so theme colours remain expressive without tinting
    // the whole selected cell. Dark mode keeps the same hierarchy with a stronger dark glass.
    val barFill = palette.glassStrong.copy(alpha = if (palette.isDark) 0.86f else 0.92f)
    val selectionFill = palette.text.copy(alpha = if (palette.isDark) 0.12f else 0.08f)
    // The pill travels between cells rather than appearing under the new one. Tabs are
    // equal-weight quarters of the bar, so its position is the animated index and nothing
    // has to be measured.
    //
    // A spring rather than the 260ms tween it used to run on: this is the one control a
    // session touches most, and impatient taps across three tabs used to restart a fixed ramp
    // each time, so the pill fell further behind the finger the faster you moved. A spring
    // carries its velocity into the next target and arrives with it.
    val indicator by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "tabIndicator",
    )
    Row(
        modifier
            .fillMaxWidth()
            // One group of four, so a screen reader announces "第 2 项，共 4 项" rather than
            // reading four unrelated controls.
            .selectableGroup()
            .padding(horizontal = Dimens.tabBarInset)
            .padding(bottom = Dimens.tabBarInset)
            .height(Dimens.tabBarHeight)
            // The reference uses a true capsule rather than a rounded rectangle: the shell
            // stays soft even after increasing the bar height.
            .shadow(Shadows.tabBar, CircleShape)
            .backdropBlur(backdrop, CircleShape)
            .overlayGlass(CircleShape, barFill, palette.tabbarBorder)
            // After the fill and before the buttons: the pill belongs to the material, not
            // over the icons.
            .drawBehind {
                val cell = size.width / tabs.size
                // Search/Profile sit over quiet page backgrounds, where the former 12% pill
                // nearly disappeared. A slightly larger, stronger indicator stays legible over both
                // artwork-heavy roots and plain roots without turning into a filled button.
                // The selected region nearly fills its cell, matching the broad soft island
                // in the reference instead of reading as a small Material indicator.
                val pillWidth = cell * 0.92f
                val pillHeight = size.height * 0.88f
                drawRoundRect(
                    color = selectionFill,
                    topLeft = Offset(
                        x = cell * indicator + (cell - pillWidth) / 2f,
                        y = (size.height - pillHeight) / 2f,
                    ),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = CornerRadius(pillHeight / 2f),
                )
            },
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
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // The tint used to cut straight from grey to accent while the pill slid underneath it;
    // crossfading them puts the two halves of the same transition on the same clock — which
    // now means the same spring, so the tint tracks the pill even through rapid taps.
    val tint by animateColorAsState(
        targetValue = if (selected) accent.accent else palette.text.copy(alpha = 0.72f),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "tabTint",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .heightIn(min = MinTouchTarget)
            .clip(CircleShape)
            // This was `clickable(indication = null)` with nothing put back, so the one
            // control every session touches most had no press feedback at all.
            .pressable(
                pressedScale = 0.92f,
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
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
            color = tint,
        )
    }
}
