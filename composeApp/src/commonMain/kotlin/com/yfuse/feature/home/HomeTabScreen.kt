package com.yfuse.feature.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.feature.calendar.CalendarScreen
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun HomeTabScreen(
    component: HomeTabComponent,
    widgetHome: @Composable () -> Unit = {},
) {
    val stack by component.stack.subscribeAsState()
    OfficialNavDisplay(
        backStack = stack.items,
        onBack = component::navigateBack,
        contentKey = { routeKey(it.configuration) },
        modifier = Modifier.fillMaxSize(),
        isLauncher = { it.instance is HomeTabComponent.Child.Player },
        isDetail = { it.instance is HomeTabComponent.Child.Detail || it.instance is HomeTabComponent.Child.Info },
    ) { entry ->
        val instance = entry.instance
        when (instance) {
            HomeTabComponent.Child.WidgetHome -> widgetHome()
            is HomeTabComponent.Child.Home -> HomeScreen(instance.component)
            is HomeTabComponent.Child.Detail -> DetailScreen(instance.component)
            is HomeTabComponent.Child.Player -> PlayerScreen(instance.component)
            is HomeTabComponent.Child.Info -> {
                com.yfuse.feature.personal.PersonalDiscoveryGuard(onOpenLibrary = component::popToRoot) {
                    val libraryDetail by instance.libraryDetail.collectAsState()
                    val state by instance.component.state.collectAsState()
                    val resolved = libraryDetail
                    when {
                        resolved != null -> DetailScreen(resolved)
                        instance.component.preferLibraryDetail && (state.sourcesLoading || state.loading) ->
                            com.yfuse.feature.widgets
                                .WidgetDetailLoading(instance.component.onBack)
                        else -> TmdbInfoScreen(instance.component)
                    }
                }
            }
            is HomeTabComponent.Child.Calendar -> CalendarScreen(instance.component)
        }
    }
}

/** Includes the complete immutable Decompose configuration in the saved-content identity. */
private fun routeKey(configuration: HomeTabComponent.Config): String = "home:$configuration"
