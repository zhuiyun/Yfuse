package com.yfuse.feature.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.feature.filesource.FileBrowserScreen
import com.yfuse.feature.filesource.FileSourceModals

/**
 * The 服务器 tab: the servers grid, and — pushed over it — the 文件来源 being browsed.
 *
 * Until shares arrived the tab had no stack at all; every surface here was a modal over the grid.
 * A share's folders are the first thing on this tab that go deeper than one level, so browsing
 * one is a real route with the push, the predictive back and the reduced-motion handling that
 * every other stack in the app already has, rather than one more overlay.
 */
@Composable
fun ServersTabScreen(component: ServersTabComponent) {
    val files = component.fileSources
    val browser by files.browser.collectAsState()
    val access by component.access.collectAsState()
    // A child profile is shown no shares, and cannot play one: a switch to it closes one left open.
    val browsing = browser?.takeIf { access.canManageServers }
    LaunchedEffect(access.canManageServers) {
        if (!access.canManageServers) files.closeBrowser()
    }
    Box(Modifier.fillMaxSize()) {
        OfficialNavDisplay(
            backStack = listOfNotNull(ServersRoute.Grid, browsing?.let { ServersRoute.Files(it.source.id) }),
            onBack = files::closeBrowser,
            contentKey = ServersRoute::key,
            modifier = Modifier.fillMaxSize(),
        ) { route ->
            when (route) {
                ServersRoute.Grid -> ServersGridScreen(component)
                is ServersRoute.Files -> FileBrowserScreen(files)
            }
        }
        FileSourceModals(files)
        // Over both routes: a share added from the grid opens straight into its folder, and the
        // 已添加 that follows would otherwise be drawn on the page just left.
        val notice by files.notice.collectAsState()
        ActionToast(message = notice, onDismiss = files::dismissNotice)
    }
}

private sealed interface ServersRoute {
    val key: String

    data object Grid : ServersRoute {
        override val key: String = "servers"
    }

    data class Files(
        val sourceId: String,
    ) : ServersRoute {
        override val key: String = "files:$sourceId"
    }
}
