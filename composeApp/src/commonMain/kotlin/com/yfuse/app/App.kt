package com.yfuse.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.feature.library.LibraryScreen
import com.yfuse.feature.profile.ProfileScreen
import com.yfuse.feature.servers.ServersScreen

private data class TabItem(val tab: Tab, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.Servers, "服务器", Icons.Rounded.Dns),
    TabItem(Tab.Library, "媒体库", Icons.Rounded.VideoLibrary),
    TabItem(Tab.Profile, "个人", Icons.Rounded.Person),
)

@Composable
fun App(root: RootComponent) {
    YfuseTheme {
        val active by root.activeTab.subscribeAsState()

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = active == item.tab,
                            onClick = { root.selectTab(item.tab) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // Only consume the bottom inset (nav bar); each screen's TopAppBar
            // handles the status-bar inset itself, so we avoid a doubled top gap.
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (active) {
                    Tab.Servers -> ServersScreen(root.servers)
                    Tab.Library -> LibraryScreen(root.library)
                    Tab.Profile -> ProfileScreen(root.profile)
                }
            }
        }
    }
}
