package com.yfuse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.PageLoadingSkeleton
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.motionItem
import com.yfuse.feature.profile.SettingsPage

/** A secondary page for the content formerly merged above the library's categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchingCenterScreen(
    component: HomeComponent,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiscovery: () -> Unit,
) {
    val hub = rememberMergedLibraryShelves(component) ?: return
    val state = hub.state.value
    val smartShelves =
        com.yfuse.feature.search
            .pinnedLibraryShelves()
    val hasVisibleContent = hub.visibleShelves.any(hub::hasContent) || smartShelves.isNotEmpty()
    StatusBarIconStyle(darkIcons = !LocalPalette.current.isDark)
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.libraryRefreshing,
            onRefresh = {
                hub.accept(HomeIntent.Refresh)
                component.refreshCalendar(forceRefresh = true)
            },
        ) {
            SettingsPage(
                title = "观看中心",
                subtitle = "汇集各服务器的观看内容",
                onBack = onBack,
                state = component.listState,
            ) {
                motionItem(key = "watching-actions") {
                    Section(title = "观看与探索") {
                        SettingsCard {
                            SettingRow(
                                "播放记录",
                                "海报 · 简介 · 季集 · 进度",
                                embedded = true,
                                icon = AppIcons.Bookmark,
                                onClick = onOpenHistory,
                            )
                            SettingsDivider()
                            SettingRow(
                                "追剧日历",
                                "查看追更与播出安排",
                                embedded = true,
                                icon = AppIcons.WatchCalendar,
                                onClick = component.onOpenCalendar,
                            )
                            SettingsDivider()
                            SettingRow(
                                "发现",
                                "推荐与热门内容",
                                embedded = true,
                                icon = AppIcons.Search,
                                onClick = onOpenDiscovery,
                            )
                        }
                    }
                }
                motionItem(key = "watching-layout") { MergedLibraryEditAction(hub) }
                hub.visibleShelves.filter(hub::hasContent).forEach { shelf ->
                    motionItem(key = "watching:$shelf") { MergedLibraryRail(hub, shelf) }
                }
                smartShelves.forEach { (rule, query) ->
                    motionItem(key = "watching-smart:${rule.name}") {
                        SmartPlaylistLibraryShelf(
                            rule = rule,
                            query = query,
                            onClick = hub::detail,
                            liftMenu = { hub.menu(it, MergedLibraryShelf.Recent) },
                        )
                    }
                }
                if (!hasVisibleContent) {
                    motionItem(key = "watching-empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            when {
                                state.libraryLoading -> PageLoadingSkeleton()
                                state.libraryError != null ->
                                    ErrorState(
                                        state.libraryError,
                                        onRetry = { hub.accept(HomeIntent.Refresh) },
                                    )
                                else -> PageHint("暂无观看内容，可先在媒体库选择影片，或前往发现探索")
                            }
                        }
                    }
                }
            }
        }
        MergedLibraryOverlays(hub)
    }
}
