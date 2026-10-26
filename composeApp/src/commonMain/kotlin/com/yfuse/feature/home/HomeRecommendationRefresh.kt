package com.yfuse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.yfuse.core.designsystem.LocalRouteVisible
import kotlinx.coroutines.delay

/** Checks only while the route is visible and the app is in the foreground. */
@Composable
fun HomeRecommendationRefreshEffect(component: HomeComponent) {
    val lifecycle = component.lifecycle
    val routeVisible = LocalRouteVisible.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.state == Lifecycle.State.RESUMED) }
    DisposableEffect(lifecycle) {
        val callbacks =
            object : Lifecycle.Callbacks {
                override fun onResume() {
                    resumed = true
                }

                override fun onPause() {
                    resumed = false
                }
            }
        lifecycle.subscribe(callbacks)
        resumed = lifecycle.state == Lifecycle.State.RESUMED
        onDispose { lifecycle.unsubscribe(callbacks) }
    }
    LaunchedEffect(component, routeVisible, resumed) {
        if (routeVisible && resumed && component.recommendationsEnabled) {
            pollHomeRecommendationDate {
                component.store.accept(HomeIntent.RefreshRecommendationsIfStale)
            }
        }
    }
}

/** The store coalesces checks by local date and ignores checks during an existing request. */
internal suspend fun pollHomeRecommendationDate(check: () -> Unit) {
    while (true) {
        check()
        delay(60_000L)
    }
}
