package com.yfuse.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.shadow
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.home.HomeTabScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchScreen

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
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    YfuseTheme(dark = dark, accent = accent) {
        val active by root.activeTab.subscribeAsState()
        val homeStack by root.home.stack.subscribeAsState()
        val browseStack by root.browse.stack.subscribeAsState()
        val searchStack by root.search.stack.subscribeAsState()
        val profileStack by root.profile.stack.subscribeAsState()

        // The bar belongs to the four roots; any pushed page (detail, grid, add
        // server, player) owns the whole screen.
        val atRoot = when (active) {
            Tab.Home -> homeStack.active.instance is HomeTabComponent.Child.Home
            Tab.Browse -> browseStack.active.instance is LibraryComponent.Child.Home
            Tab.Search -> searchStack.active.instance is SearchComponent.Child.Home
            Tab.Profile -> profileStack.active.instance is ProfileTabComponent.Child.Home
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

                if (atRoot) {
                    // `transform:translateY(90px);opacity:0` — 90px, .3s ease.
                    val shift by animateFloatAsState(
                        targetValue = if (bottomBar.hidden) 90f else 0f,
                        animationSpec = tween(durationMillis = 300),
                        label = "tabBarShift",
                    )
                    val fade by animateFloatAsState(
                        targetValue = if (bottomBar.hidden) 0f else 1f,
                        animationSpec = tween(durationMillis = 300),
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
                }
            }
        }
    }
}

/**
 * `.tabbar` — left/right 16, bottom 16, height 62, radius 31, `--pg-card` fill,
 * 1px hairline, `0 12px 30px rgba(60,90,150,.18)`, items spaced `space-around`.
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
            .glass(GlassShapes.tabBar, palette.card, palette.tabbarBorder),
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
