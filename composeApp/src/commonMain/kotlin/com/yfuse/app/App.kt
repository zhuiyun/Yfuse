package com.yfuse.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.glass
import com.yfuse.feature.browse.BrowseScreen
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.profile.ProfileTabScreen
import com.yfuse.feature.search.SearchScreen

private data class TabItem(val tab: Tab, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.Home, "首页", Icons.Rounded.Home),
    TabItem(Tab.Browse, "库", Icons.Rounded.VideoLibrary),
    TabItem(Tab.Search, "搜索", Icons.Rounded.Search),
    TabItem(Tab.Profile, "我的", Icons.Rounded.Person),
)

/** Height reserved at the bottom of scrollable content for the floating bar. */
val TabBarInset = 96.dp

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

        // Playback takes over the whole screen: no tab bar while playing.
        val isPlaying = active == Tab.Home &&
            homeStack.active.instance is LibraryComponent.Child.Player

        AppBackdrop {
            when (active) {
                Tab.Home -> LibraryScreen(root.home)
                Tab.Browse -> BrowseScreen(root.browse)
                Tab.Search -> SearchScreen(root.search)
                Tab.Profile -> ProfileTabScreen(root.profile)
            }

            if (!isPlaying) {
                GlassTabBar(
                    active = active,
                    onSelect = root::selectTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GlassTabBar(active: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .glass(GlassShapes.pill, strong = true)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            TabButton(item = item, selected = active == item.tab, onClick = { onSelect(item.tab) })
        }
    }
}

@Composable
private fun TabButton(item: TabItem, selected: Boolean, onClick: () -> Unit) {
    val glass = LocalGlass.current
    val tint = if (selected) MaterialTheme.colorScheme.primary else glass.onGlassMuted

    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
